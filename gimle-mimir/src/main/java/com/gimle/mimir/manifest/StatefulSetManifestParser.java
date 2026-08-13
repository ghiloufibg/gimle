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
 * Parses and validates a StatefulSet manifest -- the same hand-rolled {@code Map<?,?>}-walking
 * shape {@link DeploymentManifestParser}/{@link DaemonSetManifestParser} use, deliberately
 * duplicated rather than shared. Unlike {@link DaemonSetManifestParser}, {@code
 * placement.antiAffinity} is a perfectly ordinary field here -- spreading a StatefulSet's replicas
 * across distinct nodes is exactly as meaningful as it is for a Deployment, so this parser accepts
 * it the same way {@link DeploymentManifestParser} does, unlike DaemonSet's one-per-node model
 * where it would be redundant. Reached only through {@link ManifestParser}, which peels off the
 * manifest's top-level {@code kind: StatefulSet} field before delegating here.
 */
public final class StatefulSetManifestParser {

  private StatefulSetManifestParser() {}

  /**
   * A standalone entry point independent of {@link ManifestParser}'s {@code kind:} dispatch --
   * mirrors {@link DeploymentManifestParser#parse}, including its reason for existing: {@code
   * StateStore}'s own reload-on-restart path reuses this rather than duplicating YAML-loading and
   * root-shape validation a second time.
   */
  public static StatefulSetSpec parse(InputStream yamlContent) {
    Object raw;
    try {
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      raw = yaml.load(yamlContent);
    } catch (RuntimeException e) {
      throw new GimleManifestException("malformed YAML in statefulset manifest", e);
    }
    if (!(raw instanceof Map<?, ?> root)) {
      throw new GimleManifestException(
          "statefulset manifest must contain a YAML mapping at the root");
    }
    return parseRoot(root);
  }

  // Package-visible, not private: ManifestParser calls this directly after peeling off kind:,
  // mirroring DeploymentManifestParser.parseRoot's own visibility exactly.
  static StatefulSetSpec parseRoot(Map<?, ?> root) {
    String name = requireString(root, "name");
    ModuleId moduleId = parseModuleId(requireMap(root, "module"));
    String artifactPath = requireString(root, "artifactPath");
    int replicas = parseReplicas(root);
    PlacementConstraints placement = parsePlacement(root);
    Optional<String> tenantId = parseTenantId(root);
    Optional<String> artifactSha256 = parseArtifactSha256(root);

    try {
      return new StatefulSetSpec(
          name, moduleId, artifactPath, replicas, placement, tenantId, artifactSha256);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException(
          "invalid statefulset manifest for " + name + ": " + e.getMessage(), e);
    }
  }

  private static int parseReplicas(Map<?, ?> root) {
    Object value = root.get("replicas");
    if (!(value instanceof Number number)) {
      throw new GimleManifestException("missing or non-numeric required field: replicas");
    }
    return number.intValue();
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

  /**
   * Never trusted from an operator-submitted manifest -- {@code ApiServer} always overwrites
   * whatever this parses with its own freshly-computed hash at admission, matching {@code
   * DeploymentManifestParser}'s identical field exactly.
   */
  private static Optional<String> parseArtifactSha256(Map<?, ?> root) {
    Object value = root.get("artifactSha256");
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException("'artifactSha256' must be a non-blank string if present");
    }
    return Optional.of(s);
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
