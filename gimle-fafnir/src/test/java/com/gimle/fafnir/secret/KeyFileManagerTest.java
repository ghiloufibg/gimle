package com.gimle.fafnir.secret;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleSecretsException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/** Platform-generated local key file. */
class KeyFileManagerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  void generates_a_key_on_first_run_and_reuses_it_on_later_runs() {
    Path keyFile = tempDir.resolve("secret.key");

    SecretKey first = KeyFileManager.loadOrCreate(keyFile);
    SecretKey second = KeyFileManager.loadOrCreate(keyFile);

    assertArrayEquals(first.getEncoded(), second.getEncoded());
  }

  @Test
  void a_key_loaded_via_a_second_manager_instance_can_decrypt_what_the_first_encrypted() {
    Path keyFile = tempDir.resolve("secret.key");
    SecretKey writer = KeyFileManager.loadOrCreate(keyFile);
    byte[] ciphertext = SecretCipher.encrypt("hello".getBytes(), writer);

    SecretKey reader = KeyFileManager.loadOrCreate(keyFile);
    byte[] plaintext = SecretCipher.decrypt(ciphertext, reader);

    org.junit.jupiter.api.Assertions.assertEquals("hello", new String(plaintext));
  }

  @Test
  void a_corrupted_length_key_file_is_rejected_with_a_clear_error() throws Exception {
    Path keyFile = tempDir.resolve("corrupted.key");
    Files.write(keyFile, new byte[] {1, 2, 3, 4, 5, 6, 7});

    assertThrows(GimleSecretsException.class, () -> KeyFileManager.loadOrCreate(keyFile));
  }

  @Test
  void an_empty_key_file_is_rejected_with_a_clear_error() throws Exception {
    Path keyFile = tempDir.resolve("empty.key");
    Files.write(keyFile, new byte[0]);

    assertThrows(GimleSecretsException.class, () -> KeyFileManager.loadOrCreate(keyFile));
  }

  // ---- multi-key ring load and rotation ----

  @Test
  void a_fresh_ring_holds_exactly_key_id_zero_active() {
    Path keyFile = tempDir.resolve("secret.key");

    KeyRing ring = KeyFileManager.loadAllOrCreate(keyFile);

    assertEquals((byte) 0, ring.activeKeyId());
    assertEquals(1, ring.keysById().size());
  }

  @Test
  void rotate_adds_a_new_active_key_while_keeping_the_old_one_loadable() {
    Path keyFile = tempDir.resolve("secret.key");
    KeyRing original = KeyFileManager.loadAllOrCreate(keyFile);
    SecretKey originalActiveKey = original.activeKey();

    KeyRing rotated = KeyFileManager.rotate(keyFile, original);

    assertEquals((byte) 1, rotated.activeKeyId());
    assertEquals(2, rotated.keysById().size());
    assertArrayEquals(
        originalActiveKey.getEncoded(), rotated.keysById().get((byte) 0).getEncoded());
  }

  @Test
  void a_value_encrypted_before_rotation_still_decrypts_after_it() {
    Path keyFile = tempDir.resolve("secret.key");
    KeyRing before = KeyFileManager.loadAllOrCreate(keyFile);
    byte[] ciphertext =
        SecretCipher.encrypt("hello".getBytes(), before.activeKey(), before.activeKeyId());

    KeyRing after = KeyFileManager.rotate(keyFile, before);

    assertArrayEquals("hello".getBytes(), SecretCipher.decrypt(ciphertext, after.keysById()));
  }

  @Test
  void a_rotation_is_visible_to_a_ring_freshly_loaded_from_disk_afterward() {
    Path keyFile = tempDir.resolve("secret.key");
    KeyRing original = KeyFileManager.loadAllOrCreate(keyFile);
    KeyRing rotated = KeyFileManager.rotate(keyFile, original);

    KeyRing reloaded = KeyFileManager.loadAllOrCreate(keyFile);

    assertEquals(rotated.activeKeyId(), reloaded.activeKeyId());
    assertEquals(rotated.keysById().size(), reloaded.keysById().size());
    assertArrayEquals(rotated.activeKey().getEncoded(), reloaded.activeKey().getEncoded());
  }

  @Test
  void repeated_rotations_each_get_a_new_id_and_keep_every_prior_key() {
    Path keyFile = tempDir.resolve("secret.key");
    KeyRing ring = KeyFileManager.loadAllOrCreate(keyFile);

    ring = KeyFileManager.rotate(keyFile, ring);
    ring = KeyFileManager.rotate(keyFile, ring);
    ring = KeyFileManager.rotate(keyFile, ring);

    assertEquals((byte) 3, ring.activeKeyId());
    assertEquals(4, ring.keysById().size());
    assertTrue(ring.keysById().containsKey((byte) 0));
    assertTrue(ring.keysById().containsKey((byte) 1));
    assertTrue(ring.keysById().containsKey((byte) 2));
    assertTrue(ring.keysById().containsKey((byte) 3));
  }

  // ---- retirement ----

  @Test
  void retire_removes_a_non_active_key_from_the_ring_and_from_disk() {
    Path keyFile = tempDir.resolve("secret.key");
    KeyRing ring = KeyFileManager.loadAllOrCreate(keyFile);
    KeyRing rotated = KeyFileManager.rotate(keyFile, ring); // active now 1
    KeyRing rotatedAgain = KeyFileManager.rotate(keyFile, rotated); // active now 2, id 1 retireable

    KeyRing retired = KeyFileManager.retire(keyFile, rotatedAgain, (byte) 1);

    assertEquals(2, retired.keysById().size());
    assertTrue(retired.keysById().containsKey((byte) 0));
    assertTrue(retired.keysById().containsKey((byte) 2));
    assertFalse(retired.keysById().containsKey((byte) 1));
  }

  @Test
  void a_retired_key_stays_gone_after_reloading_from_disk() {
    Path keyFile = tempDir.resolve("secret.key");
    KeyRing ring = KeyFileManager.loadAllOrCreate(keyFile);
    KeyRing rotated = KeyFileManager.rotate(keyFile, ring);
    KeyRing rotatedAgain = KeyFileManager.rotate(keyFile, rotated); // active now 2, id 1 retireable
    KeyFileManager.retire(keyFile, rotatedAgain, (byte) 1);

    KeyRing reloaded = KeyFileManager.loadAllOrCreate(keyFile);

    assertFalse(reloaded.keysById().containsKey((byte) 1));
    assertTrue(reloaded.keysById().containsKey((byte) 0));
    assertTrue(reloaded.keysById().containsKey((byte) 2));
  }

  @Test
  void a_value_encrypted_under_a_retired_key_no_longer_decrypts() {
    Path keyFile = tempDir.resolve("secret.key");
    KeyRing ring = KeyFileManager.loadAllOrCreate(keyFile);
    KeyRing rotated = KeyFileManager.rotate(keyFile, ring); // active now 1
    byte[] ciphertext =
        SecretCipher.encrypt("hello".getBytes(), rotated.activeKey(), rotated.activeKeyId());
    KeyRing rotatedAgain = KeyFileManager.rotate(keyFile, rotated); // active now 2, id 1 retireable

    KeyRing retired = KeyFileManager.retire(keyFile, rotatedAgain, rotated.activeKeyId());

    assertThrows(
        IllegalStateException.class, () -> SecretCipher.decrypt(ciphertext, retired.keysById()));
  }

  @Test
  void retiring_the_active_key_is_rejected() {
    Path keyFile = tempDir.resolve("secret.key");
    KeyRing ring = KeyFileManager.loadAllOrCreate(keyFile);

    assertThrows(
        GimleSecretsException.class,
        () -> KeyFileManager.retire(keyFile, ring, ring.activeKeyId()));
  }

  @Test
  void retiring_an_unknown_key_id_is_rejected() {
    Path keyFile = tempDir.resolve("secret.key");
    KeyRing ring = KeyFileManager.loadAllOrCreate(keyFile);
    KeyRing rotated = KeyFileManager.rotate(keyFile, ring);

    assertThrows(
        GimleSecretsException.class, () -> KeyFileManager.retire(keyFile, rotated, (byte) 99));
  }

  @Test
  void retiring_key_id_zero_is_rejected() {
    Path keyFile = tempDir.resolve("secret.key");
    KeyRing ring = KeyFileManager.loadAllOrCreate(keyFile);
    KeyRing rotated = KeyFileManager.rotate(keyFile, ring);

    assertThrows(
        GimleSecretsException.class, () -> KeyFileManager.retire(keyFile, rotated, (byte) 0));
  }
}
