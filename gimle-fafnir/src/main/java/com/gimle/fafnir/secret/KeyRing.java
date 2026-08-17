package com.gimle.fafnir.secret;

import com.gimle.core.hash.Sha256;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.crypto.SecretKey;

/**
 * A versioned, rotatable set of secrets keys sharing one base file path: every key Fafnir has ever
 * used, keyed by the id {@link SecretCipher} embeds in each ciphertext it produces, plus which one
 * is currently active for new encryptions. Old keys stay loadable indefinitely so ciphertext
 * encrypted under them keeps decrypting -- {@link KeyFileManager#rotate} never deletes a key file,
 * only adds one and repoints {@code activeKeyId}.
 */
public record KeyRing(byte activeKeyId, Map<Byte, SecretKey> keysById) {

  public KeyRing {
    if (keysById == null || keysById.isEmpty()) {
      throw new IllegalArgumentException("keysById must not be empty");
    }
    if (!keysById.containsKey(activeKeyId)) {
      throw new IllegalArgumentException(
          "activeKeyId " + activeKeyId + " has no corresponding entry in keysById");
    }
    keysById = Map.copyOf(keysById);
  }

  public SecretKey activeKey() {
    return keysById.get(activeKeyId);
  }

  /**
   * A SHA-256 digest over every key id and its key material, sorted by id so load/map-iteration
   * order never affects the result -- two replicas holding identical key material always compute
   * the identical fingerprint, and a single differing key id or byte anywhere in the ring flips it.
   * Safe to log or return over an unauthenticated status surface: unlike the key material itself, a
   * fingerprint alone gives a reader no way to reconstruct or narrow down a key. This is the
   * operator-facing signal for the drift {@link KeyFileManager}'s own javadoc calls out as an
   * operational precondition it doesn't enforce itself -- every replica is expected to be
   * provisioned with identical key files, and a differing fingerprint across replicas means that
   * precondition silently stopped holding (e.g. a rotation landed on one replica's key files but
   * never made it to another's).
   */
  public String fingerprint() {
    SortedMap<Byte, SecretKey> sorted = new TreeMap<>(keysById);
    MessageDigest digest = Sha256.sha256Digest();
    for (Map.Entry<Byte, SecretKey> entry : sorted.entrySet()) {
      digest.update(ByteBuffer.allocate(1).put(entry.getKey()).array());
      digest.update(entry.getValue().getEncoded());
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
