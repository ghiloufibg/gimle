package com.gimle.fafnir;

import com.gimle.core.config.ConfigEntry;
import com.gimle.core.tenant.Tenant;
import com.gimle.fafnir.secret.KeyFileManager;
import com.gimle.fafnir.secret.KeyRing;
import com.gimle.fafnir.secret.SecretCipher;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.rpc.StoreClient;
import java.nio.file.Path;
import java.util.List;

/**
 * Fafnir's crypto boundary: the only object in the whole process (and, per the design this module
 * implements, the only object in the whole cluster outside a worker's own memory) that ever holds
 * decrypted secret-value plaintext or the key ring that produces it. Deliberately a plain Java
 * class, not tied to HTTP -- {@link FafnirServer} is the thin transport wrapper around it, so the
 * crypto logic itself is testable without spinning up a socket.
 */
public final class FafnirCrypto {

  private final StoreClient storeClient;
  private final Path secretKeyFilePath;
  // Not final: #rotate replaces this with a new ring holding one more key, mirroring ApiServer's
  // own pre-extraction volatile secretKeyRing field.
  private volatile KeyRing keyRing;

  public FafnirCrypto(StoreClient storeClient, Path secretKeyFilePath) {
    this.storeClient = storeClient;
    this.secretKeyFilePath = secretKeyFilePath;
    this.keyRing = KeyFileManager.loadAllOrCreate(secretKeyFilePath);
  }

  /**
   * Exposed so {@link FafnirServer} can build a {@link SecretStore}/{@code Authorizer} off the same
   * connection, rather than threading a second {@link StoreClient} reference through both
   * constructors.
   */
  public StoreClient storeClient() {
    return storeClient;
  }

  /**
   * Exposed so {@link FafnirServer} can derive its own session-signing key file path as a sibling
   * of this one (mirroring {@code ApiServer}'s own {@code secretKeyFilePath.resolveSibling(
   * "session.key")}), without either constructor threading a second path through the other.
   */
  public Path secretKeyFilePath() {
    return secretKeyFilePath;
  }

  /** The console status endpoint's own view of "which key is currently active." */
  public byte activeKeyId() {
    return keyRing.activeKeyId();
  }

  public byte[] encrypt(byte[] plaintext) {
    KeyRing ring = keyRing;
    return SecretCipher.encrypt(plaintext, ring.activeKey(), ring.activeKeyId());
  }

  public byte[] decrypt(byte[] ciphertext) {
    return SecretCipher.decrypt(ciphertext, keyRing.keysById());
  }

  public List<byte[]> decryptBatch(List<byte[]> ciphertexts) {
    return ciphertexts.stream().map(this::decrypt).toList();
  }

  /**
   * Generates a new secrets master key, makes it the active key for every future encryption, and
   * re-encrypts every existing encrypted entry under it (P2-16, moved here verbatim from {@code
   * ApiServer.rotateSecretsKey}) -- old keys are never deleted, so any entry this walk doesn't
   * reach still decrypts correctly either way, just not yet re-encrypted under the newest key.
   * That's true both of an entry written concurrently mid-rotation, and of {@link
   * ConfigEntry#tenantId()}'s own looser-than-{@link Tenant} scoping: this walk only visits {@link
   * StoreClient#listTenants} rather than every distinct tenant id any {@code ConfigEntry} happens
   * to name, since there is no "every config entry regardless of tenant" store query today. A
   * config entry filed under a tenant id that was never registered as a real {@code Tenant} keeps
   * decrypting under its original key indefinitely -- correct, just not eagerly rotated.
   */
  public byte rotate() {
    KeyRing newRing = KeyFileManager.rotate(secretKeyFilePath, keyRing);
    keyRing = newRing; // every future encryption already uses the new active key from here on
    for (Tenant tenant : storeClient.listTenants()) {
      for (ConfigEntry entry : storeClient.listConfigEntriesFor(tenant.id())) {
        if (!entry.encrypted()) {
          continue;
        }
        byte[] plaintext = SecretCipher.decrypt(entry.value(), newRing.keysById());
        byte[] reencrypted =
            SecretCipher.encrypt(plaintext, newRing.activeKey(), newRing.activeKeyId());
        storeClient.propose(
            new StateMutation.PutConfigEntry(
                new ConfigEntry(entry.tenantId(), entry.key(), reencrypted, true)));
      }
    }
    return newRing.activeKeyId();
  }
}
