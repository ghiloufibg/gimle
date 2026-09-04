package com.gimle.ivaldi.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClusterStoreTest {

  @TempDir Path tempDir;

  private ClusterStore store;

  @BeforeEach
  void setUp() {
    store = new ClusterStore(tempDir.resolve("clusters"));
  }

  @Test
  void creates_a_cluster_and_mints_an_id_from_its_name() {
    Map<String, Object> created =
        store.create("{\"name\":\"Local Dev\",\"controlPlaneUrl\":\"http://127.0.0.1:8080\"}");

    assertEquals("local-dev", created.get("id"));
    assertEquals("Local Dev", created.get("name"));
    assertEquals("http://127.0.0.1:8080", created.get("controlPlaneUrl"));
  }

  @Test
  void two_clusters_with_the_same_name_get_distinct_ids() {
    Map<String, Object> first =
        store.create("{\"name\":\"dup\",\"controlPlaneUrl\":\"127.0.0.1:8080\"}");
    Map<String, Object> second =
        store.create("{\"name\":\"dup\",\"controlPlaneUrl\":\"127.0.0.1:8080\"}");

    assertNotEquals(first.get("id"), second.get("id"));
  }

  @Test
  void get_of_an_unknown_id_is_empty() {
    assertEquals(Optional.empty(), store.get("no-such-cluster"));
  }

  @Test
  void save_upserts_at_an_explicit_id_and_stamps_the_id_field() {
    store.save("local-dev", "{\"name\":\"first\",\"controlPlaneUrl\":\"127.0.0.1:8080\"}");
    Map<String, Object> second =
        store.save("local-dev", "{\"name\":\"second\",\"controlPlaneUrl\":\"127.0.0.1:8080\"}");

    assertEquals("local-dev", second.get("id"));
    assertTrue(store.get("local-dev").orElseThrow().contains("second"));
  }

  @Test
  void delete_removes_a_cluster_and_reports_whether_one_existed() {
    Map<String, Object> created =
        store.create("{\"name\":\"gone-soon\",\"controlPlaneUrl\":\"127.0.0.1:8080\"}");
    String id = String.valueOf(created.get("id"));

    assertTrue(store.delete(id));
    assertFalse(store.delete(id));
    assertEquals(Optional.empty(), store.get(id));
  }

  @Test
  void list_returns_every_stored_cluster_newest_first() {
    store.create(
        "{\"name\":\"a\",\"updatedAt\":\"2026-01-01T00:00:00Z\",\"controlPlaneUrl\":\"127.0.0.1:8080\"}");
    store.create(
        "{\"name\":\"b\",\"updatedAt\":\"2026-01-02T00:00:00Z\",\"controlPlaneUrl\":\"127.0.0.1:8080\"}");

    List<Map<String, Object>> all = store.list();

    assertEquals(2, all.size());
    assertEquals("b", all.get(0).get("name"));
  }

  /**
   * A saved connection whose whole purpose is to name a control plane, without one, is a record
   * that can never run anything -- accepting it silently only moved the failure to the far end of a
   * run that had already booted a platform first.
   */
  @Test
  void refuses_to_write_a_connection_with_no_control_plane_url() {
    assertThrows(IllegalArgumentException.class, () -> store.create("{\"name\":\"blank\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.create("{\"name\":\"blank\",\"controlPlaneUrl\":\"\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.create("{\"name\":\"blank\",\"controlPlaneUrl\":\"   \"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.save("local-dev", "{\"name\":\"blank\",\"controlPlaneUrl\":\"\"}"));
  }

  @Test
  void rejects_a_body_that_is_not_a_json_object() {
    assertThrows(IllegalArgumentException.class, () -> store.create("[1,2,3]"));
    assertThrows(IllegalArgumentException.class, () -> store.create("not json at all"));
  }

  @Test
  void rejects_an_id_that_could_escape_the_store_directory() {
    assertThrows(IllegalArgumentException.class, () -> store.get("../../etc/passwd"));
    assertThrows(IllegalArgumentException.class, () -> store.save("../escape", "{}"));
  }

  @Test
  void applied_topology_starts_empty_and_round_trips_once_recorded() {
    Map<String, Object> created =
        store.create("{\"name\":\"local\",\"controlPlaneUrl\":\"127.0.0.1:8080\"}");
    String id = String.valueOf(created.get("id"));

    assertEquals(Optional.empty(), store.appliedTopology(id));

    store.recordAppliedTopology(id, "name: local\n");

    assertEquals(Optional.of("name: local\n"), store.appliedTopology(id));
  }

  @Test
  void clear_applied_topology_forgets_it() {
    Map<String, Object> created =
        store.create("{\"name\":\"local\",\"controlPlaneUrl\":\"127.0.0.1:8080\"}");
    String id = String.valueOf(created.get("id"));
    store.recordAppliedTopology(id, "name: local\n");

    store.clearAppliedTopology(id);

    assertEquals(Optional.empty(), store.appliedTopology(id));
  }

  @Test
  void delete_also_removes_the_applied_topology_sidecar_file() {
    Map<String, Object> created =
        store.create("{\"name\":\"local\",\"controlPlaneUrl\":\"127.0.0.1:8080\"}");
    String id = String.valueOf(created.get("id"));
    store.recordAppliedTopology(id, "name: local\n");

    store.delete(id);

    assertEquals(Optional.empty(), store.appliedTopology(id));
  }
}
