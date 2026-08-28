package com.gimle.mimir.manifest;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.manifest.ApiVersion;
import com.gimle.core.module.ModuleId;
import com.gimle.core.vessel.VesselSpec;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses and validates a CronJob manifest -- the same hand-rolled {@code Map<?,?>}-walking shape
 * {@link JobManifestParser} uses, deliberately duplicated rather than shared (see that class's own
 * javadoc for why). Reached only through {@link ManifestParser}, which peels off the manifest's
 * top-level {@code kind: CronJob} field before delegating here -- this class itself never reads
 * {@code kind}.
 */
public final class CronJobManifestParser {

  /** Matches {@link JobManifestParser}'s own default exactly. */
  private static final int DEFAULT_BACKOFF_LIMIT = 6;

  private static final ConcurrencyPolicy DEFAULT_CONCURRENCY_POLICY = ConcurrencyPolicy.ALLOW;

  private static final Set<String> KNOWN_FIELDS =
      Set.of(
          "name",
          "schedule",
          "jobTemplate",
          "startingDeadlineSeconds",
          "concurrencyPolicy",
          "tenantId");

  private CronJobManifestParser() {}

  /**
   * A standalone, version-blind convenience entry mirroring {@link JobManifestParser#parse}
   * exactly: parses at {@code v1alpha1} (what an unversioned manifest means), discarding warnings,
   * for this parser's own shape unit tests.
   */
  public static CronJobSpec parse(InputStream yamlContent) {
    Object raw;
    try {
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      raw = yaml.load(yamlContent);
    } catch (RuntimeException e) {
      throw new GimleManifestException("malformed YAML in cronjob manifest", e);
    }
    if (!(raw instanceof Map<?, ?> root)) {
      throw new GimleManifestException("cronjob manifest must contain a YAML mapping at the root");
    }
    return parseRoot(root, ApiVersion.V1ALPHA1, new ArrayList<>());
  }

  // Package-visible, not private: ManifestParser calls this directly after peeling off kind: and
  // apiVersion:, mirroring JobManifestParser.parseRoot's own visibility exactly.
  static CronJobSpec parseRoot(Map<?, ?> root, ApiVersion version, List<String> warnings) {
    ManifestFields.warnUnknownFields(root, KNOWN_FIELDS, warnings);
    String name = ManifestFields.requireString(root, "name");
    String schedule = ManifestFields.requireString(root, "schedule");
    JobTemplate jobTemplate =
        parseJobTemplate(ManifestFields.requireMap(root, "jobTemplate"), version, warnings);
    Optional<Duration> startingDeadline = parseStartingDeadline(root);
    ConcurrencyPolicy concurrencyPolicy = parseConcurrencyPolicy(root);
    Optional<String> tenantId = ManifestFields.parseTenantId(root);

    try {
      return new CronJobSpec(
          name, schedule, jobTemplate, startingDeadline, concurrencyPolicy, tenantId);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException(
          "invalid cronjob manifest for " + name + ": " + e.getMessage(), e);
    }
  }

  private static JobTemplate parseJobTemplate(
      Map<?, ?> template, ApiVersion version, List<String> warnings) {
    ModuleId moduleId = ManifestFields.parseModuleId(ManifestFields.requireMap(template, "module"));
    String artifactPath = ManifestFields.optionalArtifactPath(template, version, warnings);
    PlacementConstraints placement = parsePlacement(template);
    Optional<Duration> activeDeadline = parseActiveDeadline(template);
    int backoffLimit = parseBackoffLimit(template);
    Optional<VesselSpec> vessel = ManifestFields.parseVessel(template);
    try {
      return new JobTemplate(
          moduleId, artifactPath, placement, activeDeadline, backoffLimit, vessel);
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException("invalid jobTemplate: " + e.getMessage(), e);
    }
  }

  private static Optional<Duration> parseStartingDeadline(Map<?, ?> root) {
    Object value = root.get("startingDeadlineSeconds");
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Number number) || number.longValue() <= 0) {
      throw new GimleManifestException(
          "'startingDeadlineSeconds' must be a positive number if present");
    }
    return Optional.of(Duration.ofSeconds(number.longValue()));
  }

  private static ConcurrencyPolicy parseConcurrencyPolicy(Map<?, ?> root) {
    Object value = root.get("concurrencyPolicy");
    if (value == null) {
      return DEFAULT_CONCURRENCY_POLICY;
    }
    if (!(value instanceof String s)) {
      throw new GimleManifestException("'concurrencyPolicy' must be a string if present");
    }
    try {
      return ConcurrencyPolicy.valueOf(s.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new GimleManifestException(
          "'concurrencyPolicy' must be one of ALLOW, FORBID, REPLACE (case-insensitive); got: "
              + s);
    }
  }

  private static Optional<Duration> parseActiveDeadline(Map<?, ?> template) {
    Object value = template.get("activeDeadlineSeconds");
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Number number) || number.longValue() <= 0) {
      throw new GimleManifestException(
          "'jobTemplate.activeDeadlineSeconds' must be a positive number if present");
    }
    return Optional.of(Duration.ofSeconds(number.longValue()));
  }

  /** Absent means {@link #DEFAULT_BACKOFF_LIMIT}, matching {@link JobManifestParser}'s own. */
  private static int parseBackoffLimit(Map<?, ?> template) {
    Object value = template.get("backoffLimit");
    if (value == null) {
      return DEFAULT_BACKOFF_LIMIT;
    }
    if (!(value instanceof Number number) || number.intValue() < 0) {
      throw new GimleManifestException(
          "'jobTemplate.backoffLimit' must be a non-negative number if present");
    }
    return number.intValue();
  }

  // jobTemplate.placement's error messages are scoped to jobTemplate.placement.* rather than
  // ManifestFields.parsePlacement's plain placement.* -- kept as a local variant instead of
  // parameterizing that shared helper's prefix for a single caller.
  private static PlacementConstraints parsePlacement(Map<?, ?> template) {
    Object placementObj = template.get("placement");
    if (placementObj == null) {
      return PlacementConstraints.NONE;
    }
    if (!(placementObj instanceof Map<?, ?> placement)) {
      throw new GimleManifestException("'jobTemplate.placement' must be a mapping");
    }
    boolean antiAffinity = ManifestFields.booleanField(placement, "antiAffinity", false);
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
      throw new GimleManifestException("'jobTemplate.placement.requiredLabels' must be a list");
    }
    List<String> labels = new ArrayList<>();
    for (Object entry : list) {
      if (!(entry instanceof String s) || s.isBlank()) {
        throw new GimleManifestException(
            "each 'jobTemplate.placement.requiredLabels' entry must be a non-blank string");
      }
      labels.add(s);
    }
    return Optional.of(Set.copyOf(labels));
  }
}
