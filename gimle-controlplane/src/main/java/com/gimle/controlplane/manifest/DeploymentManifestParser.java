package com.gimle.controlplane.manifest;

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
    return parse_root(root);
  }

  private static DeploymentSpec parse_root(Map<?, ?> root) {
    String name = require_string(root, "name");
    ModuleId moduleId = parse_module_id(require_map(root, "module"));
    String artifactPath = require_string(root, "artifactPath");
    int replicas = parse_replicas(root);
    PlacementConstraints placement = parse_placement(root);

    try {
      return new DeploymentSpec(name, moduleId, artifactPath, replicas, placement);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException(
          "invalid deployment manifest for " + name + ": " + e.getMessage(), e);
    }
  }

  private static ModuleId parse_module_id(Map<?, ?> module) {
    String moduleName = require_string(module, "name");
    String versionText = require_string(module, "version");
    try {
      return new ModuleId(moduleName, Version.parse(versionText));
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid module reference: " + e.getMessage(), e);
    }
  }

  private static int parse_replicas(Map<?, ?> root) {
    Object value = root.get("replicas");
    if (!(value instanceof Number number)) {
      throw new GimleManifestException("missing or non-numeric required field: replicas");
    }
    return number.intValue();
  }

  private static PlacementConstraints parse_placement(Map<?, ?> root) {
    Object placementObj = root.get("placement");
    if (placementObj == null) {
      return PlacementConstraints.NONE;
    }
    if (!(placementObj instanceof Map<?, ?> placement)) {
      throw new GimleManifestException("'placement' must be a mapping");
    }
    boolean antiAffinity = boolean_field(placement, "antiAffinity", false);
    Optional<Set<String>> requiredLabels = parse_required_labels(placement);
    try {
      return new PlacementConstraints(requiredLabels, antiAffinity);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid placement: " + e.getMessage(), e);
    }
  }

  private static Optional<Set<String>> parse_required_labels(Map<?, ?> placement) {
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

  private static boolean boolean_field(Map<?, ?> map, String key, boolean defaultValue) {
    Object value = map.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Boolean b)) {
      throw new GimleManifestException("field must be a boolean if present: " + key);
    }
    return b;
  }

  private static String require_string(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException("missing or blank required field: " + key);
    }
    return s;
  }

  private static Map<?, ?> require_map(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof Map<?, ?> m)) {
      throw new GimleManifestException("missing or malformed required section: " + key);
    }
    return m;
  }
}
