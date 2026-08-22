package com.gimle.mimir.manifest;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.vessel.VesselEnvValue;
import com.gimle.core.vessel.VesselFileMount;
import com.gimle.core.vessel.VesselProbeSpec;
import com.gimle.core.vessel.VesselProbes;
import com.gimle.core.vessel.VesselSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Shared {@code Map<?,?>}-walking field helpers used identically across the five manifest parsers
 * (Deployment/DaemonSet/Job/CronJob/StatefulSet), extracted once these turned out to be
 * byte-for-byte duplicated rather than the "small, mostly-dissimilar" helpers each parser's own
 * javadoc originally described. A parser that needs genuinely different behavior (a different
 * error-message prefix, a different field set) keeps its own private copy instead of being forced
 * through here.
 */
final class ManifestFields {

  private ManifestFields() {}

  static String requireString(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException("missing or blank required field: " + key);
    }
    return s;
  }

  /**
   * The one shared reading of {@code artifactPath} across every workload kind: absent means
   * "resolve the module coordinate from the artifact registry" ({@link
   * ArtifactReference#REGISTRY_COORDINATE}), present must be a non-blank local path -- an
   * explicitly blank value is rejected rather than silently treated as the registry state, so a
   * typo like {@code artifactPath: ""} fails loudly instead of changing resolution semantics.
   */
  static String optionalArtifactPath(Map<?, ?> map) {
    Object value = map.get("artifactPath");
    if (value == null) {
      return ArtifactReference.REGISTRY_COORDINATE;
    }
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException(
          "'artifactPath' must be a non-blank string when present -- omit it entirely to resolve"
              + " the module name/version from the artifact registry");
    }
    return s;
  }

  static Map<?, ?> requireMap(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof Map<?, ?> m)) {
      throw new GimleManifestException("missing or malformed required section: " + key);
    }
    return m;
  }

  static ModuleId parseModuleId(Map<?, ?> module) {
    String moduleName = requireString(module, "name");
    String versionText = requireString(module, "version");
    try {
      return new ModuleId(moduleName, Version.parse(versionText));
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid module reference: " + e.getMessage(), e);
    }
  }

  static boolean booleanField(Map<?, ?> map, String key, boolean defaultValue) {
    Object value = map.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Boolean b)) {
      throw new GimleManifestException("field must be a boolean if present: " + key);
    }
    return b;
  }

  static Optional<Set<String>> parseRequiredLabels(Map<?, ?> placement) {
    Object value = placement.get("requiredLabels");
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof List<?> list)) {
      throw new GimleManifestException("'placement.requiredLabels' must be a list");
    }
    List<String> labels = new ArrayList<>();
    for (Object entry : list) {
      if (!(entry instanceof String s) || s.isBlank()) {
        throw new GimleManifestException(
            "each 'placement.requiredLabels' entry must be a non-blank string");
      }
      labels.add(s);
    }
    return Optional.of(Set.copyOf(labels));
  }

  /**
   * The {@code placement: { antiAffinity, requiredLabels }} shape shared by Deployment, Job, and
   * StatefulSet. DaemonSet rejects {@code antiAffinity} outright instead of reading it, and CronJob
   * scopes its error messages to {@code jobTemplate.placement.*}, so both keep their own local
   * variant rather than delegating here.
   */
  static PlacementConstraints parsePlacement(Map<?, ?> root) {
    Object placementObj = root.get("placement");
    if (placementObj == null) {
      return PlacementConstraints.NONE;
    }
    if (!(placementObj instanceof Map<?, ?> placement)) {
      throw new GimleManifestException("'placement' must be a mapping");
    }
    boolean antiAffinity = booleanField(placement, "antiAffinity", false);
    Optional<Set<String>> requiredLabels = parseRequiredLabels(placement);
    try {
      return new PlacementConstraints(requiredLabels, antiAffinity);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid placement: " + e.getMessage(), e);
    }
  }

  /** Unlike {@link #optionalIntField}, absence of a required numeric field is itself an error. */
  static int requiredIntField(Map<?, ?> map, String key, String sectionPrefix) {
    Object value = map.get(key);
    if (!(value instanceof Number number)) {
      throw new GimleManifestException(
          "missing or non-numeric required field: " + sectionPrefix + key);
    }
    return number.intValue();
  }

  /** Unlike {@link #requiredIntField}, absence means "not evaluated" rather than an error. */
  static OptionalDouble optionalDoubleField(Map<?, ?> map, String key, String sectionPrefix) {
    Object value = map.get(key);
    if (value == null) {
      return OptionalDouble.empty();
    }
    if (!(value instanceof Number number)) {
      throw new GimleManifestException("non-numeric field if present: " + sectionPrefix + key);
    }
    return OptionalDouble.of(number.doubleValue());
  }

  /** Unlike {@link #requiredIntField}, absence means "not evaluated" rather than an error. */
  static OptionalInt optionalIntField(Map<?, ?> map, String key, String sectionPrefix) {
    Object value = map.get(key);
    if (value == null) {
      return OptionalInt.empty();
    }
    if (!(value instanceof Number number)) {
      throw new GimleManifestException("non-numeric field if present: " + sectionPrefix + key);
    }
    return OptionalInt.of(number.intValue());
  }

  /**
   * The common {@code disruption: { maxUnavailable, ... }} block shape shared by every workload
   * kind that accepts one: presence check, map validation, and the {@code maxUnavailable} default
   * of 1. Each caller supplies its own {@code maxSurge} policy via {@code budgetFactory} -- today
   * Deployment accepts a nonzero surge and DaemonSet rejects one outright, and neither belongs in
   * this shared shape.
   */
  static Optional<DisruptionBudget> parseDisruptionBudget(
      Map<?, ?> root, BiFunction<Map<?, ?>, Integer, DisruptionBudget> budgetFactory) {
    Object disruptionObj = root.get("disruption");
    if (disruptionObj == null) {
      return Optional.empty();
    }
    if (!(disruptionObj instanceof Map<?, ?> disruption)) {
      throw new GimleManifestException("'disruption' must be a mapping");
    }
    int maxUnavailable = optionalIntField(disruption, "maxUnavailable", "disruption.").orElse(1);
    try {
      return Optional.of(budgetFactory.apply(disruption, maxUnavailable));
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid disruption budget: " + e.getMessage(), e);
    }
  }

  /**
   * The {@code vessel: {...}} block shared identically by every workload kind's own manifest shape:
   * presence alone (not a separate flag) is what makes a spec vessel-hosted rather than
   * module-hosted. {@code container} is whichever map directly holds the {@code vessel} key -- the
   * manifest root for Deployment/Job/DaemonSet/StatefulSet, {@code jobTemplate} for CronJob.
   */
  static Optional<VesselSpec> parseVessel(Map<?, ?> container) {
    Object vesselObj = container.get("vessel");
    if (vesselObj == null) {
      return Optional.empty();
    }
    if (!(vesselObj instanceof Map<?, ?> vessel)) {
      throw new GimleManifestException("'vessel' must be a mapping");
    }
    List<String> args = stringList(vessel, "args");
    List<String> jvmFlags = stringList(vessel, "jvmFlags");
    Map<String, VesselEnvValue> env = parseVesselEnv(vessel);
    List<VesselFileMount> files = parseVesselFiles(vessel);
    VesselProbes probes = parseVesselProbes(vessel);
    Map<?, ?> resources = requireMap(vessel, "resources");
    ResourceSpec request = parseResourceSpec(requireMap(resources, "request"), "vessel.resources.");
    ResourceSpec limit = parseResourceSpec(requireMap(resources, "limit"), "vessel.resources.");
    try {
      return Optional.of(new VesselSpec(args, jvmFlags, env, files, probes, request, limit));
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid vessel block: " + e.getMessage(), e);
    }
  }

  private static List<String> stringList(Map<?, ?> vessel, String key) {
    return stringList(vessel, key, "vessel." + key);
  }

  /**
   * Package-visible variant of the {@code vessel.*} helper above, for a top-level field like {@code
   * configMapRefs:} that isn't nested under {@code vessel} -- {@code fieldPath} is the
   * fully-qualified name used in error messages, since the caller may not be reading from within
   * {@code vessel} at all.
   */
  static List<String> stringList(Map<?, ?> map, String key, String fieldPath) {
    Object value = map.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new GimleManifestException("'" + fieldPath + "' must be a list if present");
    }
    List<String> result = new ArrayList<>();
    for (Object entry : list) {
      if (!(entry instanceof String s)) {
        throw new GimleManifestException("every '" + fieldPath + "' entry must be a string");
      }
      result.add(s);
    }
    return result;
  }

  private static Map<String, VesselEnvValue> parseVesselEnv(Map<?, ?> vessel) {
    Object envObj = vessel.get("env");
    if (envObj == null) {
      return Map.of();
    }
    if (!(envObj instanceof Map<?, ?> env)) {
      throw new GimleManifestException("'vessel.env' must be a mapping");
    }
    Map<String, VesselEnvValue> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : env.entrySet()) {
      if (!(entry.getKey() instanceof String name) || name.isBlank()) {
        throw new GimleManifestException("every 'vessel.env' key must be a non-blank string");
      }
      result.put(name, parseVesselEnvValue(name, entry.getValue()));
    }
    return result;
  }

  private static VesselEnvValue parseVesselEnvValue(String name, Object value) {
    if (value instanceof String literal) {
      return new VesselEnvValue.Literal(literal);
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new GimleManifestException(
          "'vessel.env." + name + "' must be a string, {secret: ...}, or {port: ...}");
    }
    if (map.containsKey("secret")) {
      return new VesselEnvValue.SecretRef(requireString(map, "secret"));
    }
    if (map.containsKey("port")) {
      Object port = map.get("port");
      if ("dynamic".equals(port)) {
        return new VesselEnvValue.PortAllocation(OptionalInt.empty());
      }
      if (port instanceof Number number) {
        return new VesselEnvValue.PortAllocation(OptionalInt.of(number.intValue()));
      }
      throw new GimleManifestException(
          "'vessel.env." + name + ".port' must be 'dynamic' or an integer");
    }
    throw new GimleManifestException(
        "'vessel.env." + name + "' must declare 'secret' or 'port' if it is a mapping");
  }

  private static List<VesselFileMount> parseVesselFiles(Map<?, ?> vessel) {
    Object filesObj = vessel.get("files");
    if (filesObj == null) {
      return List.of();
    }
    if (!(filesObj instanceof List<?> list)) {
      throw new GimleManifestException("'vessel.files' must be a list");
    }
    List<VesselFileMount> result = new ArrayList<>();
    for (Object entry : list) {
      if (!(entry instanceof Map<?, ?> map)) {
        throw new GimleManifestException("every 'vessel.files' entry must be a mapping");
      }
      try {
        result.add(new VesselFileMount(requireString(map, "path"), requireString(map, "config")));
      } catch (IllegalArgumentException e) {
        throw new GimleManifestException("invalid 'vessel.files' entry: " + e.getMessage(), e);
      }
    }
    return result;
  }

  private static VesselProbes parseVesselProbes(Map<?, ?> vessel) {
    Object probesObj = vessel.get("probes");
    if (probesObj == null) {
      return VesselProbes.NONE;
    }
    if (!(probesObj instanceof Map<?, ?> probes)) {
      throw new GimleManifestException("'vessel.probes' must be a mapping");
    }
    Optional<VesselProbeSpec> liveness = parseVesselProbeSpec(probes.get("liveness"), "liveness");
    Optional<VesselProbeSpec> readiness =
        parseVesselProbeSpec(probes.get("readiness"), "readiness");
    return new VesselProbes(liveness, readiness);
  }

  private static Optional<VesselProbeSpec> parseVesselProbeSpec(Object rungObj, String which) {
    if (rungObj == null) {
      return Optional.empty();
    }
    if (!(rungObj instanceof Map<?, ?> rung)) {
      throw new GimleManifestException("'vessel.probes." + which + "' must be a mapping");
    }
    int initialDelaySeconds =
        optionalIntField(rung, "initialDelaySeconds", "vessel.probes." + which + ".").orElse(0);
    if (Boolean.TRUE.equals(rung.get("tcp"))) {
      try {
        return Optional.of(new VesselProbeSpec.Tcp(initialDelaySeconds));
      } catch (IllegalArgumentException e) {
        throw new GimleManifestException(
            "invalid vessel.probes." + which + ": " + e.getMessage(), e);
      }
    }
    if (rung.get("http") instanceof String path) {
      try {
        return Optional.of(new VesselProbeSpec.Http(path, initialDelaySeconds));
      } catch (IllegalArgumentException e) {
        throw new GimleManifestException(
            "invalid vessel.probes." + which + ": " + e.getMessage(), e);
      }
    }
    throw new GimleManifestException(
        "'vessel.probes." + which + "' must be either {tcp: true} or {http: <path>}");
  }

  private static ResourceSpec parseResourceSpec(Map<?, ?> map, String sectionPrefix) {
    String memory = requireString(map, "memory");
    String cpu = requireString(map, "cpu");
    try {
      return new ResourceSpec(memory, cpu);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException(
          "invalid " + sectionPrefix + "request/limit: " + e.getMessage(), e);
    }
  }
}
