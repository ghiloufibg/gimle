package com.gimle.fafnir;

import com.gimle.fafnir.secret.SealingKeyFileManager;
import com.gimle.fafnir.secret.SealingKeyRing;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fafnir's sealing-key boundary: the only object holding the RSA keypairs a {@code
 * /secretmaps/*}{@code /seal} commit is unwrapped against. Deliberately separate from {@link
 * FafnirCrypto} rather than folded into it -- the two hold unrelated key material (symmetric vs.
 * asymmetric) with independent lifecycles, and keeping {@link FafnirCrypto} itself unchanged means
 * this is a purely additive class, not a modification of the module's existing crypto boundary. No
 * {@code StoreClient} dependency at all: unlike {@link FafnirCrypto}, sealing-key rotation never
 * needs a re-encryption sweep (a sealed blob is never stored at rest inside Fafnir -- see {@code
 * FafnirServer}'s own SecretMap seal-commit handler), so this is pure local file/JCA management,
 * matching {@code SealingKeyFileManager}'s own posture.
 */
public final class SealingCrypto {

  private static final Logger log = LoggerFactory.getLogger(SealingCrypto.class);

  private final Path sealingKeyFilePath;
  // Not final: #rotate/#retire each replace this with a new ring, mirroring FafnirCrypto's own
  // volatile keyRing field.
  private volatile SealingKeyRing sealingKeyRing;

  public SealingCrypto(Path sealingKeyFilePath) {
    this.sealingKeyFilePath = sealingKeyFilePath;
    this.sealingKeyRing = SealingKeyFileManager.loadAllOrCreate(sealingKeyFilePath);
    log.info("sealing key ring loaded, active id={}", sealingKeyRing.activeKeyId());
  }

  public byte activeSealingKeyId() {
    return sealingKeyRing.activeKeyId();
  }

  public PublicKey activePublicKey() {
    return sealingKeyRing.activePublicKey();
  }

  /**
   * The private key for {@code keyId}, or empty if that id is unknown or has been retired -- lets a
   * caller (the seal-commit handler) surface a clean per-key failure rather than a lookup exception
   * breaking a whole batch.
   */
  public Optional<PrivateKey> privateKeyFor(byte keyId) {
    KeyPair keyPair = sealingKeyRing.keyPairsById().get(keyId);
    return keyPair == null ? Optional.empty() : Optional.of(keyPair.getPrivate());
  }

  public byte rotate() {
    SealingKeyRing newRing = SealingKeyFileManager.rotate(sealingKeyFilePath, sealingKeyRing);
    sealingKeyRing = newRing;
    log.info("sealing key ring rotated, new active id={}", newRing.activeKeyId());
    return newRing.activeKeyId();
  }

  public byte retire(byte keyId) {
    SealingKeyRing newRing =
        SealingKeyFileManager.retire(sealingKeyFilePath, sealingKeyRing, keyId);
    sealingKeyRing = newRing;
    log.info("sealing key id {} retired", Byte.toUnsignedInt(keyId));
    return keyId;
  }
}
