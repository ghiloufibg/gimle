package com.gimle.holmgang.cluster;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.heimdall.Heimdall;
import com.gimle.holmgang.heimdall.HeimdallScope;
import com.gimle.holmgang.heimdall.Invariant;
import com.gimle.holmgang.heimdall.InvariantGuard;
import com.gimle.holmgang.topology.AccountSeed;
import com.gimle.holmgang.topology.ClusterSpec;
import com.gimle.holmgang.topology.NodeSpec;
import com.gimle.holmgang.topology.ProcessRole;
import com.gimle.holmgang.topology.TenantSeed;
import com.gimle.holmgang.topology.Transport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Boots a real multi-process Gimle cluster from a {@link ClusterSpec}: one JVM per process, spawned
 * with the same mechanics the platform's own launchers use, on dynamically leased loopback ports.
 * Startup follows the dependency order stores, then Muninn, then Fafnir, then control planes, then
 * agents, awaiting each stage's real readiness signal (a listening client port; {@code
 * /deployments} responding; the node appearing in {@code /nodes}, which the agent only registers
 * after its gossip join completed) before starting the stage that depends on it.
 *
 * <p>Every spawned process gets the test JVM's own classpath, so this module must test-depend on
 * every process kind's artifact. The working directory is caller-owned and never deleted here --
 * process logs are the primary forensic artifact when a scenario fails.
 */
public final class GimleCluster implements AutoCloseable {

  private static final String LOOPBACK = "127.0.0.1";
  private static final Duration STAGE_TIMEOUT = Duration.ofSeconds(60);

  private final ClusterSpec spec;
  private final Path workDir;
  private final List<ManagedProcess> spawnOrder = new ArrayList<>();
  private final List<ManagedProcess> stores = new ArrayList<>();
  private final List<ManagedProcess> controlPlanes = new ArrayList<>();
  private final List<ManagedProcess> fafnirs = new ArrayList<>();
  private final Map<String, ManagedProcess> agents = new LinkedHashMap<>();
  private final Map<Integer, ClusterApi> apis = new LinkedHashMap<>();
  private Heimdall heimdall;
  private ManagedProcess muninn;

  private GimleCluster(final ClusterSpec spec, final Path workDir) {
    this.spec = spec;
    this.workDir = workDir;
  }

  public static GimleCluster start(final ClusterSpec spec, final Path workDir) {
    if (spec.transport() == Transport.MTLS) {
      throw new HolmgangException(
          "mTLS topologies are not implemented yet: only plaintext clusters can start");
    }
    if (spec.faultsProxied()) {
      throw new HolmgangException(
          "fault-proxied topologies are not implemented yet: remove faults.proxied to start");
    }
    final GimleCluster cluster = new GimleCluster(spec, workDir);
    try {
      cluster.boot();
    } catch (final RuntimeException e) {
      cluster.close();
      throw e;
    }
    return cluster;
  }

  public GimleProcess store(final int index) {
    return stores.get(index);
  }

  public GimleProcess controlPlane(final int index) {
    return controlPlanes.get(index);
  }

  public GimleProcess fafnir(final int index) {
    return fafnirs.get(index);
  }

  public GimleProcess muninn() {
    if (muninn == null) {
      throw new HolmgangException("topology " + spec.name() + " does not enable Muninn");
    }
    return muninn;
  }

  public GimleProcess agent(final String nodeId) {
    final ManagedProcess agent = agents.get(nodeId);
    if (agent == null) {
      throw new HolmgangException("topology " + spec.name() + " has no node " + nodeId);
    }
    return agent;
  }

  public String controlPlaneBaseUrl(final int index) {
    return "http://" + controlPlanes.get(index).endpoint();
  }

  public ClusterApi api() {
    return api(0);
  }

  public ClusterApi api(final int controlPlaneIndex) {
    return apis.computeIfAbsent(
        controlPlaneIndex, index -> new ClusterApi(controlPlaneBaseUrl(index)));
  }

