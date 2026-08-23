package com.gimle.fafnir.secret;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Map;

/**
 * The asymmetric counterpart to {@link KeyRing}: every RSA keypair {@link SealingKeyFileManager}
 * has ever loaded, keyed by id, plus which one is currently active. Kept as a full {@link KeyPair}
 * per id (not just the private half) for symmetry with {@link KeyRing} and because {@code
 * activePublicKey} needs the current one -- retired/rotated-past keypairs never need their public
 * half again, but there's no cost to keeping it alongside the private half already in memory.
 */
public record SealingKeyRing(byte activeKeyId, Map<Byte, KeyPair> keyPairsById) {

  public SealingKeyRing {
    if (keyPairsById == null || keyPairsById.isEmpty()) {
      throw new IllegalArgumentException("a SealingKeyRing must hold at least one keypair");
    }
    if (!keyPairsById.containsKey(activeKeyId)) {
      throw new IllegalArgumentException(
          "activeKeyId " + activeKeyId + " has no corresponding keypair");
    }
    keyPairsById = Map.copyOf(keyPairsById);
  }

  public KeyPair activeKeyPair() {
    return keyPairsById.get(activeKeyId);
  }

  public PublicKey activePublicKey() {
    return activeKeyPair().getPublic();
  }
}
