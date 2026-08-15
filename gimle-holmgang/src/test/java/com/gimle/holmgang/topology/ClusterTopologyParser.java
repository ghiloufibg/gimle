package com.gimle.holmgang.topology;

import com.gimle.core.exception.GimleManifestException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses a cluster topology document into a {@link ClusterSpec}. Follows the same shape as the
 * manifest parsers in {@code gimle-mimir}: SnakeYAML restricted to plain maps/lists/scalars via
 * {@code SafeConstructor}, hand-validated fields, and {@link GimleManifestException} for every
 * rejection. Structural checks (is this field a number, is this section a mapping) live here;
 * semantic rules (replica minimums, duplicate node ids) live in {@link ClusterSpec}'s own
 * constructor so the {@link ClusterTopology} builder enforces them identically.
 */
public final class ClusterTopologyParser {

  private ClusterTopologyParser() {}

  public static ClusterSpec parse(final InputStream yamlContent) {
    final Object raw;
    try {
      final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      raw = yaml.load(yamlContent);
    } catch (final RuntimeException e) {
      throw new GimleManifestException("malformed YAML in topology document", e);
    }
    if (!(raw instanceof Map<?, ?> root)) {
      throw new GimleManifestException("topology document must contain a YAML mapping at the root");
    }
    return parseRoot(root);
  }

