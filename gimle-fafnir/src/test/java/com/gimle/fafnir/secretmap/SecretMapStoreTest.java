package com.gimle.fafnir.secretmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleSecretsException;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.fafnir.FafnirCrypto;
import com.gimle.fafnir.SecretMetadata;
import com.gimle.fafnir.SecretStore;
import com.gimle.fafnir.testsupport.InProcessStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Same {@link InProcessStore} fixture shape {@code SecretStoreTest} uses, {@code @Isolated} for the
 * identical reason its own class javadoc gives: the concurrency regression test below drives
 * several threads at a single-node store simultaneously.
 */
@Isolated
class SecretMapStoreTest {

  @TempDir Path tempDir;

  private InProcessStore store;
  private SecretMapStore secretMaps;

  @BeforeEach
  void setUp() throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
    store.store().putTenant(new Tenant("acme", new ResourceQuota(1, 1, 1)));
    FafnirCrypto crypto = new FafnirCrypto(store.client(), tempDir.resolve("keys/secret.key"));
    SecretStore secretStore = new SecretStore(store.client(), crypto);
    secretMaps = new SecretMapStore(store.client(), secretStore);
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  @Test
  void set_many_writes_every_key_and_each_starts_at_version_1() {
    List<SecretMapStore.SecretMapKeyResult> results =
        secretMaps.setMany("acme", "db-creds", values("username", "admin", "password", "hunter2"));

    assertEquals(2, results.size());
    for (SecretMapStore.SecretMapKeyResult result : results) {
      assertEquals(1, result.version().orElseThrow());
      assertTrue(result.error().isEmpty());
    }
  }

  @Test
  void get_metadata_returns_per_key_metadata_scoped_to_one_name_only() {
    secretMaps.setMany("acme", "db-creds", values("username", "admin"));
    secretMaps.setMany("acme", "api-keys", values("primary", "abc123"));

    List<SecretMetadata> dbCreds = secretMaps.getMetadata("acme", "db-creds");

    assertEquals(1, dbCreds.size());
    assertEquals("username", dbCreds.get(0).key());
    assertEquals(1, dbCreds.get(0).latestVersion());
    assertFalse(dbCreds.get(0).deleted());
  }

  @Test
  void list_names_returns_every_distinct_secret_map_name_for_the_tenant() {
    secretMaps.setMany("acme", "db-creds", values("username", "admin"));
    secretMaps.setMany("acme", "api-keys", values("primary", "abc123"));

    assertEquals(Set.of("db-creds", "api-keys"), Set.copyOf(secretMaps.listNames("acme")));
  }

  @Test
  void list_names_is_scoped_per_tenant() {
    secretMaps.setMany("acme", "db-creds", values("username", "admin"));
    secretMaps.setMany("globex", "db-creds", values("username", "root"));

    assertEquals(List.of("db-creds"), secretMaps.listNames("acme"));
  }

  @Test
  void get_values_returns_decrypted_values_for_exactly_the_requested_names() {
    secretMaps.setMany("acme", "db-creds", values("username", "admin", "password", "hunter2"));
    secretMaps.setMany("acme", "api-keys", values("primary", "abc123"));

    Map<String, Map<String, byte[]>> values = secretMaps.getValues("acme", List.of("db-creds"));

    assertEquals(Set.of("db-creds"), values.keySet());
    Map<String, byte[]> dbCreds = values.get("db-creds");
    assertEquals("admin", new String(dbCreds.get("username"), StandardCharsets.UTF_8));
    assertEquals("hunter2", new String(dbCreds.get("password"), StandardCharsets.UTF_8));
  }

  @Test
  void get_values_for_an_unknown_name_returns_an_empty_map_not_an_omitted_entry() {
    Map<String, Map<String, byte[]>> values = secretMaps.getValues("acme", List.of("no-such-map"));

    assertTrue(values.containsKey("no-such-map"));
    assertTrue(values.get("no-such-map").isEmpty());
  }

  @Test
  void delete_all_soft_deletes_every_key_under_the_name() {
    secretMaps.setMany("acme", "db-creds", values("username", "admin", "password", "hunter2"));

    boolean existed = secretMaps.deleteAll("acme", "db-creds", false);

    assertTrue(existed);
    assertTrue(
        secretMaps.getMetadata("acme", "db-creds").stream().allMatch(SecretMetadata::deleted));
  }

  @Test
  void delete_all_on_an_unknown_name_returns_false() {
    assertFalse(secretMaps.deleteAll("acme", "no-such-map", false));
  }

