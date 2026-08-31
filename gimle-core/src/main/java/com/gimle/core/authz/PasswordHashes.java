package com.gimle.core.authz;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2WithHmacSHA256 via the JDK's own {@link SecretKeyFactory} -- no external crypto dependency,
 * matching {@code SecretCipher}'s existing JDK-only posture for AES-256-GCM. Output is {@code
 * iterations || salt || hash}: the iteration count travels with the hash rather than being assumed
 * from today's {@link #ITERATIONS} constant, so {@link #verify} always re-derives at whatever count
 * actually produced a given stored hash. Without that, raising {@link #ITERATIONS} later (to track
 * a rising OWASP floor, as this exact value already once did) would make every previously-stored
 * hash stop verifying the instant the constant changed -- a silent mass lockout with no migration
 * path, since nothing recorded which count a given hash was made with. {@link #needsRehash} lets a
 * caller that just verified a password against an older-count hash choose to re-hash-and-store it
 * at today's count, absorbing a future floor increase incrementally instead of never. A single
 * {@code byte[]} round-trips through {@link #verify}/{@link #needsRehash} without the caller
 * tracking the salt or iteration count separately, the same self-contained shape {@code
 * SecretCipher}'s {@code iv || ciphertext} already has. Lives in {@code gimle-core}, not {@code
 * gimle-controlplane}, because both {@code gimle-pki}'s {@code PkiBootstrapMain} (seeding the
 * bootstrap account) and {@code gimle-controlplane}'s {@code ApiServer} (verifying a console login)
 * need it, and {@code gimle-pki} must never depend on {@code gimle-controlplane}.
 */
public final class PasswordHashes {

  private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
  private static final int SALT_BYTES = 16;
  private static final int HASH_BITS = 256;
  static final int ITERATIONS = 210_000; // OWASP-recommended floor for PBKDF2-HMAC-SHA256, today
  private static final int ITERATIONS_HEADER_BYTES = Integer.BYTES;
  // Shared, not one `new SecureRandom()` per call: a fresh instance's own self-seeding cost
  // dominates the actual nextBytes() work for a value this small, and SecureRandom is safe for
  // concurrent use by design (its own contract, unlike java.util.Random).
  private static final SecureRandom RANDOM = new SecureRandom();

  private PasswordHashes() {}

  /**
   * Produces a fresh {@code iterations || salt || hash} for {@code password} -- never two identical
   * outputs, always stamped with today's {@link #ITERATIONS}.
   */
  public static byte[] hash(char[] password) {
    byte[] salt = new byte[SALT_BYTES];
    RANDOM.nextBytes(salt);
    byte[] derived = derive(password, salt, ITERATIONS);
    ByteBuffer buffer = ByteBuffer.allocate(ITERATIONS_HEADER_BYTES + salt.length + derived.length);
    buffer.putInt(ITERATIONS);
    buffer.put(salt);
    buffer.put(derived);
    return buffer.array();
  }

  /**
   * Re-derives a hash from {@code password} using the iteration count and salt embedded in {@code
   * iterationsSaltAndHash} -- never today's {@link #ITERATIONS}, so a hash produced under an older
   * (lower) count still verifies correctly after the constant is later raised -- then
   * constant-time-compares it against the stored hash via {@link MessageDigest#isEqual}, never
   * {@code Arrays.equals}, which short-circuits on the first differing byte and would leak timing
   * information about how much of a guessed password matched.
   */
  public static boolean verify(char[] password, byte[] iterationsSaltAndHash) {
    if (iterationsSaltAndHash == null
        || iterationsSaltAndHash.length <= ITERATIONS_HEADER_BYTES + SALT_BYTES) {
      return false;
    }
    ByteBuffer buffer = ByteBuffer.wrap(iterationsSaltAndHash);
    int iterations = buffer.getInt();
    if (iterations <= 0) {
      return false;
    }
    byte[] salt = new byte[SALT_BYTES];
    buffer.get(salt);
    byte[] expectedHash = new byte[buffer.remaining()];
    buffer.get(expectedHash);
    byte[] actualHash = derive(password, salt, iterations);
    return MessageDigest.isEqual(actualHash, expectedHash);
  }

  /**
   * True when {@code iterationsSaltAndHash} was produced at a lower iteration count than today's
   * {@link #ITERATIONS} -- never itself a verification result; a caller must {@link #verify} the
   * password first and only consult this once that succeeds, then re-hash-and-store the password at
   * today's count to absorb the upgrade. A malformed/too-short blob is conservatively reported as
   * needing a rehash rather than throwing, matching {@link #verify}'s own "malformed input is
   * simply not a valid credential" posture.
   */
  public static boolean needsRehash(byte[] iterationsSaltAndHash) {
    if (iterationsSaltAndHash == null || iterationsSaltAndHash.length < ITERATIONS_HEADER_BYTES) {
      return true;
    }
    int iterations = ByteBuffer.wrap(iterationsSaltAndHash).getInt();
    return iterations < ITERATIONS;
  }

  private static byte[] derive(char[] password, byte[] salt, int iterations) {
    PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
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
