package com.gimle.fafnir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.fafnir.testsupport.InProcessStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
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

  @TempDir Path tempDir;

  private InProcessStore store;
  private SecretStore secrets;

  @BeforeEach
  void setUp() throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
    FafnirCrypto crypto = new FafnirCrypto(store.client(), tempDir.resolve("keys/secret.key"));
    secrets = new SecretStore(store.client(), crypto);
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  @Test
  void a_secret_written_once_reads_back_as_version_1() {
    int version = secrets.put("acme", "db-password", bytes("hunter2"));

    assertEquals(1, version);
    assertEquals("hunter2", asString(secrets.get("acme", "db-password", OptionalInt.empty())));
  }

  @Test
  void writing_a_second_value_creates_version_2_and_becomes_the_new_latest() {
    secrets.put("acme", "db-password", bytes("first"));
    secrets.put("acme", "db-password", bytes("second"));

    assertEquals("second", asString(secrets.get("acme", "db-password", OptionalInt.empty())));
    assertEquals("first", asString(secrets.get("acme", "db-password", OptionalInt.of(1))));
    assertEquals(List.of(1, 2), secrets.versions("acme", "db-password"));
  }

  @Test
  void reading_a_never_written_key_returns_empty() {
    assertTrue(secrets.get("acme", "no-such-key", OptionalInt.empty()).isEmpty());
  }

  @Test
  void list_returns_metadata_only_for_every_written_secret_in_the_tenant() {
    secrets.put("acme", "db-password", bytes("v1"));
    secrets.put("acme", "api-key", bytes("v1"));
    secrets.put("acme", "api-key", bytes("v2"));

    List<SecretMetadata> listed = secrets.list("acme");

    assertEquals(2, listed.size());
    SecretMetadata apiKey =
        listed.stream().filter(m -> m.key().equals("api-key")).findFirst().orElseThrow();
    assertEquals(2, apiKey.latestVersion());
    assertFalse(apiKey.deleted());
  }

  @Test
  void list_never_surfaces_a_value_for_any_secret() {
    secrets.put("acme", "db-password", bytes("hunter2"));

    // SecretMetadata has no value field at all -- the type itself, not just this assertion,
    // enforces the "list vs get" distinction.
    List<SecretMetadata> listed = secrets.list("acme");

    assertEquals("db-password", listed.get(0).key());
  }

  @Test
  void soft_delete_marks_the_secret_deleted_but_keeps_every_version_readable_by_number() {
    secrets.put("acme", "db-password", bytes("hunter2"));

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
  void hard_delete_removes_every_version_and_the_metadata_entry_itself() {
    secrets.put("acme", "db-password", bytes("v1"));
    secrets.put("acme", "db-password", bytes("v2"));

    boolean existed = secrets.hardDelete("acme", "db-password");

    assertTrue(existed);
    assertTrue(secrets.get("acme", "db-password", OptionalInt.of(1)).isEmpty());
    assertTrue(secrets.get("acme", "db-password", OptionalInt.of(2)).isEmpty());
    assertFalse(secrets.exists("acme", "db-password"));
    assertEquals(List.of(), secrets.list("acme"));
  }

  @Test
  void values_are_stored_encrypted_not_as_plaintext_bytes() {
    secrets.put("acme", "db-password", bytes("hunter2"));

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
        IllegalArgumentException.class, () -> secrets.put("acme", "weird@key", bytes("v")));
  }

  @Test
  void a_key_containing_a_slash_is_rejected() {
    assertThrows(
        IllegalArgumentException.class, () -> secrets.put("acme", "weird/key", bytes("v")));
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
                            return secrets.put("acme", "contended", bytes("writer-" + i));
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

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String asString(Optional<byte[]> value) {
    return new String(value.orElseThrow(), StandardCharsets.UTF_8);
  }
}
