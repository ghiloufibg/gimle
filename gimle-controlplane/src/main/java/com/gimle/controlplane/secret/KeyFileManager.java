package com.gimle.controlplane.secret;

import com.gimle.core.exception.GimleSecretsException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the control plane's AES-256 secrets master key from {@code keyFilePath}, generating one on
 * first run if absent (Phase 5 design §6.1: "platform-generated local key file" -- self-contained,
 * no external KMS dependency, consistent with this project's MVP-first/YAGNI posture). File
 * permissions are restricted to owner-read-only wherever the filesystem supports POSIX permissions
 * (every real deployment target -- Linux, macOS); on a filesystem that doesn't (Windows, common
 * only in local development), the key is still written but the restriction is skipped with a logged
 * warning rather than a hard failure, since {@code java.nio.file}'s own POSIX view is simply
 * unavailable there, not a platform this design chooses to special-case.
 */
public final class KeyFileManager {

  private static final Logger log = LoggerFactory.getLogger(KeyFileManager.class);
  private static final String ALGORITHM = "AES";
  private static final int KEY_BITS = 256;
  private static final Set<Integer> VALID_AES_KEY_LENGTHS = Set.of(16, 24, 32);

  private KeyFileManager() {}

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
      if (keyFilePath.getParent() != null) {
        Files.createDirectories(keyFilePath.getParent());
      }
      Files.write(keyFilePath, key.getEncoded());
      restrictPermissions(keyFilePath);
      return key;
    } catch (IOException e) {
      throw new UncheckedIOException(
          "failed to load or create secrets key file: " + keyFilePath, e);
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

  private static void restrictPermissions(Path path) throws IOException {
    if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    } else {
      log.warn(
          "filesystem at {} does not support POSIX permissions; secrets key file was written"
              + " without owner-only restriction (expected only in local Windows development --"
              + " every real deployment target restricts this)",
          path);
    }
  }
}
