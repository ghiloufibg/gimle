package com.gimle.hilmir.topology;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.hilmir.HilmirException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses a topology document into a {@link Topology}. Follows the same shape as {@code
 * gimle-mimir}'s manifest parsers and {@code gimle-holmgang}'s own {@code ClusterTopologyParser}:
 * SnakeYAML restricted to plain maps/lists/scalars via {@code SafeConstructor}, hand-validated
 * fields, and {@link GimleManifestException} for every rejection.
 *
 * <p>Unlike {@code ClusterTopologyParser}, every mapping in this document -- the root and every
 * section and list-entry mapping within it -- is checked for unknown keys and rejected outright if
 * it has any. A topology is hand-authored operator input meant to run unattended machines; a typo'd
 * key silently ignored here would otherwise silently produce a plan that doesn't do what the
 * operator asked, discovered only much later. Structural checks (is this field a number, is this
 * section a mapping, is this key recognized) live here; semantic rules (replica minimums, port
 * conflicts, TLS reachability) live in {@code com.gimle.hilmir.validate.TopologyValidator} instead,
 * so a topology with a semantic problem still parses and can be inspected in full.
 */
public final class TopologyParser {

  private static final int DEFAULT_STORE_RAFT_PORT = 9080;
  private static final int DEFAULT_STORE_CLIENT_PORT = 9091;
  private static final int DEFAULT_CONTROL_PLANE_PORT = 8080;
  private static final int DEFAULT_FAFNIR_PORT = 9092;
  private static final int DEFAULT_MUNINN_PORT = 9093;
  private static final int DEFAULT_ANDVARI_PORT = 9094;
  private static final int DEFAULT_GOSSIP_PORT = 9090;

  private static final Set<String> TOP_LEVEL_KEYS =
      Set.of(
          "name",
          "transport",
          "tls",
          "machines",
          "runtime",
          "store",
          "controlPlane",
          "fafnir",
          "muninn",
          "andvari",
          "agents",
          "jvm");

  private TopologyParser() {}

