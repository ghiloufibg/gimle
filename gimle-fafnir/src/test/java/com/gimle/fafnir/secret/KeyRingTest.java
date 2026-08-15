package com.gimle.fafnir.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link KeyRing#fingerprint()} is the operator-facing drift-detection signal for the "every Fafnir
 * replica is provisioned with identical key files" precondition {@link KeyFileManager}'s own
 * javadoc calls out -- these tests prove it's deterministic for identical key material and
 * sensitive to any actual difference, including the difference a rotation introduces.
 */
class KeyRingTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static SecretKey keyOf(byte... encoded) {
    return new SecretKeySpec(encoded, "AES");
  }

  @Test
  void fingerprint_is_identical_across_repeated_calls_on_the_same_ring() {
    KeyRing ring = new KeyRing((byte) 0, Map.of((byte) 0, keyOf((byte) 1, (byte) 2, (byte) 3)));

    assertEquals(ring.fingerprint(), ring.fingerprint());
  }

  @Test
  void fingerprint_is_identical_for_two_rings_holding_the_same_key_material() {
    KeyRing first = new KeyRing((byte) 0, Map.of((byte) 0, keyOf((byte) 1, (byte) 2, (byte) 3)));
    // A distinct SecretKey object, distinct map instance, same underlying bytes -- exactly what
    // two independently-loaded replicas sharing identically-provisioned key files would produce.
    KeyRing second = new KeyRing((byte) 0, Map.of((byte) 0, keyOf((byte) 1, (byte) 2, (byte) 3)));

    assertEquals(first.fingerprint(), second.fingerprint());
  }

  @Test
  void fingerprint_does_not_depend_on_keysbyid_map_iteration_order() {
    Map<Byte, SecretKey> forward = new LinkedHashMap<>();
    forward.put((byte) 0, keyOf((byte) 1, (byte) 2));
    forward.put((byte) 1, keyOf((byte) 3, (byte) 4));
    Map<Byte, SecretKey> backward = new LinkedHashMap<>();
    backward.put((byte) 1, keyOf((byte) 3, (byte) 4));
    backward.put((byte) 0, keyOf((byte) 1, (byte) 2));

    KeyRing ringA = new KeyRing((byte) 0, forward);
    KeyRing ringB = new KeyRing((byte) 0, backward);

    assertEquals(ringA.fingerprint(), ringB.fingerprint());
  }

  @Test
  void fingerprint_changes_when_key_material_differs() {
    KeyRing first = new KeyRing((byte) 0, Map.of((byte) 0, keyOf((byte) 1, (byte) 2, (byte) 3)));
    KeyRing second = new KeyRing((byte) 0, Map.of((byte) 0, keyOf((byte) 9, (byte) 9, (byte) 9)));

    assertNotEquals(first.fingerprint(), second.fingerprint());
  }

  @Test
  void fingerprint_changes_when_a_key_id_differs_even_with_identical_bytes() {
    KeyRing first = new KeyRing((byte) 0, Map.of((byte) 0, keyOf((byte) 5, (byte) 6)));
    KeyRing second = new KeyRing((byte) 1, Map.of((byte) 1, keyOf((byte) 5, (byte) 6)));

    assertNotEquals(first.fingerprint(), second.fingerprint());
  }

  @Test
  void fingerprint_changes_after_a_real_rotation_via_keyfilemanager() {
    Path keyFile = tempDir.resolve("secret.key");
    KeyRing before = KeyFileManager.loadAllOrCreate(keyFile);

    KeyRing after = KeyFileManager.rotate(keyFile, before);

    assertNotEquals(before.fingerprint(), after.fingerprint());
  }

  @Test
  void a_ring_reloaded_from_disk_after_rotation_matches_the_rotated_fingerprint() {
    Path keyFile = tempDir.resolve("secret.key");
    KeyRing original = KeyFileManager.loadAllOrCreate(keyFile);
    KeyRing rotated = KeyFileManager.rotate(keyFile, original);

    KeyRing reloaded = KeyFileManager.loadAllOrCreate(keyFile);

    // Simulates a second replica that has received the rotated key files -- it must compute the
    // same fingerprint as the replica that performed the rotation.
    assertEquals(rotated.fingerprint(), reloaded.fingerprint());
  }

  @Test
  void a_ring_that_never_received_a_rotation_still_computes_its_own_stable_fingerprint() {
    Path keyFileA = tempDir.resolve("a/secret.key");
    Path keyFileB = tempDir.resolve("b/secret.key");
    // Simulates two replicas starting from genuinely different key material (rather than sharing
    // a file) -- a divergence that must never accidentally collide.
    KeyRing ringA = KeyFileManager.loadAllOrCreate(keyFileA);
    KeyRing ringB = KeyFileManager.loadAllOrCreate(keyFileB);

    assertNotEquals(ringA.fingerprint(), ringB.fingerprint());
  }
}
