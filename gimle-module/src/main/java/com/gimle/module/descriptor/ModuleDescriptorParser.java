package com.gimle.module.descriptor;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.Requirement;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.core.module.VersionRange;
import com.gimle.core.module.VolumeRequest;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Parses and validates {@code META-INF/gimle/gimle-module.yaml} content. */
public final class ModuleDescriptorParser {

  // Java identifier segments joined by dots — module names, and the class names referenced by
  // health/lifecycle fields, both follow this shape.
  private static final Pattern BINARY_NAME =
      Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$");

  private ModuleDescriptorParser() {}

  public static ModuleDescriptor parse(InputStream yamlContent) {
    Object raw;
    try {
      // SafeConstructor restricts loading to plain maps/lists/scalars — a module artifact's
      // descriptor is untrusted input, and the default YAML constructor allows arbitrary type
      // instantiation via tags, which is a deserialization-gadget risk we don't need here.
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      raw = yaml.load(yamlContent);
    } catch (RuntimeException e) {
      throw new GimleManifestException("malformed YAML in gimle-module.yaml", e);
    }
    if (!(raw instanceof Map<?, ?> root)) {
      throw new GimleManifestException("gimle-module.yaml must contain a YAML mapping at the root");
    }
    return parseRoot(root);
  }

  private static ModuleDescriptor parseRoot(Map<?, ?> root) {
    String name = requireString(root, "name");
    if (!BINARY_NAME.matcher(name).matches()) {
      throw new GimleManifestException("'name' is not a valid module name: " + name);
    }
    Version version = parseField(root, "version", Version::parse);
    List<Requirement> requires = parseRequires(root);
    List<ServiceExport> exports = parseExports(root);
    IsolationTier tier = parseIsolation(root);
    Map<?, ?> resources = requireMap(root, "resources");
    ResourceSpec request = parseResourceSpec(requireMap(resources, "request"), "resources.request");
    ResourceSpec limit = parseResourceSpec(requireMap(resources, "limit"), "resources.limit");
    HealthProbes probes = parseHealth(root);
    Optional<String> hooks = parseLifecycleHooks(root);
    Optional<String> jobHooks = parseJobHooks(root);
    Optional<VolumeRequest> volume = parseVolume(root);

    try {
      return new ModuleDescriptor(
          name, version, requires, exports, tier, request, limit, probes, hooks, jobHooks, volume);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException(
          "invalid gimle-module.yaml for " + name + ": " + e.getMessage(), e);
    }
  }

  private static List<Requirement> parseRequires(Map<?, ?> root) {
    List<Requirement> result = new ArrayList<>();
    for (Object entry : optionalList(root, "requires")) {
      if (!(entry instanceof Map<?, ?> entryMap)) {
        throw new GimleManifestException("each 'requires' entry must be a mapping");
      }
      String moduleName = requireString(entryMap, "module");
      VersionRange range = parseField(entryMap, "version", VersionRange::parse);
      try {
        result.add(new Requirement(moduleName, range));
      } catch (IllegalArgumentException e) {
        throw new GimleManifestException("invalid requires entry: " + e.getMessage(), e);
      }
    }
    return result;
  }

  private static List<ServiceExport> parseExports(Map<?, ?> root) {
    List<ServiceExport> result = new ArrayList<>();
    for (Object entry : optionalList(root, "exports")) {
      if (!(entry instanceof Map<?, ?> entryMap)) {
        throw new GimleManifestException("each 'exports' entry must be a mapping");
      }
      String interfaceName = requireString(entryMap, "service");
      Version version = parseField(entryMap, "version", Version::parse);
      Optional<Set<String>> allowedTenants = parseAllowedTenants(entryMap);
      try {
        result.add(
            allowedTenants.isEmpty()
                ? new ServiceExport(interfaceName, version)
                : new ServiceExport(interfaceName, version, allowedTenants));
      } catch (IllegalArgumentException e) {
        throw new GimleManifestException("invalid exports entry: " + e.getMessage(), e);
      }
    }
    return result;
  }

