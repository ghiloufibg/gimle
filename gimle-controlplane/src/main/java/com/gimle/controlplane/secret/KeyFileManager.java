package com.gimle.controlplane.secret;

import com.gimle.core.exception.GimleSecretsException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the control plane's AES-256 secrets master key from {@code keyFilePath}, generating one on
 * first run if absent. A platform-generated local key file is self-contained, with no external KMS
 * dependency, consistent with this project's MVP-first/YAGNI posture. File permissions are
 * restricted to owner-read-only wherever the filesystem supports POSIX permissions (every real
 * deployment target -- Linux, macOS); on a filesystem that doesn't (Windows, common only in local
 * development), the key is still written but the restriction is skipped with a logged warning
 * rather than a hard failure, since {@code java.nio.file}'s own POSIX view is simply unavailable
 * there.
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
      Path parent = keyFilePath.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.write(keyFilePath, key.getEncoded());
      restrictPermissions(keyFilePath);
      return key;
    } catch (IOException e) {
      throw new UncheckedIOException(
          "failed to load or create secrets key file: " + keyFilePath, e);
    }
  }

  /**
   * Loads the full rotation history sharing {@code baseKeyFilePath} (P2-16): {@code
   * baseKeyFilePath} itself is always key id 0 (created via {@link #loadOrCreate} if this is the
   * first run, so a cluster that never rotates keeps today's exact single-key layout), plus any
   * sibling {@code <baseFileName>.<id>} files a prior {@link #rotate} call wrote, plus whichever id
   * a sibling {@code <baseFileName>.active} file names as current (defaulting to 0 if that sidecar
   * is absent).
   */
  public static KeyRing loadAllOrCreate(Path baseKeyFilePath) {
    SecretKey keyZero = loadOrCreate(baseKeyFilePath);
    Map<Byte, SecretKey> keysById = new HashMap<>();
    keysById.put((byte) 0, keyZero);
    Path parent = baseKeyFilePath.getParent();
    String baseFileName = fileNameString(baseKeyFilePath);
    if (parent != null && Files.isDirectory(parent)) {
      String prefix = baseFileName + ".";
      try (DirectoryStream<Path> siblings = Files.newDirectoryStream(parent, prefix + "*")) {
        for (Path sibling : siblings) {
          String suffix = fileNameString(sibling).substring(prefix.length());
          if (suffix.equals("active") || !suffix.chars().allMatch(Character::isDigit)) {
            continue;
          }
          int id = Integer.parseInt(suffix);
          if (id < 0 || id > 255) {
            continue; // not a key file this class would ever have written itself
          }
          byte[] encoded = Files.readAllBytes(sibling);
          if (!VALID_AES_KEY_LENGTHS.contains(encoded.length)) {
            throw GimleSecretsException.invalidKeyFile(sibling, encoded.length);
          }
          keysById.put((byte) id, new SecretKeySpec(encoded, ALGORITHM));
        }
      } catch (IOException e) {
        throw new UncheckedIOException(
            "failed to scan for rotated secrets key files: " + parent, e);
      }
    }
    byte activeKeyId = readActiveKeyId(activeKeyFile(baseKeyFilePath), keysById.keySet());
    return new KeyRing(activeKeyId, keysById);
  }

  /**
   * Generates a fresh key, adds it to {@code current} under the next unused id, and repoints the
   * {@code <baseFileName>.active} sidecar at it -- every previously-loaded key stays in the
   * returned ring (and its file untouched on disk), so ciphertext encrypted under any of them keeps
   * decrypting.
   */
  public static KeyRing rotate(Path baseKeyFilePath, KeyRing current) {
    int highestExisting = -1;
    for (byte id : current.keysById().keySet()) {
      highestExisting = Math.max(highestExisting, Byte.toUnsignedInt(id));
    }
    if (highestExisting >= 255) {
      throw new IllegalStateException("secrets key ring already holds the maximum 256 key ids");
    }
    byte newId = (byte) (highestExisting + 1);
    SecretKey newKey = generateKey();
    try {
      Path newKeyFile =
          baseKeyFilePath.resolveSibling(
              baseKeyFilePath.getFileName() + "." + Byte.toUnsignedInt(newId));
      Files.write(newKeyFile, newKey.getEncoded());
      restrictPermissions(newKeyFile);
      Path activeFile = activeKeyFile(baseKeyFilePath);
      Files.writeString(
          activeFile, String.valueOf(Byte.toUnsignedInt(newId)), StandardCharsets.UTF_8);
      restrictPermissions(activeFile);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to write rotated secrets key file", e);
    }
    Map<Byte, SecretKey> updated = new HashMap<>(current.keysById());
    updated.put(newId, newKey);
    return new KeyRing(newId, updated);
  }

  private static Path activeKeyFile(Path baseKeyFilePath) {
    return baseKeyFilePath.resolveSibling(baseKeyFilePath.getFileName() + ".active");
  }

  private static byte readActiveKeyId(Path activeKeyFile, Set<Byte> knownIds) {
    if (!Files.exists(activeKeyFile)) {
      return 0;
    }
    try {
      String text = Files.readString(activeKeyFile, StandardCharsets.UTF_8).strip();
      int id = Integer.parseInt(text);
      if (id < 0 || id > 255 || !knownIds.contains((byte) id)) {
        log.warn(
            "{} names key id {}, which has no corresponding key file; falling back to key id 0",
            activeKeyFile,
            text);
        return 0;
      }
      return (byte) id;
    } catch (IOException | NumberFormatException e) {
      log.warn(
          "failed to read active secrets key id from {}: {}; falling back to key id 0",
          activeKeyFile,
          e.getMessage());
      return 0;
    }
  }

  // getFileName() can only return null for a zero-element path, which never happens for either
  // caller here (a configured key-file path, or a Path yielded by a directory-stream walk) --
  // centralizing the null-guard rather than repeating it at each call site is what satisfies it.
  private static String fileNameString(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      throw new IllegalStateException("expected a regular file, got " + path);
    }
    return fileName.toString();
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
