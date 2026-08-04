package com.gimle.core.authz;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2WithHmacSHA256 via the JDK's own {@link SecretKeyFactory} -- no external crypto dependency,
 * matching {@code SecretCipher}'s existing JDK-only posture for AES-256-GCM. Output is {@code salt
 * || hash}, self-contained the same way {@code SecretCipher}'s {@code iv || ciphertext} is, so a
 * single {@code byte[]} round-trips through {@link #verify} without the caller tracking the salt
 * separately. Lives in {@code gimle-core}, not {@code gimle-controlplane}, because both {@code
 * gimle-pki}'s {@code PkiBootstrapMain} (seeding the bootstrap account) and {@code
 * gimle-controlplane}'s {@code ApiServer} (verifying a console login) need it, and {@code
 * gimle-pki} must never depend on {@code gimle-controlplane}.
 */
public final class PasswordHashes {

  private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
  private static final int SALT_BYTES = 16;
  private static final int HASH_BITS = 256;
  private static final int ITERATIONS = 210_000; // OWASP-recommended floor for PBKDF2-HMAC-SHA256

  private PasswordHashes() {}

  /** Produces a fresh {@code salt || hash} for {@code password} -- never two identical outputs. */
  public static byte[] hash(char[] password) {
    byte[] salt = new byte[SALT_BYTES];
    new SecureRandom().nextBytes(salt);
    byte[] derived = derive(password, salt);
    byte[] output = new byte[salt.length + derived.length];
    System.arraycopy(salt, 0, output, 0, salt.length);
    System.arraycopy(derived, 0, output, salt.length, derived.length);
    return output;
  }

  /**
   * Re-derives a hash from {@code password} using the salt embedded in {@code saltAndHash}, then
   * constant-time-compares it against the stored hash via {@link MessageDigest#isEqual} -- never
   * {@link Arrays#equals}, which short-circuits on the first differing byte and would leak timing
   * information about how much of a guessed password matched.
   */
  public static boolean verify(char[] password, byte[] saltAndHash) {
    if (saltAndHash == null || saltAndHash.length <= SALT_BYTES) {
      return false;
    }
    byte[] salt = Arrays.copyOfRange(saltAndHash, 0, SALT_BYTES);
    byte[] expectedHash = Arrays.copyOfRange(saltAndHash, SALT_BYTES, saltAndHash.length);
    byte[] actualHash = derive(password, salt);
    return MessageDigest.isEqual(actualHash, expectedHash);
  }

  private static byte[] derive(char[] password, byte[] salt) {
    PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, HASH_BITS);
    try {
      SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
      return factory.generateSecret(spec).getEncoded();
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", e);
    } finally {
      spec.clearPassword();
    }
  }
}
