package com.gimle.hilmir.release;

import com.gimle.core.exception.GimleManifestException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses a {@code kind: Bundle} document into a {@link Bundle}, the same restricted-YAML,
 * every-mapping-checked-for-unknown-keys shape {@code com.gimle.hilmir.topology.TopologyParser}
 * uses: {@code SafeConstructor} over plain maps/lists/scalars, a {@link GimleManifestException} for
 * every structural rejection, and an explicit allowlist enforced at every mapping level so an
 * operator's typo'd field is caught here rather than silently ignored. {@code ${values.*}}
 * substitution is deliberately not this class's job -- see {@link BundleRenderer} -- since values
 * only become known once {@code --values}/{@code --set} overrides are merged in, which happens
 * after a bundle is parsed, not while it's being parsed.
 */
public final class BundleParser {

  private static final Set<String> ROOT_KEYS =
      Set.of("kind", "name", "version", "values", "tenants", "config", "secrets", "workloads");
  private static final Set<String> TENANT_KEYS = Set.of("id", "quota");
  private static final Set<String> QUOTA_KEYS =
      Set.of("maxMemoryBytes", "maxCpuMillicores", "maxInstances");
  private static final Set<String> CONFIG_KEYS = Set.of("tenant", "key", "value");
  private static final Set<String> SECRET_KEYS = Set.of("tenant", "key", "value");
  private static final Set<String> WORKLOAD_KEYS = Set.of("file", "manifest");

  private BundleParser() {}

  public static Bundle parse(InputStream yamlContent) {
    Object raw;
    try {
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      raw = yaml.load(yamlContent);
    } catch (RuntimeException e) {
      throw new GimleManifestException("malformed YAML in bundle document", e);
    }
    if (!(raw instanceof Map<?, ?> root)) {
      throw new GimleManifestException("bundle document must contain a YAML mapping at the root");
    }
    return parseRoot(root);
  }

  private static Bundle parseRoot(Map<?, ?> root) {
    // 'kind' is checked before the unknown-key sweep below, deliberately: a document that is some
    // other manifest kind entirely (most commonly a raw `kind: Deployment` manifest -- the exact
    // shape `hilmir init` itself writes, meant for gimle-cli/the control-plane API directly, not
    // for `hilmir deploy`) should get the specific "wrong kind" message, not a confusing "unknown
    // field" complaint about whichever of that other kind's own fields happens to come first.
    String kind = requireString(root, "kind");
    if (!"Bundle".equals(kind)) {
      throw new GimleManifestException(
          "unknown bundle kind: "
              + kind
              + " (expected 'Bundle') -- hilmir deploy takes a kind: Bundle document with a"
              + " workloads: list, not a raw Deployment/DaemonSet/StatefulSet/etc. manifest"
              + " directly; wrap it as workloads: [{file: <this file>}] in a bundle, or submit it"
              + " to the control plane directly instead (gimle-cli, or the /deployments/* API)");
    }
    requireNoUnknownKeys(root, ROOT_KEYS, "bundle");
    String name = requireString(root, "name");
    String version = requireString(root, "version");
    Map<String, String> values = parseValues(root);
    List<BundleTenant> tenants = parseTenants(root);
    List<BundleConfigEntry> config = parseConfig(root);
    List<BundleSecretEntry> secrets = parseSecrets(root);
    List<BundleWorkload> workloads = parseWorkloads(root);
    return new Bundle(name, version, values, tenants, config, secrets, workloads);
  }

