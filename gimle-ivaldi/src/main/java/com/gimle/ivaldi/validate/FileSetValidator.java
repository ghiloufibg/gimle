package com.gimle.ivaldi.validate;

import com.gimle.hilmir.release.BundleParser;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.TopologyParser;
import com.gimle.hilmir.validate.TopologyValidator;
import com.gimle.mimir.manifest.ManifestParser;
import com.gimle.mimir.manifest.NetworkPolicySpec;
import com.gimle.mimir.manifest.ParsedManifest;
import com.gimle.mimir.manifest.ServiceSpec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * for the two resource kinds {@code ManifestParser} doesn't cover, and {@link BundleParser} for
 * {@code bundle.yaml} -- so a design is checked against the platform's own admission rules, not a
 * browser-side reimplementation of them. This is deliberately the authoritative check for the bytes
 * it is given: it never re-derives them from a Blueprint's node/edge graph, which stays the
 * console's own concern.
 *
 * <p>Every other file in a rendered set ({@code values.example.yaml}, {@code README.md}, {@code
 * ivaldi.blueprint.json}) has nothing here to check against and is silently skipped.
 */
public final class FileSetValidator {

  private static final Set<String> WORKLOAD_KINDS =
      Set.of("Deployment", "StatefulSet", "DaemonSet", "Job", "CronJob");

  private FileSetValidator() {}

  public static List<Finding> validate(List<RenderedFile> files) {
    List<Finding> findings = new ArrayList<>();
    for (RenderedFile file : files) {
      if ("topology.yaml".equals(file.path())) {
        validateTopology(file, findings);
      } else if ("bundle.yaml".equals(file.path())) {
        validateBundle(file, findings);
      } else if (file.path().startsWith("manifests/") && file.path().endsWith(".yaml")) {
        validateManifest(file, findings);
      }
    }
    return findings;
  }

  private static void validateTopology(RenderedFile file, List<Finding> findings) {
    Topology topology;
    try {
      topology = TopologyParser.parse(streamOf(file));
    } catch (RuntimeException e) {
      findings.add(Finding.error("TOPOLOGY_PARSE_ERROR", messageOf(e), file.path()));
      return;
    }
    for (com.gimle.hilmir.validate.Finding rule : TopologyValidator.validate(topology)) {
      Finding.Severity severity =
          rule.severity() == com.gimle.hilmir.validate.Severity.ERROR
              ? Finding.Severity.ERROR
              : Finding.Severity.WARNING;
      findings.add(new Finding(rule.code(), severity, rule.message(), file.path()));
    }
  }

  private static void validateBundle(RenderedFile file, List<Finding> findings) {
    try {
      BundleParser.parse(streamOf(file));
    } catch (RuntimeException e) {
      findings.add(Finding.error("BUNDLE_PARSE_ERROR", messageOf(e), file.path()));
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
    if (WORKLOAD_KINDS.contains(kindName)) {
      validateWorkload(file, findings);
    } else if ("Service".equals(kindName)) {
      validateService(file, root, findings);
    } else if ("NetworkPolicy".equals(kindName)) {
      validateNetworkPolicy(file, root, findings);
    } else {
      findings.add(
          Finding.error(
              "MANIFEST_UNKNOWN_KIND", "unrecognized manifest kind: " + kindName, file.path()));
    }
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
