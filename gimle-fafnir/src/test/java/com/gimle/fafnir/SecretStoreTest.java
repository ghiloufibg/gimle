package com.gimle.fafnir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleSecretsException;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.fafnir.testsupport.InProcessStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Fafnir's synthetic-key versioning scheme, exercised directly against a real store. {@code
 * concurrent_writers_to_the_same_key_never_lose_an_update_and_every_slot_has_one_winner}
 * deliberately drives six threads at a single-node {@code InProcessStore} simultaneously; under
 * this module's own class-level concurrency (root pom.xml), running that alongside other classes'
 * real HTTP servers/InProcessStores at the same time occasionally pushed a proposal/read past its
 * retry budget -- the same CPU-contention-under-class-level-concurrency cause {@code
 * RaftClusterTest} (gimle-mimir) already needed {@link Isolated} for.
 */
@Isolated
class SecretStoreTest {

  private static final String CERTIFICATE_PEM =
      "-----BEGIN CERTIFICATE-----\nMIIBkTCB+wIJAKl\n-----END CERTIFICATE-----\n";

  @TempDir Path tempDir;

  private InProcessStore store;
  private SecretStore secrets;

  @BeforeEach
  void setUp() throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
    store.store().putTenant(new Tenant("acme", new ResourceQuota(1, 1, 1)));
    FafnirCrypto crypto = new FafnirCrypto(store.client(), tempDir.resolve("keys/secret.key"));
    secrets = new SecretStore(store.client(), crypto);
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  @Test
  void a_secret_written_once_reads_back_as_version_1() {
    int version = put("acme", "db-password", bytes("hunter2"));

    assertEquals(1, version);
    assertEquals("hunter2", asString(secrets.get("acme", "db-password", OptionalInt.empty())));
  }

  @Test
  void writing_a_second_value_creates_version_2_and_becomes_the_new_latest() {
    put("acme", "db-password", bytes("first"));
    put("acme", "db-password", bytes("second"));

    assertEquals("second", asString(secrets.get("acme", "db-password", OptionalInt.empty())));
    assertEquals("first", asString(secrets.get("acme", "db-password", OptionalInt.of(1))));
    assertEquals(List.of(1, 2), versionNumbers("acme", "db-password"));
  }

  @Test
  void reading_a_never_written_key_returns_empty() {
    assertTrue(secrets.get("acme", "no-such-key", OptionalInt.empty()).isEmpty());
  }

  @Test
  void list_returns_metadata_only_for_every_written_secret_in_the_tenant() {
    put("acme", "db-password", bytes("v1"));
    put("acme", "api-key", bytes("v1"));
    put("acme", "api-key", bytes("v2"));

    List<SecretMetadata> listed = secrets.list("acme");

    assertEquals(2, listed.size());
    SecretMetadata apiKey =
        listed.stream().filter(m -> m.key().equals("api-key")).findFirst().orElseThrow();
    assertEquals(2, apiKey.latestVersion());
    assertFalse(apiKey.deleted());
  }

  @Test
  void list_never_surfaces_a_value_for_any_secret() {
    put("acme", "db-password", bytes("hunter2"));

    // SecretMetadata has no value field at all -- the type itself, not just this assertion,
    // enforces the "list vs get" distinction.
    List<SecretMetadata> listed = secrets.list("acme");

    assertEquals("db-password", listed.get(0).key());
  }

  @Test
  void list_linearizable_returns_the_same_metadata_as_the_plain_list() {
    put("acme", "db-password", bytes("hunter2"));
    put("acme", "api-key", bytes("v1"));

    assertEquals(Set.copyOf(secrets.list("acme")), Set.copyOf(secrets.listLinearizable("acme")));
  }

  @Test
  void soft_delete_marks_the_secret_deleted_but_keeps_every_version_readable_by_number() {
    put("acme", "db-password", bytes("hunter2"));

    boolean existed = secrets.softDelete("acme", "db-password");

    assertTrue(existed);
    assertTrue(secrets.get("acme", "db-password", OptionalInt.empty()).isEmpty());
    assertEquals("hunter2", asString(secrets.get("acme", "db-password", OptionalInt.of(1))));
    assertTrue(secrets.list("acme").get(0).deleted());
  }

