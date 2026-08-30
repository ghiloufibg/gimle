package com.gimle.mimir.galdr;

import com.gimle.core.exception.GimleManifestException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The hand-rolled validator behind custom-kind admission: checks a {@link SchemaModel} itself when
 * a KindDefinition is admitted (so a bad schema can never be stored), and checks-and-defaults an
 * instance's {@code spec} tree against a stored schema on every instance put. Deliberately not a
 * type system -- it exists to catch shape mistakes at admission with a good error naming the field
 * and the violated rule; anything it cannot express is the operator's job to check and report via
 * {@code status}. Every violation found is reported in one exception rather than failing on the
 * first, so a caller fixes a manifest in one round trip instead of one field at a time.
 */
public final class SchemaValidator {

  /**
   * Maximum object/list nesting a declared schema may reach -- checked when the definition is
   * admitted, so instance validation's own recursion is bounded by construction.
   */
  public static final int MAX_SCHEMA_DEPTH = 8;

  /**
   * Cap on an instance's canonical {@code spec} and on each reported {@code status}, in bytes.
   * These payloads travel the Raft log and live in every replica's snapshot, so an uncapped one is
   * a replicated-storage denial of service, not just a big row.
   */
  public static final int MAX_PAYLOAD_BYTES = 256 * 1024;

  private SchemaValidator() {}

  /** Rejects a {@code spec}/{@code status} payload over {@link #MAX_PAYLOAD_BYTES}. */
  public static void checkPayloadSize(final String what, final int lengthBytes) {
    if (lengthBytes > MAX_PAYLOAD_BYTES) {
      throw new GimleManifestException(
          what
              + " is "
              + lengthBytes
              + " bytes, over the "
              + MAX_PAYLOAD_BYTES
              + "-byte cap -- these bytes are Raft-replicated and snapshotted on every store"
              + " replica");
    }
  }

  /**
   * Validates a declared schema when its KindDefinition is admitted: duplicate/blank field names,
   * required-xor-default, empty enums, defaults violating their own constraints, inverted bounds,
   * item schemas carrying required/default, and nesting beyond {@link #MAX_SCHEMA_DEPTH}. Throws
   * {@link GimleManifestException} listing every violation found.
   */
  public static void validateDefinition(final SchemaModel schema) {
    final List<String> violations = new ArrayList<>();
    validateFields(schema.fields(), "schema.fields", 1, violations);
    throwIfAny("invalid schema", violations);
  }

  /**
   * Validates {@code spec} (a parsed YAML/JSON object tree: maps, lists, strings, numbers,
   * booleans) against {@code schema}, applying declared defaults, and returns the complete
   * defaulted tree in schema declaration order -- what admission canonicalizes and persists, so a
   * stored spec is always complete and an operator never re-derives defaulting logic. Unknown keys
   * are rejected, not ignored: a typo'd field name fails loudly at apply time. Throws {@link
   * GimleManifestException} listing every violation found.
   */
  public static Map<String, Object> validateAndDefault(
      final SchemaModel schema, final Map<String, Object> spec) {
    final List<String> violations = new ArrayList<>();
    final Map<String, Object> defaulted = validateObject(schema.fields(), spec, "spec", violations);
    throwIfAny("invalid spec", violations);
    return defaulted;
  }

  // ---- definition-side checks ----

  private static void validateFields(
      final List<SchemaField> fields,
      final String path,
      final int depth,
      final List<String> violations) {
    if (depth > MAX_SCHEMA_DEPTH) {
      violations.add(path + ": nesting exceeds the depth cap of " + MAX_SCHEMA_DEPTH);
      return;
    }
    final Set<String> seen = new LinkedHashSet<>();
    for (final SchemaField field : fields) {
      final String fieldPath = path + "." + field.name();
      if (field.name() == null || field.name().isBlank()) {
        violations.add(path + ": a field name must not be blank");
        continue;
      }
      if (!seen.add(field.name())) {
        violations.add(fieldPath + ": duplicate field name");
        continue;
      }
      validateField(field, fieldPath, depth, violations);
    }
  }

