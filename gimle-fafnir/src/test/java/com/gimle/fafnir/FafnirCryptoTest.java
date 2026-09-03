package com.gimle.fafnir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.core.config.ConfigEntry;
import com.gimle.core.exception.GimleSecretsException;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.fafnir.testsupport.InProcessStore;
import com.gimle.mimir.raft.StateMutation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FafnirCryptoTest {

  @TempDir Path tempDir;

  private InProcessStore store;
  private FafnirCrypto crypto;

  @BeforeEach
  void setUp() throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
    crypto = new FafnirCrypto(store.client(), tempDir.resolve("keys/secret.key"));
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  @Test
  void round_trips_plaintext_through_encrypt_and_decrypt() {
    byte[] plaintext = "s3cr3t-value".getBytes(StandardCharsets.UTF_8);

    byte[] ciphertext = crypto.encrypt(plaintext);

    assertNotEquals(
        new String(plaintext, StandardCharsets.UTF_8),
        new String(ciphertext, StandardCharsets.UTF_8));
    assertArrayEquals(plaintext, crypto.decrypt(ciphertext));
  }

  @Test
  void decrypt_batch_decrypts_every_entry_in_order() {
    byte[] first = crypto.encrypt("one".getBytes(StandardCharsets.UTF_8));
    byte[] second = crypto.encrypt("two".getBytes(StandardCharsets.UTF_8));

    List<byte[]> plaintexts = crypto.decryptBatch(List.of(first, second));

    assertEquals("one", new String(plaintexts.get(0), StandardCharsets.UTF_8));
    assertEquals("two", new String(plaintexts.get(1), StandardCharsets.UTF_8));
  }

  @Test
  void rotate_reencrypts_every_existing_encrypted_entry_under_the_new_active_key() {
    store.store().putTenant(new Tenant("acme", new ResourceQuota(1, 1, 1)));
    byte[] plaintext = "s3cr3t-value".getBytes(StandardCharsets.UTF_8);
    byte[] beforeRotation = crypto.encrypt(plaintext);
    store
        .client()
        .propose(
            new StateMutation.PutConfigEntry(
                new ConfigEntry("acme", "db-password", beforeRotation, true)));

    byte newKeyId = crypto.rotate();

    ConfigEntry stored =
        store.client().listConfigEntriesFor("acme").stream()
            .filter(e -> e.key().equals("db-password"))
            .findFirst()
            .orElseThrow();
    assertEquals(newKeyId, stored.value()[1]); // version(1) || keyId(1) || ...
    assertArrayEquals(plaintext, crypto.decrypt(stored.value()));
  }

  @Test
  void rotate_never_loses_a_previously_encrypted_value_still_decryptable_after_multiple_rounds() {
    byte[] plaintext = "s3cr3t-value".getBytes(StandardCharsets.UTF_8);
    byte[] ciphertext = crypto.encrypt(plaintext);

    crypto.rotate();
    crypto.rotate();
    crypto.rotate();

    assertArrayEquals(plaintext, crypto.decrypt(ciphertext));
  }

  @Test
  void a_plain_unencrypted_entry_is_untouched_by_rotation() {
    store.store().putTenant(new Tenant("acme", new ResourceQuota(1, 1, 1)));
    byte[] plainValue = "not-a-secret".getBytes(StandardCharsets.UTF_8);
    store
        .client()
        .propose(
            new StateMutation.PutConfigEntry(
                new ConfigEntry("acme", "greeting", plainValue, false)));

    crypto.rotate();

    ConfigEntry stored =
        store.client().listConfigEntriesFor("acme").stream()
            .filter(e -> e.key().equals("greeting"))
            .findFirst()
            .orElseThrow();
    assertArrayEquals(plainValue, stored.value());
  }

  @Test
  void retire_refuses_to_decrypt_a_value_still_encrypted_under_the_retired_key() {
    // id 0 can never be retired -- move active to id 1 before encrypting under it
    crypto.rotate();
    byte[] plaintext = "s3cr3t-value".getBytes(StandardCharsets.UTF_8);
    byte[] ciphertext = crypto.encrypt(plaintext);
    byte keyId = crypto.rotate(); // active moves off the key that encrypted ciphertext above

    crypto.retire(ciphertext[1]); // the key id embedded in ciphertext, not the new active one

    GimleSecretsException thrown =
        assertThrows(GimleSecretsException.class, () -> crypto.decrypt(ciphertext));
    assertNotEquals(keyId, ciphertext[1]);
    assertEquals(ciphertext[1], (byte) 1); // this test's own second key, id 1
  }

  /**
   * {@code B3}: the actual bug this cross-replica check fixes -- {@code retire} used to mutate only
   * the local, in-memory {@code retiredKeyIds} field the replica that received the call happened to
   * hold, so a *different* Fafnir replica (a real HA deployment's normal shape) kept decrypting
   * under a key an operator had just retired in response to a suspected compromise, indefinitely.
   * Two independent {@link FafnirCrypto} instances here, each with its own local key file directory
   * (mirroring two real replicas' own local disks) but sharing one store (the real Raft-replicated
   * cluster both would actually share), prove retirement on one now takes effect on the other:
   * {@code replicaA} retires the key, and {@code replicaB} -- which never received that call at all
   * -- refuses to decrypt on its very next attempt.
   */
  @Test
  void retiring_a_key_on_one_replica_is_refused_by_a_second_replica_sharing_the_same_store()
      throws IOException {
    Path replicaAKeyFile = tempDir.resolve("replica-a/secret.key");
    FafnirCrypto replicaA = new FafnirCrypto(store.client(), replicaAKeyFile);
    // id 0 can never be retired -- move active to id 1 before encrypting under it
    replicaA.rotate();
    byte[] ciphertext = replicaA.encrypt("s3cr3t-value".getBytes(StandardCharsets.UTF_8));
    // the key id actually embedded, id 1 (this replica's second)
    byte retiringKeyId = ciphertext[1];
    replicaA.rotate(); // move active off id 1 so it can be retired

    // Simulates the operator-provisioned "identical key files on every replica" precondition
    // FafnirCrypto's own constructor javadoc documents: replicaB starts with the exact same key
    // material replicaA does (both id-0 base file and the rotated id-1 sibling file), just under
    // its own local directory.
    Path replicaBKeyFile = tempDir.resolve("replica-b/secret.key");
    Files.createDirectories(replicaBKeyFile.getParent());
    Files.copy(replicaAKeyFile, replicaBKeyFile);
    Files.copy(
        replicaAKeyFile.resolveSibling(replicaAKeyFile.getFileName() + ".1"),
        replicaBKeyFile.resolveSibling(replicaBKeyFile.getFileName() + ".1"));
    FafnirCrypto replicaB = new FafnirCrypto(store.client(), replicaBKeyFile);
    // Confirms replicaB can genuinely decrypt replicaA's own ciphertext before retirement --
    // proving the two really do share provisioned key material, not just coincidentally both
    // failing.
    assertArrayEquals(
        "s3cr3t-value".getBytes(StandardCharsets.UTF_8), replicaB.decrypt(ciphertext));

    replicaA.retire(retiringKeyId);

    assertThrows(GimleSecretsException.class, () -> replicaB.decrypt(ciphertext));
  }
}
