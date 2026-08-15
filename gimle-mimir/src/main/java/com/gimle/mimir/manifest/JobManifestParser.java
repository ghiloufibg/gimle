package com.gimle.mimir.manifest;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ModuleId;
import com.gimle.core.vessel.VesselSpec;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses and validates a Job manifest -- the same hand-rolled {@code Map<?,?>}-walking shape {@link
 * DeploymentManifestParser} uses, deliberately duplicated rather than shared (that class's own
 * javadoc explains why: two call sites' worth of small, mostly-dissimilar helpers isn't worth a
 * shared utility). Reached only through {@link ManifestParser}, which peels off the manifest's
 * top-level {@code kind: Job} field before delegating here -- this class itself never reads {@code
 * kind}.
 */
public final class JobManifestParser {

  /** Kubernetes Job's own default -- a well-understood reference value, not invented here. */
  private static final int DEFAULT_BACKOFF_LIMIT = 6;

  private JobManifestParser() {}

  /**
   * A standalone entry point independent of {@link ManifestParser}'s {@code kind:} dispatch --
   * mirrors {@link DeploymentManifestParser#parse}, including its reason for existing: {@code
   * StateStore}'s own reload-on-restart path (a different package, so it needs a {@code public}
   * entry point, not just {@link #parseRoot}'s package visibility) reuses this rather than
   * duplicating YAML-loading and root-shape validation a second time.
   */
  public static JobSpec parse(InputStream yamlContent) {
    Object raw;
    try {
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      raw = yaml.load(yamlContent);
    } catch (RuntimeException e) {
      throw new GimleManifestException("malformed YAML in job manifest", e);
    }
    if (!(raw instanceof Map<?, ?> root)) {
      throw new GimleManifestException("job manifest must contain a YAML mapping at the root");
    }
    return parseRoot(root);
  }

  // Package-visible, not private: ManifestParser calls this directly after peeling off kind:,
  // mirroring DeploymentManifestParser.parseRoot's own visibility exactly.
  static JobSpec parseRoot(Map<?, ?> root) {
    String name = ManifestFields.requireString(root, "name");
    ModuleId moduleId = ManifestFields.parseModuleId(ManifestFields.requireMap(root, "module"));
    String artifactPath = ManifestFields.optionalArtifactPath(root);
    PlacementConstraints placement = ManifestFields.parsePlacement(root);
    Optional<Duration> activeDeadline = parseActiveDeadline(root);
    int backoffLimit = parseBackoffLimit(root);
    Optional<String> tenantId = parseTenantId(root);
    Optional<String> artifactSha256 = parseArtifactSha256(root);
    Optional<VesselSpec> vessel = ManifestFields.parseVessel(root);

    try {
      return new JobSpec(
          name,
          moduleId,
          artifactPath,
          placement,
          activeDeadline,
          backoffLimit,
          tenantId,
          artifactSha256,
          vessel);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException(
          "invalid job manifest for " + name + ": " + e.getMessage(), e);
    }
  }

  private static Optional<Duration> parseActiveDeadline(Map<?, ?> root) {
    Object value = root.get("activeDeadlineSeconds");
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Number number) || number.longValue() <= 0) {
      throw new GimleManifestException(
          "'activeDeadlineSeconds' must be a positive number if present");
    }
    return Optional.of(Duration.ofSeconds(number.longValue()));
  }

  /** Absent means {@link #DEFAULT_BACKOFF_LIMIT}, matching Kubernetes Job's own default. */
  private static int parseBackoffLimit(Map<?, ?> root) {
    Object value = root.get("backoffLimit");
    if (value == null) {
      return DEFAULT_BACKOFF_LIMIT;
    }
    if (!(value instanceof Number number) || number.intValue() < 0) {
      throw new GimleManifestException("'backoffLimit' must be a non-negative number if present");
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
}
