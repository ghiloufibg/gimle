package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Emitting a parsed JSON tree as the YAML the describe pane shows. */
class YamlTest {

  @Test
  void a_flat_object_becomes_one_key_per_line() {
    assertEquals(
        List.of("name: checkout-api", "replicas: 3"),
        Yaml.lines(ordered("name", "checkout-api", "replicas", 3)));
  }

  @Test
  void a_nested_object_is_indented_under_the_key_that_holds_it() {
    Map<String, Object> value = ordered("spec", ordered("name", "checkout", "replicas", 2));

    assertEquals(List.of("spec:", "  name: checkout", "  replicas: 2"), Yaml.lines(value));
  }

  @Test
  void a_list_of_objects_puts_the_first_key_on_the_dash() {
    // An element drawn as a bare dash followed by an unattached block reads as two things.
    Map<String, Object> value =
        ordered("permissions", List.of(ordered("resource", "DEPLOYMENT", "verb", "READ")));

    assertEquals(
        List.of("permissions:", "  - resource: DEPLOYMENT", "    verb: READ"), Yaml.lines(value));
  }

  @Test
  void a_list_of_scalars_is_one_dash_per_element() {
    assertEquals(
        List.of("groups:", "  - dev", "  - ops"),
        Yaml.lines(ordered("groups", List.of("dev", "ops"))));
  }

  @Test
  void an_empty_object_or_list_stays_on_the_key_line_rather_than_opening_a_block() {
    assertEquals(
        List.of("routes: []", "names: {}"),
        Yaml.lines(ordered("routes", List.of(), "names", Map.of())));
  }

  @Test
  void a_null_is_printed_as_null_because_the_field_being_absent_is_the_reading() {
    // A custom resource that no controller has written a status for reports exactly this.
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("status", null);

    assertEquals(List.of("status: null"), Yaml.lines(value));
  }

  @Test
  void a_scalar_that_would_read_back_as_something_else_is_quoted() {
    // The pane is a rendering, not a round trip, but a value that silently changes type between
    // the API and the screen is a lie about what the cluster holds.
    assertEquals(List.of("version: \"1.2\""), Yaml.lines(ordered("version", "1.2")));
    assertEquals(List.of("enabled: \"true\""), Yaml.lines(ordered("enabled", "true")));
    assertEquals(List.of("value: \"\""), Yaml.lines(ordered("value", "")));
    assertEquals(List.of("host: \"db:5432\""), Yaml.lines(ordered("host", "db:5432")));
  }

  @Test
  void an_ordinary_string_is_left_bare() {
    assertEquals(List.of("name: checkout-api"), Yaml.lines(ordered("name", "checkout-api")));
    assertEquals(List.of("path: /var/lib/gimle"), Yaml.lines(ordered("path", "/var/lib/gimle")));
  }

  @Test
  void a_number_carries_no_decimal_point_it_did_not_have() {
    assertEquals(List.of("replicas: 3"), Yaml.lines(ordered("replicas", 3.0)));
  }

  @Test
  void a_quote_or_a_newline_inside_a_value_is_escaped_rather_than_breaking_the_line() {
    List<String> lines = Yaml.lines(ordered("message", "said \"hi\"\nthen left"));

    assertEquals(1, lines.size(), lines.toString());
    assertTrue(lines.getFirst().contains("\\\"hi\\\""), lines.getFirst());
    assertTrue(lines.getFirst().contains("\\n"), lines.getFirst());
  }

  private static Map<String, Object> ordered(final Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      map.put(String.valueOf(pairs[index]), pairs[index + 1]);
    }
    return map;
  }
}
