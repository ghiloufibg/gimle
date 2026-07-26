package com.gimle.core.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

  @Test
  void parses_a_flat_object() {
    Map<?, ?> parsed = (Map<?, ?>) Json.parse("{\"a\":1,\"b\":\"text\",\"c\":true,\"d\":null}");
    assertEquals(1L, parsed.get("a"));
    assertEquals("text", parsed.get("b"));
    assertEquals(true, parsed.get("c"));
    assertNull(parsed.get("d"));
  }

  @Test
  void parses_nested_objects_and_arrays() {
    Object parsed = Json.parse("{\"tiers\":[\"TIER_1\",\"TIER_2\"],\"nested\":{\"x\":1.5}}");
    Map<?, ?> map = (Map<?, ?>) parsed;
    assertEquals(List.of("TIER_1", "TIER_2"), map.get("tiers"));
    assertEquals(1.5, ((Map<?, ?>) map.get("nested")).get("x"));
  }

  @Test
  void parses_negative_and_exponent_numbers() {
    Object parsed = Json.parse("[-5, 3.14, 2e3, -1.5e-2]");
    assertEquals(List.of(-5L, 3.14, 2000.0, -0.015), parsed);
  }

  @Test
  void parses_escaped_strings() {
    Object parsed = Json.parse("\"line1\\nline2\\t\\\"quoted\\\"\"");
    assertEquals("line1\nline2\t\"quoted\"", parsed);
  }

  @Test
  void write_round_trips_through_parse() {
    Map<String, Object> original =
        new LinkedHashMap<>(
            Map.of(
                "name",
                "orders",
                "replicas",
                3L,
                "ready",
                true,
                "labels",
                List.of("zone-a", "gpu")));
    String json = Json.write(original);
    Object reparsed = Json.parse(json);
    assertEquals(original, reparsed);
  }

  @Test
  void write_escapes_special_characters() {
    String json = Json.write(Map.of("text", "line1\nline2\"quoted\""));
    assertEquals("{\"text\":\"line1\\nline2\\\"quoted\\\"\"}", json);
  }

  @Test
  void malformed_input_throws() {
    assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":}"));
    assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2"));
    assertThrows(IllegalArgumentException.class, () -> Json.parse("not json"));
    assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1} trailing"));
  }
}