  @Test
  void delete_key_removes_only_the_named_key_leaving_siblings_untouched() {
    secretMaps.setMany("acme", "db-creds", values("username", "admin", "password", "hunter2"));

    boolean existed = secretMaps.deleteKey("acme", "db-creds", "password", true);

    assertTrue(existed);
    List<String> remaining =
        secretMaps.getMetadata("acme", "db-creds").stream().map(SecretMetadata::key).toList();
    assertEquals(List.of("username"), remaining);
  }

  @Test
  void delete_key_on_a_key_that_does_not_exist_returns_false() {
    secretMaps.setMany("acme", "db-creds", values("username", "admin"));

    assertFalse(secretMaps.deleteKey("acme", "db-creds", "no-such-key", false));
  }

  @Test
  void set_many_stamps_an_incrementing_group_version_recording_every_member_key() {
    secretMaps.setMany("acme", "db-creds", values("username", "admin"));
    secretMaps.setMany("acme", "db-creds", values("password", "hunter2"));

    List<SecretMapStore.SecretMapGroupVersion> versions =
        secretMaps.listGroupVersions("acme", "db-creds");

    assertEquals(2, versions.size());
    assertEquals(1, versions.get(0).groupVersion());
    assertEquals(Set.of("username"), versions.get(0).keys().keySet());
    assertEquals(2, versions.get(1).groupVersion());
    assertEquals(Set.of("username", "password"), versions.get(1).keys().keySet());
    assertEquals(1, versions.get(1).keys().get("username").version());
    assertTrue(versions.get(1).rollbackOfGroupVersion().isEmpty());
  }

  @Test
  void set_many_does_not_stamp_a_group_version_when_every_key_fails() {
    List<SecretMapStore.SecretMapKeyResult> results =
        secretMaps.setMany("acme", "db-creds", values("bad:key", "x"));

    assertTrue(results.get(0).error().isPresent());
    assertTrue(secretMaps.listGroupVersions("acme", "db-creds").isEmpty());
  }

  @Test
  void delete_all_stamps_a_group_version_recording_every_key_as_deleted() {
    secretMaps.setMany("acme", "db-creds", values("username", "admin"));

    secretMaps.deleteAll("acme", "db-creds", false);

    List<SecretMapStore.SecretMapGroupVersion> versions =
        secretMaps.listGroupVersions("acme", "db-creds");
    assertEquals(2, versions.size());
    assertTrue(versions.get(1).keys().get("username").deleted());
  }

  @Test
  void delete_all_on_an_unknown_name_does_not_stamp_a_group_version() {
    secretMaps.deleteAll("acme", "no-such-map", false);

    assertTrue(secretMaps.listGroupVersions("acme", "no-such-map").isEmpty());
  }

  @Test
  void delete_key_stamps_a_group_version_recording_just_that_key_as_deleted() {
    secretMaps.setMany("acme", "db-creds", values("username", "admin", "password", "hunter2"));

    secretMaps.deleteKey("acme", "db-creds", "password", false);

    List<SecretMapStore.SecretMapGroupVersion> versions =
        secretMaps.listGroupVersions("acme", "db-creds");
    assertEquals(2, versions.size());
    assertFalse(versions.get(1).keys().get("username").deleted());
    assertTrue(versions.get(1).keys().get("password").deleted());
  }

  @Test
  void delete_key_on_an_unknown_key_does_not_stamp_a_group_version() {
    secretMaps.setMany("acme", "db-creds", values("username", "admin"));

    secretMaps.deleteKey("acme", "db-creds", "no-such-key", false);

    assertEquals(1, secretMaps.listGroupVersions("acme", "db-creds").size());
  }

  @Test
  void rollback_restores_a_changed_keys_old_content_as_a_brand_new_version() {
    secretMaps.setMany("acme", "db-creds", values("password", "hunter2")); // group version 1
    secretMaps.setMany("acme", "db-creds", values("password", "hunter3")); // group version 2

    SecretMapStore.RollbackOutcome outcome = secretMaps.rollback("acme", "db-creds", 1);

    assertTrue(outcome instanceof SecretMapStore.RollbackOutcome.Applied);
    SecretMapStore.RollbackOutcome.Applied applied =
        (SecretMapStore.RollbackOutcome.Applied) outcome;
    assertEquals(3, applied.newGroupVersion());
    assertEquals(1, applied.results().size());
    assertEquals(3, applied.results().get(0).version().orElseThrow());

    Map<String, byte[]> data = secretMaps.getValues("acme", List.of("db-creds")).get("db-creds");
    assertEquals("hunter2", new String(data.get("password"), StandardCharsets.UTF_8));

    List<SecretMapStore.SecretMapGroupVersion> versions =
        secretMaps.listGroupVersions("acme", "db-creds");
    assertEquals(3, versions.size());
    assertEquals(1, versions.get(2).rollbackOfGroupVersion().orElseThrow());
  }