  @Test
  void soft_deleting_a_never_written_key_returns_false() {
    assertFalse(secrets.softDelete("acme", "no-such-key"));
  }

  @Test
  void undelete_with_no_version_restores_the_secret_as_active_at_its_same_version() {
    put("acme", "db-password", bytes("hunter2"));
    secrets.softDelete("acme", "db-password");

    OptionalInt restored = secrets.undelete("acme", "db-password", OptionalInt.empty());

    assertEquals(OptionalInt.of(1), restored);
    assertEquals("hunter2", asString(secrets.get("acme", "db-password", OptionalInt.empty())));
    assertFalse(secrets.list("acme").get(0).deleted());
  }

  @Test
  void undelete_never_mints_a_new_version() {
    put("acme", "db-password", bytes("v1"));
    put("acme", "db-password", bytes("v2"));
    secrets.softDelete("acme", "db-password");

    secrets.undelete("acme", "db-password", OptionalInt.empty());

    assertEquals(List.of(1, 2), versionNumbers("acme", "db-password"));
  }

  @Test
  void undelete_of_a_specific_older_version_makes_it_current_without_touching_the_newer_version() {
    put("acme", "db-password", bytes("v1"));
    put("acme", "db-password", bytes("v2"));
    secrets.softDelete("acme", "db-password");

    OptionalInt restored = secrets.undelete("acme", "db-password", OptionalInt.of(1));

    assertEquals(OptionalInt.of(1), restored);
    assertEquals("v1", asString(secrets.get("acme", "db-password", OptionalInt.empty())));
    assertFalse(secrets.list("acme").get(0).deleted());
    // Version 2's own stored data is untouched -- still directly readable by number even though
    // it's no longer the current pointer.
    assertEquals("v2", asString(secrets.get("acme", "db-password", OptionalInt.of(2))));
    assertEquals(List.of(1, 2), versionNumbers("acme", "db-password"));
  }

  @Test
  void undeleting_a_never_written_key_returns_empty() {
    assertTrue(secrets.undelete("acme", "no-such-key", OptionalInt.empty()).isEmpty());
  }

  @Test
  void undeleting_a_hard_deleted_secret_returns_empty_rather_than_reviving_it() {
    put("acme", "db-password", bytes("hunter2"));
    secrets.hardDelete("acme", "db-password");

    assertTrue(secrets.undelete("acme", "db-password", OptionalInt.empty()).isEmpty());
    assertFalse(secrets.exists("acme", "db-password"));
  }

  @Test
  void undeleting_a_version_number_that_was_never_written_is_rejected() {
    put("acme", "db-password", bytes("v1"));
    secrets.softDelete("acme", "db-password");

    assertThrows(
        RuntimeException.class, () -> secrets.undelete("acme", "db-password", OptionalInt.of(99)));
    // Rejected, not silently applied -- the secret is still exactly as soft-deleted as before.
    assertTrue(secrets.get("acme", "db-password", OptionalInt.empty()).isEmpty());
  }

  @Test
  void undelete_on_an_already_active_secret_is_a_harmless_no_op() {
    put("acme", "db-password", bytes("hunter2"));

    OptionalInt restored = secrets.undelete("acme", "db-password", OptionalInt.empty());

    assertEquals(OptionalInt.of(1), restored);
    assertEquals("hunter2", asString(secrets.get("acme", "db-password", OptionalInt.empty())));
  }

  @Test
  void hard_delete_removes_every_version_and_the_metadata_entry_itself() {
    put("acme", "db-password", bytes("v1"));
    put("acme", "db-password", bytes("v2"));

    boolean existed = secrets.hardDelete("acme", "db-password");

    assertTrue(existed);
    assertTrue(secrets.get("acme", "db-password", OptionalInt.of(1)).isEmpty());
    assertTrue(secrets.get("acme", "db-password", OptionalInt.of(2)).isEmpty());
    assertFalse(secrets.exists("acme", "db-password"));
    assertEquals(List.of(), secrets.list("acme"));
  }

