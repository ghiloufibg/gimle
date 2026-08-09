package com.gimle.fafnir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.gimle.core.config.ConfigEntry;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.fafnir.testsupport.InProcessStore;
import com.gimle.mimir.raft.StateMutation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FafnirCryptoTest {

  @TempDir java.nio.file.Path tempDir;

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
}
