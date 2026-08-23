package com.gimle.fafnir.secret;

import com.gimle.core.exception.GimleSecretsException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads Fafnir's RSA sealing keypair from {@code baseKeyFilePath}, generating one on first run if
 * absent -- the asymmetric counterpart to {@link KeyFileManager}, mirroring its file-per-id +
 * {@code .active} sidecar convention exactly, adapted for two artifacts per id instead of one: an
 * RSA keypair has a private and a public half, unlike a single symmetric key. Id 0's private key
 * lives at {@code baseKeyFilePath} itself (raw PKCS8 DER, no PEM wrapping -- matching {@link
 * KeyFileManager}'s own raw-bytes convention, not {@code Pem}'s PEM-wrapping one used for TLS
 * material), with its public half as a sibling {@code baseKeyFilePath + ".pub"} (raw X.509 DER).
 * Persisting the public key separately, rather than reconstructing it from the private key at load
 * time, avoids relying on the JCE provider's private key happening to be in {@code
 * RSAPrivateCrtKey} form (true for the JDK's default provider, but not guaranteed) -- simpler and
 * more portable than that reconstruction would be.
 */
public final class SealingKeyFileManager {

  private static final Logger log = LoggerFactory.getLogger(SealingKeyFileManager.class);
  private static final String ALGORITHM = "RSA";
  private static final int KEY_SIZE_BITS = 3072;

  private SealingKeyFileManager() {}

  public static SealingKeyRing loadAllOrCreate(Path baseKeyFilePath) {
    Map<Byte, KeyPair> keyPairsById = new HashMap<>();
    keyPairsById.put((byte) 0, loadOrCreate(baseKeyFilePath));
    Path parent = baseKeyFilePath.getParent();
    String baseFileName = fileNameString(baseKeyFilePath);
    if (parent != null && Files.isDirectory(parent)) {
      String prefix = baseFileName + ".";
      try (DirectoryStream<Path> siblings = Files.newDirectoryStream(parent, prefix + "*")) {
        for (Path sibling : siblings) {
          String suffix = fileNameString(sibling).substring(prefix.length());
          if (suffix.equals("active")
              || suffix.endsWith(".pub")
              || !suffix.chars().allMatch(Character::isDigit)) {
            continue;
          }
          int id;
          try {
            id = Integer.parseInt(suffix);
          } catch (NumberFormatException e) {
            continue;
          }
          if (id < 0 || id > 255) {
            continue;
          }
          keyPairsById.put((byte) id, loadKeyPair(privateKeyFile(baseKeyFilePath, (byte) id)));
        }
      } catch (IOException e) {
        throw new UncheckedIOException(
            "failed to scan for rotated sealing key files: " + parent, e);
      }
    }
    byte activeKeyId = readActiveKeyId(activeKeyFile(baseKeyFilePath), keyPairsById.keySet());
    return new SealingKeyRing(activeKeyId, keyPairsById);
  }

  /**
   * Generates a fresh RSA-3072 keypair, adds it to {@code current} under the next unused id, and
   * repoints the {@code .active} sidecar at it -- every previously-loaded keypair stays in the
   * returned ring (and its files untouched on disk), so a blob already sealed under an older public
   * key -- e.g. sitting in a CI pipeline's git history, not yet committed -- stays unwrappable
   * after rotation.
   */
  public static SealingKeyRing rotate(Path baseKeyFilePath, SealingKeyRing current) {
    int highestExisting = -1;
    for (byte id : current.keyPairsById().keySet()) {
      highestExisting = Math.max(highestExisting, Byte.toUnsignedInt(id));
    }
    if (highestExisting >= 255) {
      throw new IllegalStateException("sealing key ring already holds the maximum 256 key ids");
    }
    byte newId = (byte) (highestExisting + 1);
    KeyPair newKeyPair = generateKeyPair();
    try {
      writeKeyPair(baseKeyFilePath, newId, newKeyPair);
      Path activeFile = activeKeyFile(baseKeyFilePath);
      Files.writeString(
          activeFile, String.valueOf(Byte.toUnsignedInt(newId)), StandardCharsets.UTF_8);
      restrictPermissions(activeFile);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to write rotated sealing key file", e);
    }
    Map<Byte, KeyPair> updated = new HashMap<>(current.keyPairsById());
    updated.put(newId, newKeyPair);
    return new SealingKeyRing(newId, updated);
  }

  /**
   * Actually stops trusting {@code keyId}: deletes both its private and public key files and drops
   * it from the returned ring, so a sealed blob still referencing this id can never be unwrapped
   * again -- deliberately destructive, not a soft flag. Rejects retiring {@code
   * current.activeKeyId()} (rotate to a new active key first) or an id {@code current} doesn't
   * hold.
   */
  public static SealingKeyRing retire(Path baseKeyFilePath, SealingKeyRing current, byte keyId) {
    if (keyId == 0) {
      // Same structural constraint KeyFileManager#retire documents: id 0 always regenerates on
      // the next #loadAllOrCreate if its files are absent, so deleting it wouldn't retire it, it
      // would resurrect a different keypair under the same id.
      throw GimleSecretsException.cannotRetireBaseKey("sealing");
    }
    if (keyId == current.activeKeyId()) {
      throw GimleSecretsException.cannotRetireActiveKey("sealing", Byte.toUnsignedInt(keyId));
    }
    if (!current.keyPairsById().containsKey(keyId)) {
      throw GimleSecretsException.unknownKeyId("sealing", Byte.toUnsignedInt(keyId));
    }
    try {
      Files.delete(privateKeyFile(baseKeyFilePath, keyId));
      Files.delete(publicKeyFile(baseKeyFilePath, keyId));
    } catch (IOException e) {
      throw new UncheckedIOException("failed to delete retired sealing key files", e);
    }
    Map<Byte, KeyPair> updated = new HashMap<>(current.keyPairsById());
    updated.remove(keyId);
    return new SealingKeyRing(current.activeKeyId(), updated);
  }

  private static KeyPair loadOrCreate(Path baseKeyFilePath) {
    if (Files.exists(baseKeyFilePath)) {
      return loadKeyPair(baseKeyFilePath);
    }
    KeyPair keyPair = generateKeyPair();
    try {
      Path parent = baseKeyFilePath.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      writeKeyPair(baseKeyFilePath, (byte) 0, keyPair);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to create sealing key file: " + baseKeyFilePath, e);
    }
    return keyPair;
  }

  private static void writeKeyPair(Path baseKeyFilePath, byte id, KeyPair keyPair)
      throws IOException {
    Path privateFile = privateKeyFile(baseKeyFilePath, id);
    Path publicFile = publicKeyFile(baseKeyFilePath, id);
    Files.write(privateFile, keyPair.getPrivate().getEncoded());
    restrictPermissions(privateFile);
    // Public key files are, deliberately, never permission-restricted -- that's the entire point
    // of the key being public.
    Files.write(publicFile, keyPair.getPublic().getEncoded());
  }

  private static KeyPair loadKeyPair(Path privateKeyFilePath) {
    try {
      byte[] privateEncoded = Files.readAllBytes(privateKeyFilePath);
      byte[] publicEncoded = Files.readAllBytes(publicKeyFileFor(privateKeyFilePath));
      KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
      PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateEncoded));
      PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(publicEncoded));
      return new KeyPair(publicKey, privateKey);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to load sealing keypair: " + privateKeyFilePath, e);
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException("failed to decode sealing keypair: " + privateKeyFilePath, e);
    }
  }

  private static Path publicKeyFileFor(Path privateKeyFilePath) {
    return privateKeyFilePath.resolveSibling(privateKeyFilePath.getFileName() + ".pub");
  }

  private static Path privateKeyFile(Path baseKeyFilePath, byte id) {
    return id == 0
        ? baseKeyFilePath
        : baseKeyFilePath.resolveSibling(
            baseKeyFilePath.getFileName() + "." + Byte.toUnsignedInt(id));
  }

  private static Path publicKeyFile(Path baseKeyFilePath, byte id) {
    return publicKeyFileFor(privateKeyFile(baseKeyFilePath, id));
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
            "{} names key id {}, which has no corresponding keypair; falling back to key id 0",
            activeKeyFile,
            text);
        return 0;
      }
      return (byte) id;
    } catch (IOException | NumberFormatException e) {
      log.warn(
          "failed to read active sealing key id from {}: {}; falling back to key id 0",
          activeKeyFile,
          e.getMessage());
      return 0;
    }
  }

  private static String fileNameString(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      throw new IllegalStateException("expected a regular file, got " + path);
    }
    return fileName.toString();
  }

  private static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
      generator.initialize(KEY_SIZE_BITS);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA key pair generation unavailable", e);
    }
  }

  private static void restrictPermissions(Path path) throws IOException {
    if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    } else {
      log.warn(
          "filesystem at {} does not support POSIX permissions; sealing private key file was"
              + " written without owner-only restriction (expected only in local Windows"
              + " development -- every real deployment target restricts this)",
          path);
    }
  }
}
