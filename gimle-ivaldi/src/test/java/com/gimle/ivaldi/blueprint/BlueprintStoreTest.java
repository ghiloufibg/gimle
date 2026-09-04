package com.gimle.ivaldi.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlueprintStoreTest {

  @TempDir Path tempDir;

  private BlueprintStore store;

  @BeforeEach
  void setUp() {
    store = new BlueprintStore(tempDir.resolve("blueprints"));
  }

  @Test
  void creates_a_blueprint_and_mints_an_id_from_its_name() {
    BlueprintSummary summary =
        store.create("{\"name\":\"Orders Platform Local\",\"version\":\"1.0.0\"}");

    assertEquals("orders-platform-local", summary.id());
    assertEquals("Orders Platform Local", summary.name());
    assertEquals("1.0.0", summary.version());
  }

  @Test
  void round_trips_the_body_it_was_given_with_the_stored_id_stamped_into_it() {
    String body = "{\"name\":\"x\",\"nodes\":[{\"id\":\"n1\",\"kind\":\"machine\"}]}";
    BlueprintSummary summary = store.create(body);

    String stored = store.get(summary.id()).orElseThrow();
    assertTrue(stored.contains("\"nodes\""), stored);
    assertTrue(stored.contains("\"id\":\"" + summary.id() + "\""), stored);
  }

  /**
   * The console mints an id client-side before it ever POSTs and addresses every later save by it.
   * Minting a second, different id server-side left the two disagreeing: the console navigated to
   * the id it was handed and saved to the id inside its own document, producing two divergent
   * records for one blueprint, the opened one frozen at creation-time content.
   */
  @Test
  void honours_the_id_the_body_already_carries() {
    BlueprintSummary summary =
        store.create("{\"id\":\"bp-m6i5kklhn\",\"name\":\"Orders Platform\"}");

    assertEquals("bp-m6i5kklhn", summary.id());
    assertTrue(store.get("bp-m6i5kklhn").isPresent());
    assertTrue(store.get("orders-platform").isEmpty());
  }

  /** A POST is a create: reusing an id must never silently replace the blueprint already there. */
  @Test
  void mints_a_fresh_id_rather_than_overwriting_when_the_requested_one_is_taken() {
    BlueprintSummary first = store.create("{\"id\":\"taken\",\"name\":\"first\"}");
    BlueprintSummary second = store.create("{\"id\":\"taken\",\"name\":\"second\"}");

    assertEquals("taken", first.id());
    assertNotEquals("taken", second.id());
    assertEquals(
        "first",
        store.list().stream().filter(b -> b.id().equals("taken")).findFirst().orElseThrow().name());
  }

  /** An unusable id in the body is ignored the same way a missing one is. */
  @Test
  void falls_back_to_minting_when_the_body_carries_an_unusable_id() {
    assertEquals("x", store.create("{\"id\":\"../escape\",\"name\":\"x\"}").id());
    assertEquals("y", store.create("{\"id\":42,\"name\":\"y\"}").id());
  }

  /** A body's own id can never disagree with the id it is addressed by, on either write path. */
  @Test
  void save_stamps_the_addressed_id_into_the_body() {
    store.create("{\"id\":\"real-id\",\"name\":\"x\"}");

    store.save("real-id", "{\"id\":\"stale-id\",\"name\":\"x\",\"version\":\"2\"}");

    String stored = store.get("real-id").orElseThrow();
    assertTrue(stored.contains("\"id\":\"real-id\""), stored);
    assertFalse(stored.contains("stale-id"), stored);
  }

  @Test
  void two_blueprints_with_the_same_name_get_distinct_ids() {
    BlueprintSummary first = store.create("{\"name\":\"dup\"}");
    BlueprintSummary second = store.create("{\"name\":\"dup\"}");

    assertNotEquals(first.id(), second.id());
  }

  @Test
  void get_of_an_unknown_id_is_empty() {
    assertEquals(Optional.empty(), store.get("no-such-blueprint"));
  }

  @Test
  void save_upserts_at_an_explicit_id() {
    store.save("orders-platform-local", "{\"name\":\"first\"}");
    store.save("orders-platform-local", "{\"name\":\"second\"}");

    assertEquals(
        Optional.of("{\"name\":\"second\",\"id\":\"orders-platform-local\"}"),
        store.get("orders-platform-local"));
  }

  @Test
  void delete_removes_a_blueprint_and_reports_whether_one_existed() {
    BlueprintSummary summary = store.create("{\"name\":\"gone-soon\"}");

    assertTrue(store.delete(summary.id()));
    assertFalse(store.delete(summary.id()));
    assertEquals(Optional.empty(), store.get(summary.id()));
  }

  @Test
  void list_returns_every_stored_blueprint() {
    store.create("{\"name\":\"a\"}");
    store.create("{\"name\":\"b\"}");

    List<BlueprintSummary> all = store.list();

    assertEquals(2, all.size());
  }

  @Test
  void list_skips_a_corrupt_file_rather_than_failing() throws Exception {
    store.create("{\"name\":\"good\"}");
    java.nio.file.Files.writeString(
        tempDir.resolve("blueprints").resolve("corrupt.json"), "not json");

    List<BlueprintSummary> all = store.list();

    assertEquals(1, all.size());
    assertEquals("good", all.get(0).name());
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
}
