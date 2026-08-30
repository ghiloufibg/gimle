package com.gimle.controlplane.galdr;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.manifest.ApiVersion;
import com.gimle.mimir.galdr.KindDefinitionSpec;
import com.gimle.mimir.galdr.KindNames;
import com.gimle.mimir.galdr.KindScope;
import com.gimle.mimir.galdr.PrintColumn;
import com.gimle.mimir.galdr.SchemaField;
import com.gimle.mimir.galdr.SchemaModel;
import com.gimle.mimir.galdr.SchemaValidator;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses a {@code kind: KindDefinition} manifest into its stored {@link KindDefinitionSpec} --
 * separate from {@code ManifestParser}'s five-workload-kind dispatch on purpose: a KindDefinition
 * is not a workload, and that switch stays exactly five cases. Normalizes an unprefixed kind name
 * onto the {@code custom.} prefix (announced back as a warning the caller surfaces via {@code
 * X-Gimle-Warning}), enforces the name grammar that makes built-in shadowing structurally
 * impossible (built-ins never contain a dot), and runs {@link SchemaValidator#validateDefinition}
 * so a bad schema can never be stored. Unknown keys are rejected at every level, the same loud-typo
 * posture instance validation itself takes.
 */
public final class KindDefinitionParser {

  /** The prefix an unprefixed kind name is normalized onto. */
  public static final String DEFAULT_PREFIX = "custom.";

  private static final Set<ApiVersion> SUPPORTED_VERSIONS = Set.of(ApiVersion.V1ALPHA1);

  private static final Pattern PREFIX_SEGMENT = Pattern.compile("[a-z][a-z0-9]*");
  private static final Pattern KIND_SEGMENT = Pattern.compile("[A-Z][A-Za-z0-9]*");
  private static final Pattern DECLARED_NAME = Pattern.compile("[a-z][a-z0-9-]*");

  private static final Set<String> ROOT_KEYS =
      Set.of(
          "kind", "apiVersion", "name", "scope", "description", "names", "schema", "printColumns");

  private KindDefinitionParser() {}

  public record ParsedKindDefinition(KindDefinitionSpec spec, List<String> warnings) {}

  public static ParsedKindDefinition parse(InputStream yamlContent) {
    Object raw;
    try {
      // SafeConstructor restricts loading to plain maps/lists/scalars -- a submitted manifest is
      // untrusted input, same reasoning every other manifest parser in this codebase documents.
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      raw = yaml.load(yamlContent);
    } catch (RuntimeException e) {
      throw new GimleManifestException("malformed YAML in manifest", e);
    }
    if (!(raw instanceof Map<?, ?> root)) {
      throw new GimleManifestException("manifest must contain a YAML mapping at the root");
    }
    String kind = requireString(root, "kind");
    if (!"KindDefinition".equals(kind)) {
      throw new GimleManifestException(
          "manifest kind '" + kind + "' does not match this route (expected kind: KindDefinition)");
    }
    ApiVersion.of(root, "KindDefinition", SUPPORTED_VERSIONS);
    rejectUnknownKeys(root, ROOT_KEYS, "KindDefinition");

    List<String> warnings = new ArrayList<>();
    String kindName = normalizeKindName(requireString(root, "name"), warnings);
    KindScope scope = parseScope(requireString(root, "scope"));
    String description = optionalString(root, "description").orElse("");
    KindNames names = parseNames(root.get("names"));
    SchemaModel schema = parseSchema(root.get("schema"));
    List<PrintColumn> printColumns = parsePrintColumns(root.get("printColumns"));

    SchemaValidator.validateDefinition(schema);
    return new ParsedKindDefinition(
        new KindDefinitionSpec(kindName, scope, description, names, schema, printColumns, 0L),
        warnings);
  }

  /**
   * Every custom kind name carries a dot-separated prefix ({@code acme.Greeting}); a name submitted
   * without one is normalized onto {@value #DEFAULT_PREFIX} and the normalization announced back to
   * the submitter. Built-in kinds never contain a dot, so a future platform release can never
   * shadow a custom kind -- the collision class is structurally impossible.
   */
  public static String normalizeKindName(String declared, List<String> warnings) {
    String name = declared;
    if (!declared.contains(".")) {
      name = DEFAULT_PREFIX + declared;
      warnings.add("kind name '" + declared + "' has no prefix -- stored as '" + name + "'");
    }
    validateKindNameFormat(name);
    return name;
  }

  private static void validateKindNameFormat(String name) {
    String[] segments = name.split("\\.", -1);
    for (int i = 0; i < segments.length - 1; i++) {
      if (!PREFIX_SEGMENT.matcher(segments[i]).matches()) {
        throw new GimleManifestException(
            "invalid kind name '"
                + name
                + "' -- each prefix segment must be lowercase alphanumeric starting with a letter");
      }
    }
    if (!KIND_SEGMENT.matcher(segments[segments.length - 1]).matches()) {
      throw new GimleManifestException(
          "invalid kind name '"
              + name
              + "' -- the part after the last dot must be UpperCamelCase alphanumeric");
    }
  }

  private static KindScope parseScope(String scope) {
    return switch (scope) {
      case "Tenant" -> KindScope.TENANT;
      case "Cluster" -> KindScope.CLUSTER;
      default ->
          throw new GimleManifestException(
              "invalid scope '" + scope + "' -- must be Tenant or Cluster");
    };
  }

  private static KindNames parseNames(Object raw) {
    if (raw == null) {
      return KindNames.none();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new GimleManifestException("'names' must be a mapping of plural/shortNames");
    }
    rejectUnknownKeys(map, Set.of("plural", "shortNames"), "names");
    Optional<String> plural = optionalString(map, "plural");
    plural.ifPresent(p -> requireDeclaredNameFormat(p, "names.plural"));
    List<String> shortNames = new ArrayList<>();
    Object rawShortNames = map.get("shortNames");
    if (rawShortNames != null) {
      if (!(rawShortNames instanceof List<?> list)) {
        throw new GimleManifestException("'names.shortNames' must be a list of strings");
      }
      for (Object entry : list) {
        if (!(entry instanceof String s) || s.isBlank()) {
          throw new GimleManifestException(
              "'names.shortNames' must be a list of non-blank strings");
        }
        requireDeclaredNameFormat(s, "names.shortNames");
        shortNames.add(s);
      }
    }
    return new KindNames(plural, shortNames);
  }

  private static void requireDeclaredNameFormat(String value, String field) {
    if (!DECLARED_NAME.matcher(value).matches()) {
      throw new GimleManifestException(
          "'" + field + "' entry '" + value + "' must be lowercase alphanumeric (dashes allowed)");
    }
  }

  private static SchemaModel parseSchema(Object raw) {
    if (raw == null) {
      return new SchemaModel(List.of());
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new GimleManifestException("'schema' must be a mapping with a 'fields' list");
    }
    rejectUnknownKeys(map, Set.of("fields"), "schema");
    return new SchemaModel(parseFieldList(map.get("fields"), "schema.fields"));
  }

  private static List<SchemaField> parseFieldList(Object raw, String path) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new GimleManifestException("'" + path + "' must be a list of field mappings");
    }
    List<SchemaField> fields = new ArrayList<>();
    for (Object entry : list) {
      if (!(entry instanceof Map<?, ?> fieldMap)) {
        throw new GimleManifestException("'" + path + "' entries must each be a mapping");
      }
      fields.add(parseField(fieldMap, path));
    }
    return fields;
  }

  private static SchemaField parseField(Map<?, ?> map, String path) {
    String name = requireString(map, "name");
    String fieldPath = path + "." + name;
    String type = requireString(map, "type");
    return switch (type) {
      case "string" -> {
        rejectUnknownKeys(
            map, Set.of("name", "type", "required", "default", "maxLength"), fieldPath);
        yield new SchemaField.StringField(
            name,
            optionalBoolean(map, "required", fieldPath),
            optionalString(map, "default"),
            optionalInt(map, "maxLength", fieldPath));
      }
      case "int" -> {
        rejectUnknownKeys(
            map, Set.of("name", "type", "required", "default", "min", "max"), fieldPath);
        yield new SchemaField.IntField(
            name,
            optionalBoolean(map, "required", fieldPath),
            optionalLong(map, "default", fieldPath),
            optionalLong(map, "min", fieldPath),
            optionalLong(map, "max", fieldPath));
      }
      case "double" -> {
        rejectUnknownKeys(
            map, Set.of("name", "type", "required", "default", "min", "max"), fieldPath);
        yield new SchemaField.DoubleField(
            name,
            optionalBoolean(map, "required", fieldPath),
            optionalDouble(map, "default", fieldPath),
            optionalDouble(map, "min", fieldPath),
            optionalDouble(map, "max", fieldPath));
      }
      case "bool" -> {
        rejectUnknownKeys(map, Set.of("name", "type", "required", "default"), fieldPath);
        Object rawDefault = map.get("default");
        if (rawDefault != null && !(rawDefault instanceof Boolean)) {
          throw new GimleManifestException("'" + fieldPath + ".default' must be true or false");
        }
        yield new SchemaField.BoolField(
            name,
            optionalBoolean(map, "required", fieldPath),
            Optional.ofNullable((Boolean) rawDefault));
      }
      case "enum" -> {
        rejectUnknownKeys(map, Set.of("name", "type", "required", "default", "values"), fieldPath);
        Object rawValues = map.get("values");
        if (!(rawValues instanceof List<?> list)) {
          throw new GimleManifestException(
              "'" + fieldPath + ".values' is required and must be a list of strings");
        }
        List<String> values = new ArrayList<>();
        for (Object value : list) {
          if (!(value instanceof String s)) {
            throw new GimleManifestException(
                "'" + fieldPath + ".values' must be a list of strings");
          }
          values.add(s);
        }
        yield new SchemaField.EnumField(
            name,
            optionalBoolean(map, "required", fieldPath),
            optionalString(map, "default"),
            values);
      }
      case "list" -> {
        rejectUnknownKeys(map, Set.of("name", "type", "items", "minItems", "maxItems"), fieldPath);
        Object rawItems = map.get("items");
        if (!(rawItems instanceof Map<?, ?> itemsMap)) {
          throw new GimleManifestException(
              "'"
                  + fieldPath
                  + ".items' is required and must be a mapping declaring the element"
                  + " type");
        }
        // The item schema is a nameless type declaration; parsed under the conventional name
        // "items" so it reuses this same per-type parsing, error paths staying readable.
        Map<Object, Object> named = new java.util.LinkedHashMap<>(itemsMap);
        named.putIfAbsent("name", "items");
        yield new SchemaField.ListField(
            name,
            parseField(named, fieldPath),
            optionalInt(map, "minItems", fieldPath),
            optionalInt(map, "maxItems", fieldPath));
      }
      case "object" -> {
        rejectUnknownKeys(map, Set.of("name", "type", "fields"), fieldPath);
        yield new SchemaField.ObjectField(name, parseFieldList(map.get("fields"), fieldPath));
      }
      default ->
          throw new GimleManifestException(
              "'"
                  + fieldPath
                  + "' has unknown type '"
                  + type
                  + "' -- must be one of string, int, double, bool, enum, list, object");
    };
  }

  private static List<PrintColumn> parsePrintColumns(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new GimleManifestException("'printColumns' must be a list of {name, path} mappings");
    }
    List<PrintColumn> columns = new ArrayList<>();
    for (Object entry : list) {
      if (!(entry instanceof Map<?, ?> map)) {
        throw new GimleManifestException("'printColumns' entries must each be a mapping");
      }
      rejectUnknownKeys(map, Set.of("name", "path"), "printColumns");
      columns.add(new PrintColumn(requireString(map, "name"), requireString(map, "path")));
    }
    return columns;
  }

  // ---- field helpers ----

  private static void rejectUnknownKeys(Map<?, ?> map, Set<String> known, String where) {
    for (Object key : map.keySet()) {
      if (!(key instanceof String s) || !known.contains(s)) {
        throw new GimleManifestException(
            "unknown field '" + key + "' in " + where + " -- known fields are " + known);
      }
    }
  }

  private static String requireString(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException("missing or blank required field: " + key);
    }
    return s;
  }

  private static Optional<String> optionalString(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof String s)) {
      throw new GimleManifestException("'" + key + "' must be a string when present");
    }
    return Optional.of(s);
  }

  private static boolean optionalBoolean(Map<?, ?> map, String key, String path) {
    Object value = map.get(key);
    if (value == null) {
      return false;
    }
    if (!(value instanceof Boolean b)) {
      throw new GimleManifestException("'" + path + "." + key + "' must be true or false");
    }
    return b;
  }

  private static OptionalInt optionalInt(Map<?, ?> map, String key, String path) {
    Object value = map.get(key);
    if (value == null) {
      return OptionalInt.empty();
    }
    if (!(value instanceof Integer i)) {
      throw new GimleManifestException("'" + path + "." + key + "' must be an integer");
    }
    return OptionalInt.of(i);
  }

  private static OptionalLong optionalLong(Map<?, ?> map, String key, String path) {
    Object value = map.get(key);
    if (value instanceof Integer i) {
      return OptionalLong.of(i.longValue());
    }
    if (value instanceof Long l) {
      return OptionalLong.of(l);
    }
    if (value == null) {
      return OptionalLong.empty();
    }
    throw new GimleManifestException("'" + path + "." + key + "' must be an integer");
  }

  private static OptionalDouble optionalDouble(Map<?, ?> map, String key, String path) {
    Object value = map.get(key);
    if (value instanceof Integer i) {
      return OptionalDouble.of(i.doubleValue());
    }
    if (value instanceof Long l) {
      return OptionalDouble.of(l.doubleValue());
    }
    if (value instanceof Double d) {
      return OptionalDouble.of(d);
    }
    if (value == null) {
      return OptionalDouble.empty();
    }
    throw new GimleManifestException("'" + path + "." + key + "' must be a number");
  }
}
