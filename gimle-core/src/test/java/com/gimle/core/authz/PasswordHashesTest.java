package com.gimle.core.authz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.jupiter.api.Test;

class PasswordHashesTest {

  /**
   * Builds an {@code iterations || salt || hash} blob at an explicit, caller-chosen iteration count
   * -- {@code PasswordHashes} itself only ever hashes at today's {@link PasswordHashes#ITERATIONS},
   * so a test proving old-count hashes still verify (and are flagged for rehash) after that
   * constant rises needs its own way to produce one at a lower count, standing in for what a hash
   * stored years ago under a lower OWASP floor would actually look like on disk today.
   */
  private static byte[] hashAtIterations(char[] password, int iterations) {
    byte[] salt = new byte[16];
    new java.security.SecureRandom().nextBytes(salt);
    PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
    byte[] derived;
    try {
      derived =
          SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException(e);
    } finally {
      spec.clearPassword();
    }
    ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + salt.length + derived.length);
    buffer.putInt(iterations);
    buffer.put(salt);
    buffer.put(derived);
    return buffer.array();
  }

  @Test
  void hash_then_verify_round_trips() {
    byte[] hash = PasswordHashes.hash("correct horse battery staple".toCharArray());
    assertTrue(PasswordHashes.verify("correct horse battery staple".toCharArray(), hash));
  }

  @Test
  void verify_rejects_the_wrong_password() {
    byte[] hash = PasswordHashes.hash("correct horse battery staple".toCharArray());
    assertFalse(PasswordHashes.verify("wrong password".toCharArray(), hash));
  }

  @Test
  void two_hashes_of_the_same_password_differ_because_of_the_random_salt() {
    byte[] first = PasswordHashes.hash("same password".toCharArray());
    byte[] second = PasswordHashes.hash("same password".toCharArray());
    assertNotEquals(Arrays.toString(first), Arrays.toString(second));
    assertTrue(PasswordHashes.verify("same password".toCharArray(), first));
    assertTrue(PasswordHashes.verify("same password".toCharArray(), second));
  }

  @Test
  void verify_rejects_a_truncated_hash_instead_of_throwing() {
    assertFalse(PasswordHashes.verify("anything".toCharArray(), new byte[] {1, 2, 3}));
  }

  @Test
  void verify_still_succeeds_against_a_hash_produced_at_a_lower_iteration_count() {
    // Proves the fix: verify() re-derives at the count embedded in the hash, not today's
    // PasswordHashes.ITERATIONS -- raising that constant later must never break a hash minted
    // under a previous, lower one.
    byte[] oldHash = hashAtIterations("legacy password".toCharArray(), 1_000);
    assertTrue(PasswordHashes.verify("legacy password".toCharArray(), oldHash));
  }

  @Test
  void a_hash_produced_at_todays_iteration_count_never_needs_rehashing() {
    byte[] hash = PasswordHashes.hash("correct horse battery staple".toCharArray());
    assertFalse(PasswordHashes.needsRehash(hash));
  }

  @Test
  void a_hash_produced_at_a_lower_iteration_count_needs_rehashing() {
    byte[] oldHash =
        hashAtIterations("legacy password".toCharArray(), PasswordHashes.ITERATIONS - 1);
    assertTrue(PasswordHashes.needsRehash(oldHash));
  }

  @Test
  void a_malformed_blob_conservatively_reports_needing_a_rehash() {
    assertTrue(PasswordHashes.needsRehash(new byte[] {1, 2}));
    assertTrue(PasswordHashes.needsRehash(null));
  }
}
