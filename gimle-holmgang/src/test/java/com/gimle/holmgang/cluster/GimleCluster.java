package com.gimle.holmgang.cluster;

import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.hilmir.plan.ClusterPlan;
import com.gimle.hilmir.plan.LaunchPlanner;
import com.gimle.hilmir.plan.MachinePlan;
import com.gimle.hilmir.plan.ProcessCommand;
import com.gimle.hilmir.plan.ResolvedRuntime;
import com.gimle.hilmir.topology.AgentPlacement;
import com.gimle.hilmir.topology.AndvariRole;
import com.gimle.hilmir.topology.ControlPlaneRole;
import com.gimle.hilmir.topology.FafnirRole;
import com.gimle.hilmir.topology.Machine;
import com.gimle.hilmir.topology.MuninnRole;
import com.gimle.hilmir.topology.RuntimeSettings;
import com.gimle.hilmir.topology.ServiceReplica;
import com.gimle.hilmir.topology.StoreReplica;
import com.gimle.hilmir.topology.StoreRole;
import com.gimle.hilmir.topology.TlsMaterial;
import com.gimle.hilmir.topology.Topology;
import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.heimdall.Heimdall;
import com.gimle.holmgang.heimdall.HeimdallScope;
import com.gimle.holmgang.heimdall.Invariant;
import com.gimle.holmgang.heimdall.InvariantGuard;
import com.gimle.holmgang.loki.Loki;
import com.gimle.holmgang.topology.AccountSeed;
import com.gimle.holmgang.topology.ClusterSpec;
import com.gimle.holmgang.topology.NodeSpec;
import com.gimle.holmgang.topology.ProcessRole;
import com.gimle.holmgang.topology.TenantSeed;
import com.gimle.holmgang.topology.Transport;
import com.gimle.mimir.raft.PeerAddress;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.rpc.StoreRpc;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Boots a real multi-process Gimle cluster from a {@link ClusterSpec}: one JVM per process, spawned
 * with the same mechanics the platform's own launchers use, on dynamically leased loopback ports.
 * Startup follows the dependency order stores, then Muninn, then Andvari, then Fafnir, then control
 * planes, then agents, awaiting each stage's real readiness signal (a listening client port; {@code
 * /deployments} responding; the node appearing in {@code /nodes}, which the agent only registers
 * after its gossip join completed) before starting the stage that depends on it.
 *
 * <p>Every spawned process gets the test JVM's own classpath, so this module must test-depend on
 * every process kind's artifact. The working directory is caller-owned and never deleted here --
 * process logs are the primary forensic artifact when a scenario fails.
 */
public final class GimleCluster implements AutoCloseable {

  private static final Duration STAGE_TIMEOUT = Duration.ofSeconds(60);
  private static final Pattern BOOTSTRAP_TOKEN = Pattern.compile("bootstrap token: (\\S+)");

  private final ClusterSpec spec;
  private final Path workDir;
  private final boolean mtls;
  private final List<ManagedProcess> spawnOrder = new ArrayList<>();
  private final List<ManagedProcess> stores = new ArrayList<>();
  private final List<ManagedProcess> controlPlanes = new ArrayList<>();
  private final List<ManagedProcess> fafnirs = new ArrayList<>();
  private final Map<String, ManagedProcess> agents = new LinkedHashMap<>();
  private final Map<Integer, ClusterApi> apis = new LinkedHashMap<>();
  private final List<Integer> storeRaftPorts = new ArrayList<>();
  private final List<Integer> storeClientPorts = new ArrayList<>();
  private final List<ManagedProcess> andvaris = new ArrayList<>();
  private final List<ManagedProcess> muninns = new ArrayList<>();
  private Heimdall heimdall;
  private Path tlsDir;
  private HttpClient operatorClient;
  private Loki loki;
  private String javaExecutablePath;
  private String classpath;
  private String muninnEndpointsSpec = "";

  private GimleCluster(final ClusterSpec spec, final Path workDir) {
    this.spec = spec;
    this.workDir = workDir;
    this.mtls = spec.transport() == Transport.MTLS;
  }