  @Test
  void values_are_stored_encrypted_not_as_plaintext_bytes() {
    put("acme", "db-password", bytes("hunter2"));

    // Reach past SecretStore's own decrypt-on-read to the raw ConfigEntry the way an operator
    // inspecting gimle-mimir directly would -- proves that "@N holds ciphertext", not just that
    // #get round-trips (which would also pass if crypto were a no-op).
    byte[] raw =
        store.client().listConfigEntriesFor("acme").stream()
            .filter(e -> e.key().equals("db-password@1"))
            .findFirst()
            .orElseThrow()
            .value();
    assertFalse(new String(raw, StandardCharsets.UTF_8).contains("hunter2"));
  }

  @Test
  void a_key_containing_the_reserved_at_separator_is_rejected() {
    assertThrows(
        IllegalArgumentException.class, () -> put("acme", "weird@key", bytes("v")));
  }

  @Test
  void a_key_containing_a_slash_is_rejected() {
    assertThrows(
        IllegalArgumentException.class, () -> put("acme", "weird/key", bytes("v")));
  }

  @Test
  @Timeout(15)
  void concurrent_writers_to_the_same_key_never_lose_an_update_and_every_slot_has_one_winner()
      throws Exception {
    int writers = 6;
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    CountDownLatch ready = new CountDownLatch(writers);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Integer>> futures =
          IntStream.range(0, writers)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            ready.countDown();
                            go.await();
                            return put("acme", "contended", bytes("writer-" + i));
                          }))
              .toList();
      ready.await();
      go.countDown();
      List<Integer> claimedVersions = new ArrayList<>();
      for (Future<Integer> future : futures) {
        claimedVersions.add(future.get());
      }

      // Every writer claimed a distinct version number -- the optimistic write-verify-retry
      // loop guarantees no two racing writers ever land on the same slot.
      assertEquals(writers, claimedVersions.stream().distinct().count());
      assertEquals(
          IntStream.rangeClosed(1, writers).boxed().toList(),
          claimedVersions.stream().sorted().toList());
      // The final @meta names exactly one of the written versions as latest, and it decrypts.
      Optional<byte[]> latest = secrets.get("acme", "contended", OptionalInt.empty());
      assertTrue(latest.isPresent());
      assertTrue(asString(latest).startsWith("writer-"));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void every_version_records_the_author_and_a_write_timestamp() {
    long before = System.currentTimeMillis();
    secrets.put("acme", "db-password", bytes("v1"), SecretWrite.opaqueBy("alice"));
    secrets.put("acme", "db-password", bytes("v2"), SecretWrite.opaqueBy("bob"));

    List<SecretVersionInfo> versions = secrets.versions("acme", "db-password");

    assertEquals(2, versions.size());
    assertEquals("alice", versions.get(0).author());
    assertEquals("bob", versions.get(1).author());
    assertTrue(versions.get(0).writtenAtEpochMilli() >= before);
    assertTrue(versions.get(1).writtenAtEpochMilli() >= versions.get(0).writtenAtEpochMilli());
  }

  @Test
  void a_versions_declared_type_round_trips_through_the_stored_metadata() {
    secrets.put(
        "acme", "tls-cert", bytes(CERTIFICATE_PEM), new SecretWrite("alice", SecretType.PEM_CERTIFICATE));

    SecretVersionInfo info = secrets.versionInfo("acme", "tls-cert", 1).orElseThrow();

    assertEquals(SecretType.PEM_CERTIFICATE, info.type());
    assertEquals("alice", info.author());
  }

  @Test
  void soft_delete_and_undelete_leave_the_recorded_write_history_intact() {
    secrets.put("acme", "db-password", bytes("v1"), SecretWrite.opaqueBy("alice"));
    secrets.put("acme", "db-password", bytes("v2"), SecretWrite.opaqueBy("bob"));
    secrets.softDelete("acme", "db-password");
    secrets.undelete("acme", "db-password", OptionalInt.of(1));

    List<SecretVersionInfo> versions = secrets.versions("acme", "db-password");

    assertEquals(List.of("alice", "bob"), versions.stream().map(SecretVersionInfo::author).toList());
  }

  @Test
  void version_info_for_a_version_that_does_not_exist_is_empty() {
    put("acme", "db-password", bytes("v1"));

    assertTrue(secrets.versionInfo("acme", "db-password", 7).isEmpty());
    assertTrue(secrets.versionInfo("acme", "no-such-key", 1).isEmpty());
  }

  @Test
  void a_declared_pem_certificate_type_rejects_a_truncated_value_without_storing_anything() {
    GimleSecretsException thrown =
        assertThrows(
            GimleSecretsException.class,
            () ->
                secrets.put(
                    "acme",
                    "tls-cert",
                    bytes("-----BEGIN CERTIFICATE-----\nMIIB"),
                    new SecretWrite("alice", SecretType.PEM_CERTIFICATE)));

    assertTrue(thrown.getMessage().contains("pem-certificate"));
    // Nothing was claimed: a rejected write leaves no version behind at all.
    assertTrue(secrets.versions("acme", "tls-cert").isEmpty());
    assertFalse(secrets.exists("acme", "tls-cert"));
  }

  @Test
  void a_declared_pem_private_key_type_accepts_the_pkcs1_label_openssl_emits() {
    String pkcs1 = "-----BEGIN RSA PRIVATE KEY-----\nMIIBOgIBAAJB\n-----END RSA PRIVATE KEY-----\n";

    int version = secrets.put(
        "acme", "tls-key", bytes(pkcs1), new SecretWrite("alice", SecretType.PEM_PRIVATE_KEY));

    assertEquals(1, version);
    assertEquals(
        SecretType.PEM_PRIVATE_KEY, secrets.versionInfo("acme", "tls-key", 1).orElseThrow().type());
  }

  @Test
  void an_undeclared_type_stores_anything_unexamined() {
    int version = put("acme", "anything", bytes("-----BEGIN CERTIFICATE----- truncated"));

    assertEquals(1, version);
    assertEquals(SecretType.OPAQUE, secrets.versionInfo("acme", "anything", 1).orElseThrow().type());
  }

  @Test
  void a_value_larger_than_the_per_secret_cap_is_refused_before_anything_is_stored() {
    byte[] oversized = new byte[SecretStore.MAX_VALUE_BYTES + 1];

    assertThrows(
        GimleSecretsException.class, () -> put("acme", "too-big", oversized));
    assertFalse(secrets.exists("acme", "too-big"));
  }

  @Test
  void a_value_exactly_at_the_per_secret_cap_is_accepted() {
    byte[] atLimit = new byte[SecretStore.MAX_VALUE_BYTES];

    assertEquals(1, put("acme", "at-limit", atLimit));
  }

  @Test
  void get_many_returns_every_named_live_secret_with_its_version_metadata() {
    secrets.put("acme", "db-password", bytes("hunter2"), SecretWrite.opaqueBy("alice"));
    secrets.put("acme", "api-key", bytes("abc123"), SecretWrite.opaqueBy("bob"));

    Map<String, SecretValue> fetched =
        secrets.getMany("acme", List.of("db-password", "api-key"));

    assertEquals(Set.of("db-password", "api-key"), fetched.keySet());
    assertEquals("hunter2", new String(fetched.get("db-password").value(), StandardCharsets.UTF_8));
    assertEquals("alice", fetched.get("db-password").info().author());
    assertEquals("bob", fetched.get("api-key").info().author());
  }

  @Test
  void get_many_omits_an_unknown_or_soft_deleted_key_rather_than_failing_the_whole_batch() {
    put("acme", "db-password", bytes("hunter2"));
    put("acme", "retired", bytes("old"));
    secrets.softDelete("acme", "retired");

    Map<String, SecretValue> fetched =
        secrets.getMany("acme", List.of("db-password", "retired", "never-written"));

    assertEquals(Set.of("db-password"), fetched.keySet());
  }

  private int put(String tenantId, String key, byte[] value) {
    return secrets.put(tenantId, key, value, SecretWrite.opaqueBy("operator"));
  }

  private List<Integer> versionNumbers(String tenantId, String key) {
    return secrets.versions(tenantId, key).stream().map(SecretVersionInfo::version).toList();
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String asString(Optional<byte[]> value) {
    return new String(value.orElseThrow(), StandardCharsets.UTF_8);
  }
}