  /**
   * Reads and parses the topology document at {@code file}. Every verb that takes a topology path
   * loads it through here, so an absent or unreadable file reads identically whichever verb hit it.
   * A missing file gets its own message rather than the generic one: {@link NoSuchFileException}'s
   * own message is the bare path, which appended to a message already naming the path would print
   * it twice and say nothing about what actually went wrong.
   */
  public static Topology parseFile(final Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      return parse(in);
    } catch (final NoSuchFileException e) {
      throw new HilmirException("topology file not found: " + file, e);
    } catch (final IOException e) {
      throw new HilmirException("failed reading topology file " + file + ": " + e.getMessage(), e);
    }
  }

  public static Topology parse(final InputStream yamlContent) {
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

  private static Topology parseRoot(final Map<?, ?> root) {
    requireNoUnknownKeys(root, TOP_LEVEL_KEYS, "topology");
    final String name = requireString(root, "name");
    final Transport transport =
        optionalString(root, "transport").map(Transport::fromField).orElse(Transport.PLAINTEXT);
    final Optional<TlsMaterial> tls = parseTls(root);
    final List<Machine> machines = parseMachines(root);
    final RuntimeSettings runtime = parseRuntime(root);
    final StoreRole store = new StoreRole(parseStoreReplicas(root));
    final ControlPlaneRole controlPlane =
        new ControlPlaneRole(
            parseServiceReplicas(root, "controlPlane", DEFAULT_CONTROL_PLANE_PORT));
    final FafnirRole fafnir = parseFafnir(root);
    final MuninnRole muninn =
        new MuninnRole(parseServiceReplicas(root, "muninn", DEFAULT_MUNINN_PORT));
    final AndvariRole andvari =
        new AndvariRole(parseServiceReplicas(root, "andvari", DEFAULT_ANDVARI_PORT));
    final List<AgentPlacement> agents = parseAgents(root);
    final Map<ProcessRole, List<String>> jvmFlags = parseJvmFlags(root);
    return new Topology(
        name,
        transport,
        tls,
        machines,
        runtime,
        store,
        controlPlane,
        fafnir,
        muninn,
        andvari,
        agents,
        jvmFlags);
  }

  private static Optional<TlsMaterial> parseTls(final Map<?, ?> root) {
    final Object value = root.get("tls");
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new GimleManifestException("'tls' must be a mapping");
    }
    requireNoUnknownKeys(map, Set.of("materialDir"), "tls");
    return Optional.of(new TlsMaterial(Path.of(requireString(map, "materialDir"))));
  }

  private static List<Machine> parseMachines(final Map<?, ?> root) {
    final Object value = root.get("machines");
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> entries)) {
      throw new GimleManifestException("'machines' must be a list");
    }
    final List<Machine> machines = new ArrayList<>();
    for (final Object entry : entries) {
      if (!(entry instanceof Map<?, ?> machine)) {
        throw new GimleManifestException("each 'machines' entry must be a mapping");
      }
      requireNoUnknownKeys(
          machine, Set.of("name", "host", "sshHostKeyFingerprint", "ssh"), "machines[]");
      machines.add(
          new Machine(
              requireString(machine, "name"),
              requireString(machine, "host"),
              optionalString(machine, "sshHostKeyFingerprint"),
              parseSsh(machine, "machines[].ssh")));
    }
    return machines;
  }

  private static RuntimeSettings parseRuntime(final Map<?, ?> root) {
    final Object value = root.get("runtime");
    if (value == null) {
      return RuntimeSettings.EMPTY;
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new GimleManifestException("'runtime' must be a mapping");
    }
    requireNoUnknownKeys(
        map, Set.of("javaExecutable", "classpath", "dataRoot", "useBundledJre", "ssh"), "runtime");
    return new RuntimeSettings(
        optionalString(map, "javaExecutable"),
        optionalString(map, "classpath"),
        optionalString(map, "dataRoot").map(Path::of),
        optionalBoolean(map, "useBundledJre", false),
        parseSsh(map, "runtime.ssh"));
  }

  /**
   * The {@code ssh: { user, port, identityFile, installDir, archive }} block shared by both {@code
   * machines[]} (a per-machine override) and {@code runtime} (a topology-wide default) -- see
   * {@link SshSettings}.
   */
  private static Optional<SshSettings> parseSsh(final Map<?, ?> parent, final String context) {
    final Object value = parent.get("ssh");
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new GimleManifestException("'" + context + "' must be a mapping");
    }
    requireNoUnknownKeys(
        map, Set.of("user", "port", "identityFile", "installDir", "archive"), context);
    return Optional.of(
        new SshSettings(
            optionalString(map, "user"),
            optionalInt(map, "port"),
            optionalString(map, "identityFile"),
            optionalString(map, "installDir"),
            optionalString(map, "archive")));
  }

  private static List<StoreReplica> parseStoreReplicas(final Map<?, ?> root) {
    final Object value = root.get("store");
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new GimleManifestException("'store' must be a mapping");
    }
    requireNoUnknownKeys(map, Set.of("replicas"), "store");
    final Object replicasValue = map.get("replicas");
    if (replicasValue == null) {
      return List.of();
    }
    if (!(replicasValue instanceof List<?> entries)) {
      throw new GimleManifestException("'store.replicas' must be a list");
    }
    final List<StoreReplica> replicas = new ArrayList<>();
    for (final Object entry : entries) {
      if (!(entry instanceof Map<?, ?> replica)) {
        throw new GimleManifestException("each 'store.replicas' entry must be a mapping");
      }
      requireNoUnknownKeys(
          replica, Set.of("machine", "raftPort", "clientPort"), "store.replicas[]");
      final String machine = requireString(replica, "machine");
      final int raftPort = optionalInt(replica, "raftPort").orElse(DEFAULT_STORE_RAFT_PORT);
      final int clientPort = optionalInt(replica, "clientPort").orElse(DEFAULT_STORE_CLIENT_PORT);
      replicas.add(new StoreReplica(machine, raftPort, clientPort));
    }
    return replicas;
  }

  /** The {@code { replicas: [{ machine, port }] } } shape shared by controlPlane/muninn/andvari. */
  private static List<ServiceReplica> parseServiceReplicas(
      final Map<?, ?> root, final String sectionKey, final int defaultPort) {
    final Object value = root.get(sectionKey);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new GimleManifestException("'" + sectionKey + "' must be a mapping");
    }
    requireNoUnknownKeys(map, Set.of("replicas"), sectionKey);
    return parseReplicasList(map.get("replicas"), sectionKey, defaultPort);
  }

  private static List<ServiceReplica> parseReplicasList(
      final Object replicasValue, final String sectionKey, final int defaultPort) {
    if (replicasValue == null) {
      return List.of();
    }
    if (!(replicasValue instanceof List<?> entries)) {
      throw new GimleManifestException("'" + sectionKey + ".replicas' must be a list");
    }
    final List<ServiceReplica> replicas = new ArrayList<>();
    for (final Object entry : entries) {
      if (!(entry instanceof Map<?, ?> replica)) {
        throw new GimleManifestException(
            "each '" + sectionKey + ".replicas' entry must be a mapping");
      }
      requireNoUnknownKeys(replica, Set.of("machine", "port"), sectionKey + ".replicas[]");
      final String machine = requireString(replica, "machine");
      final int port = optionalInt(replica, "port").orElse(defaultPort);
      replicas.add(new ServiceReplica(machine, port));
    }
    return replicas;
  }

  private static FafnirRole parseFafnir(final Map<?, ?> root) {
    final Object value = root.get("fafnir");
    if (value == null) {
      return new FafnirRole(Optional.empty(), List.of());
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new GimleManifestException("'fafnir' must be a mapping");
    }
    requireNoUnknownKeys(map, Set.of("keyFile", "replicas"), "fafnir");
    final Optional<Path> keyFile = optionalString(map, "keyFile").map(Path::of);
    final List<ServiceReplica> replicas =
        parseReplicasList(map.get("replicas"), "fafnir", DEFAULT_FAFNIR_PORT);
    return new FafnirRole(keyFile, replicas);
  }

  private static List<AgentPlacement> parseAgents(final Map<?, ?> root) {
    final Object value = root.get("agents");
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> entries)) {
      throw new GimleManifestException("'agents' must be a list");
    }
    final List<AgentPlacement> agents = new ArrayList<>();
    for (final Object entry : entries) {
      if (!(entry instanceof Map<?, ?> agent)) {
        throw new GimleManifestException("each 'agents' entry must be a mapping");
      }
      requireNoUnknownKeys(agent, Set.of("machine", "nodeId", "gossipPort", "labels"), "agents[]");
      final String machine = requireString(agent, "machine");
      final String nodeId = requireString(agent, "nodeId");
      final int gossipPort = optionalInt(agent, "gossipPort").orElse(DEFAULT_GOSSIP_PORT);
      agents.add(new AgentPlacement(machine, nodeId, gossipPort, stringList(agent, "labels")));
    }
    return agents;
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

  /** Rejects any key in {@code map} outside {@code allowed} -- see the class javadoc for why. */
  private static void requireNoUnknownKeys(
      final Map<?, ?> map, final Set<String> allowed, final String context) {
    for (final Object key : map.keySet()) {
      if (!(key instanceof String s) || !allowed.contains(s)) {
        throw new GimleManifestException("unknown field in '" + context + "': " + key);
      }
    }
  }

  private static String requireString(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException("missing or blank required field: " + key);
    }
    return s;
  }

  private static Optional<String> optionalString(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof String s) || s.isBlank()) {
      throw new GimleManifestException("field must be a non-blank string if present: " + key);
    }
    return Optional.of(s);
  }

  private static Optional<Integer> optionalInt(final Map<?, ?> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Number number)) {
      throw new GimleManifestException("field must be numeric if present: " + key);
    }
    return Optional.of(number.intValue());
  }

  private static boolean optionalBoolean(
      final Map<?, ?> map, final String key, final boolean defaultValue) {
    final Object value = map.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Boolean b)) {
      throw new GimleManifestException("field must be a boolean if present: " + key);
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
}
