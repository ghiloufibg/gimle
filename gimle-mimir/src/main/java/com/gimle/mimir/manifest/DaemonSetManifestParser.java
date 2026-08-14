package com.gimle.mimir.manifest;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ModuleId;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses and validates a DaemonSet manifest -- the same hand-rolled {@code Map<?,?>}-walking shape
 * {@link DeploymentManifestParser}/{@link JobManifestParser} use, deliberately duplicated rather
 * than shared (see {@link DeploymentManifestParser}'s own javadoc for why). Reached only through
 * {@link ManifestParser}, which peels off the manifest's top-level {@code kind: DaemonSet} field
 * before delegating here -- this class itself never reads {@code kind}.
 */
public final class DaemonSetManifestParser {

  private DaemonSetManifestParser() {}

  /**
   * A standalone entry point independent of {@link ManifestParser}'s {@code kind:} dispatch --
   * mirrors {@link DeploymentManifestParser#parse}, including its reason for existing: {@code
   * StateStore}'s own reload-on-restart path reuses this rather than duplicating YAML-loading and
   * root-shape validation a second time.
   */
  public static DaemonSetSpec parse(InputStream yamlContent) {
    Object raw;
    try {
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      raw = yaml.load(yamlContent);
    } catch (RuntimeException e) {
      throw new GimleManifestException("malformed YAML in daemonset manifest", e);
    }
    if (!(raw instanceof Map<?, ?> root)) {
      throw new GimleManifestException(
          "daemonset manifest must contain a YAML mapping at the root");
    }
    return parseRoot(root);
  }

  // Package-visible, not private: ManifestParser calls this directly after peeling off kind:,
  // mirroring DeploymentManifestParser.parseRoot's own visibility exactly.
  static DaemonSetSpec parseRoot(Map<?, ?> root) {
    String name = ManifestFields.requireString(root, "name");
    ModuleId moduleId = ManifestFields.parseModuleId(ManifestFields.requireMap(root, "module"));
    String artifactPath = ManifestFields.optionalArtifactPath(root);
    PlacementConstraints placement = parsePlacement(root);
    Optional<String> tenantId = parseTenantId(root);
    Optional<String> artifactSha256 = parseArtifactSha256(root);
    Optional<DisruptionBudget> disruption = parseDisruptionBudget(root);

    try {
      return new DaemonSetSpec(
          name, moduleId, artifactPath, placement, tenantId, artifactSha256, disruption);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException(
          "invalid daemonset manifest for " + name + ": " + e.getMessage(), e);
    }
  }

  /**
   * {@code maxSurge} is meaningless on a DaemonSet (see {@link DisruptionBudget}'s own javadoc) and
   * rejected outright if nonzero, the same posture {@link #parsePlacement} already takes for {@code
   * placement.antiAffinity}.
   */
  private static Optional<DisruptionBudget> parseDisruptionBudget(Map<?, ?> root) {
    return ManifestFields.parseDisruptionBudget(
        root,
        (disruption, maxUnavailable) -> {
          if (disruption.containsKey("maxSurge")
              && ManifestFields.optionalIntField(disruption, "maxSurge", "disruption.").orElse(0)
                  != 0) {
            throw new GimleManifestException(
                "'disruption.maxSurge' is not meaningful on a DaemonSet -- one instance per node"
                    + " is already the strongest guarantee a surge could offer; remove the field");
          }
          return new DisruptionBudget(maxUnavailable);
        });
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

  private static PlacementConstraints parsePlacement(Map<?, ?> root) {
    Object placementObj = root.get("placement");
    if (placementObj == null) {
      return PlacementConstraints.NONE;
    }
    if (!(placementObj instanceof Map<?, ?> placement)) {
      throw new GimleManifestException("'placement' must be a mapping");
    }
    // No antiAffinity field at all here, unlike Deployment/Job's own parsePlacement -- reading
    // one true and silently ignoring it would look like it did something. If an operator writes
    // it, reject outright so the manifest is unambiguous about what it actually means.
    if (placement.containsKey("antiAffinity")) {
      throw new GimleManifestException(
          "'placement.antiAffinity' is not meaningful on a DaemonSet -- one per node is already"
              + " stronger than anti-affinity; remove the field");
    }
    Optional<Set<String>> requiredLabels = ManifestFields.parseRequiredLabels(placement);
    try {
      return new PlacementConstraints(requiredLabels, false);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid placement: " + e.getMessage(), e);
    }
  }
}