  private static void validateField(
      final SchemaField field, final String path, final int depth, final List<String> violations) {
    switch (field) {
      case SchemaField.StringField f -> {
        requireNotRequiredWithDefault(f.required(), f.defaultValue().isPresent(), path, violations);
        if (f.maxLength().isPresent() && f.maxLength().getAsInt() < 0) {
          violations.add(path + ": maxLength must not be negative");
        }
        if (f.defaultValue().isPresent()
            && f.maxLength().isPresent()
            && f.defaultValue().get().length() > f.maxLength().getAsInt()) {
          violations.add(
              path + ": default violates its own maxLength of " + f.maxLength().getAsInt());
        }
      }
      case SchemaField.IntField f -> {
        requireNotRequiredWithDefault(f.required(), f.defaultValue().isPresent(), path, violations);
        if (f.min().isPresent()
            && f.max().isPresent()
            && f.min().getAsLong() > f.max().getAsLong()) {
          violations.add(
              path + ": min " + f.min().getAsLong() + " exceeds max " + f.max().getAsLong());
        } else if (f.defaultValue().isPresent()
            && !withinBounds(f.defaultValue().getAsLong(), f.min(), f.max())) {
          violations.add(path + ": default violates its own min/max bounds");
        }
      }
      case SchemaField.DoubleField f -> {
        requireNotRequiredWithDefault(f.required(), f.defaultValue().isPresent(), path, violations);
        if (f.min().isPresent()
            && f.max().isPresent()
            && f.min().getAsDouble() > f.max().getAsDouble()) {
          violations.add(
              path + ": min " + f.min().getAsDouble() + " exceeds max " + f.max().getAsDouble());
        } else if (f.defaultValue().isPresent()
            && !withinBounds(f.defaultValue().getAsDouble(), f.min(), f.max())) {
          violations.add(path + ": default violates its own min/max bounds");
        }
      }
      case SchemaField.BoolField f ->
          requireNotRequiredWithDefault(
              f.required(), f.defaultValue().isPresent(), path, violations);
      case SchemaField.EnumField f -> {
        requireNotRequiredWithDefault(f.required(), f.defaultValue().isPresent(), path, violations);
        if (f.values().isEmpty()) {
          violations.add(path + ": an enum must declare at least one value");
        } else if (f.defaultValue().isPresent() && !f.values().contains(f.defaultValue().get())) {
          violations.add(
              path + ": default '" + f.defaultValue().get() + "' is not one of " + f.values());
        }
      }
      case SchemaField.ListField f -> {
        if (f.minItems().isPresent() && f.minItems().getAsInt() < 0) {
          violations.add(path + ": minItems must not be negative");
        }
        if (f.minItems().isPresent()
            && f.maxItems().isPresent()
            && f.minItems().getAsInt() > f.maxItems().getAsInt()) {
          violations.add(
              path
                  + ": minItems "
                  + f.minItems().getAsInt()
                  + " exceeds maxItems "
                  + f.maxItems().getAsInt());
        }
        if (itemCarriesRequiredOrDefault(f.items())) {
          violations.add(
              path + ": a list's item schema may not itself be required or carry a default");
        }
        validateField(f.items(), path + "[]", depth + 1, violations);
      }
      case SchemaField.ObjectField f -> validateFields(f.fields(), path, depth + 1, violations);
    }
  }

  private static boolean itemCarriesRequiredOrDefault(final SchemaField items) {
    return switch (items) {
      case SchemaField.StringField f -> f.required() || f.defaultValue().isPresent();
      case SchemaField.IntField f -> f.required() || f.defaultValue().isPresent();
      case SchemaField.DoubleField f -> f.required() || f.defaultValue().isPresent();
      case SchemaField.BoolField f -> f.required() || f.defaultValue().isPresent();
      case SchemaField.EnumField f -> f.required() || f.defaultValue().isPresent();
      case SchemaField.ListField f -> false;
      case SchemaField.ObjectField f -> false;
    };
  }

  private static void requireNotRequiredWithDefault(
      final boolean required,
      final boolean hasDefault,
      final String path,
      final List<String> violations) {
    if (required && hasDefault) {
      violations.add(path + ": a field is required or has a default, never both");
    }
  }

