package com.gimle.mimir.manifest;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.manifest.ApiVersion;
import com.gimle.core.module.ModuleId;
import com.gimle.core.vessel.VesselSpec;
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

  private static final Set<String> KNOWN_FIELDS =
      Set.of(
          "name",
          "module",
          "artifactPath",
          "replicas",
          "placement",
          "autoscale",
          "tenantId",
          "artifactSha256",
          "disruption",
          "vessel",
          "configMapRefs",
          "secretMapRefs");

  private DeploymentManifestParser() {}

  /**
   * A standalone, version-blind convenience entry used by this parser's own shape unit tests:
   * parses at {@code v1alpha1} (exactly what an unversioned manifest means) and discards warnings.
   * The real operator-facing path is {@link ManifestParser}, which resolves {@code kind:}/{@code
   * apiVersion:} itself and calls {@link #parseRoot} directly.
   */
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
    return parseRoot(root, ApiVersion.V1ALPHA1, new ArrayList<>());
  }

  // Package-visible, not private: ManifestParser calls this directly after peeling off the
  // manifest's top-level kind:/apiVersion: fields, so both entry points share this exact parsing
  // logic instead of duplicating it. Deliberately still ignorant of kind: itself -- this method's
  // only job is "given a root map and a version, build a DeploymentSpec."
  static DeploymentSpec parseRoot(Map<?, ?> root, ApiVersion version, List<String> warnings) {
    ManifestFields.warnUnknownFields(root, KNOWN_FIELDS, warnings);
    String name = ManifestFields.requireString(root, "name");
    ModuleId moduleId = ManifestFields.parseModuleId(ManifestFields.requireMap(root, "module"));
    String artifactPath = ManifestFields.optionalArtifactPath(root, version, warnings);
    int replicas = parseReplicas(root);
    PlacementConstraints placement = ManifestFields.parsePlacement(root);
    Optional<AutoscalePolicy> autoscale = ManifestFields.parseAutoscale(root);
    Optional<String> tenantId = ManifestFields.parseTenantId(root);
    Optional<String> artifactSha256 = parseArtifactSha256(root);
    Optional<DisruptionBudget> disruption = parseDisruptionBudget(root);
    Optional<VesselSpec> vessel = ManifestFields.parseVessel(root);
    List<String> configMapRefs = ManifestFields.stringList(root, "configMapRefs", "configMapRefs");
    List<String> secretMapRefs = ManifestFields.stringList(root, "secretMapRefs", "secretMapRefs");

    try {
      return new DeploymentSpec(
          name,
          moduleId,
          artifactPath,
          replicas,
          placement,
          autoscale,
          tenantId,
          artifactSha256,
          disruption,
          vessel,
          configMapRefs,
          secretMapRefs);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException(
          "invalid deployment manifest for " + name + ": " + e.getMessage(), e);
    }
  }

  /**
   * Unlike {@link DaemonSetManifestParser}'s own disruption parsing, a nonzero {@code maxSurge} is
   * accepted here -- {@code DeploymentReconciler#handleSurge} implements it via a synthetic index
   * range above {@code replicas}, a mechanism a DaemonSet's one-instance-per-node placement has no
   * equivalent of. See {@link DisruptionBudget}'s own javadoc for the full reasoning.
   */
  static Optional<DisruptionBudget> parseDisruptionBudget(Map<?, ?> root) {
    return ManifestFields.parseDisruptionBudget(
        root,
        (disruption, maxUnavailable) -> {
          int maxSurge =
              ManifestFields.optionalIntField(disruption, "maxSurge", "disruption.").orElse(0);
          return new DisruptionBudget(maxUnavailable, maxSurge);
        });
  }

  /**
   * {@code artifactSha256} is never trusted from an operator-submitted manifest -- {@code
   * ApiServer} always overwrites whatever this parses with its own freshly-computed hash at
   * admission. Parsing it here exists solely so {@code StateStore.loadAll}'s reload path (which
   * reuses this same parser against its own previously-written YAML, not user input) round-trips
   * the hash it wrote rather than losing it on every restart.
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

  private static int parseReplicas(Map<?, ?> root) {
    Object value = root.get("replicas");
    if (!(value instanceof Number number)) {
      throw new GimleManifestException("missing or non-numeric required field: replicas");
    }
    return number.intValue();
  }
}
