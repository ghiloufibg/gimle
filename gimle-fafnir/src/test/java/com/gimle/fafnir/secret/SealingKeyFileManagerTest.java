package com.gimle.fafnir.secret;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleSecretsException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

class SealingKeyFileManagerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  void a_fresh_ring_holds_exactly_key_id_zero_active() {
    Path keyFile = tempDir.resolve("sealing.key");

    SealingKeyRing ring = SealingKeyFileManager.loadAllOrCreate(keyFile);

    assertEquals((byte) 0, ring.activeKeyId());
    assertEquals(1, ring.keyPairsById().size());
  }

  @Test
  void a_ring_loaded_via_a_second_manager_instance_can_unseal_what_the_first_sealed() {
    Path keyFile = tempDir.resolve("sealing.key");
    SealingKeyRing writer = SealingKeyFileManager.loadAllOrCreate(keyFile);
    byte[] aad = SealCipher.aadFor("acme", "db-creds", "password");
    SealCipher.SealedEnvelope envelope =
        SealCipher.seal(
            "hunter2".getBytes(StandardCharsets.UTF_8), writer.activePublicKey(), (byte) 0, aad);

    SealingKeyRing reader = SealingKeyFileManager.loadAllOrCreate(keyFile);
    byte[] recovered = SealCipher.unseal(envelope, reader.activeKeyPair().getPrivate(), aad);

    assertArrayEquals("hunter2".getBytes(StandardCharsets.UTF_8), recovered);
  }

  @Test
  void rotate_adds_a_new_active_keypair_while_keeping_the_old_one_loadable() {
    Path keyFile = tempDir.resolve("sealing.key");
    SealingKeyRing original = SealingKeyFileManager.loadAllOrCreate(keyFile);

    SealingKeyRing rotated = SealingKeyFileManager.rotate(keyFile, original);

    assertEquals((byte) 1, rotated.activeKeyId());
    assertEquals(2, rotated.keyPairsById().size());
    assertTrue(rotated.keyPairsById().containsKey((byte) 0));
  }

  @Test
  void a_blob_sealed_before_rotation_still_unseals_after_it() {
    Path keyFile = tempDir.resolve("sealing.key");
    SealingKeyRing before = SealingKeyFileManager.loadAllOrCreate(keyFile);
    byte[] aad = SealCipher.aadFor("acme", "db-creds", "password");
    SealCipher.SealedEnvelope envelope =
        SealCipher.seal(
            "hunter2".getBytes(StandardCharsets.UTF_8),
            before.activePublicKey(),
            before.activeKeyId(),
            aad);

    SealingKeyRing after = SealingKeyFileManager.rotate(keyFile, before);

    byte[] recovered =
        SealCipher.unseal(
            envelope, after.keyPairsById().get(envelope.sealingKeyId()).getPrivate(), aad);
    assertArrayEquals("hunter2".getBytes(StandardCharsets.UTF_8), recovered);
  }

  @Test
  void a_rotation_is_visible_to_a_ring_freshly_loaded_from_disk_afterward() {
    Path keyFile = tempDir.resolve("sealing.key");
    SealingKeyRing original = SealingKeyFileManager.loadAllOrCreate(keyFile);
    SealingKeyRing rotated = SealingKeyFileManager.rotate(keyFile, original);

    SealingKeyRing reloaded = SealingKeyFileManager.loadAllOrCreate(keyFile);

    assertEquals(rotated.activeKeyId(), reloaded.activeKeyId());
    assertEquals(rotated.keyPairsById().size(), reloaded.keyPairsById().size());
  }

  // ---- retirement ----

  @Test
  void retire_removes_a_non_active_keypair_from_the_ring_and_from_disk() {
    Path keyFile = tempDir.resolve("sealing.key");
    SealingKeyRing ring = SealingKeyFileManager.loadAllOrCreate(keyFile);
    SealingKeyRing rotated = SealingKeyFileManager.rotate(keyFile, ring); // active now 1
    SealingKeyRing rotatedAgain =
        SealingKeyFileManager.rotate(keyFile, rotated); // active now 2, id 1 retireable

    SealingKeyRing retired = SealingKeyFileManager.retire(keyFile, rotatedAgain, (byte) 1);

    assertFalse(retired.keyPairsById().containsKey((byte) 1));
  }

  @Test
  void a_blob_sealed_under_a_since_retired_key_can_no_longer_be_unsealed() {
    Path keyFile = tempDir.resolve("sealing.key");
    SealingKeyRing ring = SealingKeyFileManager.loadAllOrCreate(keyFile);
    SealingKeyRing rotated = SealingKeyFileManager.rotate(keyFile, ring); // active now 1
    byte[] aad = SealCipher.aadFor("acme", "db-creds", "password");
    SealCipher.SealedEnvelope envelope =
        SealCipher.seal(
            "hunter2".getBytes(StandardCharsets.UTF_8),
            rotated.activePublicKey(),
            rotated.activeKeyId(),
            aad);
    SealingKeyRing rotatedAgain =
        SealingKeyFileManager.rotate(keyFile, rotated); // active now 2, id 1 retireable

    SealingKeyRing retired =
        SealingKeyFileManager.retire(keyFile, rotatedAgain, rotated.activeKeyId());

    assertFalse(retired.keyPairsById().containsKey(envelope.sealingKeyId()));
  }

  @Test
  void retiring_the_active_key_is_rejected() {
    Path keyFile = tempDir.resolve("sealing.key");
    SealingKeyRing ring = SealingKeyFileManager.loadAllOrCreate(keyFile);
    SealingKeyRing rotated = SealingKeyFileManager.rotate(keyFile, ring);

    assertThrows(
        GimleSecretsException.class,
        () -> SealingKeyFileManager.retire(keyFile, rotated, rotated.activeKeyId()));
  }

  @Test
  void retiring_an_unknown_key_id_is_rejected() {
    Path keyFile = tempDir.resolve("sealing.key");
    SealingKeyRing ring = SealingKeyFileManager.loadAllOrCreate(keyFile);

    assertThrows(
        GimleSecretsException.class, () -> SealingKeyFileManager.retire(keyFile, ring, (byte) 99));
  }

  @Test
  void retiring_key_id_zero_is_rejected() {
    Path keyFile = tempDir.resolve("sealing.key");
    SealingKeyRing ring = SealingKeyFileManager.loadAllOrCreate(keyFile);
    SealingKeyRing rotated = SealingKeyFileManager.rotate(keyFile, ring);

    assertThrows(
        GimleSecretsException.class,
        () -> SealingKeyFileManager.retire(keyFile, rotated, (byte) 0));
  }
}
