package com.gimle.mimir.manifest;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.manifest.ApiVersion;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The one real entry point for parsing an operator-submitted manifest: reads {@code kind:} and the
 * optional {@code apiVersion:}, then delegates to that kind's own {@code
 * *ManifestParser.parseRoot(Map, ApiVersion, warnings)}.
 *
 * <p>{@code kind:} is a required top-level field, with no default. Per this project's own
 * no-backward-compat convention (unreleased software, no external users -- prefer clean replacement
 * over dual-mode fallback logic), there is no reason to make {@code kind:} optional and implicitly
 * assume {@code Deployment} for old-shaped manifests; every manifest written against this codebase
 * gets {@code kind: Deployment} (or whichever kind actually applies) added, and this parser rejects
 * a manifest missing {@code kind:} outright rather than guessing at it.
 *
 * <p>{@code apiVersion:}, by contrast, is optional with a stable default: absent means {@code
 * v1alpha1} -- permanently, so an unversioned manifest can never silently change meaning -- and
 * {@code v1} (which rejects {@code artifactPath} outright in favor of artifact-registry resolution)
 * must always be declared explicitly. See {@link ApiVersion}.
 *
 * <p>{@link DeploymentManifestParser}/{@link JobManifestParser}/{@link
 * CronJobManifestParser}/{@link DaemonSetManifestParser}/{@link StatefulSetManifestParser}'s own
 * {@code parseRoot} methods remain independently usable (package-visible, not called only from
 * here) for their own kind-agnostic unit tests -- none of the five reads {@code kind} or {@code
 * apiVersion} itself, so a caller that already knows which kind and version it wants can skip this
 * dispatch entirely.
 */
public final class ManifestParser {

  /** Every workload kind currently supports the same two versions. */
  private static final Set<ApiVersion> SUPPORTED_VERSIONS =
      Set.of(ApiVersion.V1ALPHA1, ApiVersion.V1);

  private ManifestParser() {}

  public static ParsedManifest parse(InputStream yamlContent) {
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
    String kind = ManifestFields.requireString(root, "kind");
    ApiVersion version = ApiVersion.of(root, kind, SUPPORTED_VERSIONS);
    List<String> warnings = new ArrayList<>();
    WorkloadSpec spec =
        switch (kind) {
          case "Deployment" -> DeploymentManifestParser.parseRoot(root, version, warnings);
          case "Job" -> JobManifestParser.parseRoot(root, version, warnings);
          case "CronJob" -> CronJobManifestParser.parseRoot(root, version, warnings);
          case "DaemonSet" -> DaemonSetManifestParser.parseRoot(root, version, warnings);
          case "StatefulSet" -> StatefulSetManifestParser.parseRoot(root, version, warnings);
          default -> throw GimleManifestException.unknownKind(kind);
        };
    return new ParsedManifest(spec, warnings);
  }
}