  /**
   * {@code allowedTenants} is optional per export: absent means "any tenant may consume this,"
   * today's implicit behavior for every manifest written before this field existed.
   */
  private static Optional<Set<String>> parseAllowedTenants(Map<?, ?> exportEntry) {
    Object value = exportEntry.get("allowedTenants");
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof List<?> list)) {
      throw new GimleManifestException("'exports[].allowedTenants' must be a list");
    }
    Set<String> tenants = new LinkedHashSet<>();
    for (Object item : list) {
      if (!(item instanceof String s) || s.isBlank()) {
        throw new GimleManifestException(
            "each 'exports[].allowedTenants' entry must be a non-blank string");
      }
      tenants.add(s);
    }
    return Optional.of(tenants);
  }

  private static IsolationTier parseIsolation(Map<?, ?> root) {
    Map<?, ?> isolation = requireMap(root, "isolation");
    String tierText = requireString(isolation, "tier");
    try {
      return IsolationTier.valueOf(tierText);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException(
          "invalid isolation.tier: '" + tierText + "' (expected one of TIER_1, TIER_2, TIER_3)", e);
    }
  }

  private static ResourceSpec parseResourceSpec(Map<?, ?> section, String path) {
    String memory = requireString(section, "memory");
    String cpu = requireString(section, "cpu");
    try {
      return new ResourceSpec(memory, cpu);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid " + path + ": " + e.getMessage(), e);
    }
  }

  private static HealthProbes parseHealth(Map<?, ?> root) {
    Object healthObj = root.get("health");
    if (healthObj == null) {
      return HealthProbes.NONE;
    }
    if (!(healthObj instanceof Map<?, ?> health)) {
      throw new GimleManifestException("'health' must be a mapping");
    }
    Optional<String> liveness = className(health, "liveness");
    Optional<String> readiness = className(health, "readiness");
    Optional<Duration> initialDelay = parseInitialDelay(health);
    try {
      return new HealthProbes(liveness, readiness, initialDelay);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid health probes: " + e.getMessage(), e);
    }
  }

  /**
   * {@code initialDelaySeconds}: how long after ACTIVE before the first probe tick, so a module's
   * own post-start warmup (lazy init, cache fill, JIT) doesn't get torn down by an eager first
   * tick.
   */
  private static Optional<Duration> parseInitialDelay(Map<?, ?> health) {
    Object value = health.get("initialDelaySeconds");
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Number number) || number.longValue() < 0) {
      throw new GimleManifestException(
          "'health.initialDelaySeconds' must be a non-negative number");
    }
    return Optional.of(Duration.ofSeconds(number.longValue()));
  }

  private static Optional<String> parseLifecycleHooks(Map<?, ?> root) {
    return className(requireLifecycleMap(root), "hooks");
  }

  /**
   * Sibling field to {@code lifecycle.hooks}: a Job-kind module declares {@code lifecycle.jobHooks}
   * instead, naming a class implementing {@code JobHooks} rather than {@code ModuleLifecycleHooks}.
   * Both read from the same {@code lifecycle:} mapping, so this shares {@link #requireLifecycleMap}
   * with {@link #parseLifecycleHooks} rather than re-validating the block twice.
   */
  private static Optional<String> parseJobHooks(Map<?, ?> root) {
    return className(requireLifecycleMap(root), "jobHooks");
  }

  /**
   * StatefulSet-kind persistent storage: {@code volume:} at the descriptor's own top level, sibling
   * to {@code isolation:}/{@code resources:} -- absent means "no persistent storage," the only
   * shape every pre-existing descriptor has.
   */
  private static Optional<VolumeRequest> parseVolume(Map<?, ?> root) {
    Object volumeObj = root.get("volume");
    if (volumeObj == null) {
      return Optional.empty();
    }
    if (!(volumeObj instanceof Map<?, ?> volume)) {
      throw new GimleManifestException("'volume' must be a mapping");
    }
    Object sizeBytesObj = volume.get("sizeBytes");
    if (!(sizeBytesObj instanceof Number sizeBytesNumber) || sizeBytesNumber.longValue() <= 0) {
      throw new GimleManifestException("'volume.sizeBytes' must be a positive number");
    }
    String mountPath = requireString(volume, "mountPath");
    try {
      return Optional.of(new VolumeRequest(sizeBytesNumber.longValue(), mountPath));
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid volume: " + e.getMessage(), e);
    }
  }

  private static Map<?, ?> requireLifecycleMap(Map<?, ?> root) {
    Object lifecycleObj = root.get("lifecycle");
    if (lifecycleObj == null) {
      return Map.of();
    }
    if (!(lifecycleObj instanceof Map<?, ?> lifecycle)) {
      throw new GimleManifestException("'lifecycle' must be a mapping");
    }
    return lifecycle;
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

  private static Optional<String> className(Map<?, ?> map, String key) {
    Optional<String> value = optionalString(map, key);
    value.ifPresent(
        className -> {
          if (!BINARY_NAME.matcher(className).matches()) {
            throw new GimleManifestException(
                "field '" + key + "' is not a valid Java class name: " + className);
          }
        });
    return value;
  }

  private static Map<?, ?> requireMap(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof Map<?, ?> m)) {
      throw new GimleManifestException("missing or malformed required section: " + key);
    }
    return m;
  }

  private static List<?> optionalList(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> l)) {
      throw new GimleManifestException("field must be a list: " + key);
    }
    return l;
  }

  private static <T> T parseField(Map<?, ?> map, String key, Function<String, T> parser) {
    String raw = requireString(map, key);
    try {
      return parser.apply(raw);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("malformed field '" + key + "': " + e.getMessage(), e);
    }
  }
}