  public List<String> controlPlaneBaseUrls() {
    final List<String> urls = new ArrayList<>();
    for (int i = 0; i < controlPlanes.size(); i++) {
      urls.add(controlPlaneBaseUrl(i));
    }
    return urls;
  }

  public List<GimleProcess> processes() {
    return List.copyOf(spawnOrder);
  }

  public Path workDir() {
    return workDir;
  }

  /** Conditions completed by a satisfying view observed through any control-plane replica. */
  public HeimdallScope when() {
    return heimdall().scope(java.util.OptionalInt.empty());
  }

  /**
   * Conditions completed only by views fetched through one specific control-plane replica -- the
   * deterministic way to assert state written through one replica is observable through another.
   */
  public HeimdallScope when(final int controlPlaneIndex) {
    return heimdall().scope(java.util.OptionalInt.of(controlPlaneIndex));
  }

  /** Holds {@code invariant} over every observed view until the returned guard is closed. */
  public InvariantGuard holdInvariant(final Invariant invariant) {
    return heimdall().hold(invariant);
  }

  /**
   * Holds {@code invariant} for a fixed window and throws its forensic report if any view within
   * the window violated it -- the bounded form of a negative assertion ("stays unplaced", "is never
   * evicted"), which cannot complete early the way a positive condition can.
   */
  public void holdInvariantFor(final Invariant invariant, final Duration window) {
    heimdall().holdFor(invariant, window);
  }

  /**
   * The live worker JVM currently hosting one instance, found the same way the platform itself
   * can't tell you: by scanning the supervising agents' process descendants for the worker-only
   * {@code -Dgimle.log.root=.../workers/<deployment>#<index>} flag stamped at spawn time. The
   * trailing WorkerMain main-class argument is deliberately not matched -- {@code
   * ProcessHandle.Info#commandLine()} truncates this repo's long classpaths before reaching it.
   */
  public Optional<ProcessHandle> workerFor(final String deploymentName, final int instanceIndex) {
    final String key = deploymentName + "#" + instanceIndex;
    for (final ManagedProcess agent : agents.values()) {
      final Optional<ProcessHandle> worker =
          agent
              .process()
              .descendants()
              .filter(
                  handle ->
                      handle
                          .info()
                          .commandLine()
                          .map(
                              line ->
                                  line.contains("-Dgimle.log.root=")
                                      && line.contains("/workers/")
                                      && line.contains(key))
                          .orElse(false))
              .findFirst();
      if (worker.isPresent()) {
        return worker;
      }
    }
    return Optional.empty();
  }

  private synchronized Heimdall heimdall() {
    if (heimdall == null) {
      heimdall = Heimdall.attach(controlPlaneBaseUrls(), processes(), workDir);
    }
    return heimdall;
  }

  @Override
  public void close() {
    // The watcher first, so teardown kills never register as unexpected deaths mid-report; then
    // reverse of spawn order, so nothing still-running races a peer it depends on already being
    // gone. Killing an already-dead process is a harmless no-op.
    synchronized (this) {
      if (heimdall != null) {
        heimdall.close();
        heimdall = null;
      }
    }
    for (int i = spawnOrder.size() - 1; i >= 0; i--) {
      spawnOrder.get(i).killWithDescendants();
    }
  }

  private void boot() {
    createDirectories(workDir);
    final String javaExecutable = javaExecutable();
    final String classpath = System.getProperty("java.class.path");

    final PortPlan ports = PortPlan.allocate(spec);
    try (PortLease lease = ports.lease()) {
      startStores(javaExecutable, classpath, ports, lease);
      startMuninn(javaExecutable, classpath, ports, lease);
      startFafnirs(javaExecutable, classpath, ports, lease);
      startControlPlanes(javaExecutable, classpath, ports, lease);
      applySeed();
      startAgents(javaExecutable, classpath, ports, lease);
    }
  }

