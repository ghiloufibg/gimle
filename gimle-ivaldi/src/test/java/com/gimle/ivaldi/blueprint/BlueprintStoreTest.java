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
  void round_trips_the_exact_body_it_was_given() {
    String body = "{\"name\":\"x\",\"nodes\":[{\"id\":\"n1\",\"kind\":\"machine\"}]}";
    BlueprintSummary summary = store.create(body);

    assertEquals(Optional.of(body), store.get(summary.id()));
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

    assertEquals(Optional.of("{\"name\":\"second\"}"), store.get("orders-platform-local"));
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