  @Test
  void rollback_target_recording_a_deleted_key_re_applies_that_delete() {
    secretMaps.setMany("acme", "db-creds", values("password", "hunter2")); // v1: live
    secretMaps.deleteKey("acme", "db-creds", "password", false); // v2: deleted
    secretMaps.setMany("acme", "db-creds", values("password", "hunter3")); // v3: live again

    secretMaps.rollback("acme", "db-creds", 2);

    List<SecretMetadata> metadata = secretMaps.getMetadata("acme", "db-creds");
    assertEquals(1, metadata.size());
    assertTrue(metadata.get(0).deleted());
    assertTrue(secretMaps.getValues("acme", List.of("db-creds")).get("db-creds").isEmpty());
  }

  @Test
  void rollback_removes_a_key_added_after_the_target_group_version() {
    secretMaps.setMany("acme", "db-creds", values("username", "admin")); // v1
    secretMaps.setMany("acme", "db-creds", values("password", "hunter2")); // v2: password added

    SecretMapStore.RollbackOutcome outcome = secretMaps.rollback("acme", "db-creds", 1);

    SecretMapStore.RollbackOutcome.Applied applied =
        (SecretMapStore.RollbackOutcome.Applied) outcome;
    // username restored (it was part of the v1 snapshot) and password removed (it wasn't) --
    // rollback restores the group's exact prior membership, not just the keys the target recorded.
    assertEquals(2, applied.results().size());
    Map<String, byte[]> data = secretMaps.getValues("acme", List.of("db-creds")).get("db-creds");
    assertEquals(Set.of("username"), data.keySet()); // password gone; username restored and live

    Set<String> newSnapshotKeys =
        secretMaps.listGroupVersions("acme", "db-creds").get(2).keys().keySet();
    // The rollback's own stamp still records password by name -- as deleted, not absent -- the
    // same way any other soft delete's group version does.
    assertEquals(Set.of("username", "password"), newSnapshotKeys);
  }

  @Test
  void rollback_reports_a_per_key_failure_for_a_since_hard_deleted_key_without_failing_siblings() {
    secretMaps.setMany("acme", "db-creds", values("username", "admin", "password", "hunter2"));
    secretMaps.deleteKey("acme", "db-creds", "password", true); // hard delete -- history gone

    SecretMapStore.RollbackOutcome outcome = secretMaps.rollback("acme", "db-creds", 1);

    SecretMapStore.RollbackOutcome.Applied applied =
        (SecretMapStore.RollbackOutcome.Applied) outcome;
    Map<String, SecretMapStore.SecretMapKeyResult> byKey =
        applied.results().stream()
            .collect(Collectors.toMap(SecretMapStore.SecretMapKeyResult::key, r -> r));
    assertTrue(byKey.get("username").version().isPresent());
    assertTrue(byKey.get("password").error().isPresent());
  }

  @Test
  void rollback_to_an_unknown_group_version_returns_target_not_found() {
    secretMaps.setMany("acme", "db-creds", values("username", "admin"));

    SecretMapStore.RollbackOutcome outcome = secretMaps.rollback("acme", "db-creds", 99);

    assertTrue(outcome instanceof SecretMapStore.RollbackOutcome.TargetNotFound);
  }

