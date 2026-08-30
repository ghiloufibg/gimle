package com.gimle.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.config.ConfigEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * {@link ConfigVersionStore}, exercised against a real {@code gimle-mimir} store via {@link
 * InProcessStore} -- the same fixture {@code ConfigMapStoreTest} uses, whose own version-ledger
 * tests this class's own tests mirror (adapted to a single string value rather than a data map).
 */
@Isolated
class ConfigVersionStoreTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private ConfigVersionStore store;

  @BeforeEach
  void startStore() throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    store = new ConfigVersionStore(inProcessStore.client());
  }

  @AfterEach
  void stopStore() {
    inProcessStore.close();
  }

  @Test
  void put_mints_version_one_and_writes_the_live_row() {
    ConfigWriteOutcome result = store.put("acme", "log-level", "info");

    assertEquals(new ConfigWriteOutcome.Written(1), result);
    assertEquals(
        Optional.of("info"),
        entryValue(inProcessStore.store().getConfigEntry("acme", "log-level")));
  }

  @Test
  void a_second_put_bumps_the_version_by_exactly_one_and_overwrites_the_live_row() {
    store.put("acme", "log-level", "info");

    ConfigWriteOutcome result = store.put("acme", "log-level", "debug");

    assertEquals(new ConfigWriteOutcome.Written(2), result);
    assertEquals(
        Optional.of("debug"),
        entryValue(inProcessStore.store().getConfigEntry("acme", "log-level")));
  }

  @Test
  void delete_removes_the_live_row_and_mints_a_tombstone_version() {
    store.put("acme", "log-level", "info");

    ConfigDeleteOutcome result = store.delete("acme", "log-level");

    assertEquals(new ConfigDeleteOutcome.Deleted(2), result);
    assertEquals(Optional.empty(), inProcessStore.store().getConfigEntry("acme", "log-level"));
  }

  @Test
  void deleting_a_key_that_never_existed_is_a_notfound_noop() {
    ConfigDeleteOutcome result = store.delete("acme", "never-existed");

    assertEquals(new ConfigDeleteOutcome.NotFound(), result);
    assertEquals(List.of(), store.listVersions("acme", "never-existed"));
  }

  @Test
  void list_versions_shows_every_stamped_version_oldest_first_including_the_delete_tombstone() {
    store.put("acme", "log-level", "info");
    store.put("acme", "log-level", "debug");
    store.delete("acme", "log-level");

    List<ConfigVersion> versions = store.listVersions("acme", "log-level");

    assertEquals(3, versions.size());
    assertEquals(1, versions.get(0).version());
    assertEquals("info", versions.get(0).value());
    assertEquals(false, versions.get(0).deleted());
    assertEquals(2, versions.get(1).version());
    assertEquals("debug", versions.get(1).value());
    assertEquals(3, versions.get(2).version());
    assertEquals(true, versions.get(2).deleted());
  }

  @Test
  void rollback_to_an_earlier_version_restores_its_value_as_a_brand_new_version() {
    store.put("acme", "log-level", "info");
    store.put("acme", "log-level", "debug");

    ConfigRollbackOutcome result = store.rollback("acme", "log-level", 1);

    assertEquals(new ConfigRollbackOutcome.Applied(3, Optional.of("info"), false), result);
    assertEquals(
        Optional.of("info"),
        entryValue(inProcessStore.store().getConfigEntry("acme", "log-level")));
    // Rolling back never rewrites the version it targeted or anything stamped after it.
    List<ConfigVersion> versions = store.listVersions("acme", "log-level");
    assertEquals(3, versions.size());
    assertEquals("debug", versions.get(1).value());
  }

  @Test
  void rollback_to_a_deleted_version_deletes_the_key_again_as_a_new_version() {
    store.put("acme", "log-level", "info");
    store.delete("acme", "log-level");
    store.put("acme", "log-level", "debug");

    ConfigRollbackOutcome result = store.rollback("acme", "log-level", 2);

    assertEquals(new ConfigRollbackOutcome.Applied(4, Optional.empty(), true), result);
    assertEquals(Optional.empty(), inProcessStore.store().getConfigEntry("acme", "log-level"));
  }

  @Test
  void rollback_of_an_unknown_version_is_rejected_without_touching_the_live_row() {
    store.put("acme", "log-level", "info");

    ConfigRollbackOutcome result = store.rollback("acme", "log-level", 99);

    assertEquals(new ConfigRollbackOutcome.TargetNotFound(), result);
    assertEquals(
        Optional.of("info"),
        entryValue(inProcessStore.store().getConfigEntry("acme", "log-level")));
  }

  @Test
  void version_numbers_keep_counting_up_across_a_delete_then_recreate_cycle() {
    store.put("acme", "log-level", "info"); // v1
    store.delete("acme", "log-level"); // v2 (tombstone)

    ConfigWriteOutcome recreated = store.put("acme", "log-level", "debug");

    // Restarting at 1 would collide with the ledger's own v1 entry from before the delete.
    assertEquals(new ConfigWriteOutcome.Written(3), recreated);
  }

  private static Optional<String> entryValue(Optional<ConfigEntry> entry) {
    return entry.map(e -> new String(e.value(), StandardCharsets.UTF_8));
  }
}