  private static Map<String, String> parseValues(Map<?, ?> root) {
    Object value = root.get("values");
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new GimleManifestException("'values' must be a mapping");
    }
    return asFlatStringMap(map, "values");
  }

  private static List<BundleTenant> parseTenants(Map<?, ?> root) {
    Object value = root.get("tenants");
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> entries)) {
      throw new GimleManifestException("'tenants' must be a list");
    }
    List<BundleTenant> tenants = new ArrayList<>();
    for (Object entry : entries) {
      if (!(entry instanceof Map<?, ?> map)) {
        throw new GimleManifestException("each 'tenants' entry must be a mapping");
      }
      requireNoUnknownKeys(map, TENANT_KEYS, "tenants[]");
      tenants.add(new BundleTenant(requireString(map, "id"), parseQuota(map)));
    }
    return tenants;
  }

  private static BundleQuota parseQuota(Map<?, ?> tenant) {
    Object value = tenant.get("quota");
    if (!(value instanceof Map<?, ?> map)) {
      throw new GimleManifestException("tenant is missing a 'quota' mapping");
    }
    requireNoUnknownKeys(map, QUOTA_KEYS, "tenants[].quota");
    return new BundleQuota(
        requireLong(map, "maxMemoryBytes"),
        requireLong(map, "maxCpuMillicores"),
        (int) requireLong(map, "maxInstances"));
  }

  private static List<BundleConfigEntry> parseConfig(Map<?, ?> root) {
    Object value = root.get("config");
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> entries)) {
      throw new GimleManifestException("'config' must be a list");
    }
    List<BundleConfigEntry> config = new ArrayList<>();
    for (Object entry : entries) {
      if (!(entry instanceof Map<?, ?> map)) {
        throw new GimleManifestException("each 'config' entry must be a mapping");
      }
      requireNoUnknownKeys(map, CONFIG_KEYS, "config[]");
      config.add(
          new BundleConfigEntry(
              requireString(map, "tenant"), requireString(map, "key"), requireValue(map)));
    }
    return config;
  }

  private static List<BundleSecretEntry> parseSecrets(Map<?, ?> root) {
    Object value = root.get("secrets");
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> entries)) {
      throw new GimleManifestException("'secrets' must be a list");
    }
    List<BundleSecretEntry> secrets = new ArrayList<>();
    for (Object entry : entries) {
      if (!(entry instanceof Map<?, ?> map)) {
        throw new GimleManifestException("each 'secrets' entry must be a mapping");
      }
      requireNoUnknownKeys(map, SECRET_KEYS, "secrets[]");
      secrets.add(
          new BundleSecretEntry(
              requireString(map, "tenant"), requireString(map, "key"), requireValue(map)));
    }
    return secrets;
  }

  private static List<BundleWorkload> parseWorkloads(Map<?, ?> root) {
    Object value = root.get("workloads");
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> entries)) {
      throw new GimleManifestException("'workloads' must be a list");
    }
    List<BundleWorkload> workloads = new ArrayList<>();
    for (Object entry : entries) {
      if (!(entry instanceof Map<?, ?> map)) {
        throw new GimleManifestException("each 'workloads' entry must be a mapping");
      }
      requireNoUnknownKeys(map, WORKLOAD_KEYS, "workloads[]");
      Optional<String> file = optionalString(map, "file");
      Optional<String> manifest = optionalString(map, "manifest");
      if (file.isPresent() == manifest.isPresent()) {
        throw new GimleManifestException(
            "each 'workloads' entry must set exactly one of 'file' or 'manifest'");
      }
      workloads.add(new BundleWorkload(file, manifest));
    }
    return workloads;
  }

  private static Map<String, String> asFlatStringMap(Map<?, ?> map, String context) {
    Map<String, String> flat = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new GimleManifestException("'" + context + "' keys must be strings");
      }
      Object scalar = entry.getValue();
      if (scalar instanceof Map || scalar instanceof List) {
        throw new GimleManifestException(
            "'" + context + "." + key + "' must be a scalar, not nested");
      }
      flat.put(key, scalar == null ? "" : String.valueOf(scalar));
    }
    return flat;
  }

  private static String requireValue(Map<?, ?> map) {
    Object value = map.get("value");
    if (!(value instanceof String s)) {
      throw new GimleManifestException("missing or non-string required field: value");
    }
    return s;
  }

  private static void requireNoUnknownKeys(Map<?, ?> map, Set<String> allowed, String context) {
    for (Object key : map.keySet()) {
      if (!(key instanceof String s) || !allowed.contains(s)) {
        throw new GimleManifestException("unknown field in '" + context + "': " + key);
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
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException("field must be a non-blank string if present: " + key);
    }
    return Optional.of(s);
  }

  private static long requireLong(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof Number number)) {
      throw new GimleManifestException("missing or non-numeric required field: " + key);
    }
    return number.longValue();
  }
}