  public static GimleCluster start(final ClusterSpec spec, final Path workDir) {
    final GimleCluster cluster = new GimleCluster(spec, workDir);
    // Register the booted topology for the run report before boot, so it is captured even if boot
    // later fails partway.
    com.gimle.holmgang.saga.SagaCollector.instance().recordTopology(spec);
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

  /** How many store processes currently exist -- grows and shrinks with live membership changes. */
  public int storeCount() {
    return stores.size();
  }

  public GimleProcess controlPlane(final int index) {
    return controlPlanes.get(index);
  }

  /** How many control-plane replicas this topology booted. */
  public int controlPlaneCount() {
    return controlPlanes.size();
  }

  public GimleProcess fafnir(final int index) {
    return fafnirs.get(index);
  }

  /** How many Fafnir replicas this topology booted. */
  public int fafnirCount() {
    return fafnirs.size();
  }

  /** The first Muninn replica -- the common case for a topology with just one. */
  public GimleProcess muninn() {
    return muninn(0);
  }

  public GimleProcess muninn(final int index) {
    if (muninns.isEmpty()) {
      throw new HolmgangException("topology " + spec.name() + " does not enable Muninn");
    }
    return muninns.get(index);
  }

  /** How many Muninn replicas this topology booted -- zero when Muninn isn't enabled. */
  public int muninnCount() {
    return muninns.size();
  }

  /**
   * True once the given Muninn replica's own {@code /status} route answers -- a direct-port
   * liveness/readiness signal, since {@code /status} needs no store or auth round trip. Unlike a
   * control-plane replica there is no proxy hop to route this through, so it hits the replica's own
   * leased port directly.
   */
  public boolean muninnServing(final int index) {
    return httpStatusOk(scheme() + "://" + muninns.get(index).endpoint() + "/status");
  }

  /**
   * The same direct-port {@code /status} liveness signal as {@link #muninnServing}, for Andvari.
   */
  public boolean andvariServing(final int index) {
    return httpStatusOk(scheme() + "://" + andvaris.get(index).endpoint() + "/status");
  }

  private boolean httpStatusOk(final String url) {
    try {
      final HttpResponse<Void> response =
          operatorClient.send(
              HttpRequest.newBuilder(URI.create(url)).GET().build(),
              HttpResponse.BodyHandlers.discarding());
      return response.statusCode() < 500;
    } catch (final IOException e) {
      return false;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** The first Andvari replica -- the common case for a topology with just one. */
  public GimleProcess andvari() {
    return andvari(0);
  }

  public GimleProcess andvari(final int index) {
    if (andvaris.isEmpty()) {
      throw new HolmgangException("topology " + spec.name() + " does not enable Andvari");
    }
    return andvaris.get(index);
  }

  /** How many Andvari replicas this topology booted -- zero when Andvari isn't enabled. */
  public int andvariCount() {
    return andvaris.size();
  }

  public GimleProcess agent(final String nodeId) {
    final ManagedProcess agent = agents.get(nodeId);
    if (agent == null) {
      throw new HolmgangException("topology " + spec.name() + " has no node " + nodeId);
    }
    return agent;
  }

  public String controlPlaneBaseUrl(final int index) {
    return scheme() + "://" + controlPlanes.get(index).endpoint();
  }

  public ClusterApi api() {
    return api(0);
  }

  public ClusterApi api(final int controlPlaneIndex) {
    return apis.computeIfAbsent(
        controlPlaneIndex, index -> new ClusterApi(operatorClient, controlPlaneBaseUrl(index)));
  }

  /**
   * A client with no identity at all -- server-trust only under mTLS, so the TLS handshake succeeds
   * but every authorized route sees no principal. What "anonymous client is rejected" scenarios
   * call. Meaningless in plaintext mode, where the API deliberately has no auth.
   */
  public ClusterApi anonymousApi(final int controlPlaneIndex) {
    final HttpClient anonymous =
        mtls
            ? HttpClient.newBuilder()
                .sslContext(SslContexts.forServerTrustOnly(tlsDir.resolve("ca.crt")))
                .build()
            : HttpClient.newHttpClient();
    return new ClusterApi(anonymous, controlPlaneBaseUrl(controlPlaneIndex));
  }

  /**
   * The store process currently leading the Raft cluster, identified through the store's own status
   * surface (any answering member names the leader) -- never inferred from write redirects. Throws
   * when no leader is currently known (a mid-election gap): a scenario that needs the leader should
   * gate on the cluster accepting writes first.
   */
  public GimleProcess storeLeader() {
    return stores.get(storeLeaderIndex());
  }

  /** The array index (see {@link #store(int)}) of the currently-leading store. */
  public int storeLeaderIndex() {
    final String leaderId =
        storeLeaderId()
            .orElseThrow(() -> new HolmgangException("no store leader is currently known"));
    for (int i = 0; i < stores.size(); i++) {
      if (leaderId.equals(storeRaftId(i))) {
        return i;
      }
    }
    throw new HolmgangException("store leader " + leaderId + " matches no known store process");
  }

  /**
   * One store's own self-reported status, read directly off its real client port -- deliberately
   * bypassing every Loki proxy (including {@link #withStoreClient}'s own interposed-endpoint
   * choices), so it stays truthful about that replica's own view even while every one of its raft
   * peer links is cut. This is what lets a scenario observe a leader's own check-quorum
   * self-demotion happening from the inside, rather than inferring it from what the rest of the
   * cluster reports.
   */
  public StoreRpc.StatusResult storeStatus(final int index) {
    final SocketAddress address = new InetSocketAddress(host(), storeClientPorts.get(index));
    try (StoreClient client = new StoreClient(List.of(address))) {
      return client.status();
    }
  }

  /** The leading store's Raft id, empty while no answering member currently names one. */
  public Optional<String> storeLeaderId() {
    try {
      final String leaderId = withStoreClient(client -> client.status().leaderId());
      return leaderId.isEmpty() ? Optional.empty() : Optional.of(leaderId);
    } catch (final RuntimeException e) {
      return Optional.empty();
    }
  }

  /** The membership an answering store member reports; empty while none answers. */
  public List<String> storeMemberIds() {
    try {
      return withStoreClient(client -> client.status().memberIds());
    } catch (final RuntimeException e) {
      return List.of();
    }
  }

  /**
   * Spawns one additional store process and joins it to the running cluster through the real {@code
   * AddServer} membership change -- etcd-style, one server at a time. The new member participates
   * in Raft immediately; the control planes keep their original endpoint lists, which stays correct
   * because reads and leader-routed writes go through any configured member.
   */
  public GimleProcess addStore() {
    final int index = stores.size();
    final int raftPort;
    final int clientPort;
    final List<String> peers = new ArrayList<>();
    for (int i = 0; i < stores.size(); i++) {
      peers.add(host() + ":" + storeRaftPorts.get(i) + ":" + storeClientPorts.get(i));
    }
    try (PortLease lease = PortLease.reserve(2)) {
      final List<Integer> leased = lease.ports();
      raftPort = leased.get(0);
      clientPort = leased.get(1);
      final List<String> command = new ArrayList<>();
      command.add(javaExecutablePath);
      command.addAll(tlsFlags("controlplane"));
      command.addAll(spec.jvmFlags(ProcessRole.STORE));
      if (spec.muninnEnabled()) {
        command.add("-Dgimle.store.muninnEndpoint=" + muninnEndpointsSpec);
      }
      command.addAll(List.of("-cp", classpath, "com.gimle.mimir.StoreMain"));
      command.add(workDir.resolve("store-state-" + index).toString());
      command.add(String.valueOf(raftPort));
      command.add(String.valueOf(clientPort));
      command.addAll(List.of("--peers", String.join(",", peers)));
      lease.release(raftPort);
      lease.release(clientPort);
      final ManagedProcess store =
          new ManagedProcess(
              ProcessRole.STORE,
              "store-" + index,
              command,
              workDir.resolve("store-" + index + ".log"),
              host() + ":" + clientPort);
      spawnOrder.add(store);
      stores.add(store);
      storeRaftPorts.add(raftPort);
      storeClientPorts.add(clientPort);
    }
    Polls.awaitPortOpen(host(), clientPort, STAGE_TIMEOUT);
    withStoreClient(
        client -> {
          client.addServer(host() + ":" + raftPort, new PeerAddress(host(), raftPort, clientPort));
          return null;
        });
    return stores.get(index);
  }

  /**
   * The symmetric counterpart to {@link #addStore}: removes the newest member through the real
   * {@code RemoveServer} membership change first -- so the departing node is already outside the
   * quorum math -- then kills its process.
   */
  public void removeNewestStore() {
    final int index = stores.size() - 1;
    withStoreClient(
        client -> {
          client.removeServer(storeRaftId(index));
          return null;
        });
    stores.get(index).killWithDescendants();
  }

  private String storeRaftId(final int index) {
    return host() + ":" + storeRaftPorts.get(index);
  }

  /**
   * A short-lived client over the currently-live store endpoints -- rebuilt per call rather than
   * cached, so a killed member or freshly changed membership never leaves a stale endpoint list
   * behind.
   */
  private <T> T withStoreClient(final java.util.function.Function<StoreClient, T> call) {
    final List<SocketAddress> live = new ArrayList<>();
    for (int i = 0; i < stores.size(); i++) {
      if (stores.get(i).isAlive()) {
        live.add(new InetSocketAddress(host(), storeClientPorts.get(i)));
      }
    }
    if (live.isEmpty()) {
      throw new HolmgangException("no live store endpoint to query");
    }
    try (StoreClient client = new StoreClient(live)) {
      return call.apply(client);
    }
  }

  /** The fault injector over this topology's interposed links; requires {@code faults.proxied}. */
  public Loki faults() {
    if (loki == null) {
      throw new HolmgangException(
          "topology " + spec.name() + " is not fault-proxied: set faults.proxied to use Loki");
    }
    return loki;
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

  private static final Pattern WORKER_SPAWN_LINE =
      Pattern.compile("spawned worker (\\S+) as pid (\\d+)");

  /**
   * The live worker JVM currently hosting one instance, found the same way the platform itself
   * can't tell you: {@code WorkerProcessSupervisor} logs {@code "spawned worker <key> as pid
   * <pid>"} on every spawn and respawn, so the agent's own log file is a reliable, already-durable
   * record of which OS pid currently backs a given instance -- taking the last matching line
   * naturally picks up a post-respawn pid over a stale pre-kill one. Deliberately not {@code
   * ProcessHandle.Info#commandLine()}-based matching on a descendant process's own flags (an
   * earlier approach here): that command line embeds this repo's own long, still-growing classpath,
   * and Windows silently truncates a queried process's reported command line well before the
   * identifying flag ever appears in it -- a failure this method used to hit as "no live worker
   * found" for a worker that was, in fact, alive and correctly spawned.
   */
  public Optional<ProcessHandle> workerFor(final String deploymentName, final int instanceIndex) {
    final String key = deploymentName + "#" + instanceIndex;
    for (final ManagedProcess agent : agents.values()) {
      final Optional<ProcessHandle> worker =
          latestWorkerPid(agent.logFile(), key)
              .flatMap(ProcessHandle::of)
              .filter(ProcessHandle::isAlive);
      if (worker.isPresent()) {
        return worker;
      }
    }
    return Optional.empty();
  }

  private static Optional<Long> latestWorkerPid(final Path agentLogFile, final String key) {
    Long pid = null;
    try {
      // ISO-8859-1, deliberately not UTF-8: this file is still being appended to by the live
      // agent process as it's read, and a read landing mid-write can catch a multi-byte UTF-8
      // sequence (the banner's "Gimlé") only half-flushed, which UTF-8 decoding rejects outright
      // as malformed input. ISO-8859-1 maps every byte 0x00-0xFF to a character, so it can never
      // throw here -- the only text this method actually parses (the ASCII "spawned worker ..."
      // line) decodes identically either way, so a possibly-mangled banner elsewhere in the file
      // costs nothing.
      for (final String line : Files.readAllLines(agentLogFile, StandardCharsets.ISO_8859_1)) {
        final Matcher matcher = WORKER_SPAWN_LINE.matcher(line);
        if (matcher.find() && matcher.group(1).equals(key)) {
          pid = Long.parseLong(matcher.group(2));
        }
      }
    } catch (final IOException e) {
      throw new HolmgangException("failed reading agent log " + agentLogFile, e);
    }
    return Optional.ofNullable(pid);
  }

  private synchronized Heimdall heimdall() {
    if (heimdall == null) {
      heimdall = Heimdall.attach(controlPlaneBaseUrls(), processes(), workDir, operatorClient);
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
    if (loki != null) {
      loki.close();
      loki = null;
    }
  }

  /**
   * The single machine every process in this topology is placed on -- dynamic loopback port leasing
   * (see {@link PortPlan}/{@link PortLease}) is a holmgang-only need so many topologies can coexist
   * on one CI machine; a real deployment declares real hosts, which is exactly what {@code
   * gimle-hilmir}'s {@link Machine} otherwise models. One machine is enough to carry that mapping.
   */
  private static final String MACHINE = "local";

  private void boot() {
    createDirectories(workDir);
    final String javaExecutable = javaExecutable();
    final String classpath = System.getProperty("java.class.path");
    this.javaExecutablePath = javaExecutable;
    this.classpath = classpath;

    if (mtls) {
      generateTlsMaterial(javaExecutable, classpath);
    }
    operatorClient = buildOperatorClient();
    if (spec.faultsProxied()) {
      loki = new Loki();
    }

    final PortPlan ports = PortPlan.allocate(spec, host());
    this.muninnEndpointsSpec = ports.muninnEndpointsSpec();
    try (PortLease lease = ports.lease()) {
      // A pure function of the topology plus the already-resolved runtime -- no process spawning,
      // no network calls. Its own global boot order (stores, Muninn, Andvari, Fafnir, control
      // planes, agents) is exactly the order this method spawns in below, which is what lets each
      // later stage's command line reference an earlier stage's already-known address.
      final ResolvedRuntime runtime = new ResolvedRuntime(javaExecutable, classpath, workDir);
      final ClusterPlan clusterPlan = LaunchPlanner.plan(buildTopology(ports), runtime);
      final MachinePlan machinePlan =
          clusterPlan.byMachine().getOrDefault(MACHINE, new MachinePlan(MACHINE, List.of()));

      startStores(machinePlan, ports, lease);
      startMuninn(machinePlan, ports, lease);
      startAndvari(machinePlan, ports, lease);
      startFafnirs(machinePlan, ports, lease);
      startControlPlanes(machinePlan, ports, lease);
      applySeed();
      startAgents(machinePlan, ports, lease);
    }
  }

  /**
   * Maps this topology's own {@link ClusterSpec} plus the concrete ports {@link PortPlan} already
   * leased onto a {@code gimle-hilmir} {@link Topology}, everything on the single {@link #MACHINE}.
   * {@link LaunchPlanner} turns the result into the same per-role command lines this class used to
   * hand-build itself. {@code runtime} is deliberately left at {@link RuntimeSettings#EMPTY} here
   * -- the concrete java executable, classpath, and data root are already known to {@link #boot()},
   * so they are supplied directly as a {@link ResolvedRuntime} to {@link LaunchPlanner#plan}
   * instead of round-tripping through this optional-fields section and back.
   */
  private Topology buildTopology(final PortPlan ports) {
    final List<StoreReplica> storeReplicas = new ArrayList<>();
    for (int i = 0; i < spec.storeReplicas(); i++) {
      storeReplicas.add(
          new StoreReplica(MACHINE, ports.storeRaftPorts.get(i), ports.storeClientPorts.get(i)));
    }
    final List<ServiceReplica> controlPlaneReplicas = new ArrayList<>();
    for (int i = 0; i < spec.controlPlaneReplicas(); i++) {
      controlPlaneReplicas.add(new ServiceReplica(MACHINE, ports.controlPlanePorts.get(i)));
    }
    final List<ServiceReplica> fafnirReplicas = new ArrayList<>();
    for (int i = 0; i < spec.fafnirReplicas(); i++) {
      fafnirReplicas.add(new ServiceReplica(MACHINE, ports.fafnirPorts.get(i)));
    }
    final List<ServiceReplica> muninnReplicas = new ArrayList<>();
    for (int i = 0; i < spec.muninnReplicas(); i++) {
      muninnReplicas.add(new ServiceReplica(MACHINE, ports.muninnPorts.get(i)));
    }
    final List<ServiceReplica> andvariReplicas = new ArrayList<>();
    for (int i = 0; i < spec.andvariReplicas(); i++) {
      andvariReplicas.add(new ServiceReplica(MACHINE, ports.andvariPorts.get(i)));
    }
    final List<AgentPlacement> agentPlacements = new ArrayList<>();
    for (int i = 0; i < spec.nodes().size(); i++) {
      final NodeSpec node = spec.nodes().get(i);
      agentPlacements.add(
          new AgentPlacement(MACHINE, node.id(), ports.gossipPorts.get(i), node.labels()));
    }
    final Map<com.gimle.hilmir.topology.ProcessRole, List<String>> extraJvmFlags =
        new EnumMap<>(com.gimle.hilmir.topology.ProcessRole.class);
    for (final ProcessRole role : ProcessRole.values()) {
      final List<String> flags = spec.jvmFlags(role);
      if (!flags.isEmpty()) {
        extraJvmFlags.put(toHilmirRole(role), flags);
      }
    }
    return new Topology(
        spec.name(),
        mtls
            ? com.gimle.hilmir.topology.Transport.MTLS
            : com.gimle.hilmir.topology.Transport.PLAINTEXT,
        mtls ? Optional.of(new TlsMaterial(tlsDir)) : Optional.empty(),
        List.of(new Machine(MACHINE, host())),
        RuntimeSettings.EMPTY,
        new StoreRole(storeReplicas),
        new ControlPlaneRole(controlPlaneReplicas),
        new FafnirRole(Optional.of(workDir.resolve("fafnir-secret.key")), fafnirReplicas),
        new MuninnRole(muninnReplicas),
        new AndvariRole(andvariReplicas),
        agentPlacements,
        extraJvmFlags);
  }

  /**
   * {@code com.gimle.holmgang.topology.ProcessRole} and {@code
   * com.gimle.hilmir.topology.ProcessRole} are two distinct enums with identical members:
   * holmgang's own copy stays untouched (it belongs to {@link ClusterSpec}, a package this refactor
   * does not touch), so every topology-to-plan boundary needs an explicit mapping rather than a
   * shared type.
   */
  private static com.gimle.hilmir.topology.ProcessRole toHilmirRole(final ProcessRole role) {
    return switch (role) {
      case STORE -> com.gimle.hilmir.topology.ProcessRole.STORE;
      case CONTROL_PLANE -> com.gimle.hilmir.topology.ProcessRole.CONTROL_PLANE;
      case FAFNIR -> com.gimle.hilmir.topology.ProcessRole.FAFNIR;
      case MUNINN -> com.gimle.hilmir.topology.ProcessRole.MUNINN;
      case ANDVARI -> com.gimle.hilmir.topology.ProcessRole.ANDVARI;
      case AGENT -> com.gimle.hilmir.topology.ProcessRole.AGENT;
      case WORKER -> com.gimle.hilmir.topology.ProcessRole.WORKER;
    };
  }

  /** The inverse of {@link #toHilmirRole}, for turning a planned command back into a spawn. */
  private static ProcessRole toHolmgangRole(final com.gimle.hilmir.topology.ProcessRole role) {
    return switch (role) {
      case STORE -> ProcessRole.STORE;
      case CONTROL_PLANE -> ProcessRole.CONTROL_PLANE;
      case FAFNIR -> ProcessRole.FAFNIR;
      case MUNINN -> ProcessRole.MUNINN;
      case ANDVARI -> ProcessRole.ANDVARI;
      case AGENT -> ProcessRole.AGENT;
      case WORKER -> ProcessRole.WORKER;
    };
  }

  /**
   * Spawns {@code command} exactly as {@link LaunchPlanner} planned it. {@code
   * command.readinessAddress()} is, in every role, precisely what {@link ManagedProcess}'s own
   * {@code endpoint} means (the client port for a store, the HTTP port for everything else, the
   * gossip bind address for an agent), and {@code command.logFileName()} already matches this
   * class's own historical per-process log naming ({@code "store-0.log"}, ...), so both carry over
   * unchanged. {@link ManagedProcess} still does its own {@link JavaArgFile} rewrite internally --
   * kept rather than routed through {@code gimle-hilmir}'s own copy of that class, which is
   * package-private and therefore not reachable from this module.
   */
  private ManagedProcess spawn(final ProcessCommand command) {
    return new ManagedProcess(
        toHolmgangRole(command.role()),
        command.id(),
        command.command(),
        workDir.resolve(command.logFileName()),
        command.readinessAddress());
  }

  /** Every {@code role}-tagged command in {@code machinePlan}, in {@link LaunchPlanner}'s order. */
  private static List<ProcessCommand> commandsForRole(
      final MachinePlan machinePlan, final com.gimle.hilmir.topology.ProcessRole role) {
    final List<ProcessCommand> matching = new ArrayList<>();
    for (final ProcessCommand command : machinePlan.commands()) {
      if (command.role() == role) {
        matching.add(command);
      }
    }
    return matching;
  }

  /**
   * A copy of {@code command} with {@code flag}'s following value replaced by {@code newValue} --
   * {@link ProcessCommand#command()} is immutable, so overriding one flag's value (the Loki
   * store-endpoint/peers substitution below) means rebuilding the whole record. A no-op when {@code
   * flag} is absent, which happens legitimately (a single-store topology has no {@code --peers} to
   * override).
   */
  private static ProcessCommand withFlagValueReplaced(
      final ProcessCommand command, final String flag, final String newValue) {
    final List<String> original = command.command();
    final int index = original.indexOf(flag);
    if (index < 0 || index + 1 >= original.size()) {
      return command;
    }
    final List<String> rewritten = new ArrayList<>(original);
    rewritten.set(index + 1, newValue);
    return new ProcessCommand(
        command.role(),
        command.id(),
        command.machine(),
        rewritten,
        command.logFileName(),
        command.dataDir(),
        command.readinessAddress(),
        command.needsBootstrapToken());
  }

  /**
   * A copy of {@code command} with {@code extraArg} (a {@code -D...} JVM flag -- the mTLS
   * bootstrap-token flag) inserted just before the command's own first {@code -cp}, never appended
   * at the end: an agent's command embeds a *second* {@code -cp classpath
   * com.gimle.worker.WorkerMain} tail after its own, for the worker it spawns, so a flag appended
   * at the very end would land after that tail as an inert trailing argument instead of a JVM flag
   * the agent's own launch actually sees.
   */
  private static ProcessCommand withAppendedArg(
      final ProcessCommand command, final String extraArg) {
    final List<String> original = command.command();
    final List<String> rewritten = new ArrayList<>(original);
    final int cpIndex = original.indexOf("-cp");
    rewritten.add(cpIndex < 0 ? rewritten.size() : cpIndex, extraArg);
    return new ProcessCommand(
        command.role(),
        command.id(),
        command.machine(),
        rewritten,
        command.logFileName(),
        command.dataDir(),
        command.readinessAddress(),
        command.needsBootstrapToken());
  }

  /**
   * Everything is addressed as {@code localhost} under mTLS, never {@code 127.0.0.1}: the PKI mints
   * DNS-only subject alternative names, so an IP-addressed URL would fail hostname verification
   * against an otherwise perfectly valid certificate.
   */
  private String host() {
    return mtls ? "localhost" : "127.0.0.1";
  }

  private String scheme() {
    return mtls ? "https" : "http";
  }

  private void generateTlsMaterial(final String java, final String classpath) {
    tlsDir = workDir.resolve("tls");
    runOneShot(
        List.of(
            java,
            "-cp",
            classpath,
            "com.gimle.pki.PkiBootstrapMain",
            tlsDir.toString(),
            "holmgang-ca",
            "localhost"),
        workDir.resolve("pki.log"),
        Duration.ofSeconds(120),
        "TLS material generation");
  }

  private HttpClient buildOperatorClient() {
    if (!mtls) {
      return HttpClient.newHttpClient();
    }
    return HttpClient.newBuilder()
        .sslContext(
            SslContexts.forMutualTls(
                new TlsSettings(
                    tlsDir.resolve("operator.crt"),
                    tlsDir.resolve("operator.key"),
                    tlsDir.resolve("ca.crt"))))
        .build();
  }

  /** The four flags every TLS-mode process gets, presenting {@code certName}'s leaf. */
  private List<String> tlsFlags(final String certName) {
    if (!mtls) {
      return List.of();
    }
    return List.of(
        "-Dgimle.transport.protocol=tls",
        "-Dgimle.tls.certFile=" + tlsDir.resolve(certName + ".crt"),
        "-Dgimle.tls.keyFile=" + tlsDir.resolve(certName + ".key"),
        "-Dgimle.tls.caFile=" + tlsDir.resolve("ca.crt"));
  }

  private String mintBootstrapToken(final String java, final String classpath) {
    final Path logFile = workDir.resolve("cli-token.log");
    final List<String> command = new ArrayList<>();
    command.add(java);
    command.addAll(tlsFlags("operator"));
    command.addAll(List.of("-cp", classpath, "com.gimle.cli.GimleCli"));
    command.addAll(List.of("cert", "token", "create"));
    command.addAll(List.of("--server", controlPlanes.get(0).endpoint()));
    runOneShot(command, logFile, Duration.ofSeconds(60), "bootstrap token minting");
    final String output;
    try {
      output = Files.readString(logFile);
    } catch (final IOException e) {
      throw new HolmgangException("failed reading the token minting output at " + logFile, e);
    }
    final Matcher matcher = BOOTSTRAP_TOKEN.matcher(output);
    if (!matcher.find()) {
      throw new HolmgangException("no bootstrap token in the CLI output; see " + logFile);
    }
    return matcher.group(1);
  }

  private static void runOneShot(
      final List<String> command,
      final Path logFile,
      final Duration timeout,
      final String description) {
    // See JavaArgFile's own javadoc: the same shared, long test classpath every ManagedProcess
    // spawn needs this for applies here too (TLS material generation, bootstrap token minting).
    final ProcessBuilder pb =
        new ProcessBuilder(JavaArgFile.rewrite(command, Path.of(logFile + ".args")));
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    try {
      final Process process = pb.start();
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new HolmgangException(description + " timed out; see " + logFile);
      }
      if (process.exitValue() != 0) {
        throw new HolmgangException(
            description + " failed with exit code " + process.exitValue() + "; see " + logFile);
      }
    } catch (final IOException e) {
      throw new HolmgangException(description + " could not start", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HolmgangException(description + " was interrupted", e);
    }
  }

  /**
   * Under fault proxying, every store must dial every other through its own private set of Loki
   * raft proxies rather than the real ports -- {@link LaunchPlanner} has no notion of a
   * fault-injecting proxy, so it always plans every store's {@code --peers} with the real raft
   * ports. Registering the proxies still has to happen upfront, before any store spawns, since each
   * store's own {@code --peers} argument is fixed at spawn time (unlike the control-plane
   * substitution below, which is one proxy per replica): a proxy per (store, peer) pair, not one
   * shared per target, since check-quorum tracks a leader's own *outbound* replication responses,
   * so isolating a store must cut its own outbound view of every peer as well as every peer's
   * inbound view of it (see {@code Loki#cutStoreFromPeers}). {@link #storePeersSpecExcluding}
   * already knows how to build the substituted value from a dial-port map, so this method's only
   * job is computing that map (empty when fault proxying is off) and handing planned commands
   * through {@link #withFlagValueReplaced}.
   */
  private void startStores(
      final MachinePlan machinePlan, final PortPlan ports, final PortLease lease) {
    final Map<Integer, Map<Integer, Integer>> raftDialPorts = new LinkedHashMap<>();
    if (loki != null) {
      for (int i = 0; i < spec.storeReplicas(); i++) {
        final Map<Integer, Integer> peerRaftPorts = new LinkedHashMap<>();
        for (int j = 0; j < spec.storeReplicas(); j++) {
          if (j != i) {
            peerRaftPorts.put(j, ports.storeRaftPorts.get(j));
          }
        }
        raftDialPorts.put(i, loki.interposeStoreToStores(i, host(), peerRaftPorts));
      }
    }
    final List<ProcessCommand> planned =
        commandsForRole(machinePlan, com.gimle.hilmir.topology.ProcessRole.STORE);
    for (int i = 0; i < planned.size(); i++) {
      ProcessCommand command = planned.get(i);
      if (loki != null) {
        final String peers = storePeersSpecExcluding(i, ports, raftDialPorts.get(i));
        command = withFlagValueReplaced(command, "--peers", peers);
      }
      lease.release(ports.storeRaftPorts.get(i));
      lease.release(ports.storeClientPorts.get(i));
      final ManagedProcess store = spawn(command);
      spawnOrder.add(store);
      stores.add(store);
      storeRaftPorts.add(ports.storeRaftPorts.get(i));
      storeClientPorts.add(ports.storeClientPorts.get(i));
    }
    for (final int clientPort : ports.storeClientPorts) {
      Polls.awaitPortOpen(host(), clientPort, STAGE_TIMEOUT);
    }
  }

  /**
   * Every other store's own raft peer address for store {@code excludeIndex}'s {@code --peers}
   * argument: dialed through {@code dialPortsByPeerIndex} (that store's own interposed raft
   * proxies, keyed by peer index) under fault proxying, the real raft ports otherwise. The raft id
   * every store computes for a peer (used only for that store's own local bookkeeping --
   * vote/commit counting never compares it against anything the peer itself asserts) is free to be
   * proxy-based without breaking consensus; the one known cost is that a leader's client-redirect
   * hint, which a follower resolves through its own peer-address-derived map, can miss under
   * proxying and fall back to {@code StoreClient}'s plain round-robin -- slower, never incorrect,
   * and irrelevant to a caller like this one that reads leadership straight from {@code
   * StatusResult} instead.
   */
  private String storePeersSpecExcluding(
      final int excludeIndex,
      final PortPlan ports,
      final Map<Integer, Integer> dialPortsByPeerIndex) {
    final List<String> peers = new ArrayList<>();
    for (int j = 0; j < ports.storeRaftPorts.size(); j++) {
      if (j == excludeIndex) {
        continue;
      }
      final int dialPort =
          dialPortsByPeerIndex != null ? dialPortsByPeerIndex.get(j) : ports.storeRaftPorts.get(j);
      peers.add(host() + ":" + dialPort + ":" + ports.storeClientPorts.get(j));
    }
    return String.join(",", peers);
  }

  private void startMuninn(
      final MachinePlan machinePlan, final PortPlan ports, final PortLease lease) {
    // Before Fafnir/control planes, not after: Muninn only needs the store, and bringing it up
    // this early means it is already reachable to receive shipped data from every process started
    // after it. LaunchPlanner's own global boot order already places its planned commands here.
    final List<ProcessCommand> planned =
        commandsForRole(machinePlan, com.gimle.hilmir.topology.ProcessRole.MUNINN);
    for (int i = 0; i < planned.size(); i++) {
      lease.release(ports.muninnPorts.get(i));
      final ManagedProcess muninnProcess = spawn(planned.get(i));
      spawnOrder.add(muninnProcess);
      muninns.add(muninnProcess);
    }
    for (final int port : ports.muninnPorts) {
      Polls.awaitPortOpen(host(), port, STAGE_TIMEOUT);
    }
  }

  private void startAndvari(
      final MachinePlan machinePlan, final PortPlan ports, final PortLease lease) {
    final List<ProcessCommand> planned =
        commandsForRole(machinePlan, com.gimle.hilmir.topology.ProcessRole.ANDVARI);
    for (int i = 0; i < planned.size(); i++) {
      lease.release(ports.andvariPorts.get(i));
      final ManagedProcess andvari = spawn(planned.get(i));
      spawnOrder.add(andvari);
      andvaris.add(andvari);
    }
    for (final int port : ports.andvariPorts) {
      Polls.awaitPortOpen(host(), port, STAGE_TIMEOUT);
    }
  }

  private void startFafnirs(
      final MachinePlan machinePlan, final PortPlan ports, final PortLease lease) {
    final List<ProcessCommand> planned =
        commandsForRole(machinePlan, com.gimle.hilmir.topology.ProcessRole.FAFNIR);
    for (int i = 0; i < planned.size(); i++) {
      lease.release(ports.fafnirPorts.get(i));
      final ManagedProcess fafnir = spawn(planned.get(i));
      spawnOrder.add(fafnir);
      fafnirs.add(fafnir);
    }
    for (final int port : ports.fafnirPorts) {
      Polls.awaitPortOpen(host(), port, STAGE_TIMEOUT);
    }
  }

  /**
   * {@link LaunchPlanner} always plans every control-plane replica's {@code --store-endpoints}
   * against the topology's own real store addresses -- it has no notion of a fault-injecting proxy.
   * Under fault proxying, this replica's real view of the stores must instead run through Loki's
   * own per-replica proxy sockets, which Loki only binds lazily, right here at boot time, from the
   * real (already-leased) store client ports -- a genuine runtime dependency a pure planner cannot
   * express. The fix is a post-hoc, single-flag substitution on the planner's own output: {@code
   * --store-endpoints}'s value is always exactly one token and {@link LaunchPlanner} never emits
   * any other flag with that literal name, so replacing it via {@link #withFlagValueReplaced} is
   * safe and keeps {@code gimle-hilmir} itself completely untouched.
   *
   * <p>Unlike the store-to-store substitution in {@link #startStores}, this one is genuinely
   * per-replica rather than upfront: each replica's own Loki proxies are registered as that
   * replica's own command is about to spawn, exactly matching this method's own original spawn
   * order (control planes come up and are awaited for readiness one at a time, not all at once).
   */
  private void startControlPlanes(
      final MachinePlan machinePlan, final PortPlan ports, final PortLease lease) {
    final List<ProcessCommand> planned =
        commandsForRole(machinePlan, com.gimle.hilmir.topology.ProcessRole.CONTROL_PLANE);
    for (int i = 0; i < planned.size(); i++) {
      ProcessCommand command = planned.get(i);
      if (loki != null) {
        final String storeEndpoints =
            endpointsSpec(loki.interposeControlPlaneToStores(i, host(), ports.storeClientPorts));
        command = withFlagValueReplaced(command, "--store-endpoints", storeEndpoints);
      }
      lease.release(ports.controlPlanePorts.get(i));
      final ManagedProcess controlPlane = spawn(command);
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

  /**
   * {@link ProcessCommand#needsBootstrapToken()} is {@code true} for every mtls agent command
   * {@link LaunchPlanner} produces -- minting the token itself requires a live call against an
   * already-running control plane, which is exactly what a pure planner cannot do, so the launcher
   * (here) mints it and appends {@code -Dgimle.tls.bootstrapToken=<token>} via {@link
   * #withAppendedArg} before spawning.
   */
  private void startAgents(
      final MachinePlan machinePlan, final PortPlan ports, final PortLease lease) {
    final List<ProcessCommand> planned =
        commandsForRole(machinePlan, com.gimle.hilmir.topology.ProcessRole.AGENT);
    for (int i = 0; i < planned.size(); i++) {
      ProcessCommand command = planned.get(i);
      if (command.needsBootstrapToken()) {
        final String token = mintBootstrapToken(javaExecutablePath, classpath);
        command = withAppendedArg(command, "-Dgimle.tls.bootstrapToken=" + token);
      }
      lease.release(ports.gossipPorts.get(i));
      final ManagedProcess agent = spawn(command);
      spawnOrder.add(agent);
      agents.put(spec.nodes().get(i).id(), agent);
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

  private static String endpointsSpecOf(final String host, final List<Integer> ports) {
    final List<String> endpoints = new ArrayList<>();
    for (final int port : ports) {
      endpoints.add(host + ":" + port);
    }
    return String.join(",", endpoints);
  }

  private String endpointsSpec(final List<Integer> ports) {
    return endpointsSpecOf(host(), ports);
  }

  /** The cluster's whole port budget, leased up front and assigned to roles deterministically. */
  private record PortPlan(
      String host,
      List<Integer> storeRaftPorts,
      List<Integer> storeClientPorts,
      List<Integer> fafnirPorts,
      List<Integer> controlPlanePorts,
      List<Integer> gossipPorts,
      List<Integer> muninnPorts,
      List<Integer> andvariPorts,
      PortLease leased) {

    static PortPlan allocate(final ClusterSpec spec, final String host) {
      final int count =
          spec.storeReplicas() * 2
              + spec.fafnirReplicas()
              + spec.controlPlaneReplicas()
              + spec.nodes().size()
              + spec.muninnReplicas()
              + spec.andvariReplicas();
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
      final List<Integer> muninn = new ArrayList<>();
      for (int i = 0; i < spec.muninnReplicas(); i++) {
        muninn.add(ports.get(next++));
      }
      final List<Integer> andvari = new ArrayList<>();
      for (int i = 0; i < spec.andvariReplicas(); i++) {
        andvari.add(ports.get(next++));
      }
      return new PortPlan(
          host, storeRaft, storeClient, fafnir, controlPlane, gossip, muninn, andvari, lease);
    }

    PortLease lease() {
      return leased;
    }

    /** Every configured Muninn replica's own {@code host:port}, comma-joined. */
    String muninnEndpointsSpec() {
      return endpointsSpecOf(host, muninnPorts);
    }

    /** Every configured Andvari replica's own {@code host:port}, comma-joined. */
    String andvariEndpointsSpec() {
      return endpointsSpecOf(host, andvariPorts);
    }

    /** Every *other* Andvari replica's {@code host:port}, for one replica's own peer-sync list. */
    String andvariPeersSpecExcluding(final int excludeIndex) {
      final List<String> peers = new ArrayList<>();
      for (int i = 0; i < andvariPorts.size(); i++) {
        if (i != excludeIndex) {
          peers.add(host + ":" + andvariPorts.get(i));
        }
      }
      return String.join(",", peers);
    }

    String storeEndpointsSpec() {
      return endpointsSpecOf(host, storeClientPorts);
    }
  }
}
