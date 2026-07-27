package com.gimle.controlplane.secret;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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
}
