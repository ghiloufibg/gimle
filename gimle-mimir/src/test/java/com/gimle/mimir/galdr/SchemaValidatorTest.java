package com.gimle.mimir.galdr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SchemaValidatorTest {

  // ---- shared field shorthands ----

  private static SchemaField.StringField string(String name, boolean required) {
    return new SchemaField.StringField(name, required, Optional.empty(), OptionalInt.empty());
  }

  private static SchemaField.IntField intField(String name, long min, long max) {
    return new SchemaField.IntField(
        name, false, OptionalLong.empty(), OptionalLong.of(min), OptionalLong.of(max));
  }

  /** The walkthrough's Greeting schema: message (required string), repeat, tone. */
  private static SchemaModel greetingSchema() {
    return new SchemaModel(
        List.of(
            string("message", true),
            new SchemaField.IntField(
                "repeat", false, OptionalLong.of(1L), OptionalLong.of(1L), OptionalLong.of(100L)),
            new SchemaField.EnumField(
                "tone", false, Optional.of("friendly"), List.of("friendly", "formal"))));
  }

  // ---- instance validation: happy and failure pairs, per type ----

  static Stream<Arguments> acceptedSpecs() {
    return Stream.of(
        Arguments.of(
            "string within maxLength",
            new SchemaModel(
                List.of(
                    new SchemaField.StringField(
                        "message", true, Optional.empty(), OptionalInt.of(10)))),
            Map.of("message", "hello")),
        Arguments.of(
            "int at both inclusive bounds",
            new SchemaModel(List.of(intField("repeat", 1, 100))),
            Map.of("repeat", 100)),
        Arguments.of(
            "double bounds accept a plain integer scalar",
            new SchemaModel(
                List.of(
                    new SchemaField.DoubleField(
                        "ratio",
                        false,
                        OptionalDouble.empty(),
                        OptionalDouble.of(0.0),
                        OptionalDouble.of(1.0)))),
            Map.of("ratio", 1)),
        Arguments.of(
            "bool",
            new SchemaModel(List.of(new SchemaField.BoolField("enabled", false, Optional.empty()))),
            Map.of("enabled", true)),
        Arguments.of(
            "enum exact membership",
            new SchemaModel(
                List.of(
                    new SchemaField.EnumField(
                        "tone", true, Optional.empty(), List.of("friendly", "formal")))),
            Map.of("tone", "formal")),
        Arguments.of(
            "list of ints within item bounds and size bounds",
            new SchemaModel(
                List.of(
                    new SchemaField.ListField(
                        "counts", intField("items", 0, 10), OptionalInt.of(1), OptionalInt.of(3)))),
            Map.of("counts", List.of(1, 2, 3))),
        Arguments.of(
            "nested object with its own required field present",
            new SchemaModel(
                List.of(new SchemaField.ObjectField("greeting", List.of(string("message", true))))),
            Map.of("greeting", Map.of("message", "hi"))),
        Arguments.of(
            "absent optional list and object are simply absent",
            new SchemaModel(
                List.of(
                    new SchemaField.ListField(
                        "counts",
                        intField("items", 0, 10),
                        OptionalInt.empty(),
                        OptionalInt.empty()),
                    new SchemaField.ObjectField("greeting", List.of(string("message", true))))),
            Map.of()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("acceptedSpecs")
  void accepts_a_valid_spec(String label, SchemaModel schema, Map<String, Object> spec) {
    assertDoesNotThrow(() -> SchemaValidator.validateAndDefault(schema, spec));
  }

  static Stream<Arguments> rejectedSpecs() {
    return Stream.of(
        Arguments.of(
            "missing required string",
            greetingSchema(),
            Map.of(),
            "spec.message: required field is missing"),
        Arguments.of(
            "wrong type for string",
            greetingSchema(),
            Map.of("message", 7),
            "spec.message: must be a string"),
        Arguments.of(
            "string over maxLength",
            new SchemaModel(
                List.of(
                    new SchemaField.StringField(
                        "message", true, Optional.empty(), OptionalInt.of(3)))),
            Map.of("message", "hello"),
            "exceeds maxLength 3"),
        Arguments.of(
            "int below inclusive minimum",
            greetingSchema(),
            Map.of("message", "hi", "repeat", 0),
            "spec.repeat: 0"),
        Arguments.of(
            "int above inclusive maximum",
            greetingSchema(),
            Map.of("message", "hi", "repeat", 101),
            "spec.repeat: 101"),
        Arguments.of(
            "double given a string",
            new SchemaModel(
                List.of(
                    new SchemaField.DoubleField(
                        "ratio",
                        true,
                        OptionalDouble.empty(),
                        OptionalDouble.empty(),
                        OptionalDouble.empty()))),
            Map.of("ratio", "0.5"),
            "spec.ratio: must be a number"),
        Arguments.of(
            "bool given a string",
            new SchemaModel(List.of(new SchemaField.BoolField("enabled", true, Optional.empty()))),
            Map.of("enabled", "true"),
            "spec.enabled: must be true or false"),
        Arguments.of(
            "enum outside the declared set, case-sensitively",
            greetingSchema(),
            Map.of("message", "hi", "tone", "Friendly"),
            "'Friendly' is not one of"),
        Arguments.of(
            "list under minItems",
            new SchemaModel(
                List.of(
                    new SchemaField.ListField(
                        "counts",
                        intField("items", 0, 10),
                        OptionalInt.of(2),
                        OptionalInt.empty()))),
            Map.of("counts", List.of(1)),
            "fewer than minItems 2"),
        Arguments.of(
            "list over maxItems",
            new SchemaModel(
                List.of(
                    new SchemaField.ListField(
                        "counts",
                        intField("items", 0, 10),
                        OptionalInt.empty(),
                        OptionalInt.of(1)))),
            Map.of("counts", List.of(1, 2)),
            "more than maxItems 1"),
        Arguments.of(
            "list element violating the item schema",
            new SchemaModel(
                List.of(
                    new SchemaField.ListField(
                        "counts",
                        intField("items", 0, 10),
                        OptionalInt.empty(),
                        OptionalInt.empty()))),
            Map.of("counts", List.of(1, 99)),
            "spec.counts[1]: 99"),
        Arguments.of(
            "object given a scalar",
            new SchemaModel(
                List.of(new SchemaField.ObjectField("greeting", List.of(string("message", true))))),
            Map.of("greeting", "hello"),
            "spec.greeting: must be an object"),
        Arguments.of(
            "nested required field missing inside a present object",
            new SchemaModel(
                List.of(new SchemaField.ObjectField("greeting", List.of(string("message", true))))),
            Map.of("greeting", Map.of()),
            "spec.greeting.message: required field is missing"),
        Arguments.of(
            "unknown key rejected, not silently pruned",
            greetingSchema(),
            Map.of("message", "hi", "mesage", "typo"),
            "spec.mesage: unknown field"),
        Arguments.of(
            "unknown key nested inside an object",
            new SchemaModel(
                List.of(new SchemaField.ObjectField("greeting", List.of(string("message", true))))),
            Map.of("greeting", Map.of("message", "hi", "extra", 1)),
            "spec.greeting.extra: unknown field"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectedSpecs")
  void rejects_an_invalid_spec_naming_the_field(
      String label, SchemaModel schema, Map<String, Object> spec, String expectedFragment) {
    GimleManifestException e =
        assertThrows(
            GimleManifestException.class, () -> SchemaValidator.validateAndDefault(schema, spec));
    assertTrue(
        e.getMessage().contains(expectedFragment),
        "expected message to contain '" + expectedFragment + "' but was: " + e.getMessage());
  }

  @Test
  void reports_every_violation_in_one_exception_not_just_the_first() {
    GimleManifestException e =
        assertThrows(
            GimleManifestException.class,
            () ->
                SchemaValidator.validateAndDefault(
                    greetingSchema(), Map.of("repeat", 0, "tone", "loud")));
    assertTrue(e.getMessage().contains("spec.message: required field is missing"), e.getMessage());
    assertTrue(e.getMessage().contains("spec.repeat: 0"), e.getMessage());
    assertTrue(e.getMessage().contains("'loud' is not one of"), e.getMessage());
  }

  // ---- defaulting ----

  @Test
  void applies_defaults_so_the_returned_spec_is_complete() {
    Map<String, Object> defaulted =
        SchemaValidator.validateAndDefault(greetingSchema(), Map.of("message", "hello"));
    assertEquals("hello", defaulted.get("message"));
    assertEquals(1L, defaulted.get("repeat"));
    assertEquals("friendly", defaulted.get("tone"));
  }

  @Test
  void a_supplied_value_wins_over_its_default() {
    Map<String, Object> defaulted =
        SchemaValidator.validateAndDefault(
            greetingSchema(), Map.of("message", "hello", "repeat", 3, "tone", "formal"));
    assertEquals(3L, defaulted.get("repeat"));
    assertEquals("formal", defaulted.get("tone"));
  }

  @Test
  void applies_defaults_inside_a_present_nested_object_but_never_materializes_an_absent_one() {
    SchemaModel schema =
        new SchemaModel(
            List.of(
                new SchemaField.ObjectField(
                    "greeting",
                    List.of(
                        new SchemaField.StringField(
                            "suffix", false, Optional.of("!"), OptionalInt.empty())))));

    Map<String, Object> withObject =
        SchemaValidator.validateAndDefault(schema, Map.of("greeting", Map.of()));
    assertEquals(Map.of("suffix", "!"), withObject.get("greeting"));

    Map<String, Object> withoutObject = SchemaValidator.validateAndDefault(schema, Map.of());
    assertFalse(withoutObject.containsKey("greeting"));
  }

  @Test
  void an_optional_field_with_no_default_stays_absent() {
    Map<String, Object> defaulted =
        SchemaValidator.validateAndDefault(
            new SchemaModel(List.of(string("message", true), string("note", false))),
            Map.of("message", "hi"));
    assertFalse(defaulted.containsKey("note"));
  }

  // ---- definition validation ----

  @Test
  void accepts_the_walkthrough_definition_schema() {
    assertDoesNotThrow(() -> SchemaValidator.validateDefinition(greetingSchema()));
  }

  static Stream<Arguments> rejectedDefinitions() {
    return Stream.of(
        Arguments.of(
            "required and default on the same field",
            new SchemaModel(
                List.of(
                    new SchemaField.StringField(
                        "message", true, Optional.of("hi"), OptionalInt.empty()))),
            "required or has a default, never both"),
        Arguments.of(
            "empty enum values",
            new SchemaModel(
                List.of(new SchemaField.EnumField("tone", false, Optional.empty(), List.of()))),
            "at least one value"),
        Arguments.of(
            "enum default outside its own values",
            new SchemaModel(
                List.of(
                    new SchemaField.EnumField(
                        "tone", false, Optional.of("loud"), List.of("friendly", "formal")))),
            "'loud' is not one of"),
        Arguments.of(
            "int default violating its own bounds",
            new SchemaModel(
                List.of(
                    new SchemaField.IntField(
                        "repeat",
                        false,
                        OptionalLong.of(0L),
                        OptionalLong.of(1L),
                        OptionalLong.of(100L)))),
            "default violates its own min/max bounds"),
        Arguments.of(
            "string default over its own maxLength",
            new SchemaModel(
                List.of(
                    new SchemaField.StringField(
                        "message", false, Optional.of("hello"), OptionalInt.of(3)))),
            "default violates its own maxLength"),
        Arguments.of(
            "inverted int bounds",
            new SchemaModel(List.of(intField("repeat", 10, 1))),
            "min 10 exceeds max 1"),
        Arguments.of(
            "inverted list size bounds",
            new SchemaModel(
                List.of(
                    new SchemaField.ListField(
                        "counts", intField("items", 0, 10), OptionalInt.of(3), OptionalInt.of(1)))),
            "minItems 3 exceeds maxItems 1"),
        Arguments.of(
            "duplicate field names",
            new SchemaModel(List.of(string("message", true), string("message", false))),
            "duplicate field name"),
        Arguments.of(
            "blank field name", new SchemaModel(List.of(string(" ", false))), "must not be blank"),
        Arguments.of(
            "list item schema carrying a default",
            new SchemaModel(
                List.of(
                    new SchemaField.ListField(
                        "notes",
                        new SchemaField.StringField(
                            "items", false, Optional.of("hi"), OptionalInt.empty()),
                        OptionalInt.empty(),
                        OptionalInt.empty()))),
            "may not itself be required or carry a default"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectedDefinitions")
  void rejects_an_invalid_definition(String label, SchemaModel schema, String expectedFragment) {
    GimleManifestException e =
        assertThrows(
            GimleManifestException.class, () -> SchemaValidator.validateDefinition(schema));
    assertTrue(
        e.getMessage().contains(expectedFragment),
        "expected message to contain '" + expectedFragment + "' but was: " + e.getMessage());
  }

  @Test
  void accepts_nesting_at_the_depth_cap_and_rejects_one_level_beyond_it() {
    assertDoesNotThrow(() -> SchemaValidator.validateDefinition(new SchemaModel(nestedObjects(8))));
    GimleManifestException e =
        assertThrows(
            GimleManifestException.class,
            () -> SchemaValidator.validateDefinition(new SchemaModel(nestedObjects(9))));
    assertTrue(e.getMessage().contains("depth cap of 8"), e.getMessage());
  }

  /** {@code depth} levels of nesting: leaf at level {@code depth}, objects above it. */
  private static List<SchemaField> nestedObjects(int depth) {
    if (depth == 1) {
      return List.of(string("leaf", false));
    }
    return List.of(new SchemaField.ObjectField("level" + depth, nestedObjects(depth - 1)));
  }

  @Test
  void a_list_of_objects_counts_both_levels_toward_the_depth_cap() {
    // A list level and its object-item level each consume one of the 8: 6 object levels inside
    // the item object land the leaf exactly at the cap, a 7th goes one past it.
    assertDoesNotThrow(
        () ->
            SchemaValidator.validateDefinition(
                new SchemaModel(
                    List.of(
                        new SchemaField.ListField(
                            "entries",
                            new SchemaField.ObjectField("items", nestedObjects(6)),
                            OptionalInt.empty(),
                            OptionalInt.empty())))));
    GimleManifestException e =
        assertThrows(
            GimleManifestException.class,
            () ->
                SchemaValidator.validateDefinition(
                    new SchemaModel(
                        List.of(
                            new SchemaField.ListField(
                                "entries",
                                new SchemaField.ObjectField("items", nestedObjects(7)),
                                OptionalInt.empty(),
                                OptionalInt.empty())))));
    assertTrue(e.getMessage().contains("depth cap of 8"), e.getMessage());
  }

  // ---- payload size caps ----

  @Test
  void accepts_a_payload_at_the_cap_and_rejects_one_byte_over() {
    assertDoesNotThrow(() -> SchemaValidator.checkPayloadSize("spec", 256 * 1024));
    GimleManifestException e =
        assertThrows(
            GimleManifestException.class,
            () -> SchemaValidator.checkPayloadSize("status", 256 * 1024 + 1));
    assertTrue(e.getMessage().contains("status"), e.getMessage());
    assertTrue(e.getMessage().contains("262144-byte cap"), e.getMessage());
  }
}
