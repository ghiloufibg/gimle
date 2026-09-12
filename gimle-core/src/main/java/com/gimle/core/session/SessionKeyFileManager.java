package com.gimle.core.session;

import com.gimle.core.exception.GimleSecretsException;
import com.gimle.core.io.OwnerOnlyFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Loads a process's own AES-256 session-cookie-signing key from {@code keyFilePath}, generating one
 * on first run if absent -- the same load-or-create/owner-only-permissions logic {@code
 * gimle-fafnir}'s own {@code KeyFileManager} uses for secret-value encryption keys, kept as a
 * separate, single-key (no rotation, no ring) class here rather than a shared dependency on that
 * one: this key signs session tokens, not secret values, and {@link SessionTokens}' own javadoc
 * explains why it's deliberately never rotated (a session token's short TTL already bounds its
 * exposure window) -- a genuinely different lifecycle from a key ring, not the same concern split
 * across two processes.
 *
 * <p>Lives in {@code gimle-core} so both {@code gimle-controlplane}'s {@code ApiServer} and {@code
 * gimle-fafnir}'s {@code FafnirServer} construct their own independent instance from the same
 * class, each pointed at its own key file -- deliberately never a shared key or shared file between
 * the two processes: each console's session is its own, matching the rest of Fafnir's own
 * defense-in-depth posture (it never trusts "authenticated somewhere else" as proof by itself).
 */
public final class SessionKeyFileManager {

  private static final String ALGORITHM = "AES";
  private static final int KEY_BITS = 256;
  private static final Set<Integer> VALID_AES_KEY_LENGTHS = Set.of(16, 24, 32);

  private SessionKeyFileManager() {}

  public static SecretKey loadOrCreate(Path keyFilePath) {
    try {
      if (Files.exists(keyFilePath)) {
        byte[] encoded = Files.readAllBytes(keyFilePath);
        if (!VALID_AES_KEY_LENGTHS.contains(encoded.length)) {
          throw GimleSecretsException.invalidKeyFile(keyFilePath, encoded.length);
        }
        return new SecretKeySpec(encoded, ALGORITHM);
      }
      SecretKey key = generateKey();
      Path parent = keyFilePath.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      OwnerOnlyFiles.write(keyFilePath, key.getEncoded());
      return key;
    } catch (IOException e) {
      throw new UncheckedIOException(
          "failed to load or create session signing key file: " + keyFilePath, e);
    }
  }

  private static SecretKey generateKey() {
    try {
      KeyGenerator generator = KeyGenerator.getInstance(ALGORITHM);
      generator.init(KEY_BITS);
      return generator.generateKey();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("AES key generation unavailable", e);
    }
  }
}
