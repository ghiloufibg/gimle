package com.gimle.core.session;

import com.gimle.core.exception.GimleSecretsException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger log = LoggerFactory.getLogger(SessionKeyFileManager.class);
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
      createKeyFile(keyFilePath);
      Files.write(keyFilePath, key.getEncoded(), StandardOpenOption.WRITE);
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

  /**
   * Creates {@code path} atomically with owner-only permissions already applied on a POSIX
   * filesystem, so the key file is never briefly visible at default (often world-readable)
   * permissions between creation and a separate chmod call -- and never left behind at those
   * default permissions if the process crashes between the two. Falls back to a plain create plus a
   * logged warning where POSIX permissions aren't available.
   */
  private static void createKeyFile(Path path) throws IOException {
    if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.createFile(
          path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } else {
      Files.createFile(path);
      log.warn(
          "filesystem at {} does not support POSIX permissions; session signing key file was"
              + " created without owner-only restriction (expected only in local Windows"
              + " development -- every real deployment target restricts this)",
          path);
    }
  }
}
