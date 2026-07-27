package com.gimle.controlplane.secret;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM encrypt/decrypt for {@code ConfigEntry} values (Phase 5 design §6.2) -- the JDK's own
 * {@code Cipher}/{@code SecretKeySpec}, no external crypto library, matching this project's "prefer
 * what the JDK already provides" posture (AppCDS/JFR/{@code ModuleLayer} are all stock-JDK
 * mechanisms too). Output is {@code iv || ciphertext-with-tag}, self-contained so a single {@code
 * byte[]} round-trips through {@code decrypt} without a caller having to track the IV separately.
 */
public final class SecretCipher {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int GCM_IV_LENGTH_BYTES = 12;
  private static final int GCM_TAG_LENGTH_BITS = 128;

  private SecretCipher() {}

  public static byte[] encrypt(byte[] plaintext, SecretKey key) {
    try {
      byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
      new SecureRandom().nextBytes(iv);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext);
      byte[] result = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, result, 0, iv.length);
      System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
      return result;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("secret encryption failed", e);
    }
  }

  public static byte[] decrypt(byte[] ivAndCiphertext, SecretKey key) {
    if (ivAndCiphertext.length < GCM_IV_LENGTH_BYTES) {
      throw new IllegalArgumentException(
          "ciphertext shorter than the GCM IV it must be prefixed with");
    }
    try {
      byte[] iv = Arrays.copyOfRange(ivAndCiphertext, 0, GCM_IV_LENGTH_BYTES);
      byte[] ciphertext =
          Arrays.copyOfRange(ivAndCiphertext, GCM_IV_LENGTH_BYTES, ivAndCiphertext.length);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      return cipher.doFinal(ciphertext);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("secret decryption failed", e);
    }
  }
}