  private void startStores(
      final String java, final String classpath, final PortPlan ports, final PortLease lease) {
    for (int i = 0; i < spec.storeReplicas(); i++) {
      final int raftPort = ports.storeRaftPorts.get(i);
      final int clientPort = ports.storeClientPorts.get(i);
      final List<String> command = new ArrayList<>();
      command.add(java);
      command.addAll(spec.jvmFlags(ProcessRole.STORE));
      if (spec.muninnEnabled()) {
        command.add("-Dgimle.store.muninnEndpoint=" + LOOPBACK + ":" + ports.muninnPort);
      }
      command.addAll(List.of("-cp", classpath, "com.gimle.mimir.StoreMain"));
      command.add(workDir.resolve("store-state-" + i).toString());
      command.add(String.valueOf(raftPort));
      command.add(String.valueOf(clientPort));
      final String peers = ports.storePeersSpecExcluding(i);
      if (!peers.isBlank()) {
        command.addAll(List.of("--peers", peers));
      }
      lease.release(raftPort);
      lease.release(clientPort);
      final ManagedProcess store =
          new ManagedProcess(
              ProcessRole.STORE,
              "store-" + i,
              command,
              workDir.resolve("store-" + i + ".log"),
              LOOPBACK + ":" + clientPort);
      spawnOrder.add(store);
      stores.add(store);
    }
    for (final int clientPort : ports.storeClientPorts) {
      Polls.awaitPortOpen(LOOPBACK, clientPort, STAGE_TIMEOUT);
    }
  }

  private void startMuninn(
      final String java, final String classpath, final PortPlan ports, final PortLease lease) {
    if (!spec.muninnEnabled()) {
      return;
    }
    // Before Fafnir/control planes, not after: Muninn only needs the store, and bringing it up
    // this early means it is already reachable to receive shipped data from every process started
    // after it.
    final List<String> command = new ArrayList<>();
    command.add(java);
    command.addAll(spec.jvmFlags(ProcessRole.MUNINN));
    command.addAll(List.of("-cp", classpath, "com.gimle.muninn.MuninnMain"));
    command.add(String.valueOf(ports.muninnPort));
    command.addAll(List.of("--store-endpoints", ports.storeEndpointsSpec()));
    command.addAll(List.of("--data-root", workDir.resolve("muninn-data").toString()));
    lease.release(ports.muninnPort);
    muninn =
        new ManagedProcess(
            ProcessRole.MUNINN,
            "muninn",
            command,
            workDir.resolve("muninn.log"),
            LOOPBACK + ":" + ports.muninnPort);
    spawnOrder.add(muninn);
    Polls.awaitPortOpen(LOOPBACK, ports.muninnPort, STAGE_TIMEOUT);
  }

  private void startFafnirs(
      final String java, final String classpath, final PortPlan ports, final PortLease lease) {
    // One key file shared across every replica: each must be able to decrypt secrets written
    // through any other.
    final Path fafnirSecretKey = workDir.resolve("fafnir-secret.key");
    for (int i = 0; i < spec.fafnirReplicas(); i++) {
      final int port = ports.fafnirPorts.get(i);
      final List<String> command = new ArrayList<>();
      command.add(java);
      command.addAll(spec.jvmFlags(ProcessRole.FAFNIR));
      if (spec.muninnEnabled()) {
        command.add("-Dgimle.fafnir.muninnEndpoint=" + LOOPBACK + ":" + ports.muninnPort);
      }
      command.addAll(List.of("-cp", classpath, "com.gimle.fafnir.FafnirMain"));
      command.add(String.valueOf(port));
      command.add(fafnirSecretKey.toString());
      command.addAll(List.of("--store-endpoints", ports.storeEndpointsSpec()));
      lease.release(port);
      final ManagedProcess fafnir =
          new ManagedProcess(
              ProcessRole.FAFNIR,
              "fafnir-" + i,
              command,
              workDir.resolve("fafnir-" + i + ".log"),
              LOOPBACK + ":" + port);
      spawnOrder.add(fafnir);
      fafnirs.add(fafnir);
    }
    for (final int port : ports.fafnirPorts) {
      Polls.awaitPortOpen(LOOPBACK, port, STAGE_TIMEOUT);
    }
  }

