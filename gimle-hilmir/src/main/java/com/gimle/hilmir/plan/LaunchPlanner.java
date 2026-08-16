package com.gimle.hilmir.plan;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.hilmir.topology.AgentPlacement;
import com.gimle.hilmir.topology.Machine;
import com.gimle.hilmir.topology.ProcessRole;
import com.gimle.hilmir.topology.ServiceReplica;
import com.gimle.hilmir.topology.StoreReplica;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.Transport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link Topology} into the exact per-machine process commands the platform's own process
 * kinds expect -- generalizing {@code gimle-holmgang}'s {@code GimleCluster} boot logic (one
 * machine, loopback, dynamically leased ports) to many machines with statically declared hosts and
 * ports. A pure function of its arguments: no process spawning, no network calls -- every command
 * it returns is exact enough to assert on in a test, and exact enough for a launcher to spawn
 * largely as-is (see {@link ProcessCommand}'s own javadoc for the two steps a launcher still owns
 * before actually spawning one). The one deliberate, narrow exception: when {@code
 * topology.runtime().useBundledJre()} is {@code true}, resolving STORE/MUNINN/ANDVARI/FAFNIR/
 * CONTROL_PLANE's own {@code java} executable does check for a file's existence under {@code
 * GIMLE_HOME} (see {@link BundledJreResolver}) -- {@code GIMLE_HOME} itself is still an explicit
 * argument to this class, never read via {@code System.getenv} internally, for exactly the same
 * testability reason {@link ResolvedRuntime}'s own javadoc gives for keeping default-resolution out
 * of this class: the 2-arg {@link #plan(Topology, ResolvedRuntime)} overload is the real entry
 * point (it reads {@code GIMLE_HOME} once, for real callers), and the 3-arg overload below exists
 * so a test can supply a fake value directly instead of mutating the JVM's real environment. {@code
 * AGENT} (and the {@code WORKER} command tail it appends) never receives a {@code gimleHomeEnv} at
 * all -- {@link #planAgents} keeps its original 2-arg signature, so it is structurally incapable of
 * resolving a bundled JRE, not merely conventionally excluded from doing so.
 *
 * <p>Global boot order -- stores, then Muninn, then Andvari, then Fafnir, then control planes, then
 * agents -- mirrors {@code GimleCluster.boot()} exactly (each later stage's command line references
 * an earlier stage's already-known address, e.g. every consumer's {@code --store-endpoints}), and
 * each {@link MachinePlan#commands()} preserves that same relative order, filtered down to just the
 * processes placed on that one machine.
 *
 * <p>{@link #plan} does not itself validate {@code topology} -- it assumes a topology with no
 * {@code com.gimle.hilmir.validate.Severity#ERROR} finding (a caller runs {@code
 * TopologyValidator.validate} and aborts on error first, exactly as {@code hilmir plan} does) and
 * fails with a plain {@link GimleManifestException} on the (validator-would-have-caught) shapes
 * that would otherwise make a command meaningless to build, such as a replica referencing a machine
 * absent from {@code topology.machines()}.
 */
public final class LaunchPlanner {

  private LaunchPlanner() {}

  public static ClusterPlan plan(final Topology topology, final ResolvedRuntime runtime) {
    return plan(topology, runtime, System.getenv("GIMLE_HOME"));
  }

  /**
   * Split out from {@link #plan(Topology, ResolvedRuntime)} so a test can supply a fake {@code
   * GIMLE_HOME} directly, the same shape {@link BundledJreResolver}'s own package-private overload
   * (and {@code com.gimle.hilmir.extension.GatewayJarLocator}'s {@code resolveModulesDir}) already
   * established for the identical problem.
   */
  static ClusterPlan plan(
      final Topology topology, final ResolvedRuntime runtime, final String gimleHomeEnv) {
    final List<ProcessCommand> commands = new ArrayList<>();
    commands.addAll(planStores(topology, runtime, gimleHomeEnv));
    commands.addAll(planMuninn(topology, runtime, gimleHomeEnv));
    commands.addAll(planAndvari(topology, runtime, gimleHomeEnv));
    commands.addAll(planFafnirs(topology, runtime, gimleHomeEnv));
    commands.addAll(planControlPlanes(topology, runtime, gimleHomeEnv));
    commands.addAll(planAgents(topology, runtime));

    final Map<String, List<ProcessCommand>> byMachine = new LinkedHashMap<>();
    for (final Machine machine : topology.machines()) {
      byMachine.put(machine.name(), new ArrayList<>());
    }
    for (final ProcessCommand command : commands) {
      byMachine.computeIfAbsent(command.machine(), k -> new ArrayList<>()).add(command);
    }
    final Map<String, MachinePlan> plans = new LinkedHashMap<>();
    byMachine.forEach(
        (machine, machineCommands) ->
            plans.put(machine, new MachinePlan(machine, machineCommands)));
    return new ClusterPlan(plans);
  }

  private static List<ProcessCommand> planStores(
      final Topology topology, final ResolvedRuntime runtime, final String gimleHomeEnv) {
    final List<StoreReplica> replicas = topology.store().replicas();
    final List<ProcessCommand> out = new ArrayList<>();
    for (int i = 0; i < replicas.size(); i++) {
      final StoreReplica replica = replicas.get(i);
      final String id = "store-" + i;
      final Path dataDir = runtime.dataRoot().resolve(id);
      final List<String> command = new ArrayList<>();
      command.add(
          BundledJreResolver.resolveJavaExecutable(topology, runtime, "mimir", gimleHomeEnv));
      // No per-store leaf exists in the platform's own generated material; the store presents the
      // control-plane certificate, the same reuse GimleCluster's own tlsFlags already established.
      command.addAll(tlsFlags(topology, "controlplane"));
      command.addAll(topology.jvmFlags(ProcessRole.STORE));
      if (!topology.muninn().replicas().isEmpty()) {
        command.add("-Dgimle.store.muninnEndpoint=" + muninnEndpointsSpec(topology));
      }
      command.add(dataRootFlag(dataDir));
      command.add(logRootFlag(runtime, id));
      command.addAll(List.of("-cp", runtime.classpath(), "com.gimle.mimir.StoreMain"));
      command.add(dataDir.toString());
      command.add(String.valueOf(replica.raftPort()));
      command.add(String.valueOf(replica.clientPort()));
      command.addAll(List.of("--host", hostOf(topology, replica.machine())));
      final String peers = storePeersExcluding(topology, i);
      if (!peers.isEmpty()) {
        command.addAll(List.of("--peers", peers));
      }
      out.add(
          new ProcessCommand(
              ProcessRole.STORE,
              id,
              replica.machine(),
              command,
              id + ".log",
              dataDir,
              hostOf(topology, replica.machine()) + ":" + replica.clientPort(),
              false));
    }
    return out;
  }

  private static List<ProcessCommand> planMuninn(
      final Topology topology, final ResolvedRuntime runtime, final String gimleHomeEnv) {
    final List<ServiceReplica> replicas = topology.muninn().replicas();
    final List<ProcessCommand> out = new ArrayList<>();
    for (int i = 0; i < replicas.size(); i++) {
      final ServiceReplica replica = replicas.get(i);
      final String id = "muninn-" + i;
      final Path dataDir = runtime.dataRoot().resolve(id);
      final List<String> command = new ArrayList<>();
      command.add(
          BundledJreResolver.resolveJavaExecutable(topology, runtime, "muninn", gimleHomeEnv));
      command.addAll(tlsFlags(topology, "muninn"));
      command.addAll(topology.jvmFlags(ProcessRole.MUNINN));
      command.add(dataRootFlag(dataDir));
      command.add(logRootFlag(runtime, id));
      command.addAll(List.of("-cp", runtime.classpath(), "com.gimle.muninn.MuninnMain"));
      command.add(String.valueOf(replica.port()));
      command.addAll(List.of("--host", hostOf(topology, replica.machine())));
      command.addAll(List.of("--store-endpoints", storeEndpointsSpec(topology)));
      command.addAll(List.of("--data-root", dataDir.toString()));
      out.add(
          new ProcessCommand(
              ProcessRole.MUNINN,
              id,
              replica.machine(),
              command,
              id + ".log",
              dataDir,
              hostOf(topology, replica.machine()) + ":" + replica.port(),
              false));
    }
    return out;
  }

  private static List<ProcessCommand> planAndvari(
      final Topology topology, final ResolvedRuntime runtime, final String gimleHomeEnv) {
    final List<ServiceReplica> replicas = topology.andvari().replicas();
    final List<ProcessCommand> out = new ArrayList<>();
    for (int i = 0; i < replicas.size(); i++) {
      final ServiceReplica replica = replicas.get(i);
      final String id = "andvari-" + i;
      final Path dataDir = runtime.dataRoot().resolve(id);
      final List<String> command = new ArrayList<>();
      command.add(
          BundledJreResolver.resolveJavaExecutable(topology, runtime, "andvari", gimleHomeEnv));
      command.addAll(tlsFlags(topology, "andvari"));
      command.addAll(topology.jvmFlags(ProcessRole.ANDVARI));
      command.add(dataRootFlag(dataDir));
      command.add(logRootFlag(runtime, id));
      command.addAll(List.of("-cp", runtime.classpath(), "com.gimle.andvari.AndvariMain"));
      command.add(String.valueOf(replica.port()));
      command.addAll(List.of("--host", hostOf(topology, replica.machine())));
      command.addAll(List.of("--store-endpoints", storeEndpointsSpec(topology)));
      command.addAll(List.of("--data-root", dataDir.toString()));
      final String peers = andvariPeersExcluding(topology, i);
      if (!peers.isEmpty()) {
        command.addAll(List.of("--peer-endpoints", peers));
      }
      out.add(
          new ProcessCommand(
              ProcessRole.ANDVARI,
              id,
              replica.machine(),
              command,
              id + ".log",
              dataDir,
              hostOf(topology, replica.machine()) + ":" + replica.port(),
              false));
    }
    return out;
  }

  private static List<ProcessCommand> planFafnirs(
      final Topology topology, final ResolvedRuntime runtime, final String gimleHomeEnv) {
    final List<ServiceReplica> replicas = topology.fafnir().replicas();
    final List<ProcessCommand> out = new ArrayList<>();
    for (int i = 0; i < replicas.size(); i++) {
      final ServiceReplica replica = replicas.get(i);
      final String id = "fafnir-" + i;
      final Path dataDir = runtime.dataRoot().resolve(id);
      final Path keyFile =
          topology
              .fafnir()
              .keyFile()
              .orElseThrow(
                  () ->
                      new GimleManifestException(
                          "fafnir.replicas is non-empty but fafnir.keyFile is unset"));
      final List<String> command = new ArrayList<>();
      command.add(
          BundledJreResolver.resolveJavaExecutable(topology, runtime, "fafnir", gimleHomeEnv));
      command.addAll(tlsFlags(topology, "fafnir"));
      command.addAll(topology.jvmFlags(ProcessRole.FAFNIR));
      if (!topology.muninn().replicas().isEmpty()) {
        command.add("-Dgimle.fafnir.muninnEndpoint=" + muninnEndpointsSpec(topology));
      }
      command.add(dataRootFlag(dataDir));
      command.add(logRootFlag(runtime, id));
      command.addAll(List.of("-cp", runtime.classpath(), "com.gimle.fafnir.FafnirMain"));
      command.add(String.valueOf(replica.port()));
      command.add(keyFile.toString());
      command.addAll(List.of("--host", hostOf(topology, replica.machine())));
      command.addAll(List.of("--store-endpoints", storeEndpointsSpec(topology)));
      out.add(
          new ProcessCommand(
              ProcessRole.FAFNIR,
              id,
              replica.machine(),
              command,
              id + ".log",
              dataDir,
              hostOf(topology, replica.machine()) + ":" + replica.port(),
              false));
    }
    return out;
  }

  private static List<ProcessCommand> planControlPlanes(
      final Topology topology, final ResolvedRuntime runtime, final String gimleHomeEnv) {
    final List<ServiceReplica> replicas = topology.controlPlane().replicas();
    final List<ProcessCommand> out = new ArrayList<>();
    for (int i = 0; i < replicas.size(); i++) {
      final ServiceReplica replica = replicas.get(i);
      final String id = "controlplane-" + i;
      final Path dataDir = runtime.dataRoot().resolve(id);
      final List<String> command = new ArrayList<>();
      command.add(
          BundledJreResolver.resolveJavaExecutable(
              topology, runtime, "controlplane", gimleHomeEnv));
      command.addAll(tlsFlags(topology, "controlplane"));
      if (topology.transport() == Transport.MTLS) {
        // The CA key enables /bootstrap/csr and /bootstrap/tokens -- agents certificate-bootstrap
        // through this control plane.
        command.add("-Dgimle.pki.caKeyFile=" + materialDir(topology).resolve("ca.key"));
      }
      command.addAll(topology.jvmFlags(ProcessRole.CONTROL_PLANE));
      command.add(dataRootFlag(dataDir));
      command.add(logRootFlag(runtime, id));
      command.addAll(
          List.of("-cp", runtime.classpath(), "com.gimle.controlplane.ControlPlaneMain"));
      command.add(String.valueOf(replica.port()));
      command.add(runtime.dataRoot().resolve(id + "-secret.key").toString());
      command.addAll(List.of("--host", hostOf(topology, replica.machine())));
      command.addAll(List.of("--store-endpoints", storeEndpointsSpec(topology)));
      command.addAll(List.of("--fafnir-endpoint", fafnirEndpointAt(topology, i)));
      if (!topology.muninn().replicas().isEmpty()) {
        command.addAll(List.of("--muninn-endpoint", muninnEndpointsSpec(topology)));
      }
      if (!topology.andvari().replicas().isEmpty()) {
        command.addAll(List.of("--andvari-endpoint", andvariEndpointsSpec(topology)));
      }
      out.add(
          new ProcessCommand(
              ProcessRole.CONTROL_PLANE,
              id,
              replica.machine(),
              command,
              id + ".log",
              dataDir,
              hostOf(topology, replica.machine()) + ":" + replica.port(),
              false));
    }
    return out;
  }

  private static List<ProcessCommand> planAgents(
      final Topology topology, final ResolvedRuntime runtime) {
    final List<AgentPlacement> agents = topology.agents();
    final List<ProcessCommand> out = new ArrayList<>();
    // Every later agent seeds off the first agent's own gossip address; the first agent seeds
    // nothing ("-"), matching GimleCluster.startAgents's own logic, generalized off a single
    // loopback host() constant there to each agent's own machine's host here.
    final String firstGossipAddress =
        agents.isEmpty()
            ? null
            : hostOf(topology, agents.get(0).machine()) + ":" + agents.get(0).gossipPort();
    final boolean mtls = topology.transport() == Transport.MTLS;
    for (int i = 0; i < agents.size(); i++) {
      final AgentPlacement agent = agents.get(i);
      final String id = "agent-" + agent.nodeId();
      final Path dataDir = runtime.dataRoot().resolve(id);
      final String gossipAddress = hostOf(topology, agent.machine()) + ":" + agent.gossipPort();
      final String seeds = i == 0 ? "-" : firstGossipAddress;
      final List<String> command = new ArrayList<>();
      command.add(runtime.javaExecutable());
      // The agent's own leaf does not exist yet -- it generates a keypair/CSR against these paths
      // and bootstraps its certificate through the control plane at first launch, gated by a
      // bootstrap token the launcher (not this planner) mints and appends.
      command.addAll(tlsFlags(topology, "node-" + agent.nodeId()));
      command.addAll(topology.jvmFlags(ProcessRole.AGENT));
      command.add("-Dgimle.agent.fafnirEndpoint=" + fafnirEndpointAt(topology, i));
      if (!topology.muninn().replicas().isEmpty()) {
        command.add("-Dgimle.agent.muninnEndpoint=" + muninnEndpointsSpec(topology));
      }
      if (!topology.andvari().replicas().isEmpty()) {
        command.add("-Dgimle.agent.andvariEndpoint=" + andvariEndpointsSpec(topology));
      }
      command.add(dataRootFlag(dataDir));
      command.add(logRootFlag(runtime, id));
      if (!agent.labels().isEmpty()) {
        command.add("-Dgimle.node.labels=" + String.join(",", agent.labels()));
      }
      command.addAll(List.of("-cp", runtime.classpath(), "com.gimle.agent.AgentMain"));
      command.add(agent.nodeId());
      command.add(controlPlaneBaseUrl(topology, 0));
      command.add(gossipAddress);
      command.add(seeds);
      // The worker command tail: AgentMain appends each spawned worker's own instance-specific
      // positional arguments (nodeId, tenant, control-socket path) itself at spawn time, so only
      // the java invocation prefix belongs here.
      command.add(runtime.javaExecutable());
      command.addAll(topology.jvmFlags(ProcessRole.WORKER));
      command.addAll(List.of("-cp", runtime.classpath(), "com.gimle.worker.WorkerMain"));
      out.add(
          new ProcessCommand(
              ProcessRole.AGENT,
              id,
              agent.machine(),
              command,
              id + ".log",
              dataDir,
              gossipAddress,
              mtls));
    }
    return out;
  }

  /** The four flags every mtls-mode process gets, presenting {@code certName}'s leaf. */
  private static List<String> tlsFlags(final Topology topology, final String certName) {
    if (topology.transport() != Transport.MTLS) {
      return List.of();
    }
    final Path dir = materialDir(topology);
    return List.of(
        "-Dgimle.transport.protocol=tls",
        "-Dgimle.tls.certFile=" + dir.resolve(certName + ".crt"),
        "-Dgimle.tls.keyFile=" + dir.resolve(certName + ".key"),
        "-Dgimle.tls.caFile=" + dir.resolve("ca.crt"));
  }

  private static Path materialDir(final Topology topology) {
    return topology
        .tls()
        .orElseThrow(() -> new GimleManifestException("mtls transport requires tls.materialDir"))
        .materialDir();
  }

  /**
   * Every process gets its own {@code -Dgimle.data.root}/{@code -Dgimle.log.root}, never shared: an
   * unscoped root causes real cross-run and cross-process corruption (a control plane's own {@code
   * ArtifactPullCache} caching jars by presence alone across a stale prior run being the sharpest
   * example), the same reasoning {@code GimleCluster}'s own {@code startControlPlanes}/ {@code
   * startAgents} document -- generalized here to every process kind, not only the two that happened
   * to need it in a loopback-only test harness.
   */
  private static String dataRootFlag(final Path dataDir) {
    return "-Dgimle.data.root=" + dataDir;
  }

  private static String logRootFlag(final ResolvedRuntime runtime, final String id) {
    return "-Dgimle.log.root=" + runtime.dataRoot().resolve(id + "-logs");
  }

  private static String hostOf(final Topology topology, final String machineName) {
    return topology.machines().stream()
        .filter(m -> m.name().equals(machineName))
        .map(Machine::host)
        .findFirst()
        .orElseThrow(
            () -> new GimleManifestException("no machine named '" + machineName + "' in topology"));
  }

  private static String scheme(final Topology topology) {
    return topology.transport() == Transport.MTLS ? "https" : "http";
  }

  private static String controlPlaneBaseUrl(final Topology topology, final int index) {
    final ServiceReplica replica = topology.controlPlane().replicas().get(index);
    return scheme(topology) + "://" + hostOf(topology, replica.machine()) + ":" + replica.port();
  }

  private static String storeEndpointsSpec(final Topology topology) {
    final List<String> endpoints = new ArrayList<>();
    for (final StoreReplica replica : topology.store().replicas()) {
      endpoints.add(hostOf(topology, replica.machine()) + ":" + replica.clientPort());
    }
    return String.join(",", endpoints);
  }

  /** Every *other* store replica's {@code host:raftPort:clientPort}, never empty for >1 replica. */
  private static String storePeersExcluding(final Topology topology, final int excludeIndex) {
    final List<StoreReplica> replicas = topology.store().replicas();
    final List<String> peers = new ArrayList<>();
    for (int i = 0; i < replicas.size(); i++) {
      if (i == excludeIndex) {
        continue;
      }
      final StoreReplica replica = replicas.get(i);
      peers.add(
          hostOf(topology, replica.machine())
              + ":"
              + replica.raftPort()
              + ":"
              + replica.clientPort());
    }
    return String.join(",", peers);
  }

  private static String muninnEndpointsSpec(final Topology topology) {
    final List<String> endpoints = new ArrayList<>();
    for (final ServiceReplica replica : topology.muninn().replicas()) {
      endpoints.add(hostOf(topology, replica.machine()) + ":" + replica.port());
    }
    return String.join(",", endpoints);
  }

  private static String andvariEndpointsSpec(final Topology topology) {
    final List<String> endpoints = new ArrayList<>();
    for (final ServiceReplica replica : topology.andvari().replicas()) {
      endpoints.add(hostOf(topology, replica.machine()) + ":" + replica.port());
    }
    return String.join(",", endpoints);
  }

  private static String andvariPeersExcluding(final Topology topology, final int excludeIndex) {
    final List<ServiceReplica> replicas = topology.andvari().replicas();
    final List<String> peers = new ArrayList<>();
    for (int i = 0; i < replicas.size(); i++) {
      if (i == excludeIndex) {
        continue;
      }
      final ServiceReplica replica = replicas.get(i);
      peers.add(hostOf(topology, replica.machine()) + ":" + replica.port());
    }
    return String.join(",", peers);
  }

  /**
   * Round-robins consumer {@code index} across the fafnir replicas -- {@code FafnirClient} talks to
   * exactly one address, so this is what exercises every replica rather than just replica 0.
   */
  private static String fafnirEndpointAt(final Topology topology, final int index) {
    final List<ServiceReplica> replicas = topology.fafnir().replicas();
    if (replicas.isEmpty()) {
      throw new GimleManifestException(
          "cannot plan: no fafnir replicas declared, but every fafnir consumer requires one");
    }
    final ServiceReplica replica = replicas.get(index % replicas.size());
    return hostOf(topology, replica.machine()) + ":" + replica.port();
  }
}