  /**
   * The exit criterion for group-level rollback: several threads bulk-writing distinct keys and
   * several threads rolling back to group version 1, all concurrently on the same name, must never
   * corrupt the group-version sequence (no duplicate or skipped numbers -- each successful call
   * stamps exactly one) and must never lose a writer's own key.
   */
  @Test
  @Timeout(20)
  void concurrent_writers_and_rollbacks_on_the_same_name_never_corrupt_the_group_version_sequence()
      throws Exception {
    secretMaps.setMany("acme", "contended", values("seed", "v0")); // group version 1
    int writers = 4;
    int rollbackers = 2;
    int total = writers + rollbackers;
    ExecutorService pool = Executors.newFixedThreadPool(total);
    CountDownLatch ready = new CountDownLatch(total);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Void>> futures = new ArrayList<>();
      for (int i = 0; i < writers; i++) {
        int idx = i;
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  setManyWithRetry("contended", "key-" + idx, "value-" + idx);
                  return null;
                }));
      }
      for (int i = 0; i < rollbackers; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  rollbackWithRetry("contended", 1);
                  return null;
                }));
      }
      ready.await();
      go.countDown();
      for (Future<Void> future : futures) {
        future.get();
      }

      List<SecretMapStore.SecretMapGroupVersion> versions =
          secretMaps.listGroupVersions("acme", "contended");
      List<Integer> expectedNumbers = IntStream.rangeClosed(1, versions.size()).boxed().toList();
      List<Integer> actualNumbers =
          versions.stream()
              .map(SecretMapStore.SecretMapGroupVersion::groupVersion)
              .sorted()
              .toList();
      assertEquals(expectedNumbers, actualNumbers);

      Set<String> keys =
          secretMaps.getMetadata("acme", "contended").stream()
              .map(SecretMetadata::key)
              .collect(Collectors.toSet());
      for (int i = 0; i < writers; i++) {
        assertTrue(keys.contains("key-" + i));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private void rollbackWithRetry(String name, int targetGroupVersion) {
    for (int attempt = 0; attempt < 50; attempt++) {
      try {
        secretMaps.rollback("acme", name, targetGroupVersion);
        return;
      } catch (GimleSecretsException e) {
        // Lease contended for this attempt's whole 50-try budget; retry, same as setManyWithRetry.
      }
    }
    throw new AssertionError("could not roll back " + name + " under contention");
  }

  /**
   * N threads each bulk-writing a *distinct* key under the *same* SecretMap name concurrently --
   * the identical shape {@code ConfigMapStoreTest}'s own concurrency regression test uses. Each
   * thread's own {@link #setManyWithRetry} plays the role a well-behaved caller must: a single
   * {@link SecretMapStore#setMany} call only gets {@code MAX_LEASE_ATTEMPTS} (50) internal tries at
   * the shared per-name lease, which is not always enough headroom for every one of several threads
   * contending on it at once (a real, expected outcome of "no lease means retry the whole batch,"
   * not a bug) -- so the outer retry here is what a real caller (the CLI, in practice) is expected
   * to do on a lease-contention failure, exactly as {@code ConfigMapStoreTest#patchOneKeyWithRetry}
   * already establishes for {@code ConfigMapStore}'s own lease.
   */
  @Test
  @Timeout(15)
  void concurrent_batch_writers_to_distinct_keys_of_the_same_name_never_lose_a_key()
      throws Exception {
    int writers = 6;
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    CountDownLatch ready = new CountDownLatch(writers);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Void>> futures =
          IntStream.range(0, writers)
              .mapToObj(
                  i ->
                      pool.<Void>submit(
                          () -> {
                            ready.countDown();
                            go.await();
                            setManyWithRetry("contended", "key-" + i, "value-" + i);
                            return null;
                          }))
              .toList();
      ready.await();
      go.countDown();
      for (Future<Void> future : futures) {
        future.get();
      }

      // Every writer's own key survived -- the lease serializes the batches, but never drops one.
      Set<String> keys =
          secretMaps.getMetadata("acme", "contended").stream()
              .map(SecretMetadata::key)
              .collect(Collectors.toSet());
      assertEquals(
          IntStream.range(0, writers).mapToObj(i -> "key-" + i).collect(Collectors.toSet()), keys);
    } finally {
      pool.shutdownNow();
    }
  }

  private void setManyWithRetry(String name, String key, String value) {
    for (int attempt = 0; attempt < 50; attempt++) {
      try {
        List<SecretMapStore.SecretMapKeyResult> results =
            secretMaps.setMany("acme", name, values(key, value));
        for (SecretMapStore.SecretMapKeyResult result : results) {
          assertTrue(result.error().isEmpty(), () -> "unexpected failure: " + result);
        }
        return;
      } catch (GimleSecretsException e) {
        // Lease contended for this attempt's whole 50-try budget; another writer is mid-batch for
        // the same name -- retry against whatever's current, exactly like patchOneKeyWithRetry.
      }
    }
    throw new AssertionError("could not write key " + key + " under contention");
  }

  private static Map<String, byte[]> values(String... keyValuePairs) {
    Map<String, byte[]> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValuePairs.length; i += 2) {
      map.put(keyValuePairs[i], keyValuePairs[i + 1].getBytes(StandardCharsets.UTF_8));
    }
    return map;
  }
}
