package com.gimle.controlplane.secret;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/** AES-256-GCM round-trip (Phase 5 design §6.2). */
class SecretCipherTest {

  private static SecretKey key() throws NoSuchAlgorithmException {
    KeyGenerator generator = KeyGenerator.getInstance("AES");
    generator.init(256);
    return generator.generateKey();
  }

  @Test
  void round_trips_plaintext_through_encryption_and_decryption() throws Exception {
    SecretKey key = key();
    byte[] plaintext = "s3cr3t-value".getBytes(StandardCharsets.UTF_8);

    byte[] ciphertext = SecretCipher.encrypt(plaintext, key);
    byte[] decrypted = SecretCipher.decrypt(ciphertext, key);

    assertArrayEquals(plaintext, decrypted);
  }

  @Test
  void ciphertext_never_contains_the_plaintext_bytes() throws Exception {
    SecretKey key = key();
    byte[] plaintext = "s3cr3t-value".getBytes(StandardCharsets.UTF_8);

    byte[] ciphertext = SecretCipher.encrypt(plaintext, key);

    assertNotEquals(
        new String(plaintext, StandardCharsets.UTF_8),
        new String(ciphertext, StandardCharsets.UTF_8));
  }

  @Test
  void the_same_plaintext_encrypts_differently_each_time_due_to_a_random_iv() throws Exception {
    SecretKey key = key();
    byte[] plaintext = "s3cr3t-value".getBytes(StandardCharsets.UTF_8);

    byte[] first = SecretCipher.encrypt(plaintext, key);
    byte[] second = SecretCipher.encrypt(plaintext, key);

    assertNotEquals(
        new String(first, StandardCharsets.UTF_8), new String(second, StandardCharsets.UTF_8));
    assertArrayEquals(plaintext, SecretCipher.decrypt(first, key));
    assertArrayEquals(plaintext, SecretCipher.decrypt(second, key));
  }

  @Test
  void decrypting_with_the_wrong_key_fails() throws Exception {
    byte[] ciphertext = SecretCipher.encrypt("s3cr3t".getBytes(StandardCharsets.UTF_8), key());
    SecretKey wrongKey = key();

    assertThrows(IllegalStateException.class, () -> SecretCipher.decrypt(ciphertext, wrongKey));
  }

  @Test
  void decrypting_a_too_short_ciphertext_is_rejected() throws Exception {
    assertThrows(
        IllegalArgumentException.class, () -> SecretCipher.decrypt(new byte[] {1, 2, 3}, key()));
  }
}
