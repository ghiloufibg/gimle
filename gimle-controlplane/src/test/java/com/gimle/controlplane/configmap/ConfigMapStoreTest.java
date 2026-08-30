package com.gimle.controlplane.configmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.gimle.controlplane.testsupport.InProcessStore;
import java.io.IOException;
import java.nio.file.Path;
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
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * {@link ConfigMapStore}, exercised against a real {@code gimle-mimir} store via {@link
 * InProcessStore} -- the same fixture {@code NetworkPolicyRegistryTest} uses. {@code
 * concurrent_writers_of_distinct_keys_to_the_same_configmap_never_lose_an_update} deliberately
 * drives several threads at a single-node store simultaneously; isolated for the same
 * CPU-contention-under-class-level-concurrency reason {@code SecretStoreTest} is.
 */
@Isolated
class ConfigMapStoreTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private ConfigMapStore store;

  @BeforeEach
  void startStore() throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    store = new ConfigMapStore(inProcessStore.client());
  }

  @AfterEach
  void stopStore() {
    inProcessStore.close();
  }

  @Test
  void get_of_an_unknown_name_is_empty() {
    assertEquals(Optional.empty(), store.get("acme", "nope"));
  }

  @Test
  void list_of_an_unknown_tenant_is_empty() {
    assertEquals(List.of(), store.list("acme"));
  }

  @Test
  void an_unconditional_put_creates_version_one() {
    ConfigMapWriteResult result =
        store.put("acme", "app-config", Map.of("a", "1"), OptionalInt.empty());

    assertEquals(new ConfigMapWriteResult.Written(1), result);
    ConfigMap saved = store.get("acme", "app-config").orElseThrow();
    assertEquals(1, saved.version());
    assertEquals(Map.of("a", "1"), saved.data());
  }

  @Test
  void a_second_unconditional_put_bumps_the_version_by_exactly_one_and_replaces_the_data() {
    store.put("acme", "app-config", Map.of("a", "1"), OptionalInt.empty());

    ConfigMapWriteResult result =
        store.put("acme", "app-config", Map.of("b", "2"), OptionalInt.empty());

    assertEquals(new ConfigMapWriteResult.Written(2), result);
    ConfigMap saved = store.get("acme", "app-config").orElseThrow();
    assertEquals(2, saved.version());
    // PUT is a full replace -- the first put's "a" key does not survive.
    assertEquals(Map.of("b", "2"), saved.data());
  }

  @Test
  void patch_merges_only_the_sent_keys_leaving_others_untouched() {
    store.put("acme", "app-config", Map.of("a", "1", "b", "2"), OptionalInt.empty());

    ConfigMapWriteResult result = store.patch("acme", "app-config", Map.of("b", "20"), 1);

    assertEquals(new ConfigMapWriteResult.Written(2), result);
    ConfigMap saved = store.get("acme", "app-config").orElseThrow();
    assertEquals(Map.of("a", "1", "b", "20"), saved.data());
  }

  @Test
  void patch_with_expected_version_zero_against_an_absent_configmap_is_the_create_case() {
    ConfigMapWriteResult result = store.patch("acme", "brand-new", Map.of("a", "1"), 0);

    assertEquals(new ConfigMapWriteResult.Written(1), result);
    assertEquals(Map.of("a", "1"), store.get("acme", "brand-new").orElseThrow().data());
  }

  @Test
  void put_with_a_stale_expected_version_returns_a_version_conflict_and_writes_nothing() {
    store.put("acme", "app-config", Map.of("a", "1"), OptionalInt.empty());

    ConfigMapWriteResult result =
        store.put("acme", "app-config", Map.of("a", "2"), OptionalInt.of(99));

    ConfigMapWriteResult.VersionConflict conflict =
        assertInstanceOf(ConfigMapWriteResult.VersionConflict.class, result);
    assertEquals(1, conflict.currentVersion());
    assertEquals(Map.of("a", "1"), conflict.currentData());
    // The rejected write must not have landed.
    assertEquals(1, store.get("acme", "app-config").orElseThrow().version());
  }

  @Test
  void delete_removes_the_configmap() {
    store.put("acme", "app-config", Map.of("a", "1"), OptionalInt.empty());

    ConfigMapDeleteOutcome result = store.delete("acme", "app-config");

    assertEquals(new ConfigMapDeleteOutcome.Deleted(2), result);
    assertEquals(Optional.empty(), store.get("acme", "app-config"));
  }

  @Test
  void deleting_a_name_that_never_existed_is_a_notfound_noop() {
    ConfigMapDeleteOutcome result = store.delete("acme", "never-existed");

    assertEquals(new ConfigMapDeleteOutcome.NotFound(), result);
    // No version minted for a no-op delete.
    assertEquals(List.of(), store.listVersions("acme", "never-existed"));
  }

  @Test
  void list_versions_shows_every_stamped_version_oldest_first_including_the_delete_tombstone() {
    store.put("acme", "app-config", Map.of("a", "1"), OptionalInt.empty());
    store.patch("acme", "app-config", Map.of("b", "2"), 1);
    store.delete("acme", "app-config");

    List<ConfigMapVersion> versions = store.listVersions("acme", "app-config");

    assertEquals(3, versions.size());
    assertEquals(1, versions.get(0).version());
    assertEquals(Map.of("a", "1"), versions.get(0).data());
    assertEquals(false, versions.get(0).deleted());
    assertEquals(2, versions.get(1).version());
    assertEquals(Map.of("a", "1", "b", "2"), versions.get(1).data());
    assertEquals(3, versions.get(2).version());
    assertEquals(true, versions.get(2).deleted());
  }

  @Test
  void rollback_to_an_earlier_version_restores_its_data_as_a_brand_new_version() {
    store.put("acme", "app-config", Map.of("a", "1"), OptionalInt.empty());
    store.put("acme", "app-config", Map.of("b", "2"), OptionalInt.empty());

    ConfigMapRollbackOutcome result = store.rollback("acme", "app-config", 1);

    assertEquals(new ConfigMapRollbackOutcome.Applied(3, Map.of("a", "1"), false), result);
    ConfigMap saved = store.get("acme", "app-config").orElseThrow();
    assertEquals(3, saved.version());
    assertEquals(Map.of("a", "1"), saved.data());
    // Rolling back never rewrites the version it targeted or anything stamped after it.
    List<ConfigMapVersion> versions = store.listVersions("acme", "app-config");
    assertEquals(3, versions.size());
    assertEquals(Map.of("b", "2"), versions.get(1).data());
  }

  @Test
  void rollback_to_a_deleted_version_deletes_the_configmap_again_as_a_new_version() {
    store.put("acme", "app-config", Map.of("a", "1"), OptionalInt.empty());
    store.delete("acme", "app-config");
    store.put("acme", "app-config", Map.of("b", "2"), OptionalInt.empty());

    ConfigMapRollbackOutcome result = store.rollback("acme", "app-config", 2);

    assertEquals(new ConfigMapRollbackOutcome.Applied(4, Map.of(), true), result);
    assertEquals(Optional.empty(), store.get("acme", "app-config"));
  }

  @Test
  void rollback_of_an_unknown_version_is_rejected_without_touching_the_live_row() {
    store.put("acme", "app-config", Map.of("a", "1"), OptionalInt.empty());

    ConfigMapRollbackOutcome result = store.rollback("acme", "app-config", 99);

    assertEquals(new ConfigMapRollbackOutcome.TargetNotFound(), result);
    assertEquals(Map.of("a", "1"), store.get("acme", "app-config").orElseThrow().data());
  }

  @Test
  void version_numbers_keep_counting_up_across_a_delete_then_recreate_cycle() {
    store.put("acme", "app-config", Map.of("a", "1"), OptionalInt.empty()); // v1
    store.delete("acme", "app-config"); // v2 (tombstone)

    ConfigMapWriteResult recreated =
        store.put("acme", "app-config", Map.of("b", "2"), OptionalInt.empty());

    // Restarting at 1 would collide with the ledger's own v1 entry from before the delete.
    assertEquals(new ConfigMapWriteResult.Written(3), recreated);
  }

  @Test
  void get_many_returns_only_the_requested_names() {
    store.put("acme", "a", Map.of("k", "1"), OptionalInt.empty());
    store.put("acme", "b", Map.of("k", "2"), OptionalInt.empty());
    store.put("acme", "c", Map.of("k", "3"), OptionalInt.empty());

    List<ConfigMap> result = store.getMany("acme", List.of("a", "c"));

    assertEquals(Set.of("a", "c"), Set.copyOf(result.stream().map(ConfigMap::name).toList()));
  }

  /**
   * The issue's own required concurrency regression, modeled on {@code SecretStoreTest}'s analogous
   * test: N threads each write a *distinct* key to the *same* ConfigMap concurrently. Unlike {@code
   * SecretStore.put}, {@link ConfigMapStore#patch}'s {@code expectedVersion} check is intentionally
   * caller-driven with no internal retry on a stale version -- only lease contention retries
   * internally. So each test thread here plays the role a well-behaved caller must: read the
   * current version, patch, and on a {@code VersionConflict} re-read and retry -- exactly the loop
   * a real concurrent CLI/console writer would need. Asserts the final ConfigMap contains every key
   * any thread wrote.
   */
  @Test
  @Timeout(15)
  void concurrent_writers_of_distinct_keys_to_the_same_configmap_never_lose_an_update()
      throws Exception {
    int writers = 6;
    store.put("acme", "shared", Map.of(), OptionalInt.empty());
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
                            patchOneKeyWithRetry("k" + i, "writer-" + i);
                            return null;
                          }))
              .toList();
      ready.await();
      go.countDown();
      for (Future<Void> future : futures) {
        future.get();
      }

      Map<String, String> finalData = store.get("acme", "shared").orElseThrow().data();
      for (int i = 0; i < writers; i++) {
        assertEquals("writer-" + i, finalData.get("k" + i));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * The caller-side read-patch-retry-on-conflict loop {@link ConfigMapStore#patch} deliberately
   * pushes onto callers -- see this class's own javadoc on the test above.
   */
  private void patchOneKeyWithRetry(String key, String value) {
    for (int attempt = 0; attempt < 50; attempt++) {
      int currentVersion = store.get("acme", "shared").map(ConfigMap::version).orElse(0);
      ConfigMapWriteResult result =
          store.patch("acme", "shared", Map.of(key, value), currentVersion);
      if (result instanceof ConfigMapWriteResult.Written) {
        return;
      }
      // VersionConflict: another writer landed first; retry against the now-current version.
    }
    throw new AssertionError("could not write key " + key + " under contention");
  }
}
