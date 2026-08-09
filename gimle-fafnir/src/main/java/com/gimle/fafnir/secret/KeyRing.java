package com.gimle.fafnir.secret;

import java.util.Map;
import javax.crypto.SecretKey;

/**
 * A versioned, rotatable set of secrets keys sharing one base file path (P2-16): every key Fafnir
 * has ever used, keyed by the id {@link SecretCipher} embeds in each ciphertext it produces, plus
 * which one is currently active for new encryptions. Old keys stay loadable indefinitely so
 * ciphertext encrypted under them keeps decrypting -- {@link KeyFileManager#rotate} never deletes a
 * key file, only adds one and repoints {@code activeKeyId}.
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
}
