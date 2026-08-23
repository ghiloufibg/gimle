package com.gimle.fafnir.secretmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleSecretsException;
import com.gimle.fafnir.FafnirCrypto;
import com.gimle.fafnir.SecretMetadata;
import com.gimle.fafnir.SecretStore;
import com.gimle.fafnir.testsupport.InProcessStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
