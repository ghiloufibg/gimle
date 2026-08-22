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

    store.delete("acme", "app-config");

    assertEquals(Optional.empty(), store.get("acme", "app-config"));
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
