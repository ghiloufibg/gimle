package com.gimle.fafnir.secret;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM encrypt/decrypt for secret values -- the JDK's own {@code Cipher}/{@code
 * SecretKeySpec}, no external crypto library, matching this project's "prefer what the JDK already
 * provides" posture (AppCDS/JFR/{@code ModuleLayer} are all stock-JDK mechanisms too).
 *
 * <p>Output is {@code version(1) || keyId(1) || iv(12) || ciphertext-with-tag}: {@code keyId} lets
 * {@link #decrypt(byte[], Map)} pick the right key out of a {@link KeyFileManager} {@link KeyRing}
 * after a rotation, without a caller having to track which key encrypted which blob separately.
 *
 * <p>{@link #decrypt} also accepts the legacy, pre-key-id {@code iv || ciphertext-with-tag} layout
 * (no version/key-id prefix at all) for anything encrypted before this field existed. There is no
 * reserved version byte in that legacy layout to branch on directly -- a legacy blob's first byte
 * is just the first byte of a uniformly random IV, indistinguishable by content from {@link
 * #CURRENT_VERSION}. AES-GCM's own authentication tag is the real discriminator instead: a
 * new-format decode attempt against a legacy blob fails tag verification (astronomically unlikely
 * to spuriously succeed), so decoding falls back to the legacy layout under key id 0 -- the
 * original, pre-rotation key file always keeps that id -- on exactly that failure, never by
 * guessing from the byte pattern alone.
 */
public final class SecretCipher {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int GCM_IV_LENGTH_BYTES = 12;
  private static final int GCM_TAG_LENGTH_BITS = 128;
  private static final byte CURRENT_VERSION = 1;
  private static final byte LEGACY_KEY_ID = 0;
  private static final int PREFIX_LENGTH = 2; // version + keyId
  // Shared, not one `new SecureRandom()` per encrypt call: a fresh instance's own self-seeding
  // cost dominates the actual nextBytes() work for a value this small, and SecureRandom is safe
  // for concurrent use by design (its own contract, unlike java.util.Random).
  private static final SecureRandom RANDOM = new SecureRandom();

  private SecretCipher() {}

  /** Convenience for a single-key caller (the common case: no rotation in play). */
  public static byte[] encrypt(byte[] plaintext, SecretKey key) {
    return encrypt(plaintext, key, LEGACY_KEY_ID);
  }

  public static byte[] encrypt(byte[] plaintext, SecretKey key, byte keyId) {
    byte[] iv = randomIv();
    byte[] ciphertext = runCipher(Cipher.ENCRYPT_MODE, plaintext, key, iv);
    byte[] result = new byte[PREFIX_LENGTH + iv.length + ciphertext.length];
    result[0] = CURRENT_VERSION;
    result[1] = keyId;
    System.arraycopy(iv, 0, result, PREFIX_LENGTH, iv.length);
    System.arraycopy(ciphertext, 0, result, PREFIX_LENGTH + iv.length, ciphertext.length);
    return result;
  }

  /**
   * Convenience for a single-key caller; equivalent to {@code decrypt(blob, Map.of((byte) 0,
   * key))}.
   */
  public static byte[] decrypt(byte[] blob, SecretKey key) {
    return decrypt(blob, Map.of(LEGACY_KEY_ID, key));
  }

  public static byte[] decrypt(byte[] blob, Map<Byte, SecretKey> keysById) {
    if (blob.length >= PREFIX_LENGTH + GCM_IV_LENGTH_BYTES && blob[0] == CURRENT_VERSION) {
      SecretKey key = keysById.get(blob[1]);
      if (key != null) {
        try {
          byte[] iv = Arrays.copyOfRange(blob, PREFIX_LENGTH, PREFIX_LENGTH + GCM_IV_LENGTH_BYTES);
          byte[] ciphertext =
              Arrays.copyOfRange(blob, PREFIX_LENGTH + GCM_IV_LENGTH_BYTES, blob.length);
          return runCipher(Cipher.DECRYPT_MODE, ciphertext, key, iv);
        } catch (IllegalStateException newFormatDecryptFailed) {
          // Falls through to the legacy attempt below -- see the class javadoc for why this,
          // rather than the leading byte, is the real format discriminator.
        }
      }
    }
    SecretKey legacyKey = keysById.get(LEGACY_KEY_ID);
    if (legacyKey == null) {
      throw new IllegalStateException(
          "no key id " + LEGACY_KEY_ID + " available to attempt legacy-format decryption");
    }
    return decryptLegacy(blob, legacyKey);
  }

  private static byte[] decryptLegacy(byte[] ivAndCiphertext, SecretKey key) {
    if (ivAndCiphertext.length < GCM_IV_LENGTH_BYTES) {
      throw new IllegalArgumentException(
          "ciphertext shorter than the GCM IV it must be prefixed with");
    }
    byte[] iv = Arrays.copyOfRange(ivAndCiphertext, 0, GCM_IV_LENGTH_BYTES);
    byte[] ciphertext =
        Arrays.copyOfRange(ivAndCiphertext, GCM_IV_LENGTH_BYTES, ivAndCiphertext.length);
    return runCipher(Cipher.DECRYPT_MODE, ciphertext, key, iv);
  }

  private static byte[] randomIv() {
    byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
    RANDOM.nextBytes(iv);
    return iv;
  }

  private static byte[] runCipher(int mode, byte[] input, SecretKey key, byte[] iv) {
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      return cipher.doFinal(input);
    } catch (GeneralSecurityException e) {
      String action = mode == Cipher.ENCRYPT_MODE ? "encryption" : "decryption";
      throw new IllegalStateException("secret " + action + " failed", e);
    }
  }
}
