package com.gimle.mimir.manifest;

import com.gimle.core.exception.GimleManifestException;
import java.io.InputStream;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The one real entry point for parsing an operator-submitted manifest (priority-3 design doc §2):
 * reads {@code kind:}, then delegates to that kind's own {@code *ManifestParser.parseRoot(Map)}.
 *
 * <p>{@code kind:} is a required top-level field, with no default. Per this project's own
 * no-backward-compat convention (unreleased software, no external users -- prefer clean replacement
 * over dual-mode fallback logic), there is no reason to make {@code kind:} optional and implicitly
 * assume {@code Deployment} for old-shaped manifests; every manifest written against this codebase
 * gets {@code kind: Deployment} (or whichever kind actually applies) added, and this parser rejects
 * a manifest missing {@code kind:} outright rather than guessing at it.
 *
 * <p>{@link DeploymentManifestParser}/{@link JobManifestParser}/{@link CronJobManifestParser}'s own
 * {@code parseRoot} methods remain independently usable (package-visible, not called only from
 * here) for their own kind-agnostic unit tests -- none of the three reads {@code kind} itself, so a
 * caller that already knows which kind it wants can skip this dispatch entirely.
 */
public final class ManifestParser {

  private ManifestParser() {}

  public static WorkloadSpec parse(InputStream yamlContent) {
    Object raw;
    try {
      // SafeConstructor restricts loading to plain maps/lists/scalars -- a submitted manifest is
      // untrusted input, same reasoning every other manifest parser in this codebase documents.
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      raw = yaml.load(yamlContent);
    } catch (RuntimeException e) {
      throw new GimleManifestException("malformed YAML in manifest", e);
    }
    if (!(raw instanceof Map<?, ?> root)) {
      throw new GimleManifestException("manifest must contain a YAML mapping at the root");
    }
    String kind = requireString(root, "kind");
    return switch (kind) {
      case "Deployment" -> DeploymentManifestParser.parseRoot(root);
      case "Job" -> JobManifestParser.parseRoot(root);
      case "CronJob" -> CronJobManifestParser.parseRoot(root);
      default -> throw GimleManifestException.unknownKind(kind);
    };
  }

  private static String requireString(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException("missing or blank required field: " + key);
    }
    return s;
  }
}