  // ---- instance-side checks ----

  private static Map<String, Object> validateObject(
      final List<SchemaField> fields,
      final Map<String, Object> value,
      final String path,
      final List<String> violations) {
    final Set<String> declared = new LinkedHashSet<>();
    for (final SchemaField field : fields) {
      declared.add(field.name());
    }
    for (final Object key : value.keySet()) {
      if (!(key instanceof String s) || !declared.contains(s)) {
        violations.add(path + "." + key + ": unknown field -- declared fields are " + declared);
      }
    }
    final Map<String, Object> defaulted = new LinkedHashMap<>();
    for (final SchemaField field : fields) {
      final String fieldPath = path + "." + field.name();
      if (!value.containsKey(field.name())) {
        handleAbsent(field, fieldPath, defaulted, violations);
        continue;
      }
      final Object raw = value.get(field.name());
      final Object checked = validateValue(field, raw, fieldPath, violations);
      if (checked != null) {
        defaulted.put(field.name(), checked);
      }
    }
    return defaulted;
  }

  private static void handleAbsent(
      final SchemaField field,
      final String path,
      final Map<String, Object> defaulted,
      final List<String> violations) {
    switch (field) {
      case SchemaField.StringField f -> {
        if (f.required()) {
          violations.add(path + ": required field is missing");
        } else {
          f.defaultValue().ifPresent(v -> defaulted.put(f.name(), v));
        }
      }
      case SchemaField.IntField f -> {
        if (f.required()) {
          violations.add(path + ": required field is missing");
        } else if (f.defaultValue().isPresent()) {
          defaulted.put(f.name(), f.defaultValue().getAsLong());
        }
      }
      case SchemaField.DoubleField f -> {
        if (f.required()) {
          violations.add(path + ": required field is missing");
        } else if (f.defaultValue().isPresent()) {
          defaulted.put(f.name(), f.defaultValue().getAsDouble());
        }
      }
      case SchemaField.BoolField f -> {
        if (f.required()) {
          violations.add(path + ": required field is missing");
        } else {
          f.defaultValue().ifPresent(v -> defaulted.put(f.name(), v));
        }
      }
      case SchemaField.EnumField f -> {
        if (f.required()) {
          violations.add(path + ": required field is missing");
        } else {
          f.defaultValue().ifPresent(v -> defaulted.put(f.name(), v));
        }
      }
      // Lists and objects have no required/default -- absent is simply absent.
      case SchemaField.ListField f -> {}
      case SchemaField.ObjectField f -> {}
    }
  }

