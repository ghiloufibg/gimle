package com.gimle.holmgang.cluster;

import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
  private Heimdall heimdall;
  private ManagedProcess muninn;
  private Path tlsDir;
  private HttpClient operatorClient;
  private Loki loki;
  private String javaExecutablePath;
  private String classpath;
  private int muninnPort = -1;

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

  public GimleProcess muninn() {
    if (muninn == null) {
      throw new HolmgangException("topology " + spec.name() + " does not enable Muninn");
    }
    return muninn;
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
    final String leaderId =
        storeLeaderId()
            .orElseThrow(() -> new HolmgangException("no store leader is currently known"));
    for (int i = 0; i < stores.size(); i++) {
      if (leaderId.equals(storeRaftId(i))) {
        return stores.get(i);
      }
    }
    throw new HolmgangException("store leader " + leaderId + " matches no known store process");
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
        command.add("-Dgimle.store.muninnEndpoint=" + host() + ":" + muninnPort);
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
    this.muninnPort = ports.muninnPort();
    try (PortLease lease = ports.lease()) {
      startStores(javaExecutable, classpath, ports, lease);
      startMuninn(javaExecutable, classpath, ports, lease);
      startAndvari(javaExecutable, classpath, ports, lease);
      startFafnirs(javaExecutable, classpath, ports, lease);
      startControlPlanes(javaExecutable, classpath, ports, lease);
      applySeed();
      startAgents(javaExecutable, classpath, ports, lease);
    }
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

  private void startStores(
      final String java, final String classpath, final PortPlan ports, final PortLease lease) {
    for (int i = 0; i < spec.storeReplicas(); i++) {
      final int raftPort = ports.storeRaftPorts.get(i);
      final int clientPort = ports.storeClientPorts.get(i);
      final List<String> command = new ArrayList<>();
      command.add(java);
      // No per-store leaf exists in the generated material; the store presents the control-plane
      // certificate, the same reuse the platform's own TLS bootstrap ships with today.
      command.addAll(tlsFlags("controlplane"));
      command.addAll(spec.jvmFlags(ProcessRole.STORE));
      if (spec.muninnEnabled()) {
        command.add("-Dgimle.store.muninnEndpoint=" + host() + ":" + ports.muninnPort);
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
              host() + ":" + clientPort);
      spawnOrder.add(store);
      stores.add(store);
      storeRaftPorts.add(raftPort);
      storeClientPorts.add(clientPort);
    }
    for (final int clientPort : ports.storeClientPorts) {
      Polls.awaitPortOpen(host(), clientPort, STAGE_TIMEOUT);
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
    command.addAll(tlsFlags("muninn"));
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
            host() + ":" + ports.muninnPort);
    spawnOrder.add(muninn);
    Polls.awaitPortOpen(host(), ports.muninnPort, STAGE_TIMEOUT);
  }

  private void startAndvari(
      final String java, final String classpath, final PortPlan ports, final PortLease lease) {
    if (spec.andvariReplicas() == 0) {
      return;
    }
    // Like Muninn, Andvari needs only the store, so it comes up before the control planes and
    // agents that resolve module coordinates through it.
    for (int i = 0; i < spec.andvariReplicas(); i++) {
      final int port = ports.andvariPorts.get(i);
      final List<String> command = new ArrayList<>();
      command.add(java);
      command.addAll(tlsFlags("andvari"));
      command.addAll(spec.jvmFlags(ProcessRole.ANDVARI));
      command.addAll(List.of("-cp", classpath, "com.gimle.andvari.AndvariMain"));
      command.add(String.valueOf(port));
      command.addAll(List.of("--store-endpoints", ports.storeEndpointsSpec()));
      command.addAll(List.of("--data-root", workDir.resolve("andvari-data-" + i).toString()));
      final String peers = ports.andvariPeersSpecExcluding(i);
      if (!peers.isBlank()) {
        command.addAll(List.of("--peer-endpoints", peers));
      }
      lease.release(port);
      final ManagedProcess andvari =
          new ManagedProcess(
              ProcessRole.ANDVARI,
              "andvari-" + i,
              command,
              workDir.resolve("andvari-" + i + ".log"),
              host() + ":" + port);
      spawnOrder.add(andvari);
      andvaris.add(andvari);
    }
    for (final int port : ports.andvariPorts) {
      Polls.awaitPortOpen(host(), port, STAGE_TIMEOUT);
    }
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
      command.addAll(tlsFlags("fafnir"));
      command.addAll(spec.jvmFlags(ProcessRole.FAFNIR));
      if (spec.muninnEnabled()) {
        command.add("-Dgimle.fafnir.muninnEndpoint=" + host() + ":" + ports.muninnPort);
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
              host() + ":" + port);
      spawnOrder.add(fafnir);
      fafnirs.add(fafnir);
    }
    for (final int port : ports.fafnirPorts) {
      Polls.awaitPortOpen(host(), port, STAGE_TIMEOUT);
    }
  }

  private void startControlPlanes(
      final String java, final String classpath, final PortPlan ports, final PortLease lease) {
    for (int i = 0; i < spec.controlPlaneReplicas(); i++) {
      final int port = ports.controlPlanePorts.get(i);
      // Round-robin across the Fafnir replicas -- FafnirClient talks to exactly one address, so
      // this is what makes every replica independently exercised rather than just replica #0.
      final int fafnirPort = ports.fafnirPorts.get(i % ports.fafnirPorts.size());
      // Under fault proxying, each replica gets its own interposed store endpoints -- which is
      // exactly what lets Loki cut one replica's view of the store without touching another's.
      final String storeEndpoints =
          loki != null
              ? endpointsSpec(loki.interposeControlPlaneToStores(i, host(), ports.storeClientPorts))
              : ports.storeEndpointsSpec();
      final List<String> command = new ArrayList<>();
      command.add(java);
      command.addAll(tlsFlags("controlplane"));
      if (mtls) {
        // The CA key enables /bootstrap/csr and /bootstrap/tokens -- agents certificate-bootstrap
        // through this control plane.
        command.add("-Dgimle.pki.caKeyFile=" + tlsDir.resolve("ca.key"));
      }
      command.addAll(spec.jvmFlags(ProcessRole.CONTROL_PLANE));
      // Per-replica, not left at ControlPlaneMain's own cwd-relative "gimle-data" default: that
      // default is exactly what startAgents already avoids below via its own gimle.data.root, and
      // for the same reason -- a control plane resolving registry-coordinate artifacts through
      // ArtifactPullCache persistently caches jars by presence alone, so an unscoped path would
      // survive this run's own workDir and silently poison the next run against a stale jar
      // cached under a coordinate the next run's fresh Andvari instance re-pushes with new bytes.
      command.add("-Dgimle.data.root=" + workDir.resolve("controlplane-data-" + i));
      command.addAll(List.of("-cp", classpath, "com.gimle.controlplane.ControlPlaneMain"));
      command.add(String.valueOf(port));
      command.add(workDir.resolve("controlplane-secret-" + i + ".key").toString());
      command.addAll(List.of("--store-endpoints", storeEndpoints));
      command.addAll(List.of("--fafnir-endpoint", host() + ":" + fafnirPort));
      if (spec.muninnEnabled()) {
        command.addAll(List.of("--muninn-endpoint", host() + ":" + ports.muninnPort));
      }
      if (spec.andvariReplicas() > 0) {
        command.addAll(List.of("--andvari-endpoint", ports.andvariEndpointsSpec()));
      }
      lease.release(port);
      final ManagedProcess controlPlane =
          new ManagedProcess(
              ProcessRole.CONTROL_PLANE,
              "controlplane-" + i,
              command,
              workDir.resolve("controlplane-" + i + ".log"),
              host() + ":" + port);
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
    // One token per agent, minted through the real /bootstrap/tokens surface with the operator
    // identity -- so every mTLS topology exercises certificate bootstrap for free.
    String firstGossipAddress = null;
    for (int i = 0; i < spec.nodes().size(); i++) {
      final NodeSpec node = spec.nodes().get(i);
      final int gossipPort = ports.gossipPorts.get(i);
      final String gossipAddress = host() + ":" + gossipPort;
      // The first agent seeds nothing; every later one seeds off the first, so a multi-node
      // topology forms one gossip cluster rather than several singletons.
      final String seeds = firstGossipAddress == null ? "-" : firstGossipAddress;
      if (firstGossipAddress == null) {
        firstGossipAddress = gossipAddress;
      }
      final int fafnirPort = ports.fafnirPorts.get(i % ports.fafnirPorts.size());
      final List<String> command = new ArrayList<>();
      command.add(java);
      // The agent's own leaf does not exist yet: it generates a keypair and CSR against these
      // paths and bootstraps its certificate through the control plane, gated by the token.
      command.addAll(tlsFlags("node-" + node.id()));
      if (mtls) {
        command.add("-Dgimle.tls.bootstrapToken=" + mintBootstrapToken(java, classpath));
      }
      command.addAll(spec.jvmFlags(ProcessRole.AGENT));
      command.add("-Dgimle.agent.fafnirEndpoint=" + host() + ":" + fafnirPort);
      if (spec.muninnEnabled()) {
        command.add("-Dgimle.agent.muninnEndpoint=" + host() + ":" + ports.muninnPort);
      }
      if (spec.andvariReplicas() > 0) {
        command.add("-Dgimle.agent.andvariEndpoint=" + ports.andvariEndpointsSpec());
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
      int muninnPort,
      List<Integer> andvariPorts,
      PortLease leased) {

    static PortPlan allocate(final ClusterSpec spec, final String host) {
      final int count =
          spec.storeReplicas() * 2
              + spec.fafnirReplicas()
              + spec.controlPlaneReplicas()
              + spec.nodes().size()
              + (spec.muninnEnabled() ? 1 : 0)
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
      final int muninn = spec.muninnEnabled() ? ports.get(next++) : -1;
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

    String storePeersSpecExcluding(final int excludeIndex) {
      final List<String> peers = new ArrayList<>();
      for (int i = 0; i < storeRaftPorts.size(); i++) {
        if (i == excludeIndex) {
          continue;
        }
        peers.add(host + ":" + storeRaftPorts.get(i) + ":" + storeClientPorts.get(i));
      }
      return String.join(",", peers);
    }
  }
}