  private void startControlPlanes(
      final String java, final String classpath, final PortPlan ports, final PortLease lease) {
    for (int i = 0; i < spec.controlPlaneReplicas(); i++) {
      final int port = ports.controlPlanePorts.get(i);
      // Round-robin across the Fafnir replicas -- FafnirClient talks to exactly one address, so
      // this is what makes every replica independently exercised rather than just replica #0.
      final int fafnirPort = ports.fafnirPorts.get(i % ports.fafnirPorts.size());
      final List<String> command = new ArrayList<>();
      command.add(java);
      command.addAll(spec.jvmFlags(ProcessRole.CONTROL_PLANE));
      command.addAll(List.of("-cp", classpath, "com.gimle.controlplane.ControlPlaneMain"));
      command.add(String.valueOf(port));
      command.add(workDir.resolve("controlplane-secret-" + i + ".key").toString());
      command.addAll(List.of("--store-endpoints", ports.storeEndpointsSpec()));
      command.addAll(List.of("--fafnir-endpoint", LOOPBACK + ":" + fafnirPort));
      if (spec.muninnEnabled()) {
        command.addAll(List.of("--muninn-endpoint", LOOPBACK + ":" + ports.muninnPort));
      }
      lease.release(port);
      final ManagedProcess controlPlane =
          new ManagedProcess(
              ProcessRole.CONTROL_PLANE,
              "controlplane-" + i,
              command,
              workDir.resolve("controlplane-" + i + ".log"),
              LOOPBACK + ":" + port);
      spawnOrder.add(controlPlane);
      controlPlanes.add(controlPlane);
      final int replicaIndex = i;
      Polls.await(
          () -> api(replicaIndex).isServing(),
          STAGE_TIMEOUT,
          "control-plane replica #" + replicaIndex + " should start accepting requests");
    }
  }

  private void applySeed() {
    final ClusterApi api = api();
    for (final AccountSeed account : spec.seed().accounts()) {
      api.putAccount(account.username(), account.password());
    }
    for (final TenantSeed tenant : spec.seed().tenants()) {
      api.putTenant(tenant.id(), tenant.quota());
    }
  }

  private void startAgents(
      final String java, final String classpath, final PortPlan ports, final PortLease lease) {
    String firstGossipAddress = null;
    for (int i = 0; i < spec.nodes().size(); i++) {
      final NodeSpec node = spec.nodes().get(i);
      final int gossipPort = ports.gossipPorts.get(i);
      final String gossipAddress = LOOPBACK + ":" + gossipPort;
      // The first agent seeds nothing; every later one seeds off the first, so a multi-node
      // topology forms one gossip cluster rather than several singletons.
      final String seeds = firstGossipAddress == null ? "-" : firstGossipAddress;
      if (firstGossipAddress == null) {
        firstGossipAddress = gossipAddress;
      }
      final int fafnirPort = ports.fafnirPorts.get(i % ports.fafnirPorts.size());
      final List<String> command = new ArrayList<>();
      command.add(java);
      command.addAll(spec.jvmFlags(ProcessRole.AGENT));
      command.add("-Dgimle.agent.fafnirEndpoint=" + LOOPBACK + ":" + fafnirPort);
      if (spec.muninnEnabled()) {
        command.add("-Dgimle.agent.muninnEndpoint=" + LOOPBACK + ":" + ports.muninnPort);
      }
      // Per-node, not shared: a second agent would otherwise write its agent-platform.log to the
      // exact same file as the first, interleaving both processes' JSON output.
      command.add("-Dgimle.log.root=" + workDir.resolve("gimle-logs-" + node.id()));
      command.add("-Dgimle.data.root=" + workDir.resolve("gimle-data-" + node.id()));
      if (!node.labels().isEmpty()) {
        command.add("-Dgimle.node.labels=" + String.join(",", node.labels()));
      }
      command.addAll(List.of("-cp", classpath, "com.gimle.agent.AgentMain"));
      command.add(node.id());
      command.add(controlPlaneBaseUrl(0));
      command.add(gossipAddress);
      command.add(seeds);
      command.add(java);
      command.addAll(spec.jvmFlags(ProcessRole.WORKER));
      command.addAll(List.of("-cp", classpath, "com.gimle.worker.WorkerMain"));
      lease.release(gossipPort);
      final ManagedProcess agent =
          new ManagedProcess(
              ProcessRole.AGENT,
              node.id(),
              command,
              workDir.resolve("agent-" + node.id() + ".log"),
              gossipAddress);
      spawnOrder.add(agent);
      agents.put(node.id(), agent);
    }
    for (final NodeSpec node : spec.nodes()) {
      Polls.await(
          () -> api().nodeRegistered(node.id()),
          STAGE_TIMEOUT,
          "node " + node.id() + " should register with the control plane");
    }
  }

