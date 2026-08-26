package com.gimle.fafnir.secret;

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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads Fafnir's AES-256 secrets master key from {@code keyFilePath}, generating one on first run
 * if absent. A platform-generated local key file is self-contained, with no external KMS
 * dependency, consistent with this project's MVP-first/YAGNI posture. File permissions are
 * restricted to owner-read-only wherever the filesystem supports POSIX permissions (every real
 * deployment target -- Linux, macOS); on a filesystem that doesn't (Windows, common only in local
 * development), the key is still written but the restriction is skipped with a logged warning
 * rather than a hard failure, since {@code java.nio.file}'s own POSIX view is simply unavailable
 * there.
 *
 * <p>Every Fafnir replica must be started with the same {@code keyFilePath} pointing at
 * identically-provisioned key material -- an operational precondition, not something this class
 * enforces itself, matching a single-operator, no-external-KMS deployment's actual needs.
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
   * Loads the full rotation history sharing {@code baseKeyFilePath}: {@code baseKeyFilePath} itself
   * is always key id 0 (created via {@link #loadOrCreate} if this is the first run, so a cluster
   * that never rotates keeps today's exact single-key layout), plus any sibling {@code
   * <baseFileName>.<id>} files a prior {@link #rotate} call wrote, plus whichever id a sibling
   * {@code <baseFileName>.active} file names as current (defaulting to 0 if that sidecar is
   * absent).
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
          int id;
          try {
            id = Integer.parseInt(suffix);
          } catch (NumberFormatException e) {
            continue; // all-digit but too long to fit an int -- not a key file this class wrote
          }
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
      Path newKeyFile = keyFilePathForId(baseKeyFilePath, newId);
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

  /**
   * Actually stops trusting {@code keyId}: deletes its on-disk key file and drops it from the
   * returned {@link KeyRing}, so any ciphertext still encrypted under it becomes permanently
   * unrecoverable through {@link SecretCipher#decrypt(byte[], Map)} the moment that ring replaces
   * {@code current} in {@code FafnirCrypto} -- a deliberately destructive operation, not a soft
   * flag, matching what "retire" is meant to accomplish. Rejects retiring {@code
   * current.activeKeyId()} (rotate to a new active key first) or an id {@code current} doesn't
   * hold.
   */
  public static KeyRing retire(Path baseKeyFilePath, KeyRing current, byte keyId) {
    if (keyId == 0) {
      // #loadAllOrCreate always regenerates a fresh id-0 key if baseKeyFilePath is absent (the
      // "first run" case every other id's own file relies on that never triggering for it) --
      // deleting it wouldn't retire it, it would silently resurrect a *different* key under the
      // same id on the next load. Rotate away from id 0; it can never be retired.
      throw GimleSecretsException.cannotRetireBaseKey("secrets");
    }
    if (keyId == current.activeKeyId()) {
      throw GimleSecretsException.cannotRetireActiveKey("secrets", Byte.toUnsignedInt(keyId));
    }
    if (!current.keysById().containsKey(keyId)) {
      throw GimleSecretsException.unknownKeyId("secrets", Byte.toUnsignedInt(keyId));
    }
    try {
      Files.delete(keyFilePathForId(baseKeyFilePath, keyId));
    } catch (IOException e) {
      throw new UncheckedIOException("failed to delete retired secrets key file", e);
    }
    Map<Byte, SecretKey> updated = new HashMap<>(current.keysById());
    updated.remove(keyId);
    recordRetiredKeyId(baseKeyFilePath, keyId);
    return new KeyRing(current.activeKeyId(), updated);
  }

  /**
   * Every key id ever retired for {@code baseKeyFilePath}, durably: the on-disk key file itself is
   * gone the moment {@link #retire} runs (that's what makes retirement destructive rather than a
   * soft flag), so without a separate record a later decrypt attempt against a retired id can't be
   * told apart from one that names an id this ring never held at all -- both just come up "no such
   * key." This is that record, read at startup by {@code FafnirCrypto} so it survives a restart,
   * and used only to give a specific "that key was retired" error rather than change what decrypts.
   */
  public static Set<Byte> loadRetiredKeyIds(Path baseKeyFilePath) {
    Path file = retiredKeyIdsFile(baseKeyFilePath);
    if (!Files.exists(file)) {
      return Set.of();
    }
    try {
      Set<Byte> ids = new HashSet<>();
      for (String token : Files.readString(file, StandardCharsets.UTF_8).split(",")) {
        String trimmed = token.strip();
        if (trimmed.isEmpty()) {
          continue;
        }
        int id = Integer.parseInt(trimmed);
        if (id >= 0 && id <= 255) {
          ids.add((byte) id);
        }
      }
      return Set.copyOf(ids);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read retired secrets key ids: " + file, e);
    }
  }

  private static void recordRetiredKeyId(Path baseKeyFilePath, byte keyId) {
    Set<Byte> retired = new HashSet<>(loadRetiredKeyIds(baseKeyFilePath));
    retired.add(keyId);
    StringBuilder csv = new StringBuilder();
    for (byte id : retired) {
      if (csv.length() > 0) {
        csv.append(',');
      }
      csv.append(Byte.toUnsignedInt(id));
    }
    try {
      Path file = retiredKeyIdsFile(baseKeyFilePath);
      Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
      restrictPermissions(file);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to record retired secrets key id " + keyId, e);
    }
  }

  private static Path retiredKeyIdsFile(Path baseKeyFilePath) {
    return baseKeyFilePath.resolveSibling(baseKeyFilePath.getFileName() + ".retired");
  }

  // Id 0 is always the base file itself; every other id lives at "<baseFileName>.<id>" -- the
  // same layout #rotate already writes, shared here so retire deletes exactly the file rotate (or
  // the original loadOrCreate, for id 0) created.
  private static Path keyFilePathForId(Path baseKeyFilePath, byte id) {
    return id == 0
        ? baseKeyFilePath
        : baseKeyFilePath.resolveSibling(
            baseKeyFilePath.getFileName() + "." + Byte.toUnsignedInt(id));
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
