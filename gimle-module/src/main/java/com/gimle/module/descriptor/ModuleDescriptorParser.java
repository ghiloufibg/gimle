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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    return parse_root(root);
  }

  private static ModuleDescriptor parse_root(Map<?, ?> root) {
    String name = require_string(root, "name");
    if (!BINARY_NAME.matcher(name).matches()) {
      throw new GimleManifestException("'name' is not a valid module name: " + name);
    }
    Version version = parse_field(root, "version", Version::parse);
    List<Requirement> requires = parse_requires(root);
    List<ServiceExport> exports = parse_exports(root);
    IsolationTier tier = parse_isolation(root);
    Map<?, ?> resources = require_map(root, "resources");
    ResourceSpec request =
        parse_resource_spec(require_map(resources, "request"), "resources.request");
    ResourceSpec limit = parse_resource_spec(require_map(resources, "limit"), "resources.limit");
    HealthProbes probes = parse_health(root);
    Optional<String> hooks = parse_lifecycle_hooks(root);

    try {
      return new ModuleDescriptor(
          name, version, requires, exports, tier, request, limit, probes, hooks);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException(
          "invalid gimle-module.yaml for " + name + ": " + e.getMessage(), e);
    }
  }

  private static List<Requirement> parse_requires(Map<?, ?> root) {
    List<Requirement> result = new ArrayList<>();
    for (Object entry : optional_list(root, "requires")) {
      if (!(entry instanceof Map<?, ?> entryMap)) {
        throw new GimleManifestException("each 'requires' entry must be a mapping");
      }
      String moduleName = require_string(entryMap, "module");
      VersionRange range = parse_field(entryMap, "version", VersionRange::parse);
      try {
        result.add(new Requirement(moduleName, range));
      } catch (IllegalArgumentException e) {
        throw new GimleManifestException("invalid requires entry: " + e.getMessage(), e);
      }
    }
    return result;
  }

  private static List<ServiceExport> parse_exports(Map<?, ?> root) {
    List<ServiceExport> result = new ArrayList<>();
    for (Object entry : optional_list(root, "exports")) {
      if (!(entry instanceof Map<?, ?> entryMap)) {
        throw new GimleManifestException("each 'exports' entry must be a mapping");
      }
      String interfaceName = require_string(entryMap, "service");
      Version version = parse_field(entryMap, "version", Version::parse);
      try {
        result.add(new ServiceExport(interfaceName, version));
      } catch (IllegalArgumentException e) {
        throw new GimleManifestException("invalid exports entry: " + e.getMessage(), e);
      }
    }
    return result;
  }

  private static IsolationTier parse_isolation(Map<?, ?> root) {
    Map<?, ?> isolation = require_map(root, "isolation");
    String tierText = require_string(isolation, "tier");
    try {
      return IsolationTier.valueOf(tierText);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException(
          "invalid isolation.tier: '" + tierText + "' (expected one of TIER_1, TIER_2, TIER_3)", e);
    }
  }

  private static ResourceSpec parse_resource_spec(Map<?, ?> section, String path) {
    String memory = require_string(section, "memory");
    String cpu = require_string(section, "cpu");
    try {
      return new ResourceSpec(memory, cpu);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid " + path + ": " + e.getMessage(), e);
    }
  }

  private static HealthProbes parse_health(Map<?, ?> root) {
    Object healthObj = root.get("health");
    if (healthObj == null) {
      return HealthProbes.NONE;
    }
    if (!(healthObj instanceof Map<?, ?> health)) {
      throw new GimleManifestException("'health' must be a mapping");
    }
    Optional<String> liveness = class_name(health, "liveness");
    Optional<String> readiness = class_name(health, "readiness");
    try {
      return new HealthProbes(liveness, readiness);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid health probes: " + e.getMessage(), e);
    }
  }

  private static Optional<String> parse_lifecycle_hooks(Map<?, ?> root) {
    Object lifecycleObj = root.get("lifecycle");
    if (lifecycleObj == null) {
      return Optional.empty();
    }
    if (!(lifecycleObj instanceof Map<?, ?> lifecycle)) {
      throw new GimleManifestException("'lifecycle' must be a mapping");
    }
    return class_name(lifecycle, "hooks");
  }

  private static String require_string(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException("missing or blank required field: " + key);
    }
    return s;
  }

  private static Optional<String> optional_string(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException("field must be a non-blank string if present: " + key);
    }
    return Optional.of(s);
  }

  private static Optional<String> class_name(Map<?, ?> map, String key) {
    Optional<String> value = optional_string(map, key);
    value.ifPresent(
        className -> {
          if (!BINARY_NAME.matcher(className).matches()) {
            throw new GimleManifestException(
                "field '" + key + "' is not a valid Java class name: " + className);
          }
        });
    return value;
  }

  private static Map<?, ?> require_map(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof Map<?, ?> m)) {
      throw new GimleManifestException("missing or malformed required section: " + key);
    }
    return m;
  }

  private static List<?> optional_list(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> l)) {
      throw new GimleManifestException("field must be a list: " + key);
    }
    return l;
  }

  private static <T> T parse_field(Map<?, ?> map, String key, Function<String, T> parser) {
    String raw = require_string(map, key);
    try {
      return parser.apply(raw);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("malformed field '" + key + "': " + e.getMessage(), e);
    }
  }
}
