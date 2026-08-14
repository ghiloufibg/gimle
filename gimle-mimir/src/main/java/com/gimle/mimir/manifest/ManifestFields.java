package com.gimle.mimir.manifest;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import java.util.ArrayList;
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
}