  /** Returns the validated (possibly deep-defaulted) value, or {@code null} on a violation. */
  private static Object validateValue(
      final SchemaField field, final Object raw, final String path, final List<String> violations) {
    return switch (field) {
      case SchemaField.StringField f -> {
        if (!(raw instanceof String s)) {
          yield reject(violations, path + ": must be a string, got " + typeName(raw));
        }
        if (f.maxLength().isPresent() && s.length() > f.maxLength().getAsInt()) {
          yield reject(
              violations,
              path + ": length " + s.length() + " exceeds maxLength " + f.maxLength().getAsInt());
        }
        yield s;
      }
      case SchemaField.IntField f -> {
        if (!(raw instanceof Integer) && !(raw instanceof Long)) {
          yield reject(violations, path + ": must be an integer, got " + typeName(raw));
        }
        final long v = ((Number) raw).longValue();
        if (!withinBounds(v, f.min(), f.max())) {
          yield reject(violations, path + ": " + v + boundsSuffix(f.min(), f.max()));
        }
        yield v;
      }
      case SchemaField.DoubleField f -> {
        if (!(raw instanceof Integer) && !(raw instanceof Long) && !(raw instanceof Double)) {
          yield reject(violations, path + ": must be a number, got " + typeName(raw));
        }
        final double v = ((Number) raw).doubleValue();
        if (!withinBounds(v, f.min(), f.max())) {
          yield reject(violations, path + ": " + v + boundsSuffix(f.min(), f.max()));
        }
        yield v;
      }
      case SchemaField.BoolField f -> {
        if (!(raw instanceof Boolean b)) {
          yield reject(violations, path + ": must be true or false, got " + typeName(raw));
        }
        yield b;
      }
      case SchemaField.EnumField f -> {
        if (!(raw instanceof String s)) {
          yield reject(violations, path + ": must be a string, got " + typeName(raw));
        }
        if (!f.values().contains(s)) {
          yield reject(violations, path + ": '" + s + "' is not one of " + f.values());
        }
        yield s;
      }
      case SchemaField.ListField f -> {
        if (!(raw instanceof List<?> list)) {
          yield reject(violations, path + ": must be a list, got " + typeName(raw));
        }
        if (f.minItems().isPresent() && list.size() < f.minItems().getAsInt()) {
          yield reject(
              violations,
              path
                  + ": "
                  + list.size()
                  + " item(s), fewer than minItems "
                  + f.minItems().getAsInt());
        }
        if (f.maxItems().isPresent() && list.size() > f.maxItems().getAsInt()) {
          yield reject(
              violations,
              path
                  + ": "
                  + list.size()
                  + " item(s), more than maxItems "
                  + f.maxItems().getAsInt());
        }
        final List<Object> checked = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
          final Object element =
              validateValue(f.items(), list.get(i), path + "[" + i + "]", violations);
          if (element != null) {
            checked.add(element);
          }
        }
        yield List.copyOf(checked);
      }
      case SchemaField.ObjectField f -> {
        if (!(raw instanceof Map<?, ?> map)) {
          yield reject(violations, path + ": must be an object, got " + typeName(raw));
        }
        @SuppressWarnings("unchecked") // keys are re-checked as Strings inside validateObject
        final Map<String, Object> asObject = (Map<String, Object>) map;
        yield validateObject(f.fields(), asObject, path, violations);
      }
    };
  }

  private static Object reject(final List<String> violations, final String message) {
    violations.add(message);
    return null;
  }

  private static boolean withinBounds(
      final long v, final java.util.OptionalLong min, final java.util.OptionalLong max) {
    return (min.isEmpty() || v >= min.getAsLong()) && (max.isEmpty() || v <= max.getAsLong());
  }

  private static boolean withinBounds(
      final double v, final java.util.OptionalDouble min, final java.util.OptionalDouble max) {
    return (min.isEmpty() || v >= min.getAsDouble()) && (max.isEmpty() || v <= max.getAsDouble());
  }

  private static String boundsSuffix(
      final java.util.OptionalLong min, final java.util.OptionalLong max) {
    if (min.isPresent() && max.isPresent()) {
      return " is outside the inclusive bounds [" + min.getAsLong() + ", " + max.getAsLong() + "]";
    }
    return min.isPresent()
        ? " is below the inclusive minimum " + min.getAsLong()
        : " is above the inclusive maximum " + max.getAsLong();
  }

  private static String boundsSuffix(
      final java.util.OptionalDouble min, final java.util.OptionalDouble max) {
    if (min.isPresent() && max.isPresent()) {
      return " is outside the inclusive bounds ["
          + min.getAsDouble()
          + ", "
          + max.getAsDouble()
          + "]";
    }
    return min.isPresent()
        ? " is below the inclusive minimum " + min.getAsDouble()
        : " is above the inclusive maximum " + max.getAsDouble();
  }

  private static String typeName(final Object raw) {
    if (raw == null) {
      return "null";
    }
    if (raw instanceof Map) {
      return "an object";
    }
    if (raw instanceof List) {
      return "a list";
    }
    if (raw instanceof String) {
      return "a string";
    }
    if (raw instanceof Boolean) {
      return "a boolean";
    }
    if (raw instanceof Number) {
      return "a number";
    }
    return raw.getClass().getSimpleName();
  }

  private static void throwIfAny(final String prefix, final List<String> violations) {
    if (!violations.isEmpty()) {
      throw new GimleManifestException(prefix + ": " + String.join("; ", violations));
    }
  }
}
