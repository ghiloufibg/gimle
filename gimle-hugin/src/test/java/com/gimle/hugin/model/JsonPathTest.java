package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Reading a column's value out of whatever shape the response turned out to be. */
class JsonPathTest {

  @Test
  void a_nested_path_reads_the_value_at_the_end_of_it() {
    Map<String, Object> tenant =
        Map.of("id", "acme", "usage", Map.of("instances", 3), "quota", Map.of("maxInstances", 10));

    assertEquals("acme", JsonPath.textAt(tenant, "id"));
    assertEquals("3", JsonPath.textAt(tenant, "usage.instances"));
    assertEquals("10", JsonPath.textAt(tenant, "quota.maxInstances"));
  }

  @Test
  void a_path_naming_something_that_is_not_there_is_an_empty_cell_not_a_failure() {
    // Column paths come from two places allowed to be wrong about a given response: this module's
    // own definitions, and print columns authored by whoever registered a custom kind.
    Map<String, Object> object = Map.of("spec", Map.of("name", "checkout"));

    assertEquals("", JsonPath.textAt(object, "spec.replicas"));
    assertEquals("", JsonPath.textAt(object, "status.phase.deep.deeper"));
    assertEquals("", JsonPath.textAt(object, "name.through.a.string"));
  }

  @Test
  void a_null_value_reads_as_absent_rather_than_as_the_word_null() {
    // A custom resource with no status yet reports status: null, which is a blank cell, not text.
    Map<String, Object> object = new LinkedHashMap<>();
    object.put("status", null);

    assertEquals("", JsonPath.textAt(object, "status"));
  }

  @Test
  void a_boolean_reads_as_yes_or_no_because_a_column_of_true_scans_badly() {
    assertEquals("yes", JsonPath.textAt(Map.of("quotaViolating", true), "quotaViolating"));
    assertEquals("no", JsonPath.textAt(Map.of("quotaViolating", false), "quotaViolating"));
  }

  @Test
  void a_whole_number_keeps_no_decimal_point_of_its_own() {
    // JSON has one number type and a parser is free to hand back a double; a table has two.
    assertEquals("3", JsonPath.textAt(Map.of("n", 3.0), "n"));
    assertEquals("3.50", JsonPath.textAt(Map.of("n", 3.5), "n"));
  }

  @Test
  void a_list_of_scalars_is_joined_but_a_list_of_objects_is_only_counted() {
    // A role's permissions printed in full would be the only thing on its row.
    assertEquals("dev,ops", JsonPath.textAt(Map.of("groups", List.of("dev", "ops")), "groups"));
    assertEquals(
        "2 entries",
        JsonPath.textAt(
            Map.of("permissions", List.of(Map.of("verb", "READ"), Map.of())), "permissions"));
    assertEquals("", JsonPath.textAt(Map.of("groups", List.of()), "groups"));
  }

  @Test
  void an_object_says_how_many_fields_it_has_rather_than_spilling_them_into_the_cell() {
    assertEquals("2 fields", JsonPath.textAt(Map.of("quota", Map.of("a", 1, "b", 2)), "quota"));
  }
}
