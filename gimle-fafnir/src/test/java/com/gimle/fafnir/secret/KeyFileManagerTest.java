package com.gimle.fafnir.secret;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleSecretsException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/** Platform-generated local key file (Phase 5 design §6.1). */
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

  // ---- P2-16: multi-key ring load and rotation ----

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
}
