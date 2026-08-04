package com.gimle.mimir.manifest;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses and validates a deployment manifest -- the same hand-rolled {@code Map<?,?>}-walking shape
 * {@code gimle-module}'s {@code ModuleDescriptorParser} uses, deliberately not shared as a common
 * utility across the two modules for two call sites' worth of small, mostly-dissimilar helpers.
 */
public final class DeploymentManifestParser {

  private DeploymentManifestParser() {}

  public static DeploymentSpec parse(InputStream yamlContent) {
    Object raw;
    try {
      // SafeConstructor restricts loading to plain maps/lists/scalars -- a submitted manifest is
      // untrusted input, same reasoning ModuleDescriptorParser already documents.
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      raw = yaml.load(yamlContent);
    } catch (RuntimeException e) {
      throw new GimleManifestException("malformed YAML in deployment manifest", e);
    }
    if (!(raw instanceof Map<?, ?> root)) {
      throw new GimleManifestException(
          "deployment manifest must contain a YAML mapping at the root");
    }
    return parseRoot(root);
  }

  private static DeploymentSpec parseRoot(Map<?, ?> root) {
    String name = requireString(root, "name");
    ModuleId moduleId = parseModuleId(requireMap(root, "module"));
    String artifactPath = requireString(root, "artifactPath");
    int replicas = parseReplicas(root);
    PlacementConstraints placement = parsePlacement(root);
    Optional<AutoscalePolicy> autoscale = parseAutoscale(root);
    Optional<String> tenantId = parseTenantId(root);

    try {
      return new DeploymentSpec(
          name, moduleId, artifactPath, replicas, placement, autoscale, tenantId);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException(
          "invalid deployment manifest for " + name + ": " + e.getMessage(), e);
    }
  }

  private static Optional<AutoscalePolicy> parseAutoscale(Map<?, ?> root) {
    Object autoscaleObj = root.get("autoscale");
    if (autoscaleObj == null) {
      return Optional.empty();
    }
    if (!(autoscaleObj instanceof Map<?, ?> autoscale)) {
      throw new GimleManifestException("'autoscale' must be a mapping");
    }
    int minReplicas = requiredIntField(autoscale, "minReplicas");
    int maxReplicas = requiredIntField(autoscale, "maxReplicas");
    int targetCpuUtilizationPercent = requiredIntField(autoscale, "targetCpuUtilizationPercent");
    try {
      return Optional.of(
          new AutoscalePolicy(minReplicas, maxReplicas, targetCpuUtilizationPercent));
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid autoscale policy: " + e.getMessage(), e);
    }
  }

  /** {@code tenantId} is optional: absent means untenanted. */
  private static Optional<String> parseTenantId(Map<?, ?> root) {
    Object value = root.get("tenantId");
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException("'tenantId' must be a non-blank string if present");
    }
    return Optional.of(s);
  }

  private static int requiredIntField(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof Number number)) {
      throw new GimleManifestException("missing or non-numeric required field: autoscale." + key);
    }
    return number.intValue();
  }

  private static ModuleId parseModuleId(Map<?, ?> module) {
    String moduleName = requireString(module, "name");
    String versionText = requireString(module, "version");
    try {
      return new ModuleId(moduleName, Version.parse(versionText));
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid module reference: " + e.getMessage(), e);
    }
  }

  private static int parseReplicas(Map<?, ?> root) {
    Object value = root.get("replicas");
    if (!(value instanceof Number number)) {
      throw new GimleManifestException("missing or non-numeric required field: replicas");
    }
    return number.intValue();
  }

  private static PlacementConstraints parsePlacement(Map<?, ?> root) {
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

  private static Optional<Set<String>> parseRequiredLabels(Map<?, ?> placement) {
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

  private static boolean booleanField(Map<?, ?> map, String key, boolean defaultValue) {
    Object value = map.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Boolean b)) {
      throw new GimleManifestException("field must be a boolean if present: " + key);
    }
    return b;
  }

  private static String requireString(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException("missing or blank required field: " + key);
    }
    return s;
  }

  private static Map<?, ?> requireMap(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof Map<?, ?> m)) {
      throw new GimleManifestException("missing or malformed required section: " + key);
    }
    return m;
  }
}
