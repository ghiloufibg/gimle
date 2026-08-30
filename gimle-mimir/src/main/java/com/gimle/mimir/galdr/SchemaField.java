package com.gimle.mimir.galdr;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * One named field in a custom kind's declared {@link SchemaModel} -- the parsed, stored form, never
 * re-parsed YAML. Deliberately small: six scalar-ish shapes plus {@link ObjectField} for nesting
 * and {@link ListField} for repetition, each carrying only the constraints {@link SchemaValidator}
 * actually enforces. Defaults live inside the variant whose type they must match, so a stored
 * schema can never carry a default of the wrong type. {@code list} and {@code object} fields take
 * no {@code required}/default at all: an absent one is simply absent, and their contents are only
 * validated when present.
 */
public sealed interface SchemaField {

  String name();

  record StringField(
      String name, boolean required, Optional<String> defaultValue, OptionalInt maxLength)
      implements SchemaField {}

  record IntField(
      String name, boolean required, OptionalLong defaultValue, OptionalLong min, OptionalLong max)
      implements SchemaField {}

  record DoubleField(
      String name,
      boolean required,
      OptionalDouble defaultValue,
      OptionalDouble min,
      OptionalDouble max)
      implements SchemaField {}

  record BoolField(String name, boolean required, Optional<Boolean> defaultValue)
      implements SchemaField {}

  record EnumField(
      String name, boolean required, Optional<String> defaultValue, List<String> values)
      implements SchemaField {

    public EnumField {
      values = List.copyOf(values);
    }
  }

  /**
   * {@code items} describes each element's type and constraints; its own {@code name} is unused
   * (conventionally {@code "items"}) and it may not itself be {@code required} or carry a default
   * -- element presence is the list's own membership, not a per-element flag. Both rules are
   * enforced when the definition is admitted, in {@link SchemaValidator#validateDefinition}.
   */
  record ListField(String name, SchemaField items, OptionalInt minItems, OptionalInt maxItems)
      implements SchemaField {}

  record ObjectField(String name, List<SchemaField> fields) implements SchemaField {

    public ObjectField {
      fields = List.copyOf(fields);
    }
  }
}