  private static void createDirectories(final Path workDir) {
    try {
      Files.createDirectories(workDir);
    } catch (final IOException e) {
      throw new HolmgangException("failed creating cluster work directory " + workDir, e);
    }
  }

  private static String javaExecutable() {
    final Optional<String> command = ProcessHandle.current().info().command();
    if (command.isPresent()) {
      return command.get();
    }
    final Path javaBin = Path.of(System.getProperty("java.home"), "bin");
    for (final String candidate : List.of("java", "java.exe")) {
      final Path path = javaBin.resolve(candidate);
      if (Files.isRegularFile(path)) {
        return path.toString();
      }
    }
    throw new HolmgangException("could not locate the java launcher under " + javaBin);
  }

  /** The cluster's whole port budget, leased up front and assigned to roles deterministically. */
  private record PortPlan(
      List<Integer> storeRaftPorts,
      List<Integer> storeClientPorts,
      List<Integer> fafnirPorts,
      List<Integer> controlPlanePorts,
      List<Integer> gossipPorts,
      int muninnPort,
      PortLease leased) {

    static PortPlan allocate(final ClusterSpec spec) {
      final int count =
          spec.storeReplicas() * 2
              + spec.fafnirReplicas()
              + spec.controlPlaneReplicas()
              + spec.nodes().size()
              + (spec.muninnEnabled() ? 1 : 0);
      final PortLease lease = PortLease.reserve(count);
      final List<Integer> ports = lease.ports();
      int next = 0;
      final List<Integer> storeRaft = new ArrayList<>();
      final List<Integer> storeClient = new ArrayList<>();
      for (int i = 0; i < spec.storeReplicas(); i++) {
        storeRaft.add(ports.get(next++));
        storeClient.add(ports.get(next++));
      }
      final List<Integer> fafnir = new ArrayList<>();
      for (int i = 0; i < spec.fafnirReplicas(); i++) {
        fafnir.add(ports.get(next++));
      }
      final List<Integer> controlPlane = new ArrayList<>();
      for (int i = 0; i < spec.controlPlaneReplicas(); i++) {
        controlPlane.add(ports.get(next++));
      }
      final List<Integer> gossip = new ArrayList<>();
      for (int i = 0; i < spec.nodes().size(); i++) {
        gossip.add(ports.get(next++));
      }
      final int muninn = spec.muninnEnabled() ? ports.get(next) : -1;
      return new PortPlan(storeRaft, storeClient, fafnir, controlPlane, gossip, muninn, lease);
    }

    PortLease lease() {
      return leased;
    }

    String storeEndpointsSpec() {
      final List<String> endpoints = new ArrayList<>();
      for (final int clientPort : storeClientPorts) {
        endpoints.add(LOOPBACK + ":" + clientPort);
      }
      return String.join(",", endpoints);
    }

    String storePeersSpecExcluding(final int excludeIndex) {
      final List<String> peers = new ArrayList<>();
      for (int i = 0; i < storeRaftPorts.size(); i++) {
        if (i == excludeIndex) {
          continue;
        }
        peers.add(LOOPBACK + ":" + storeRaftPorts.get(i) + ":" + storeClientPorts.get(i));
      }
      return String.join(",", peers);
    }
  }
}
