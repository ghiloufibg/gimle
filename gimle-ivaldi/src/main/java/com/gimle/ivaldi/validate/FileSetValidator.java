package com.gimle.ivaldi.validate;

import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.tenant.Tenant;
import com.gimle.hilmir.release.Bundle;
import com.gimle.hilmir.release.BundleParser;
import com.gimle.hilmir.release.BundleTenant;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.TopologyParser;
import com.gimle.hilmir.topology.Transport;
import com.gimle.hilmir.validate.TopologyValidator;
import com.gimle.mimir.manifest.LimitRangeSpec;
import com.gimle.mimir.manifest.ManifestParser;
import com.gimle.mimir.manifest.NetworkPolicySpec;
import com.gimle.mimir.manifest.ParsedManifest;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.manifest.WorkloadSpec;
import com.gimle.module.artifact.ModuleArtifactReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Tier-2 validation: runs a Blueprint's already-rendered files through the real platform parsers
 * and validators -- {@link TopologyParser}/{@link TopologyValidator} for {@code topology.yaml},
 * {@link ManifestParser} for the five workload kinds, {@link ServiceSpec}/{@link NetworkPolicySpec}
 * plus a LimitRange's own bound blocks for the three resource kinds {@code ManifestParser} doesn't
 * cover, and {@link BundleParser} for {@code bundle.yaml} -- so a design is checked against the
 * platform's own admission rules, not a browser-side reimplementation of them. This is deliberately
 * the authoritative check for the bytes it is given: it never re-derives them from a Blueprint's
 * node/edge graph, which stays the console's own concern.
 *
 * <p>{@code ivaldi.artifacts.yaml} is read too, though not as a platform document: it is Ivaldi's
 * own record of which local jar backs each manifest's module coordinate, and a topology with no
 * Andvari replica to push those jars to cannot host them. The same record is also what makes {@link
 * #requireJarResourcesWithinLimitRange} possible: a jar-sourced workload's real {@code
 * resources.request}/{@code resources.limit} come from its own {@code gimle-module.yaml} inside the
 * jar, never from the manifest this validator otherwise reads (see {@code DeploymentSpec}'s own
 * javadoc) -- so without opening the jar here, a design can validate clean against a tenant's
 * LimitRange while the module it actually pushes violates that same range, discovered only once a
 * whole cluster has booted and the control plane's own admission plugin runs the identical check
 * this method runs early, against the same {@link LimitRangeSpec#violation}. A registry-sourced
 * workload (a bare module coordinate, no local jar) has no equivalent check here: its real
 * descriptor lives in Andvari, unreachable from bytes alone, and this validator deliberately never
 * makes a live call to check it. Every other file in a rendered set ({@code values.example.yaml},
 * {@code README.md}, {@code ivaldi.blueprint.json}) has nothing here to check against and is
 * silently skipped.
 */
public final class FileSetValidator {

  private static final String SIDECAR_PATH = "ivaldi.artifacts.yaml";

  private static final Set<String> WORKLOAD_KINDS =
      Set.of("Deployment", "StatefulSet", "DaemonSet", "Job", "CronJob");

  private FileSetValidator() {}

  public static List<Finding> validate(List<RenderedFile> files) {
    List<Finding> findings = new ArrayList<>();
    Optional<Topology> topology = Optional.empty();
    Optional<Bundle> bundle = Optional.empty();
    for (RenderedFile file : files) {
      if ("topology.yaml".equals(file.path())) {
        topology = validateTopology(file, findings);
      } else if ("bundle.yaml".equals(file.path())) {
        bundle = validateBundle(file, findings);
      } else if (file.path().startsWith("manifests/") && file.path().endsWith(".yaml")) {
        validateManifest(file, findings);
      }
    }
    Optional<Bundle> parsedBundle = bundle;
    topology.ifPresent(parsed -> validateAcrossFiles(parsed, parsedBundle, files, findings));
    return findings;
  }

  /**
   * The checks no single file can make about itself, all of which otherwise only surface as a
   * runtime failure at the far end of a run that has already booted an entire platform first.
   */
  private static void validateAcrossFiles(
      Topology topology,
      Optional<Bundle> bundle,
      List<RenderedFile> files,
      List<Finding> findings) {
    List<JarArtifact> jars;
    try {
      jars = JarArtifact.readFrom(files);
    } catch (IllegalArgumentException malformed) {
      findings.add(Finding.error("ARTIFACTS_INVALID", malformed.getMessage(), SIDECAR_PATH));
      jars = List.of();
    }
    requireRegistryForJarArtifacts(topology, jars, findings);
    requireJarResourcesWithinLimitRange(jars, files, findings);
    bundle.ifPresent(parsed -> requireSingleTenantUnderPlaintext(topology, parsed, findings));
  }

  /**
   * A jar-sourced workload is not applied from the path it names: the run pushes that jar to the
   * cluster's own Andvari registry first, then applies a registry coordinate. A topology with no
   * Andvari replica therefore cannot host one, and says so only through a 503 from the control
   * plane at push time -- after the whole platform is up. Tier 1 already warns about the mirror
   * case (a registry-sourced workload with no registry to resolve it from).
   */
  private static void requireRegistryForJarArtifacts(
      Topology topology, List<JarArtifact> jars, List<Finding> findings) {
    if (topology.andvari().replicas().isEmpty()) {
      for (JarArtifact jar : jars) {
        findings.add(
            Finding.error(
                "NO_ANDVARI_FOR_JAR",
                "this workload is sourced from a local jar, which the run pushes to the cluster's"
                    + " own artifact registry, but the topology declares no andvari replica to"
                    + " push it to",
                jar.manifestPath()));
      }
    }
  }

  /**
   * Cross-checks each jar-sourced workload's own {@code gimle-module.yaml} resource declaration
   * against its tenant's LimitRange -- see this class's own javadoc for why the manifest itself
   * cannot answer this. Reuses {@link LimitRangeSpec#violation}, the exact check the control
   * plane's own admission plugin runs, so a design flagged clean here is checked the same way a
   * live cluster would check it, not by a re-derived approximation of that rule. A jar that cannot
   * be read at all is reported here too (mirroring {@code RunController}'s own push-time check,
   * just early) rather than left for the run to discover after the platform is already up.
   */
  private static void requireJarResourcesWithinLimitRange(
      List<JarArtifact> jars, List<RenderedFile> files, List<Finding> findings) {
    if (jars.isEmpty()) {
      return;
    }
    Map<String, LimitRangeSpec> limitRangesByTenant = limitRangesByTenant(files);
    if (limitRangesByTenant.isEmpty()) {
      return;
    }
    for (JarArtifact jar : jars) {
      RenderedFile manifest =
          files.stream().filter(f -> f.path().equals(jar.manifestPath())).findFirst().orElse(null);
      if (manifest == null) {
        continue; // an unresolved manifest path is JarArtifact's own concern, not this one's
      }
      WorkloadSpec spec;
      try {
        spec = ManifestParser.parse(streamOf(manifest)).spec();
      } catch (RuntimeException e) {
        continue; // already reported by validateWorkload against this same manifest
      }
      Optional<String> tenantId = spec.tenantId();
      if (tenantId.isEmpty()) {
        continue;
      }
      LimitRangeSpec limitRange = limitRangesByTenant.get(tenantId.get());
      if (limitRange == null) {
        continue;
      }
      if (!Files.isRegularFile(jar.jar())) {
        findings.add(
            Finding.error(
                "JAR_ARTIFACT_UNREADABLE",
                "no jar at " + jar.jar() + " -- check the artifact path",
                jar.manifestPath()));
        continue;
      }
      ModuleArtifact artifact;
      try {
        artifact = ModuleArtifactReader.read(jar.jar());
      } catch (RuntimeException notAModule) {
        findings.add(
            Finding.error(
                "JAR_ARTIFACT_UNREADABLE",
                "not a pushable module artifact at " + jar.jar() + ": " + notAModule.getMessage(),
                jar.manifestPath()));
        continue;
      }
      limitRange
          .violation(artifact.descriptor().resourceRequest(), artifact.descriptor().resourceLimit())
          .ifPresent(
              violation ->
                  findings.add(
                      Finding.error(
                          "LIMITRANGE_VIOLATION",
                          "workload "
                              + spec.name()
                              + " violates tenant "
                              + tenantId.get()
                              + "'s limit range: "
                              + violation
                              + " -- this is the module's own real resource declaration"
                              + " (gimle-module.yaml inside the jar), not whatever value the"
                              + " Inspector's own Resources fields happen to show",
                          jar.manifestPath())));
    }
  }

  /**
   * Every tenant-bounding LimitRange this file set declares, keyed by tenant id -- a tenant whose
   * range declares no bounds at all is omitted, since an unconstrained range can never be violated
   * and this map exists only to be checked against.
   */
  private static Map<String, LimitRangeSpec> limitRangesByTenant(List<RenderedFile> files) {
    Map<String, LimitRangeSpec> byTenant = new LinkedHashMap<>();
    for (RenderedFile file : files) {
      if (!file.path().startsWith("manifests/") || !file.path().endsWith(".yaml")) {
        continue;
      }
      Map<?, ?> root;
      try {
        root = readMapping(file.content());
      } catch (RuntimeException e) {
        continue; // already reported by validateManifest against this same file
      }
      if (!"LimitRange".equals(String.valueOf(root.get("kind")))) {
        continue;
      }
      if (!(root.get("name") instanceof String tenantId) || tenantId.isBlank()) {
        continue; // already reported by validateLimitRange against this same file
      }
      try {
        Optional<ResourceSpec> minRequest = bound(root, "minRequest");
        Optional<ResourceSpec> maxRequest = bound(root, "maxRequest");
        Optional<ResourceSpec> minLimit = bound(root, "minLimit");
        Optional<ResourceSpec> maxLimit = bound(root, "maxLimit");
        if (minRequest.isEmpty()
            && maxRequest.isEmpty()
            && minLimit.isEmpty()
            && maxLimit.isEmpty()) {
          continue;
        }
        byTenant.put(
            tenantId, new LimitRangeSpec(tenantId, minRequest, maxRequest, minLimit, maxLimit));
      } catch (IllegalArgumentException e) {
        // already reported by validateLimitRange against this same file
      }
    }
    return byTenant;
  }

  /**
   * Plaintext transport gives the control plane no caller identity to tell tenants apart, so it
   * permits exactly one tenant of the operator's own beyond the ones the platform seeds for itself
   * -- a bundle declaring two can never apply, whoever applies it. Only the bundle's own count is
   * knowable here: whether the target cluster already holds a different tenant is a question for
   * the live cluster, not for these bytes.
   */
  private static void requireSingleTenantUnderPlaintext(
      Topology topology, Bundle bundle, List<Finding> findings) {
    if (topology.transport() != Transport.PLAINTEXT) {
      return;
    }
    List<String> ownTenants =
        bundle.tenants().stream()
            .map(BundleTenant::id)
            .filter(id -> !Tenant.isPlatformSeeded(id))
            .distinct()
            .toList();
    if (ownTenants.size() > 1) {
      findings.add(
          Finding.error(
              "PLAINTEXT_MULTI_TENANT",
              "plaintext transport allows only one tenant of your own, but this bundle declares "
                  + ownTenants.size()
                  + " ("
                  + String.join(", ", ownTenants)
                  + ") -- switch the topology to mtls for real multi-tenancy",
              "bundle.yaml"));
    }
  }

  private static Optional<Topology> validateTopology(RenderedFile file, List<Finding> findings) {
    Topology topology;
    try {
      topology = TopologyParser.parse(streamOf(file));
    } catch (RuntimeException e) {
      findings.add(Finding.error("TOPOLOGY_PARSE_ERROR", messageOf(e), file.path()));
      return Optional.empty();
    }
    for (com.gimle.hilmir.validate.Finding rule : TopologyValidator.validate(topology)) {
      Finding.Severity severity =
          rule.severity() == com.gimle.hilmir.validate.Severity.ERROR
              ? Finding.Severity.ERROR
              : Finding.Severity.WARNING;
      findings.add(
          new Finding(rule.code(), severity, rule.message(), file.path(), rule.resource()));
    }
    return Optional.of(topology);
  }

  private static Optional<Bundle> validateBundle(RenderedFile file, List<Finding> findings) {
    try {
      return Optional.of(BundleParser.parse(streamOf(file)));
    } catch (RuntimeException e) {
      findings.add(Finding.error("BUNDLE_PARSE_ERROR", messageOf(e), file.path()));
      return Optional.empty();
    }
  }

  private static void validateManifest(RenderedFile file, List<Finding> findings) {
    Map<?, ?> root;
    try {
      root = readMapping(file.content());
    } catch (RuntimeException e) {
      findings.add(Finding.error("MANIFEST_PARSE_ERROR", messageOf(e), file.path()));
      return;
    }
    Object kind = root.get("kind");
    if (kind == null) {
      findings.add(
          Finding.error("MANIFEST_NO_KIND", "manifest is missing a 'kind' field", file.path()));
      return;
    }
    String kindName = String.valueOf(kind);
    // A manifest holds exactly one resource, so every finding it produces is about that resource
    // -- attributed once here rather than at each of the dozen places one is raised below.
    List<Finding> own = new ArrayList<>();
    if (WORKLOAD_KINDS.contains(kindName)) {
      validateWorkload(file, own);
    } else if ("Service".equals(kindName)) {
      validateService(file, root, own);
    } else if ("NetworkPolicy".equals(kindName)) {
      validateNetworkPolicy(file, root, own);
    } else if ("LimitRange".equals(kindName)) {
      validateLimitRange(file, root, own);
    } else {
      own.add(
          Finding.error(
              "MANIFEST_UNKNOWN_KIND", "unrecognized manifest kind: " + kindName, file.path()));
    }
    Object resourceName = root.get("name");
    String resource = kindName + "/" + (resourceName == null ? "" : resourceName);
    own.forEach(finding -> findings.add(finding.about(resource)));
  }

  private static void validateWorkload(RenderedFile file, List<Finding> findings) {
    ParsedManifest parsed;
    try {
      parsed = ManifestParser.parse(streamOf(file));
    } catch (RuntimeException e) {
      findings.add(Finding.error("MANIFEST_INVALID", messageOf(e), file.path()));
      return;
    }
    for (String warning : parsed.warnings()) {
      findings.add(Finding.warning("MANIFEST_DEPRECATION", warning, file.path()));
    }
  }

  // ManifestParser only dispatches the five workload kinds; Service and NetworkPolicy have no
  // `gimle apply -f` parser of their own on the server side (they are POST JSON bodies, not
  // manifest kinds ManifestParser knows) -- so their own record constructors, which already
  // validate every field a POST would, are the closest thing to a shared parser for them.
  private static void validateService(RenderedFile file, Map<?, ?> root, List<Finding> findings) {
    try {
      String name = requireString(root, "name", "service");
      Optional<String> tenantId = optionalString(root, "tenantId");
      Set<String> deploymentNames = stringSet(root, "deploymentNames");
      int port = requireInt(root, "port", "service");
      OptionalInt targetPort =
          root.get("targetPort") == null
              ? OptionalInt.empty()
              : OptionalInt.of(requireInt(root, "targetPort", "service"));
      new ServiceSpec(name, tenantId, deploymentNames, port, targetPort, false, Optional.empty());
    } catch (IllegalArgumentException e) {
      findings.add(Finding.error("SERVICE_INVALID", messageOf(e), file.path()));
    }
  }

  private static void validateNetworkPolicy(
      RenderedFile file, Map<?, ?> root, List<Finding> findings) {
    try {
      String name = requireString(root, "name", "network policy");
      String tenantId = requireString(root, "tenantId", "network policy");
      Optional<Set<String>> deploymentNames = optionalStringSet(root, "deploymentNames");
      Optional<Set<String>> allowedCallerTenantIds =
          optionalStringSet(root, "allowedCallerTenantIds");
      new NetworkPolicySpec(
          name,
          tenantId,
          deploymentNames,
          Optional.empty(),
          allowedCallerTenantIds,
          Optional.empty());
    } catch (IllegalArgumentException e) {
      findings.add(Finding.error("NETWORKPOLICY_INVALID", messageOf(e), file.path()));
    }
  }

  /**
   * Built as the platform's own {@link LimitRangeSpec}, not shape-tested.
   *
   * <p>A shape test passed three documents the platform refuses outright -- a blank quantity, an
   * unparseable one, and a minimum above its maximum -- so the run reached {@code PUT /limitranges}
   * and failed there, after the whole cluster had already booted, which is the failure this pass
   * exists to pre-empt. The record's own constructor already enforces every one of those rules.
   */
  private static void validateLimitRange(
      RenderedFile file, Map<?, ?> root, List<Finding> findings) {
    Object name = root.get("name");
    if (!(name instanceof String tenantId) || tenantId.isBlank()) {
      findings.add(
          Finding.error(
              "LIMITRANGE_INVALID", "limit range has no tenant 'name' field", file.path()));
      return;
    }
    try {
      Optional<ResourceSpec> minRequest = bound(root, "minRequest");
      Optional<ResourceSpec> maxRequest = bound(root, "maxRequest");
      Optional<ResourceSpec> minLimit = bound(root, "minLimit");
      Optional<ResourceSpec> maxLimit = bound(root, "maxLimit");
      if (minRequest.isEmpty()
          && maxRequest.isEmpty()
          && minLimit.isEmpty()
          && maxLimit.isEmpty()) {
        findings.add(
            Finding.warning(
                "LIMITRANGE_NO_BOUNDS",
                "limit range for tenant '"
                    + tenantId
                    + "' declares no bounds and constrains nothing",
                file.path()));
        return;
      }
      new LimitRangeSpec(tenantId, minRequest, maxRequest, minLimit, maxLimit);
    } catch (IllegalArgumentException e) {
      findings.add(Finding.error("LIMITRANGE_INVALID", messageOf(e), file.path()));
    }
  }

  /** One bound block, which the platform reads as complete once present: both halves required. */
  private static Optional<ResourceSpec> bound(Map<?, ?> root, String key) {
    Object value = root.get(key);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Map<?, ?> pair)) {
      throw new IllegalArgumentException("limit range '" + key + "' must be a mapping");
    }
    Object memory = pair.get("memory");
    Object cpu = pair.get("cpu");
    if (memory == null || cpu == null) {
      throw new IllegalArgumentException(
          "limit range '" + key + "' requires both 'memory' and 'cpu'");
    }
    return Optional.of(new ResourceSpec(String.valueOf(memory), String.valueOf(cpu)));
  }

  // ---- shared YAML plumbing ----

  private static Map<?, ?> readMapping(String content) {
    // SafeConstructor restricts loading to plain maps/lists/scalars -- rendered Blueprint output
    // is treated as untrusted input, the same reasoning every platform manifest parser documents.
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Object raw = yaml.load(content);
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("manifest must contain a YAML mapping at the root");
    }
    return map;
  }

  private static InputStream streamOf(RenderedFile file) {
    return new ByteArrayInputStream(file.content().getBytes(StandardCharsets.UTF_8));
  }

  private static String messageOf(RuntimeException e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  private static String requireString(Map<?, ?> root, String key, String what) {
    Object value = root.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new IllegalArgumentException(what + " is missing a non-blank '" + key + "'");
    }
    return s;
  }

  private static Optional<String> optionalString(Map<?, ?> root, String key) {
    Object value = root.get(key);
    return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
  }

  private static int requireInt(Map<?, ?> root, String key, String what) {
    Object value = root.get(key);
    if (!(value instanceof Number n)) {
      throw new IllegalArgumentException(what + " is missing a numeric '" + key + "'");
    }
    return n.intValue();
  }

  private static Set<String> stringSet(Map<?, ?> root, String key) {
    Object value = root.get(key);
    if (!(value instanceof List<?> list)) {
      return Set.of();
    }
    Set<String> result = new LinkedHashSet<>();
    for (Object entry : list) {
      result.add(String.valueOf(entry));
    }
    return result;
  }

  private static Optional<Set<String>> optionalStringSet(Map<?, ?> root, String key) {
    return root.containsKey(key) ? Optional.of(stringSet(root, key)) : Optional.empty();
  }
}
