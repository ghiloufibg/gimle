package com.gimle.fafnir.secret;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/** AES-256-GCM round-trip. */
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

  // ---- key id, and the legacy (pre-key-id) format ----

  @Test
  void round_trips_through_a_specific_key_id() throws Exception {
    SecretKey key = key();
    byte[] plaintext = "s3cr3t-value".getBytes(StandardCharsets.UTF_8);

    byte[] ciphertext = SecretCipher.encrypt(plaintext, key, (byte) 7);

    assertArrayEquals(plaintext, SecretCipher.decrypt(ciphertext, Map.of((byte) 7, key)));
  }

  @Test
  void decrypt_picks_the_right_key_out_of_several_by_the_blobs_own_key_id() throws Exception {
    SecretKey keyZero = key();
    SecretKey keyOne = key();
    byte[] plaintext = "s3cr3t-value".getBytes(StandardCharsets.UTF_8);
    byte[] underKeyOne = SecretCipher.encrypt(plaintext, keyOne, (byte) 1);

    Map<Byte, SecretKey> ring = Map.of((byte) 0, keyZero, (byte) 1, keyOne);

    assertArrayEquals(plaintext, SecretCipher.decrypt(underKeyOne, ring));
  }

  @Test
  void a_blob_naming_a_key_id_absent_from_the_ring_falls_back_to_legacy_decryption_and_fails()
      throws Exception {
    SecretKey key = key();
    byte[] underUnknownKeyId =
        SecretCipher.encrypt("s3cr3t".getBytes(StandardCharsets.UTF_8), key, (byte) 5);

    // Key id 5 isn't in this ring, and the blob is genuinely new-format (not actually legacy), so
    // the legacy fallback attempt (under key id 0) is guaranteed to fail its own GCM tag check.
    assertThrows(
        IllegalStateException.class,
        () -> SecretCipher.decrypt(underUnknownKeyId, Map.of((byte) 0, key)));
  }

  @Test
  void a_pre_key_id_legacy_blob_still_decrypts_under_key_id_zero() throws Exception {
    SecretKey key = key();
    byte[] plaintext = "s3cr3t-value".getBytes(StandardCharsets.UTF_8);
    // The exact legacy, pre-key-id layout: iv(12) || ciphertext-with-tag, no version/key-id prefix.
    byte[] legacyBlob = legacyEncrypt(plaintext, key);

    assertArrayEquals(plaintext, SecretCipher.decrypt(legacyBlob, Map.of((byte) 0, key)));
    // The single-key convenience overload is equivalent to a ring with only key id 0.
    assertArrayEquals(plaintext, SecretCipher.decrypt(legacyBlob, key));
  }

  private static byte[] legacyEncrypt(byte[] plaintext, SecretKey key) throws Exception {
    byte[] iv = new byte[12];
    new java.security.SecureRandom().nextBytes(iv);
    javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        javax.crypto.Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, iv));
    byte[] ciphertext = cipher.doFinal(plaintext);
    byte[] result = new byte[iv.length + ciphertext.length];
    System.arraycopy(iv, 0, result, 0, iv.length);
    System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
    return result;
  }
}