  /** Loads a topology from a classpath resource, e.g. {@code topologies/minimal.yaml}. */
  public static ClusterSpec fromClasspath(final String resource) {
    try (InputStream in =
        ClusterTopologyParser.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new GimleManifestException("no topology resource on the classpath: " + resource);
      }
      return parse(in);
    } catch (final IOException e) {
      throw new GimleManifestException("failed reading topology resource: " + resource, e);
    }
  }

  private static ClusterSpec parseRoot(final Map<?, ?> root) {
    final String name = requireString(root, "name");
    final Transport transport =
        optionalString(root, "transport").map(Transport::fromField).orElse(Transport.PLAINTEXT);
    final int storeReplicas = replicasOf(root, "store");
    final int controlPlaneReplicas = replicasOf(root, "controlPlane");
    final int fafnirReplicas = replicasOf(root, "fafnir");
    final boolean muninnEnabled = muninnEnabled(root);
    final boolean andvariEnabled = andvariEnabled(root);
    final int andvariReplicas = andvariReplicas(root);
    final List<NodeSpec> nodes = parseNodes(root);
    final boolean faultsProxied = faultsProxied(root);
    final Map<ProcessRole, List<String>> extraJvmFlags = parseJvmFlags(root);
    final SeedSpec seed = parseSeed(root);
    return new ClusterSpec(
        name,
        transport,
        storeReplicas,
        controlPlaneReplicas,
        fafnirReplicas,
        muninnEnabled,
        andvariEnabled,
        andvariReplicas,
        nodes,
        faultsProxied,
        extraJvmFlags,
        seed);
  }

  /** An absent role section means one replica -- the smallest cluster that can function at all. */
  private static int replicasOf(final Map<?, ?> root, final String section) {
    final Object value = root.get(section);
    if (value == null) {
      return 1;
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new GimleManifestException("'" + section + "' must be a mapping");
    }
    return requireInt(map, "replicas", section + ".");
  }

  private static boolean muninnEnabled(final Map<?, ?> root) {
    final Object value = root.get("muninn");
    if (value == null) {
      return false;
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new GimleManifestException("'muninn' must be a mapping");
    }
    return requireBoolean(map, "enabled", "muninn.");
  }

  private static boolean andvariEnabled(final Map<?, ?> root) {
    final Object value = root.get("andvari");
    if (value == null) {
      return false;
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new GimleManifestException("'andvari' must be a mapping");
    }
    return requireBoolean(map, "enabled", "andvari.");
  }

  /** An absent {@code andvari.replicas} means one replica -- unset stays a single-instance topology. */
  private static int andvariReplicas(final Map<?, ?> root) {
    final Object value = root.get("andvari");
    if (!(value instanceof Map<?, ?> map)) {
      return 1;
    }
    final Object replicas = map.get("replicas");
    return replicas == null ? 1 : requireInt(map, "replicas", "andvari.");
  }

  private static boolean faultsProxied(final Map<?, ?> root) {
    final Object value = root.get("faults");
    if (value == null) {
      return false;
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new GimleManifestException("'faults' must be a mapping");
    }
    return requireBoolean(map, "proxied", "faults.");
  }

  private static List<NodeSpec> parseNodes(final Map<?, ?> root) {
    final Object value = root.get("nodes");
    if (value == null) {
      return List.of();
    }
    if (value instanceof Map<?, ?> shorthand) {
      final int count = requireInt(shorthand, "count", "nodes.");
      if (count < 0) {
        throw new GimleManifestException("nodes.count must be >= 0, got " + count);
      }
      final List<NodeSpec> nodes = new ArrayList<>();
      for (int i = 1; i <= count; i++) {
        nodes.add(new NodeSpec("node-" + i, List.of()));
      }
      return nodes;
    }
    if (value instanceof List<?> entries) {
      final List<NodeSpec> nodes = new ArrayList<>();
      for (final Object entry : entries) {
        if (!(entry instanceof Map<?, ?> node)) {
          throw new GimleManifestException("each 'nodes' entry must be a mapping");
        }
        nodes.add(new NodeSpec(requireString(node, "id"), stringList(node, "labels")));
      }
      return nodes;
    }
    throw new GimleManifestException(
        "'nodes' must be either a { count: N } mapping or a list of node entries");
  }

  private static Map<ProcessRole, List<String>> parseJvmFlags(final Map<?, ?> root) {
    final Object value = root.get("jvm");
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Map<?, ?> jvm)) {
      throw new GimleManifestException("'jvm' must be a mapping");
    }
    final Map<ProcessRole, List<String>> flags = new EnumMap<>(ProcessRole.class);
    for (final Map.Entry<?, ?> entry : jvm.entrySet()) {
      if (!(entry.getKey() instanceof String roleName)) {
        throw new GimleManifestException("'jvm' keys must be role names");
      }
      flags.put(ProcessRole.fromField(roleName), stringList(jvm, roleName));
    }
    return flags;
  }

  private static SeedSpec parseSeed(final Map<?, ?> root) {
    final Object value = root.get("seed");
    if (value == null) {
      return SeedSpec.EMPTY;
    }
    if (!(value instanceof Map<?, ?> seed)) {
      throw new GimleManifestException("'seed' must be a mapping");
    }
    final List<AccountSeed> accounts = new ArrayList<>();
    for (final Map<?, ?> account : mapList(seed, "accounts")) {
      accounts.add(
          new AccountSeed(requireString(account, "username"), requireString(account, "password")));
    }
    final List<TenantSeed> tenants = new ArrayList<>();
    for (final Map<?, ?> tenant : mapList(seed, "tenants")) {
      final Map<?, ?> quota = requireMap(tenant, "quota");
      tenants.add(
          new TenantSeed(
              requireString(tenant, "id"),
              new QuotaSpec(
                  requireLong(quota, "maxMemoryBytes", "seed.tenants.quota."),
                  requireLong(quota, "maxCpuMillicores", "seed.tenants.quota."),
                  requireInt(quota, "maxInstances", "seed.tenants.quota."))));
    }
    return new SeedSpec(accounts, tenants);
  }

  private static String requireString(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException("missing or blank required field: " + key);
    }
    return s;
  }

  private static java.util.Optional<String> optionalString(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      return java.util.Optional.empty();
    }
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException("field must be a non-blank string if present: " + key);
    }
    return java.util.Optional.of(s);
  }

  private static Map<?, ?> requireMap(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (!(value instanceof Map<?, ?> m)) {
      throw new GimleManifestException("missing or malformed required section: " + key);
    }
    return m;
  }

  private static int requireInt(final Map<?, ?> map, final String key, final String prefix) {
    final Object value = map.get(key);
    if (!(value instanceof Number number)) {
      throw new GimleManifestException("missing or non-numeric required field: " + prefix + key);
    }
    return number.intValue();
  }

  private static long requireLong(final Map<?, ?> map, final String key, final String prefix) {
    final Object value = map.get(key);
    if (!(value instanceof Number number)) {
      throw new GimleManifestException("missing or non-numeric required field: " + prefix + key);
    }
    return number.longValue();
  }

  private static boolean requireBoolean(
      final Map<?, ?> map, final String key, final String prefix) {
    final Object value = map.get(key);
    if (!(value instanceof Boolean b)) {
      throw new GimleManifestException("missing or non-boolean required field: " + prefix + key);
    }
    return b;
  }

  private static List<String> stringList(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new GimleManifestException("'" + key + "' must be a list");
    }
    final List<String> strings = new ArrayList<>();
    for (final Object entry : list) {
      if (!(entry instanceof String s) || s.isBlank()) {
        throw new GimleManifestException("each '" + key + "' entry must be a non-blank string");
      }
      strings.add(s);
    }
    return strings;
  }

  private static List<Map<?, ?>> mapList(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new GimleManifestException("'" + key + "' must be a list");
    }
    final List<Map<?, ?>> maps = new ArrayList<>();
    for (final Object entry : list) {
      if (!(entry instanceof Map<?, ?> m)) {
        throw new GimleManifestException("each '" + key + "' entry must be a mapping");
      }
      maps.add(m);
    }
    return maps;
  }
}
