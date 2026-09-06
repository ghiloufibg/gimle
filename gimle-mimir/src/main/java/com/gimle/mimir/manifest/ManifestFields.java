package com.gimle.mimir.manifest;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.manifest.ApiVersion;
import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ReclaimPolicy;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.tenant.Tenant;
import com.gimle.core.vessel.VesselEnvValue;
import com.gimle.core.vessel.VesselFileMount;
import com.gimle.core.vessel.VesselProbeSpec;
import com.gimle.core.vessel.VesselProbes;
import com.gimle.core.vessel.VesselSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
   * The one shared reading of {@code artifactPath} across every workload kind, and the one place
   * the field's per-{@code apiVersion} treatment lives. Under {@code v1} the key's very presence is
   * rejected -- even {@code artifactPath: ""} or a bare {@code artifactPath:} -- since that version
   * resolves every module coordinate from the artifact registry. Under {@code v1alpha1} the
   * historical semantics hold exactly: absent means "resolve the module coordinate from the
   * artifact registry" ({@link ArtifactReference#REGISTRY_COORDINATE}), present must be a non-blank
   * local path (an explicitly blank value is rejected rather than silently treated as the registry
   * state, so a typo like {@code artifactPath: ""} fails loudly instead of changing resolution
   * semantics) -- but a local path now also records a deprecation warning, since the path is
   * resolved against the reading process's own working directory, not the manifest file.
   */
  static String optionalArtifactPath(Map<?, ?> map, ApiVersion version, List<String> warnings) {
    if (version == ApiVersion.V1 && map.containsKey("artifactPath")) {
      throw new GimleManifestException(
          "'artifactPath' is not accepted in apiVersion v1 -- push the jar to the artifact"
              + " registry (gimle artifact push, or kind: ArtifactSet for a set) and let"
              + " module: {name, version} resolve it from there; only v1alpha1 manifests"
              + " (deprecated) may name a local path");
    }
    Object value = map.get("artifactPath");
    if (value == null) {
      return ArtifactReference.REGISTRY_COORDINATE;
    }
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException(
          "'artifactPath' must be a non-blank string when present -- omit it entirely to resolve"
              + " the module name/version from the artifact registry");
    }
    warnings.add(
        "'artifactPath' is deprecated and resolved against the reading process's own working"
            + " directory, not this manifest file -- omit it and push the artifact to the"
            + " registry instead (rejected outright in apiVersion v1)");
    return s;
  }

  /**
   * Warns -- never rejects -- about a top-level field present in the submitted YAML that plays no
   * role in {@code knownFields} for this manifest kind: the same "warn, don't break forward/
   * backward tolerance" posture {@link #optionalArtifactPath}'s own deprecation notice already
   * takes for a recognized-but-deprecated field, extended to a field this kind's schema never
   * recognized at all -- a typo, or one copied in from a different manifest kind (e.g. {@code
   * isolationTier} on a Deployment) -- which otherwise gets silently dropped with no signal
   * whatsoever. {@code kind}/{@code apiVersion} are always implicitly known: every manifest carries
   * them, and {@link ManifestParser} itself reads both before any kind-specific parser ever sees
   * the root map.
   */
  static void warnUnknownFields(Map<?, ?> root, Set<String> knownFields, List<String> warnings) {
    for (Object rawKey : root.keySet()) {
      if (!(rawKey instanceof String key)) {
        continue;
      }
      if (key.equals("kind") || key.equals("apiVersion") || knownFields.contains(key)) {
        continue;
      }
      warnings.add(
          "'" + key + "' is not a recognized field for this manifest kind and was ignored");
    }
  }

  /**
   * The {@code tenantId} field shared by all five workload kinds: absent resolves to {@link
   * Tenant#DEFAULT_TENANT_ID} rather than {@code Optional.empty()} -- the same "omit the namespace,
   * land in {@code default}" defaulting Kubernetes applies at admission, so "no tenant" is never a
   * valid-but-broken state with nothing addressable to configure it. A present-but-blank value is
   * still rejected outright rather than silently treated as absent.
   */
  static Optional<String> parseTenantId(Map<?, ?> root) {
    Object value = root.get("tenantId");
    if (value == null) {
      return Optional.of(Tenant.DEFAULT_TENANT_ID);
    }
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException("'tenantId' must be a non-blank string if present");
    }
    return Optional.of(s);
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
  /**
   * {@code placement.priority}, the PriorityClass analogue -- absent means 0. Negative is allowed
   * and meaningful (a workload marked more evictable than the default); a non-integer is a manifest
   * error rather than something to round, since silently reinterpreting a resource guarantee is
   * exactly the kind of surprise a scheduling knob must not spring.
   */
  private static int priorityField(Map<?, ?> placement) {
    Object raw = placement.get("priority");
    if (raw == null) {
      return 0;
    }
    if (raw instanceof Integer value) {
      return value;
    }
    if (raw instanceof Long value && value == value.intValue()) {
      return value.intValue();
    }
    throw new GimleManifestException("'placement.priority' must be an integer");
  }

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
    int priority = priorityField(placement);
    try {
      return new PlacementConstraints(requiredLabels, antiAffinity, priority);
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
   * The {@code autoscale: {...}} block shared identically by every workload kind that accepts one
   * -- unlike {@link #parseDisruptionBudget}, no per-kind knob differs, so this needs no
   * factory-lambda escape hatch.
   */
  static Optional<AutoscalePolicy> parseAutoscale(Map<?, ?> root) {
    Object autoscaleObj = root.get("autoscale");
    if (autoscaleObj == null) {
      return Optional.empty();
    }
    if (!(autoscaleObj instanceof Map<?, ?> autoscale)) {
      throw new GimleManifestException("'autoscale' must be a mapping");
    }
    int minReplicas = requiredIntField(autoscale, "minReplicas", "autoscale.");
    int maxReplicas = requiredIntField(autoscale, "maxReplicas", "autoscale.");
    int targetCpuUtilizationPercent =
        requiredIntField(autoscale, "targetCpuUtilizationPercent", "autoscale.");
    OptionalDouble targetRequestRatePerSecond =
        optionalDoubleField(autoscale, "targetRequestRatePerSecond", "autoscale.");
    OptionalDouble targetErrorRatePercent =
        optionalDoubleField(autoscale, "targetErrorRatePercent", "autoscale.");
    OptionalInt targetQueueDepth = optionalIntField(autoscale, "targetQueueDepth", "autoscale.");
    AutoscalePolicy.CombinationMode combinationMode = parseCombinationMode(autoscale);
    OptionalDouble cpuWeight = optionalDoubleField(autoscale, "cpuWeight", "autoscale.");
    OptionalDouble requestRateWeight =
        optionalDoubleField(autoscale, "requestRateWeight", "autoscale.");
    OptionalDouble errorRateWeight =
        optionalDoubleField(autoscale, "errorRateWeight", "autoscale.");
    OptionalDouble queueDepthWeight =
        optionalDoubleField(autoscale, "queueDepthWeight", "autoscale.");
    Duration scaleUpCooldown =
        parseCooldown(
            autoscale, "scaleUpCooldownSeconds", AutoscalePolicy.DEFAULT_SCALE_UP_COOLDOWN);
    Duration scaleDownCooldown =
        parseCooldown(
            autoscale, "scaleDownCooldownSeconds", AutoscalePolicy.DEFAULT_SCALE_DOWN_COOLDOWN);
    try {
      return Optional.of(
          new AutoscalePolicy(
              minReplicas,
              maxReplicas,
              targetCpuUtilizationPercent,
              targetRequestRatePerSecond,
              targetErrorRatePercent,
              targetQueueDepth,
              combinationMode,
              cpuWeight,
              requestRateWeight,
              errorRateWeight,
              queueDepthWeight,
              scaleUpCooldown,
              scaleDownCooldown));
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid autoscale policy: " + e.getMessage(), e);
    }
  }

  /**
   * A stabilization window, in whole seconds. Absent means {@code defaultValue} (see {@link
   * AutoscalePolicy}'s own javadoc for why the two directions default differently); {@code 0} is a
   * meaningful value operators do write -- it disables that direction's window outright -- so
   * unlike {@code startingDeadlineSeconds} this accepts zero and rejects only a negative.
   */
  private static Duration parseCooldown(Map<?, ?> autoscale, String field, Duration defaultValue) {
    Object value = autoscale.get(field);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Number number) || number.longValue() < 0) {
      throw new GimleManifestException(
          "'autoscale." + field + "' must be a non-negative number of seconds if present");
    }
    return Duration.ofSeconds(number.longValue());
  }

  /**
   * {@code autoscale.mode} is optional and defaults to {@code worst-signal} -- an absent value
   * reproduces {@link AutoscalePolicy.CombinationMode#WORST_SIGNAL} exactly, matching every
   * manifest written before {@code weighted} mode existed.
   */
  private static AutoscalePolicy.CombinationMode parseCombinationMode(Map<?, ?> autoscale) {
    Object value = autoscale.get("mode");
    if (value == null) {
      return AutoscalePolicy.CombinationMode.WORST_SIGNAL;
    }
    if (!(value instanceof String s)) {
      throw new GimleManifestException("'autoscale.mode' must be a string if present");
    }
    return switch (s) {
      case "worst-signal" -> AutoscalePolicy.CombinationMode.WORST_SIGNAL;
      case "weighted" -> AutoscalePolicy.CombinationMode.WEIGHTED;
      default ->
          throw new GimleManifestException(
              "'autoscale.mode' must be 'worst-signal' or 'weighted', got: " + s);
    };
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
          "'vessel.env."
              + name
              + "' must be a string, {secret: ...}, {port: ...}, or {volume: ...}");
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
    if (map.containsKey("volume")) {
      if (!(map.get("volume") instanceof Map<?, ?> volume)) {
        throw new GimleManifestException("'vessel.env." + name + ".volume' must be a mapping");
      }
      Object sizeBytesObj = volume.get("sizeBytes");
      if (!(sizeBytesObj instanceof Number sizeBytes) || sizeBytes.longValue() <= 0) {
        throw new GimleManifestException(
            "'vessel.env." + name + ".volume.sizeBytes' must be a positive number");
      }
      ReclaimPolicy reclaimPolicy = ReclaimPolicy.RETAIN;
      Object policyObj = volume.get("reclaimPolicy");
      if (policyObj != null) {
        if (!(policyObj instanceof String policy) || policy.isBlank()) {
          throw new GimleManifestException(
              "'vessel.env." + name + ".volume.reclaimPolicy' must be 'Retain' or 'Delete'");
        }
        try {
          reclaimPolicy = ReclaimPolicy.valueOf(policy.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
          throw new GimleManifestException(
              "'vessel.env."
                  + name
                  + ".volume.reclaimPolicy' must be 'Retain' or 'Delete', got '"
                  + policy
                  + "'");
        }
      }
      return new VesselEnvValue.VolumeMount(sizeBytes.longValue(), reclaimPolicy);
    }
    throw new GimleManifestException(
        "'vessel.env." + name + "' must declare 'secret', 'port', or 'volume' if it is a mapping");
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
        result.add(
            new VesselFileMount(
                requireString(map, "path"),
                map.containsKey("config")
                    ? Optional.of(requireString(map, "config"))
                    : Optional.empty(),
                map.containsKey("secret")
                    ? Optional.of(requireString(map, "secret"))
                    : Optional.empty()));
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
    Optional<String> portName = optionalPortNameField(rung, which);
    if (Boolean.TRUE.equals(rung.get("tcp"))) {
      try {
        return Optional.of(new VesselProbeSpec.Tcp(portName, initialDelaySeconds));
      } catch (IllegalArgumentException e) {
        throw new GimleManifestException(
            "invalid vessel.probes." + which + ": " + e.getMessage(), e);
      }
    }
    if (rung.get("http") instanceof String path) {
      try {
        return Optional.of(new VesselProbeSpec.Http(path, portName, initialDelaySeconds));
      } catch (IllegalArgumentException e) {
        throw new GimleManifestException(
            "invalid vessel.probes." + which + ": " + e.getMessage(), e);
      }
    }
    throw new GimleManifestException(
        "'vessel.probes." + which + "' must be either {tcp: true} or {http: <path>}");
  }

  /**
   * Which {@code vessel.env} {@code {port: ...}} entry this probe rung dials, by env-var name --
   * required once a vessel declares more than one such entry (see {@code VesselSpec}'s own compact
   * constructor), optional otherwise.
   */
  private static Optional<String> optionalPortNameField(Map<?, ?> rung, String which) {
    Object port = rung.get("port");
    if (port == null) {
      return Optional.empty();
    }
    if (!(port instanceof String s) || s.isBlank()) {
      throw new GimleManifestException(
          "'vessel.probes."
              + which
              + ".port' must be a non-blank string naming a vessel.env"
              + " {port: ...} entry");
    }
    return Optional.of(s);
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
