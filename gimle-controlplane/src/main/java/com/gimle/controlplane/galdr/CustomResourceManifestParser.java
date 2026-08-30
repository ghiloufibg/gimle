package com.gimle.controlplane.galdr;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.manifest.ApiVersion;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses one custom-resource instance manifest generically -- no per-kind parser exists or ever
 * will; the kind's own stored schema does the real validation afterwards, via {@code
 * SchemaValidator}. The root level is reserved ({@code kind}, {@code apiVersion}, {@code name},
 * {@code tenantId}, {@code spec}), so no user schema can ever collide with a future reserved field;
 * all user data lives under {@code spec:}, unlike the flat workload manifests.
 */
public final class CustomResourceManifestParser {

  private static final Set<ApiVersion> SUPPORTED_VERSIONS = Set.of(ApiVersion.V1ALPHA1);

  private static final Set<String> ROOT_KEYS =
      Set.of("kind", "apiVersion", "name", "tenantId", "spec");

  private CustomResourceManifestParser() {}

  public record ParsedCustomResource(
      String kindName, String name, Optional<String> tenantId, Map<String, Object> spec) {}

  public static ParsedCustomResource parse(InputStream yamlContent) {
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
    for (Object key : root.keySet()) {
      if (!(key instanceof String s) || !ROOT_KEYS.contains(s)) {
        throw new GimleManifestException(
            "unknown root field '"
                + key
                + "' -- the root level is reserved; user data belongs under spec:");
      }
    }
    String kindName = requireString(root, "kind");
    ApiVersion.of(root, kindName, SUPPORTED_VERSIONS);
    String name = requireString(root, "name");
    Optional<String> tenantId = optionalString(root, "tenantId");
    Map<String, Object> spec = parseSpec(root.get("spec"));
    return new ParsedCustomResource(kindName, name, tenantId, spec);
  }

  private static Map<String, Object> parseSpec(Object raw) {
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new GimleManifestException("'spec' must be a mapping when present");
    }
    Map<String, Object> spec = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      spec.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return spec;
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
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException("'" + key + "' must be a non-blank string when present");
    }
    return Optional.of(s);
  }
}
