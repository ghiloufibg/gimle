package com.gimle.module.descriptor;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ReclaimPolicy;
import com.gimle.core.module.Requirement;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.core.module.VersionRange;
import com.gimle.core.module.VolumeRequest;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
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

  // A module's declared name is the name its own ModuleLayer is resolved by, so anything the JPMS
  // module system cannot name is rejected here, where the reason can still be stated, rather than
  // surfacing later as an unresolvable layer at instance start.
  private static final String NAME_RULE =
      "a module name must be dot-separated Java identifiers (letters, digits, _ and $, never"
          + " starting with a digit), because it is used verbatim as the JPMS module name this"
          + " module's own layer is resolved by";

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
      throw new GimleManifestException("rejected module name '" + name + "': " + NAME_RULE);
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
    Map<String, VolumeRequest> volumes = parseVolumes(root);

    try {
      return new ModuleDescriptor(
          name, version, requires, exports, tier, request, limit, probes, hooks, jobHooks, volumes);
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
    Optional<Duration> initialDelay = parseNonNegativeSeconds(health, "initialDelaySeconds");
    Optional<Duration> interval = parsePositiveSeconds(health, "intervalSeconds");
    Optional<Duration> timeout = parsePositiveSeconds(health, "timeoutSeconds");
    OptionalInt failureThreshold = parseFailureThreshold(health);
    try {
      return new HealthProbes(
          liveness, readiness, initialDelay, interval, timeout, failureThreshold);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid health probes: " + e.getMessage(), e);
    }
  }

  /**
   * {@code initialDelaySeconds}: how long after ACTIVE before the first probe tick, so a module's
   * own post-start warmup (lazy init, cache fill, JIT) doesn't get torn down by an eager first
   * tick. Zero is meaningful here -- "probe immediately" -- so unlike {@link #parsePositiveSeconds}
   * it is accepted.
   */
  private static Optional<Duration> parseNonNegativeSeconds(Map<?, ?> health, String key) {
    Object value = health.get(key);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Number number) || number.longValue() < 0) {
      throw new GimleManifestException("'health." + key + "' must be a non-negative number");
    }
    return Optional.of(Duration.ofSeconds(number.longValue()));
  }

  /**
   * {@code intervalSeconds}/{@code timeoutSeconds}: this module's own probe cadence and per-check
   * deadline, overriding the worker-wide defaults it would otherwise share with every other module
   * on the same worker. Zero is rejected rather than silently normalized -- a zero interval ticks
   * without pause and a zero timeout fails every check before it can run, so either one is a
   * manifest mistake, not an aggressive-but-valid setting.
   */
  private static Optional<Duration> parsePositiveSeconds(Map<?, ?> health, String key) {
    Object value = health.get(key);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Number number) || number.longValue() <= 0) {
      throw new GimleManifestException("'health." + key + "' must be a positive number");
    }
    return Optional.of(Duration.ofSeconds(number.longValue()));
  }

  /**
   * {@code failureThreshold}: how many consecutive liveness failures this module tolerates before
   * the worker restarts it. One is the lowest meaningful value ("restart on the first failure").
   */
  private static OptionalInt parseFailureThreshold(Map<?, ?> health) {
    Object value = health.get("failureThreshold");
    if (value == null) {
      return OptionalInt.empty();
    }
    if (!(value instanceof Number number) || number.intValue() < 1) {
      throw new GimleManifestException("'health.failureThreshold' must be a number of at least 1");
    }
    return OptionalInt.of(number.intValue());
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
   * StatefulSet-kind persistent storage: {@code volumes:} at the descriptor's own top level,
   * sibling to {@code isolation:}/{@code resources:} -- a mapping of volume name to that volume's
   * own {@code sizeBytes}/{@code reclaimPolicy}, each name the key a hook reaches its directory
   * back through via {@code ModuleContext.dataDirectory(name)}. Absent means "no persistent
   * storage." The singular {@code volume:} shape declares one volume named {@code data} -- the
   * common single-volume module's shorthand, not a second parallel schema (it parses into exactly
   * the same map).
   */
  private static Map<String, VolumeRequest> parseVolumes(Map<?, ?> root) {
    Object volumesObj = root.get("volumes");
    Object singularObj = root.get("volume");
    if (volumesObj != null && singularObj != null) {
      throw new GimleManifestException("declare either 'volume' or 'volumes', not both");
    }
    Map<String, VolumeRequest> volumes = new LinkedHashMap<>();
    if (singularObj != null) {
      volumes.put("data", parseVolumeEntry(singularObj, "volume"));
      return volumes;
    }
    if (volumesObj == null) {
      return volumes;
    }
    if (!(volumesObj instanceof Map<?, ?> volumesMap)) {
      throw new GimleManifestException("'volumes' must be a mapping of name to volume");
    }
    for (Map.Entry<?, ?> entry : volumesMap.entrySet()) {
      String volumeName = String.valueOf(entry.getKey());
      if (volumeName.isBlank()) {
        throw new GimleManifestException("'volumes' names must not be blank");
      }
      volumes.put(volumeName, parseVolumeEntry(entry.getValue(), "volumes." + volumeName));
    }
    return volumes;
  }

  private static VolumeRequest parseVolumeEntry(Object volumeObj, String field) {
    if (!(volumeObj instanceof Map<?, ?> volume)) {
      throw new GimleManifestException("'" + field + "' must be a mapping");
    }
    Object sizeBytesObj = volume.get("sizeBytes");
    if (!(sizeBytesObj instanceof Number sizeBytesNumber) || sizeBytesNumber.longValue() <= 0) {
      throw new GimleManifestException("'" + field + ".sizeBytes' must be a positive number");
    }
    try {
      return new VolumeRequest(sizeBytesNumber.longValue(), parseReclaimPolicy(volume));
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid " + field + ": " + e.getMessage(), e);
    }
  }

  /**
   * {@code volume.reclaimPolicy} is optional and case-insensitive ({@code Retain}/{@code Delete});
   * absent defaults to {@link ReclaimPolicy#RETAIN} -- the safe posture for data a permanent
   * removal would otherwise destroy.
   */
  private static ReclaimPolicy parseReclaimPolicy(Map<?, ?> volume) {
    Object policyObj = volume.get("reclaimPolicy");
    if (policyObj == null) {
      return ReclaimPolicy.RETAIN;
    }
    if (!(policyObj instanceof String policy) || policy.isBlank()) {
      throw new GimleManifestException("'volume.reclaimPolicy' must be 'Retain' or 'Delete'");
    }
    try {
      return ReclaimPolicy.valueOf(policy.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException(
          "'volume.reclaimPolicy' must be 'Retain' or 'Delete', got '" + policy + "'");
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
