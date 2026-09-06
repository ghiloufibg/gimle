package com.gimle.agent;

import com.gimle.agent.bifrost.BifrostProxy;
import com.gimle.agent.bifrost.BifrostSettings;
import com.gimle.agent.bifrost.HttpServiceSource;
import com.gimle.agent.networkpolicy.HttpNetworkPolicySource;
import com.gimle.core.banner.GimleBanner;
import com.gimle.core.banner.GimleVersion;
import com.gimle.core.exception.GimleClusterException;
import com.gimle.core.exception.GimleIsolationException;
import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.exception.GimleSecretsException;
import com.gimle.core.exception.GimleTlsException;
import com.gimle.core.logging.GimleLogging;
import com.gimle.core.logging.LogFileReader;
import com.gimle.core.module.ArtifactKind;
import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.core.module.ReclaimPolicy;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.core.module.VolumeRequest;
import com.gimle.core.net.DnsCacheTtl;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.protocol.CsrPurpose;
import com.gimle.core.protocol.CsrRequestStatus;
import com.gimle.core.protocol.CsrResult;
import com.gimle.core.protocol.CsrSubmission;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.Json;
import com.gimle.core.restart.RestartTracker;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.core.vessel.VesselEntrypoint;
import com.gimle.core.vessel.VesselEnvValue;
import com.gimle.core.vessel.VesselFileMount;
import com.gimle.core.vessel.VesselProbeSpec;
import com.gimle.core.vessel.VesselProbes;
import com.gimle.core.vessel.VesselSpec;
import com.gimle.fabric.catalog.CatalogDelta;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.fabric.cluster.GossipConfig;
import com.gimle.fabric.cluster.GossipMember;
import com.gimle.fabric.cluster.MemberId;
import com.gimle.mimir.authz.CertificateRotationAuditor;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.module.artifact.ArtifactPullCache;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.artifact.ResolvedArtifact;
import com.gimle.module.artifact.VesselEntrypointParser;
import com.gimle.observability.AgentMetrics;
import com.gimle.observability.CertificateRotationMetrics;
import com.gimle.observability.MuninnShipper;
import com.gimle.os.ResourceLimitHandle;
import com.gimle.os.ResourceLimiter;
import com.gimle.os.VolumeHandle;
import com.gimle.os.VolumeManager;
import com.gimle.os.localdisk.LocalDiskVolumeManager;
import com.gimle.os.portable.PortableJvmFlagsResourceLimiter;
import com.gimle.pki.CertificateRotationListener;
import com.gimle.pki.CertificateRotationMonitor;
import com.gimle.pki.CertificateRotationStatus;
import com.gimle.pki.CertificateSigningRequests;
import com.gimle.pki.Pem;
import com.gimle.pki.RenewalSchedule;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The node agent's entry point. Registers with the control plane once, then loops forever: polls
 * {@code GET /nodes/{nodeId}/assignments} and reconciles the locally-supervised {@link
 * WorkerProcessSupervisor} set against it (spawning a worker JVM per newly-assigned instance,
 * tearing one down per instance no longer assigned -- each replica gets its own worker JVM,
 * matching the scheduler's anti-affinity assumption), then reports a heartbeat.
 *
 * <p>Independent of that control-plane loop, this agent also runs a {@link GossipMember} (SWIM
 * membership over UDP, joined via {@code seeds}) carrying a {@link ServiceCatalog} on its gossip
 * piggyback channel: it folds {@code ServiceRegistered}/{@code ServiceUnregistered} reports from
 * its own supervised workers into the catalog, and relays every genuinely new delta -- local or
 * learned from gossip about a remote node -- back down to every supervised worker as a {@code
 * CatalogUpdate}, so each worker's own {@code FabricServiceRegistry} stays eventually consistent
 * without ever querying a central catalog service.
 */
public final class AgentMain {

  private static final Logger log = LoggerFactory.getLogger(AgentMain.class);
  private static final Duration TICK_INTERVAL = Duration.ofSeconds(5);
  private static final AtomicLong CORRELATION_COUNTER = new AtomicLong();

  /**
   * Which {@code supervised} key a lifecycle-command correlation id was sent on behalf of -- {@link
   * ControlMessage.Nack} carries no {@link ModuleId} (unlike {@code ModuleStateChanged}/ {@code
   * HealthReport}/etc.), so under Tier 1 density {@link #readLoop} has no other way to tell a
   * packed sibling's own Install/Resolve/Start/Stop failure apart from the connection-owning
   * instance's. Without this, a nacked command belonging to a packed sibling silently updated the
   * wrong {@code SupervisedInstance} (the owner's, via {@link #readLoop}'s own {@code instance}
   * parameter) -- typically a no-op, since the owner is rarely sitting at {@code INSTALLED} -- and
   * the sibling itself stayed stuck at {@code INSTALLED} forever with no diagnostic trail naming it
   * at all. Entries are removed as their correlation id is observed (see {@link #readLoop}); a
   * connection that drops before a reply arrives leaves a small number of orphaned entries behind,
   * an accepted cost against this map growing without bound for the life of the agent process.
   */
  private static final Map<String, String> pendingLifecycleCorrelations = new ConcurrentHashMap<>();

  /** Request timeout for every outbound HTTP call this agent makes to the control plane. */
  private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private static final Duration REGISTRATION_INITIAL_BACKOFF = Duration.ofSeconds(1);

  private static final Duration REGISTRATION_MAX_BACKOFF = Duration.ofSeconds(30);

  /** Connect timeout for every {@link HttpClient} this agent builds. */
  private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(5);

  /**
   * The restart-backoff policy shared by every vessel and worker process this agent supervises: 1s
   * initial delay, doubling, capped at 30s, up to 5 attempts per 10-minute window.
   */
  private static RestartTracker defaultRestartTracker() {
    return new RestartTracker(
        Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30), 5, Duration.ofMinutes(10));
  }

  /**
   * How long {@link #stopInstance} waits for a worker to confirm {@code UNINSTALLED} after {@code
   * StopModule} before giving up and force-killing it anyway -- see that method's own javadoc for
   * why this wait exists at all. Generous relative to the sub-second, same-machine IPC round trip a
   * graceful stop ordinarily takes (send StopModule, run any {@code onStop} hook, drain, send
   * {@code ServiceUnregistered} then {@code ModuleStateChanged}), while still bounding how long a
   * genuinely wedged worker can stall this agent's single-threaded tick loop.
   */
  private static final Duration STOP_GRACE_PERIOD = Duration.ofSeconds(5);

  private static final Duration STOP_GRACE_POLL_INTERVAL = Duration.ofMillis(100);

  /**
   * The default Tier 1 density cap: the most Tier-1 instances this agent will pack into one shared
   * worker JVM before preferring a fresh one, unless {@link #MAX_TIER1_DENSITY_PROPERTY} says
   * otherwise. A count, not a weight -- {@link Tier1WorkerBudget} is what stops a worker being
   * oversubscribed by heap, and this bounds how many co-tenants share one JVM's scheduling,
   * connection pool and crash domain regardless of how small each one's declared limit is. Four is
   * a deliberately conservative starting point rather than a measured optimum. See {@link
   * #findReusableTier1Worker} for the rest of the reuse decision (same node implicitly, since this
   * only ever scans this agent's own {@code supervised} map; same tenant or both untenanted; never
   * the same placement twice; and the budget check above).
   */
  static final int DEFAULT_MAX_TIER1_DENSITY = 4;

  /**
   * Overrides {@link #DEFAULT_MAX_TIER1_DENSITY}. Same optional-system-property shape as {@code
   * gimle.agent.bifrostEnabled}/{@code gimle.agent.fafnirEndpoint} -- unset means the default, and
   * {@code 1} disables packing entirely (every Tier-1 instance gets its own worker JVM).
   */
  static final String MAX_TIER1_DENSITY_PROPERTY = "gimle.agent.maxTier1Density";

  /**
   * How often each {@link MuninnShipper} instance ticks -- own logs and every supervised worker's.
   */
  private static final Duration MUNINN_SHIP_INTERVAL = Duration.ofSeconds(5);

  /**
   * Enables the retaining-path attribution {@code OldObjectSampleCorrelator} (gimle-module) can
   * only surface when the worker JVM itself was launched with {@code path-to-gc-roots=true} -- that
   * setting is a recording-launch option, not something settable through the in-process {@code
   * RecordingStream} API a worker's own leak detector uses. Always-on, not tied to any {@code
   * ResourceSpec}: it's an observability concern every worker JVM needs regardless of its isolation
   * tier.
   */
  private static final String LEAK_DETECTION_JFR_FLAG =
      "-XX:StartFlightRecording:name=gimle-leak-detection,disk=false,settings=profile,path-to-gc-roots=true";

  /**
   * The hard-coded whitelist a worker-relayed {@code RelayControlPlaneRead} path is checked against
   * before this agent ever makes a real call on the module's behalf -- the trust boundary of the
   * whole mechanism, since a worker JVM (and the hosted module running inside it) is never trusted
   * to only ask for something this agent would have allowed. Exactly {@code GET /endpoints/{name}}
   * today: one path segment of allowed characters, no {@code /} (rules out a smuggled second
   * segment or a traversal attempt across segments), no {@code ?}/{@code #} (rules out a query
   * string or fragment sneaking in extra semantics), and never a whole segment of just {@code .} or
   * {@code ..} (the one way a single-segment path can still mean "go up a level" once resolved
   * against this agent's own base URL).
   */
  private static final Pattern RELAY_WHITELIST_PATTERN =
      Pattern.compile("^/endpoints/(?!\\.{1,2}$)[a-zA-Z0-9._-]+$");

  private AgentMain() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    // The tick loop's own catch (Error e) below only covers Errors thrown by that one thread.
    // This agent starts several others (gossip, the admin API, the config/network-policy relays,
    // Bifrost) with no such explicit guard of their own; an OutOfMemoryError on any of them would
    // otherwise just kill that one thread (the JVM's own default uncaught-exception behavior)
    // while every other non-daemon thread keeps the process alive as an unresponsive zombie --
    // the same failure mode handleFatalTickError exists to avoid for the main thread, generalized
    // here to every thread this process ever starts. An ordinary (non-Error) uncaught exception is
    // left to the default handler's usual stderr print -- that thread dying alone is the existing,
    // unremarkable behavior for a real bug in background work, not a reason to halt the process.
    Thread.setDefaultUncaughtExceptionHandler(
        defaultUncaughtExceptionHandler(Runtime.getRuntime()::halt));
    DnsCacheTtl.apply();
    GimleBanner.print(
        System.out,
        Map.of(
            "app.name", "Gimlé Node Agent",
            "app.description", "worker supervision, resource assignment, capacity reporting",
            "app.version", GimleVersion.current()));
    if (args.length < 5) {
      System.err.println(
          "usage: AgentMain <nodeId> <controlPlaneBaseUrl> <gossipBindHost:port>"
              + " <seeds(host:port,host:port|-)> <javaExecutable> <worker-command-tail...>");
      System.exit(2);
      return;
    }
    String nodeId = args[0];
    URI baseUrl = URI.create(args[1]);
    InetSocketAddress gossipBindAddress = parseHostPort(args[2]);
    List<InetSocketAddress> seeds = parseSeeds(args[3]);
    String javaExecutable = args[4];
    List<String> commandTail = List.of(args).subList(5, args.length);
    // A system property, not a positional arg: commandTail above is already fully variadic
    // (everything from index 5 on), so a new required positional arg has nowhere to go without
    // breaking that convention -- matches gimle.node.labels/gimle.tls.* already using this same
    // mechanism for agent-side configuration. Null (unset) is a legitimate, supported state: an
    // agent with no tenant ever using secrets never needs Fafnir configured at all.
    String fafnirEndpoint = System.getProperty("gimle.agent.fafnirEndpoint");
    URI fafnirBaseUrl =
        fafnirEndpoint == null ? null : URI.create((baseUrl.getScheme()) + "://" + fafnirEndpoint);
    // Same optional-system-property posture as gimle.agent.fafnirEndpoint above: null means "ship
    // nowhere," and local-only tailing via AgentLogServer keeps working entirely unchanged.
    // Accepts a comma-separated list of Muninn replicas (each shipped to independently, see
    // MuninnShipper) as well as the original single-endpoint form, so an existing single-address
    // config keeps working unchanged.
    String muninnEndpoint = System.getProperty("gimle.agent.muninnEndpoint");
    // Same optional posture again: null is a legitimate state -- an agent whose assignments all
    // carry an explicit artifactPath never needs the artifact registry at all. One or more
    // comma-separated host:port entries, one per peer-syncing Andvari replica -- ArtifactPullCache
    // fails over between them itself (see its own javadoc), so an unreachable replica never stalls
    // a resolution a different configured one could have answered.
    String andvariEndpoint = System.getProperty("gimle.agent.andvariEndpoint");
    List<URI> andvariBaseUrls = parseAndvariEndpoints(andvariEndpoint, baseUrl.getScheme());
    // Same optional-system-property posture once more: null means the Admin Fault API never opens
    // a port on this agent at all -- an operator who never uses it (SSH-based fault injection, or
    // no chaos tool at all) sees zero behavior change. One or more comma-separated host:port
    // gimle-mimir store replicas, the same shape gimle.agent.storeEndpoints' sibling properties
    // already use.
    String storeEndpoint = System.getProperty("gimle.agent.storeEndpoints");
    // The Admin Fault API binds ephemerally (port 0) by default, discovered only via this agent's
    // own log line below -- fine for a human operator reading logs, useless for a static
    // `adminApi:` target document (or a test) that must know the port in advance. This lets either
    // pin it to a known value; still defaults to ephemeral so multiple agents on one host never
    // collide by accident.
    int adminApiPort = Integer.getInteger("gimle.agent.adminApiPort", 0);
    // Off by default -- an agent that never sets this property behaves exactly as it did before
    // Bifrost existed. Poll interval is its own separate property so an operator can tune
    // convergence latency without touching the control-plane-loop TICK_INTERVAL above, which
    // governs unrelated work.
    boolean bifrostEnabled =
        Boolean.parseBoolean(System.getProperty("gimle.agent.bifrostEnabled", "false"));
    // How many Tier-1 instances this node packs into one shared worker JVM. Read and validated
    // here, before anything is supervised, so a bad value fails the agent at startup rather than
    // silently reverting to the default the first time a Tier-1 instance is placed.
    int maxTier1Density = parseMaxTier1Density(System.getProperty(MAX_TIER1_DENSITY_PROPERTY));
    // How large each shared Tier 1 worker JVM is, and how much of it the instances packed into it
    // may claim between them. Read here for the same reason the density cap is: a malformed value
    // must fail the agent at startup, not the first time a Tier-1 instance is placed on it.
    Tier1WorkerBudget tier1Budget = Tier1WorkerBudget.fromSystemProperties();
    // The NodePort analogue: wildcard-bind each Bifrost listener at its service's own port so
    // callers off this node can dial <nodeHost>:<servicePort>. Off by default -- loopback-only,
    // today's posture.
    boolean bifrostExposeServices =
        Boolean.parseBoolean(System.getProperty("gimle.agent.bifrostExposeServices", "false"));
    // Bifrost's identity-verifying mode: terminate TLS on every listener and demand a
    // cluster-CA-signed client certificate, so a NetworkPolicySpec restricting a service is
    // enforced against the caller's certificate-carried tenant instead of failing the listener
    // closed. Requires the cluster transport itself to be TLS (the listener presents this agent's
    // own node certificate).
    boolean bifrostTls =
        Boolean.parseBoolean(System.getProperty("gimle.agent.bifrostTlsEnabled", "false"));
    Duration bifrostPollInterval =
        Duration.ofMillis(
            Long.parseLong(System.getProperty("gimle.agent.bifrostPollIntervalMillis", "5000")));
    // Unlike Bifrost above, always on rather than opt-in: relaying an (ordinarily empty) policy
    // set has no side effect of its own, only enforcement does, and enforcement is already
    // opt-in per policy -- an operator who has declared no NetworkPolicySpec sees this poller
    // ship an empty list every tick, exactly today's unchanged "every call permitted" behavior.
    Duration networkPolicyPollInterval =
        Duration.ofMillis(
            Long.parseLong(
                System.getProperty("gimle.agent.networkPolicyPollIntervalMillis", "5000")));

    System.setProperty("gimle.process.role", "AGENT");
    System.setProperty("gimle.node.id", nodeId);
    Path logRoot = Path.of(System.getProperty("gimle.log.root", "gimle-logs"));
    GimleLogging.attachPlatformFileAppender(logRoot.resolve("agent-platform.log"));

    // Before anything below that needs gimle.tls.certFile/keyFile to already point at real files
    // (the MuninnShipper construction just below above all): in TLS mode this agent starts with
    // no certificate of its own and only obtains one here, live, via the bootstrap CSR flow --
    // see this method's own javadoc. Constructing a mutual-TLS SSLContext any earlier would fail
    // outright since those files wouldn't exist yet.
    bootstrapCertificateIfNeeded(nodeId, baseUrl);

    // One Timer/Counter pair around this agent's own tick body -- constructed unconditionally
    // (cheap, in-memory-only unless shipped) so #agentTick can record into it regardless of
    // whether muninnEndpoint is configured.
    AgentMetrics agentMetrics = new AgentMetrics();

    if (muninnEndpoint != null) {
      List<String> muninnEndpoints = MuninnShipper.parseEndpoints(muninnEndpoint);
      // This agent's own platform log has no per-instance scoping -- ships once, for the whole
      // process lifetime, under this node's own identity (the same node-scoped ingest shape
      // MuninnServer registers alongside the instance-scoped one).
      MuninnShipper ownLogShipper =
          new MuninnShipper(
              muninnEndpoints, "/ingest/logs/nodes/" + nodeId + "/PLATFORM", MUNINN_SHIP_INTERVAL);
      ownLogShipper.startShippingLogFile(
          logRoot.resolve("agent-platform.log"), LogFileReader.configuredMaxFiles());
      new MuninnShipper(muninnEndpoints, "/ingest/metrics/AGENT/" + nodeId, MUNINN_SHIP_INTERVAL)
          .startShippingMetrics(agentMetrics.registry());
    }

    // Created here rather than at its previous spot further down, so AgentLogServer's own
    // resolver lambda below can already close over the real, live map instead of a snapshot: a
    // Tier 1 density-packed instance's logs live under a different instance's own worker
    // directory (see SupervisedInstance#workerKey), and this is how a log request finds it.
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    // Tracked separately from supervised: a vessel instance has no ControlChannelServer/
    // ModuleDescriptor/worker connection at all, so stretching SupervisedInstance to cover both
    // shapes would leave every module-only field meaningless for a vessel. Both maps are keyed
    // identically (deploymentName#instanceIndex) and both contribute to the same heartbeat.
    Map<String, SupervisedVessel> supervisedVessels = new ConcurrentHashMap<>();
    // StatefulSet-kind persistent storage -- a sibling data root to gimle.log.root above,
    // defaulting alongside it rather than under it, matching the same
    // "own top-level directory, own property" convention gimle.log.root itself established.
    // Created before the log server below so its /volumes surface can serve off it.
    Path dataRoot = Path.of(System.getProperty("gimle.data.root", "gimle-data"));
    VolumeManager volumeManager = new LocalDiskVolumeManager(dataRoot);
    AgentLogServer logServer =
        new AgentLogServer(
            logRoot,
            0,
            (tenantId, deploymentName, instanceIndex) ->
                workerDirectoryKey(
                    supervised, supervisedVessels, tenantId, deploymentName, instanceIndex),
            volumeManager,
            () ->
                supervised.values().stream()
                    .filter(instance -> !instance.volumeHandles.isEmpty())
                    .map(
                        instance ->
                            AgentLogServer.volumeKey(
                                instance.assigned.tenantId(),
                                instance.assigned.deploymentName(),
                                instance.assigned.instanceIndex()))
                    .collect(Collectors.toUnmodifiableSet()),
            // Read live per request rather than snapshotted: an instance's fabric address only
            // exists once its worker's Hello handshake lands, so a lookup a moment after placement
            // must see the address the moment it arrives, not a copy taken before it did.
            (tenantId, deploymentName, instanceIndex) -> {
              SupervisedInstance instance =
                  findSupervised(supervised, tenantId, deploymentName, instanceIndex);
              if (instance == null) {
                return Optional.empty();
              }
              return Optional.of(
                  new InstanceFabricEndpoint(
                      Optional.ofNullable(instance.fabricWorkerId),
                      Optional.ofNullable(instance.fabricTcpAddress),
                      instance.fabricUdsPath));
            });
    logServer.start();
    String apiAddress = resolveAdvertisedHost() + ":" + logServer.port();
    log.info("agent {} serving logs at {}", nodeId, apiAddress);

    ResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    // Registry-pulled jars land beside the volume roots under the same gimle.data.root -- one
    // node-local data directory, not a second property.
    ArtifactPullCache artifactCache = new ArtifactPullCache(dataRoot.resolve("artifact-cache"));
    // Same one-node-local-data-directory convention as artifactCache above. SleipnirCache owns key
    // computation/file state/sweep; SleipnirTrainer owns only the training subprocess orchestration
    // and calls into SleipnirCache to commit -- kept as two classes because their concurrency
    // shapes genuinely differ (cacheFor is fast/synchronous/hot-path, training is slow/background/
    // one-shot), unlike ArtifactPullCache's own single-class precedent where both operations are
    // fast and synchronous. supervised is passed by reference (not copied) since sweep() needs the
    // live view of which cache keys are still referenced by a currently-running worker.
    SleipnirCache sleipnirCache =
        new SleipnirCache(dataRoot.resolve("aot-cache"), supervised, javaExecutable);
    // commandTail is already known at this point (a CLI argument parsed at the very top of main),
    // so training can start immediately -- off a background thread, non-blocking -- the same
    // "construct, then start()" shape as NetworkPolicyRelay below. Every worker spawn from here on
    // checks SleipnirCache.cacheFor and benefits once training completes; nothing blocks on it.
    SleipnirTrainer sleipnirTrainer = new SleipnirTrainer(javaExecutable, sleipnirCache);
    sleipnirTrainer.start(commandTail);
    CapacityTracker capacityTracker = CapacityTracker.ofThisMachine();
    // A second, separately-scoped tracker from capacityTracker above: that one sums each
    // instance's own declared *request* (an oversubscription-style scheduling figure, read
    // elsewhere via its own snapshot()), while this one sums the real committed ceiling
    // (handle.limit()) a freshly spawned worker JVM is actually started with -- the only figure
    // that can genuinely exhaust this machine's real memory. Reserved only at the point a new
    // worker JVM is spawned (startInstance), never for an instance packed into an already-running
    // shared worker (installIntoExistingWorker), since packing costs no additional real memory.
    CapacityTracker committedWorkerCapacity = CapacityTracker.ofThisMachine();
    // Which start failure each assigned instance has already been reported for, so a
    // level-triggered retry of an unfixable start doesn't re-post the same event every tick. See
    // reportStartFailure.
    Map<String, String> reportedStartFailures = new ConcurrentHashMap<>();
    HttpClient httpClient = buildHttpClient();
    // Keyed the same way supervised is (deploymentName#instanceIndex): a supervised instance's
    // pair of MuninnShippers (its worker's own PLATFORM log, its own APPLICATION log), started
    // the same tick the instance is added to supervised and closed the same tick it's removed.
    // Empty (never populated) when muninnEndpoint is unset.
    Map<String, List<MuninnShipper>> instanceShippers = new ConcurrentHashMap<>();
    // Keyed by the worker-JVM-generated Hello#workerId, NOT by the agent-side instance key
    // instanceShippers above uses -- one metrics shipper and one traces shipper per worker
    // *process*, established the moment that worker's Hello arrives (readLoop) and closed when
    // the last SupervisedInstance sharing its connection is torn down
    // (stopInstance). Empty (never populated) when muninnEndpoint is unset, same as
    // instanceShippers.
    Map<String, WorkerShipperPair> workerShippers = new ConcurrentHashMap<>();

    MemberId self = new MemberId(nodeId, gossipBindAddress);
    GossipMember gossipMember = new GossipMember(self, GossipConfig.defaults());
    ServiceCatalog catalog = new ServiceCatalog();
    gossipMember.attachCatalog(catalog);
    // React to SWIM's own DEAD/ALIVE convergence directly, rather than leaving every caller to
    // rediscover a dead node's unreachability independently through its own circuit breaker.
    gossipMember.onMembershipChange(catalog::onMembershipChange);
    catalog.onDelta(delta -> relayCatalogDelta(delta, supervised));
    gossipMember.start();
    gossipMember.join(seeds);
    log.info("agent {} gossip member listening at {}", nodeId, gossipMember.self().gossipAddress());

    AgentGossipServer gossipServer = new AgentGossipServer(gossipMember, 0);
    gossipServer.start();
    log.info("agent {} serving gossip membership at :{}", nodeId, gossipServer.port());

    // Constructed only when storeEndpoint is configured -- see AgentAdminServer's own javadoc for
    // why this is the one HTTP surface on this agent with its own independent StoreClient/
    // Authorizer, matching Fafnir/Andvari/Muninn's defense-in-depth pattern rather than
    // AgentLogServer's "trust the network topology" posture.
    AgentAdminServer adminServer = null;
    // Shared with the certificate-rotation monitor below, which appends its own durable audit
    // events through the same client -- an agent with no store endpoints configured keeps every
    // rotation signal except that durable trail.
    StoreClient storeClient = null;
    if (storeEndpoint != null && !storeEndpoint.isBlank()) {
      storeClient = new StoreClient(parseStoreEndpoints(storeEndpoint));
      adminServer = new AgentAdminServer(storeClient, adminApiPort, supervised);
      adminServer.start();
      log.info("agent {} serving admin fault API at :{}", nodeId, adminServer.port());
    }

    // Every rotation check -- including one that fails -- is metered into the same registry this
    // agent already ships to Muninn and, at the start and the escalation point of a failure
    // streak, appended to the durable audit trail. A rotation that quietly stops working is
    // harmless only until the certificate it failed to renew expires, which is exactly the failure
    // an operator must be able to see coming.
    CertificateRotationMetrics certificateRotationMetrics =
        new CertificateRotationMetrics(agentMetrics.registry());
    CertificateRotationListener rotationListener =
        status ->
            certificateRotationMetrics.recordCheck(
                status.outcome().name(),
                status.consecutiveFailures(),
                status.remainingValidity(Instant.now()));
    if (storeClient != null) {
      rotationListener =
          new CertificateRotationAuditor(storeClient, nodeId).andThen(rotationListener);
    }
    CertificateRotationMonitor rotationMonitor =
        new CertificateRotationMonitor("agent " + nodeId, TICK_INTERVAL, rotationListener);

    registerWithRetry(httpClient, baseUrl, nodeId, resourceLimiter, apiAddress);
    log.info("agent {} registered with control plane at {}", nodeId, baseUrl);

    // Shared by BifrostProxy and NetworkPolicyRelay below -- HttpNetworkPolicySource is stateless
    // (each call is its own independent HTTP round trip), so one instance safely serves both
    // consumers rather than each opening a second, identical one.
    HttpNetworkPolicySource networkPolicySource = new HttpNetworkPolicySource(httpClient, baseUrl);

    // Independent of the tick loop below (its own self-scheduled poller, same shape as the
    // MuninnShipper construction above): a caller on this node dials a service's synthesized
    // loopback ClusterIP, and Bifrost forwards to one of that service's live endpoints, preferring
    // ones on this same node. A NetworkPolicySpec restricting a service makes Bifrost fail closed
    // by default (it relays opaque bytes, so unlike FabricServer it has no caller tenant identity
    // to check a policy's allow list against) -- unless the TLS-terminating mode is on, which
    // gives each listener a verified certificate-carried caller tenant to enforce the policy with.
    if (bifrostEnabled) {
      Optional<SSLContext> bifrostTlsContext = Optional.empty();
      if (bifrostTls) {
        if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
          log.warn(
              "gimle.agent.bifrostTlsEnabled is set but the cluster transport is plaintext --"
                  + " bifrost has no certificate material to terminate TLS with, starting in"
                  + " plaintext fail-closed mode instead");
        } else {
          bifrostTlsContext = Optional.of(SslContexts.forMutualTls(TlsSettings.fromConfig()));
        }
      }
      BifrostProxy bifrostProxy =
          new BifrostProxy(
              new HttpServiceSource(httpClient, baseUrl),
              networkPolicySource,
              new BifrostSettings(
                  bifrostPollInterval,
                  bifrostExposeServices,
                  Optional.of(nodeId),
                  bifrostTlsContext));
      bifrostProxy.start();
      log.info(
          "agent {} started bifrost service proxy{}{}",
          nodeId,
          bifrostExposeServices ? " (exposing services on all interfaces)" : "",
          bifrostTlsContext.isPresent() ? " (TLS identity-verifying mode)" : "");
    }

    // Same self-scheduled-poller shape as BifrostProxy just above, relaying down to every
    // supervised worker's own FabricServer instead of binding local listeners -- see
    // NetworkPolicyRelay's own javadoc for why it isn't itself in the bifrost package.
    NetworkPolicyRelay networkPolicyRelay =
        new NetworkPolicyRelay(networkPolicySource, networkPolicyPollInterval, supervised);
    networkPolicyRelay.start();
    log.info("agent {} started network policy relay", nodeId);

    // Same relay shape once more, for config and secrets: re-fetch on an interval and re-send
    // only what changed, so a config edit or rotated secret reaches a running instance instead
    // of waiting for its next restart. 0 disables the relay entirely (initial-delivery-only,
    // the pre-relay behavior).
    long configRelayIntervalMillis =
        Long.parseLong(System.getProperty("gimle.agent.configRelayIntervalMillis", "30000"));
    if (configRelayIntervalMillis > 0) {
      // Captures the startup HttpClient, the same way HttpNetworkPolicySource/HttpServiceSource
      // above already do -- the tick loop's cert-rotation swap below reassigns the local, which a
      // lambda can't capture directly.
      final HttpClient relayHttpClient = httpClient;
      ConfigRelay configRelay =
          new ConfigRelay(
              instance -> fetchConfigEntries(instance, relayHttpClient, baseUrl, fafnirBaseUrl),
              Duration.ofMillis(configRelayIntervalMillis),
              supervised);
      configRelay.start();
      log.info("agent {} started config relay (interval {}ms)", nodeId, configRelayIntervalMillis);
    }

    while (!Thread.currentThread().isInterrupted()) {
      long tickStartNanos = System.nanoTime();
      boolean tickFailed = false;
      try {
        reconcileAssignments(
            httpClient,
            baseUrl,
            fafnirBaseUrl,
            andvariBaseUrls,
            artifactCache,
            sleipnirCache,
            muninnEndpoint,
            nodeId,
            supervised,
            supervisedVessels,
            instanceShippers,
            workerShippers,
            javaExecutable,
            commandTail,
            resourceLimiter,
            volumeManager,
            capacityTracker,
            committedWorkerCapacity,
            gossipMember,
            catalog,
            logRoot,
            maxTier1Density,
            tier1Budget,
            reportedStartFailures);
        sendHeartbeat(
            httpClient,
            baseUrl,
            nodeId,
            supervised,
            supervisedVessels,
            capacityTracker,
            committedWorkerCapacity,
            volumeManager);
        RotationOutcome rotationOutcome =
            rotateCertificateIfDue(httpClient, baseUrl, rotationMonitor);
        httpClient = rotationOutcome.httpClient();
        if (rotationOutcome.status().rotated()) {
          gossipMember.reloadDtlsMaterial();
          if (adminServer != null) {
            adminServer.reloadTlsMaterial();
          }
        }
        renewWorkerCertificates(httpClient, baseUrl, nodeId, supervised);
      } catch (RuntimeException | IOException e) {
        tickFailed = true;
        log.error("agent tick failed: {}", e.getMessage(), e);
      } catch (Error e) {
        // An Error -- OutOfMemoryError foremost, reconciling a large enough set of pre-existing
        // deployments against this JVM's own fixed heap is exactly the kind of allocation burst
        // that can trip it -- is not caught by the clause above on purpose: JLS convention is that
        // an Error signals a condition an application should not try to recover from, and this
        // agent's own object graph (supervised instances, worker supervisors, open connections) may
        // be in a state it can no longer reason about. See handleFatalTickError's own javadoc for
        // why this agent must exit itself here rather than relying on a launch flag.
        handleFatalTickError(e, Runtime.getRuntime()::halt);
      } finally {
        agentMetrics.recordTick(Duration.ofNanos(System.nanoTime() - tickStartNanos), tickFailed);
      }
      Thread.sleep(TICK_INTERVAL.toMillis());
    }
  }

  /**
   * What a fatal {@link Error} anywhere in this agent gets treated as -- the tick loop's own {@code
   * catch (Error e)} calls this directly, and {@code main}'s {@link
   * Thread#setDefaultUncaughtExceptionHandler} backstops every other thread this agent starts
   * (gossip, the admin API, the config/network-policy relays, Bifrost) with the same handling.
   * Extracted so a test can assert on it without actually terminating the JVM running the test --
   * {@code terminate} is {@link Runtime#halt(int)} in production, a recording stub in a test.
   * {@code halt} (not {@code exit}) skips shutdown hooks and finalizers, which themselves risk
   * allocating under the same low-memory condition that caused this. Unlike the worker JVMs this
   * agent spawns (whose launch always carries {@code -XX:+ExitOnOutOfMemoryError}, see {@link
   * #stableWorkerFlags}), this agent's own JVM was never launched with that flag -- a launch-time
   * option, not something a running process can add to itself -- so without this, an uncaught
   * {@link Error} on any one thread would kill only that thread while every other non-daemon thread
   * this agent started keeps the process alive as a zombie that never ticks or heartbeats again,
   * indistinguishable from a healthy agent in {@code ps aux}. The exit code matches HotSpot's own
   * for {@code -XX:+ExitOnOutOfMemoryError} -- the same abrupt-but-honest exit a worker gives its
   * supervisor, so this agent's own supervisor (systemd/hilmir/docker) sees a real process exit and
   * restarts it the same way.
   */
  static void handleFatalTickError(Error e, IntConsumer terminate) {
    log.error("agent tick suffered a fatal error; halting so the supervisor can restart it", e);
    terminate.accept(WorkerProcessSupervisor.OOM_EXIT_CODE);
  }

  /**
   * What {@code main} installs via {@link Thread#setDefaultUncaughtExceptionHandler} -- a factory
   * rather than an inline lambda so a test can drive it against a throwaway thread with a recording
   * {@code terminate} stub, the same seam {@link #handleFatalTickError} itself uses.
   */
  static Thread.UncaughtExceptionHandler defaultUncaughtExceptionHandler(IntConsumer terminate) {
    return (thread, throwable) -> {
      if (throwable instanceof Error fatal) {
        handleFatalTickError(fatal, terminate);
      } else {
        log.error("uncaught exception on thread {}", thread.getName(), throwable);
      }
    };
  }

  private static InetSocketAddress parseHostPort(String text) {
    int at = text.lastIndexOf(':');
    if (at < 0) {
      throw new IllegalArgumentException("expected host:port, got: " + text);
    }
    return new InetSocketAddress(text.substring(0, at), Integer.parseInt(text.substring(at + 1)));
  }

  /**
   * Rejects a non-numeric, zero, or negative density outright rather than silently falling back to
   * the default: an operator who set this meant to change the packing behavior, and a value that
   * quietly does nothing is worse than a startup failure that says exactly what is wrong.
   */
  static int parseMaxTier1Density(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT_MAX_TIER1_DENSITY;
    }
    final int parsed;
    try {
      parsed = Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          MAX_TIER1_DENSITY_PROPERTY
              + " must be a positive integer (instances per shared worker JVM), got: "
              + value);
    }
    if (parsed < 1) {
      throw new IllegalArgumentException(
          MAX_TIER1_DENSITY_PROPERTY
              + " must be at least 1 (1 disables Tier 1 packing entirely), got: "
              + parsed);
    }
    return parsed;
  }

  private static List<InetSocketAddress> parseSeeds(String text) {
    if (text.equals("-") || text.isBlank()) {
      return List.of();
    }
    List<InetSocketAddress> seeds = new ArrayList<>();
    for (String entry : text.split(",")) {
      seeds.add(parseHostPort(entry));
    }
    return seeds;
  }

  /**
   * One or more comma-separated {@code host:port} Andvari replicas, resolved into base URIs under
   * {@code scheme} -- empty when {@code andvariEndpoint} is {@code null}, the "no registry
   * configured" state {@link #resolveArtifactReference} already handles.
   */
  private static List<URI> parseAndvariEndpoints(String andvariEndpoint, String scheme) {
    if (andvariEndpoint == null || andvariEndpoint.isBlank()) {
      return List.of();
    }
    List<URI> endpoints = new ArrayList<>();
    for (String entry : andvariEndpoint.split(",")) {
      String trimmed = entry.trim();
      if (!trimmed.isEmpty()) {
        endpoints.add(URI.create(scheme + "://" + trimmed));
      }
    }
    return endpoints;
  }

  /**
   * One or more comma-separated {@code host:port} gimle-mimir store replicas for {@link
   * AgentAdminServer}'s own {@code StoreClient} -- reuses {@link #parseHostPort} directly, the same
   * parser {@code gossipBindHost:port}/{@code seeds} already use.
   */
  private static List<SocketAddress> parseStoreEndpoints(String storeEndpoint) {
    List<SocketAddress> endpoints = new ArrayList<>();
    for (String entry : storeEndpoint.split(",")) {
      String trimmed = entry.trim();
      if (!trimmed.isEmpty()) {
        endpoints.add(parseHostPort(trimmed));
      }
    }
    return endpoints;
  }

  /**
   * Relays a newly-applied catalog delta -- local or gossip-learned -- to every supervised worker's
   * own locally-cached catalog.
   */
  private static void relayCatalogDelta(
      CatalogDelta delta, Map<String, SupervisedInstance> supervised) {
    ControlMessage update = toCatalogUpdate(delta);
    for (SupervisedInstance instance : supervised.values()) {
      WorkerConnection connection = instance.connection;
      if (connection != null) {
        try {
          connection.send(update);
        } catch (IOException e) {
          log.warn("failed to relay catalog update to a supervised worker: {}", e.getMessage());
        }
      }
    }
  }

  // ---- control-plane registration/heartbeat/assignment fetch ----

  /**
   * Retries registration until it succeeds, rather than letting a transient control-plane hiccup at
   * exactly this moment take the node out of the cluster until a human notices. The steady-state
   * tick loop already survives the same class of failure; there is no reason startup should be the
   * one moment a timeout is fatal. Backs off exponentially to a ceiling so a control plane that is
   * genuinely down is not hammered, and keeps retrying rather than giving up after a fixed count --
   * an agent that has nothing else to do until it registers has no better state to fall back to.
   */
  static void registerWithRetry(
      HttpClient httpClient,
      URI baseUrl,
      String nodeId,
      ResourceLimiter resourceLimiter,
      String apiAddress)
      throws InterruptedException {
    Duration backoff = REGISTRATION_INITIAL_BACKOFF;
    for (int attempt = 1; ; attempt++) {
      try {
        register(httpClient, baseUrl, nodeId, resourceLimiter, apiAddress);
        return;
      } catch (IOException | RuntimeException e) {
        log.warn(
            "agent {} could not register with control plane at {} (attempt {}): {} -- retrying in"
                + " {}",
            nodeId,
            baseUrl,
            attempt,
            e.getMessage(),
            backoff);
        Thread.sleep(backoff);
        backoff = nextRegistrationBackoff(backoff);
      }
    }
  }

  static Duration nextRegistrationBackoff(Duration current) {
    Duration doubled = current.multipliedBy(2);
    return doubled.compareTo(REGISTRATION_MAX_BACKOFF) > 0 ? REGISTRATION_MAX_BACKOFF : doubled;
  }

  private static void register(
      HttpClient httpClient,
      URI baseUrl,
      String nodeId,
      ResourceLimiter resourceLimiter,
      String apiAddress)
      throws IOException, InterruptedException {
    Set<IsolationTier> supportedTiers = new LinkedHashSet<>();
    for (IsolationTier tier : IsolationTier.values()) {
      if (resourceLimiter.supports(tier)) {
        supportedTiers.add(tier);
      }
    }
    Map<String, Object> capabilities = new LinkedHashMap<>();
    capabilities.put("supportedTiers", supportedTiers.stream().map(Enum::name).toList());
    capabilities.put("labels", nodeLabels());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("capabilities", capabilities);
    body.put("apiAddress", apiAddress);

    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/nodes/" + nodeId + "/register"))
            .timeout(HTTP_REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    // A control plane still coming up answers 503 here; treating that as success would leave this
    // agent believing it is registered while the cluster has never heard of it.
    if (response.statusCode() / 100 != 2) {
      throw new IOException(
          "control plane refused node registration with status " + response.statusCode());
    }
  }

  /**
   * Operator-assigned placement labels for this node (e.g. {@code -Dgimle.node.labels=gpu,ssd}),
   * matching {@code gimle.process.role}/{@code gimle.node.id}'s existing system-property config
   * pattern rather than adding a new positional CLI argument -- this keeps every existing launcher
   * (AgentMojo, gimle-smoke-tests, manual invocations) working unchanged. Empty by default: a node
   * with no labels simply can't satisfy any deployment that requires one.
   */
  private static List<String> nodeLabels() {
    String raw = System.getProperty("gimle.node.labels", "");
    if (raw.isBlank()) {
      return List.of();
    }
    List<String> labels = new ArrayList<>();
    for (String label : raw.split(",")) {
      if (!label.isBlank()) {
        labels.add(label.trim());
      }
    }
    return labels;
  }

  // ---- TLS bootstrap and rotation ----

  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";
  private static final String BOOTSTRAP_TOKEN_PROPERTY = "gimle.tls.bootstrapToken";

  private static HttpClient buildHttpClient() {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return HttpClient.newBuilder().connectTimeout(HTTP_CONNECT_TIMEOUT).build();
    }
    return HttpClient.newBuilder()
        .connectTimeout(HTTP_CONNECT_TIMEOUT)
        .sslContext(SslContexts.forMutualTls(TlsSettings.fromConfig()))
        .build();
  }

  /**
   * On first startup with {@code gimle.transport.protocol=tls} and no local cert/key files present
   * yet, generates a key pair and CSR in-process and submits it (plus the one-time bootstrap token
   * an operator provisioned this agent with) to {@code POST /bootstrap/csr}. Reachable over
   * server-authenticated-only TLS (the agent already has {@code gimle.tls.caFile}, handed to it out
   * of band -- same as every other {@code gimle.tls.*} property -- so it can verify the control
   * plane's identity before it has one of its own). No-op if the cert/key files already exist (a
   * redeploy of an already-bootstrapped node) or if TLS isn't enabled at all.
   */
  private static void bootstrapCertificateIfNeeded(String nodeId, URI baseUrl)
      throws IOException, InterruptedException {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return;
    }
    Path certFile = requiredPathProperty(CERT_FILE_PROPERTY);
    Path keyFile = requiredPathProperty(KEY_FILE_PROPERTY);
    if (Files.isRegularFile(certFile) && Files.isRegularFile(keyFile)) {
      return;
    }
    Path caFile = requiredPathProperty(CA_FILE_PROPERTY);
    String bootstrapToken = System.getProperty(BOOTSTRAP_TOKEN_PROPERTY);
    if (bootstrapToken == null || bootstrapToken.isBlank()) {
      throw GimleTlsException.missingProperty(BOOTSTRAP_TOKEN_PROPERTY);
    }
    log.info("agent {} has no certificate yet; requesting one via bootstrap CSR", nodeId);

    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(
            keyPair, new X500Name("CN=" + nodeId), List.of(resolveAdvertisedHost()));

    SSLContext trustOnly = SslContexts.forServerTrustOnly(caFile);
    HttpClient bootstrapClient =
        HttpClient.newBuilder().connectTimeout(HTTP_CONNECT_TIMEOUT).sslContext(trustOnly).build();
    Map<String, Object> body =
        csrSubmissionToJson(
            new CsrSubmission(
                CsrPurpose.NODE_CLIENT, Pem.encodeCsr(csr), Optional.of(bootstrapToken)));
    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/bootstrap/csr"))
            .timeout(HTTP_REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        bootstrapClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "bootstrap CSR submission rejected with status "
              + response.statusCode()
              + ": "
              + response.body());
    }
    CsrResult result = csrResultFromJson(Json.asObject(Json.parse(response.body())));
    Files.writeString(certFile, result.certificatePem().orElseThrow(), StandardCharsets.US_ASCII);
    Files.writeString(
        keyFile, Pem.encodePrivateKey(keyPair.getPrivate()), StandardCharsets.US_ASCII);
    log.info("agent {} obtained a signed certificate via bootstrap CSR", nodeId);
  }

  /**
   * What one rotation check produced: the {@link HttpClient} to use from here on (a fresh one only
   * after an actual rotation, since the old one still holds the retired key material) plus the
   * {@link CertificateRotationStatus} saying what happened -- which of the not-rotated exits it
   * was, how much validity the certificate on disk still has, and how many checks in a row have
   * failed. The caller needs the rotated/not-rotated distinction to know whether to also refresh
   * {@code gossipMember}'s own DTLS material; a raw {@link HttpClient} return gives no such signal.
   */
  private record RotationOutcome(HttpClient httpClient, CertificateRotationStatus status) {}

  /**
   * Checked once per tick: if the agent's currently-loaded leaf certificate is due for renewal,
   * submits a same-subject/fresh-key-pair rotation CSR over its *current* (still-valid) mTLS
   * connection, writes the new cert/key, and returns a freshly-built {@link HttpClient} for the
   * caller to use from then on -- unlike {@code ApiServer}, the agent isn't a TLS *server*
   * anywhere, so "hot-swap" here is just handing back a new outbound client, not the JDK
   * listening-socket rebuild {@code ApiServer#reloadTlsMaterial} needs. Returns {@code current}
   * unchanged (no-op) in plaintext mode, when not yet due, or if the rotation request fails --
   * failures are retried on a later tick, not fatal to this one, and every check (failures
   * included) is reported through {@code monitor} so a rotation that has quietly stopped working is
   * visible long before the certificate it failed to renew expires.
   */
  private static RotationOutcome rotateCertificateIfDue(
      HttpClient current, URI baseUrl, CertificateRotationMonitor monitor) {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return new RotationOutcome(current, monitor.disabled());
    }
    X509Certificate certificate = null;
    try {
      TlsSettings settings = TlsSettings.fromConfig();
      certificate =
          Pem.decodeCertificate(Files.readString(settings.certFile(), StandardCharsets.US_ASCII));
      if (!RenewalSchedule.of(certificate).isDue(Instant.now())) {
        return new RotationOutcome(current, monitor.notDue(certificate));
      }
      log.info("agent certificate due for renewal, requesting rotation");
      KeyPair keyPair = generateRsaKeyPair();
      // X500Name.getInstance(...getEncoded()), never new X500Name(...getName()): the latter
      // round-trips through X500Principal's RFC 2253 string rendering, which reorders a multi-RDN
      // subject (most-specific RDN first, i.e. CN before O) relative to the certificate's own
      // ASN.1 encoding order (O before CN, per Subjects.withOrganization) -- every node subject
      // carries both. A reordered CSR subject here fails ApiServer#handleRotationRequest's own
      // byte-for-byte comparison against the presented certificate's real encoding, so every real
      // rotation attempt would be rejected with 403 and this tick's renewal would silently retry
      // forever.
      X500Name subject = X500Name.getInstance(certificate.getSubjectX500Principal().getEncoded());
      PKCS10CertificationRequest csr = CertificateSigningRequests.generate(keyPair, subject);
      Map<String, Object> body =
          csrSubmissionToJson(new CsrSubmission(CsrPurpose.NODE_CLIENT, Pem.encodeCsr(csr)));
      HttpRequest request =
          HttpRequest.newBuilder(baseUrl.resolve("/bootstrap/csr"))
              .timeout(HTTP_REQUEST_TIMEOUT)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> response =
          current.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        return new RotationOutcome(
            current,
            monitor.failed(
                "the rotation request was rejected with status "
                    + response.statusCode()
                    + ": "
                    + response.body(),
                certificate));
      }
      CsrResult result = csrResultFromJson(Json.asObject(Json.parse(response.body())));
      String issuedPem =
          result
              .certificatePem()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "the rotation request returned status "
                              + result.status()
                              + " with no certificate"));
      // Key written *before* cert, deliberately: gimle-worker's FabricServerTlsWatcher polls
      // only certFile's mtime to detect a rotation happened, from a separate process with no
      // synchronization with this one. Writing the key first guarantees that by the time the
      // watcher ever observes certFile's mtime move, the matching key is already fully on disk --
      // otherwise a poll landing between the two writes could pair a fresh cert with the stale key.
      Files.writeString(
          settings.keyFile(),
          Pem.encodePrivateKey(keyPair.getPrivate()),
          StandardCharsets.US_ASCII);
      Files.writeString(settings.certFile(), issuedPem, StandardCharsets.US_ASCII);
      return new RotationOutcome(
          buildHttpClient(), monitor.rotated(Pem.decodeCertificate(issuedPem)));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new RotationOutcome(
          current, monitor.failed("interrupted while requesting rotation", certificate));
    } catch (IOException | RuntimeException e) {
      return new RotationOutcome(
          current,
          monitor.failed(e.getMessage() == null ? e.toString() : e.getMessage(), certificate));
    }
  }

  private static Path requiredPathProperty(String property) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      throw GimleTlsException.missingProperty(property);
    }
    return Path.of(value);
  }

  static Map<String, Object> csrSubmissionToJson(CsrSubmission submission) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("purpose", submission.purpose().name());
    map.put("csrPem", submission.csrPem());
    submission.bootstrapToken().ifPresent(token -> map.put("bootstrapToken", token));
    submission.tenantId().ifPresent(tenantId -> map.put("tenantId", tenantId));
    return map;
  }

  static CsrResult csrResultFromJson(Map<String, Object> json) {
    CsrRequestStatus status = CsrRequestStatus.valueOf((String) json.get("status"));
    Optional<String> requestId = Optional.ofNullable((String) json.get("requestId"));
    Optional<String> certificatePem = Optional.ofNullable((String) json.get("certificatePem"));
    Optional<String> caCertificatePem = Optional.ofNullable((String) json.get("caCertificatePem"));
    return new CsrResult(status, requestId, certificatePem, caCertificatePem);
  }

  static KeyPair generateRsaKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA key pair generation unavailable", e);
    }
  }

  /**
   * Same pattern as {@code WorkerMain.resolveAdvertisedHost()}: self-reported, not captured from
   * the registration request's raw socket (wrong behind NAT/a proxy). A deployment concern
   * independent of this protocol -- real multi-homed/NAT'd hosts need real address configuration;
   * loopback keeps single-machine setups working.
   */
  private static String resolveAdvertisedHost() {
    try {
      return InetAddress.getLocalHost().getHostAddress();
    } catch (UnknownHostException e) {
      return "127.0.0.1";
    }
  }

  /**
   * Reports whichever of this node's two budgets is actually binding, not just the declared-request
   * one: {@code capacityTracker} sums each instance's own small declared request, while {@code
   * committedWorkerCapacity} sums the real ceiling every spawned worker JVM is started with -- and
   * it is the latter that {@link #startInstance} refuses a spawn against. Reporting the request sum
   * alone let a node whose agent was already refusing to spawn anything keep advertising room the
   * machine does not have, so the scheduler kept sending it work it could never run.
   */
  static void sendHeartbeat(
      HttpClient httpClient,
      URI baseUrl,
      String nodeId,
      Map<String, SupervisedInstance> supervised,
      Map<String, SupervisedVessel> supervisedVessels,
      CapacityTracker capacityTracker,
      CapacityTracker committedWorkerCapacity,
      VolumeManager volumeManager)
      throws IOException, InterruptedException {
    CapacityTracker.Snapshot snapshot = capacityTracker.snapshot();
    CapacityTracker.Snapshot committed = committedWorkerCapacity.snapshot();
    Map<String, Object> capacity = new LinkedHashMap<>();
    capacity.put("totalMemoryBytes", snapshot.totalMemoryBytes());
    capacity.put(
        "assignedMemoryBytes",
        Math.max(snapshot.assignedMemoryBytes(), committed.assignedMemoryBytes()));
    capacity.put("totalCpuMillicores", snapshot.totalCpuMillicores());
    capacity.put(
        "assignedCpuMillicores",
        Math.max(snapshot.assignedCpuMillicores(), committed.assignedCpuMillicores()));

    List<Map<String, Object>> instances = new ArrayList<>();
    for (SupervisedInstance instance : supervised.values()) {
      sampleVolumeUsageIfDue(instance, volumeManager);
      instances.add(observationJson(instance));
    }
    for (SupervisedVessel vessel : supervisedVessels.values()) {
      instances.add(vesselObservationJson(vessel));
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("capacity", capacity);
    body.put("instances", instances);

    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/nodes/" + nodeId + "/heartbeat"))
            .timeout(HTTP_REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
            .build();
    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
  }

  /**
   * Relays one worker-reported {@link InstanceEvent} to the control plane, the same
   * agent-forwards-what-a-worker-told-it shape {@link #sendHeartbeat} already has -- best-effort,
   * matching {@code WorkerMain}'s own {@code sendQuietly} posture for this same message: a lost
   * event costs nothing more than an incomplete timeline entry, never a stalled agent.
   */
  private static void postInstanceEvent(
      HttpClient httpClient, URI baseUrl, String nodeId, InstanceEvent event) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", event.id());
    body.put("deploymentName", event.deploymentName());
    body.put("instanceIndex", event.instanceIndex());
    body.put("kind", event.kind().name());
    body.put("message", event.message());
    event.causeSummary().ifPresent(summary -> body.put("causeSummary", summary));
    body.put("occurredAtEpochMilli", event.occurredAtEpochMilli());
    try {
      HttpRequest request =
          HttpRequest.newBuilder(baseUrl.resolve("/nodes/" + nodeId + "/events"))
              .timeout(HTTP_REQUEST_TIMEOUT)
              .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
              .build();
      httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.warn(
          "failed to relay instance event {} for node {}: {}", event.id(), nodeId, e.getMessage());
    }
  }

  /** How stale a volume-usage sample may get before the next heartbeat re-walks the directory. */
  private static final Duration VOLUME_USAGE_SAMPLE_INTERVAL = Duration.ofMinutes(1);

  /**
   * Refreshes {@link SupervisedInstance#volumeUsageBytes} by walking the volume directory -- but at
   * most once per {@link #VOLUME_USAGE_SAMPLE_INTERVAL}, not on every heartbeat tick: the sample is
   * a soft, advisory observation (the same posture as {@code VolumeRequest#sizeBytes} itself), and
   * re-walking a large data directory every few seconds would cost real I/O for no added truth.
   */
  private static void sampleVolumeUsageIfDue(
      SupervisedInstance instance, VolumeManager volumeManager) {
    if (instance.volumeHandles.isEmpty()) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now - instance.volumeUsageSampledAtMillis < VOLUME_USAGE_SAMPLE_INTERVAL.toMillis()) {
      return;
    }
    try {
      instance.volumeUsageBytes =
          volumeManager.usedBytes(
              instance.assigned.tenantId(),
              instance.assigned.deploymentName(),
              instance.assigned.instanceIndex());
      instance.volumeUsageSampledAtMillis = now;
    } catch (RuntimeException e) {
      log.warn(
          "failed to sample volume usage for {}#{}: {}",
          instance.assigned.deploymentName(),
          instance.assigned.instanceIndex(),
          e.getMessage());
    }
  }

  static Map<String, Object> observationJson(SupervisedInstance instance) {
    String state = instance.lifecycleState;
    // alive is an EXCLUSION check ("not known-crashed"), not an inclusion check ("is one of the
    // states I expect") -- deliberately, so a COMPLETED job already reports alive=true without
    // this line needing to change: a successfully finished Job is not a crash HealthReconciler
    // should reschedule. Rewriting this as an inclusion list (e.g.
    // "ACTIVE".equals(state) || "COMPLETED".equals(state)) would silently break for the next
    // terminal state this file doesn't yet know about -- keep it an exclusion check.
    boolean alive = !"FAILED".equals(state);
    // A genuinely reported readiness result (this instance's own WorkerRuntime probe loop, via
    // HealthReport) always wins; absent one -- no readiness probe declared, or none has ticked yet
    // since the last transition -- ACTIVE itself is the only signal there ever was, the same
    // fallback this always used before HealthReport existed.
    boolean ready = instance.readinessReported.orElse("ACTIVE".equals(state));

    Map<String, Object> moduleId = new LinkedHashMap<>();
    moduleId.put("name", instance.assigned.moduleId().name());
    moduleId.put("version", instance.assigned.moduleId().version().toString());

    Map<String, Object> observation = new LinkedHashMap<>();
    observation.put("deploymentName", instance.assigned.deploymentName());
    observation.put("instanceIndex", instance.assigned.instanceIndex());
    observation.put("moduleId", moduleId);
    observation.put("lifecycleState", state);
    observation.put("alive", alive);
    observation.put("ready", ready);
    observation.put("cpuMillicoresUsed", instance.cpuMillicoresUsed);
    observation.put("memoryBytesUsed", instance.memoryBytesUsed);
    observation.put("requestRatePerSecond", instance.requestRatePerSecond);
    observation.put("errorRatePerSecond", instance.errorRatePerSecond);
    observation.put("queueDepth", instance.queueDepth);
    observation.put("ports", instance.ports);
    observation.put("volumeUsageBytes", instance.volumeUsageBytes);
    // Absent until this worker's own Hello handshake arrives (see the connection-reader loop
    // that sets fabricWorkerId) -- omitted entirely rather than sent null, the same "present only
    // when known" convention every other optional field on this map already follows.
    if (instance.fabricWorkerId != null) {
      observation.put("workerId", instance.fabricWorkerId);
    }
    instance.assigned.tenantId().ifPresent(tenantId -> observation.put("tenantId", tenantId));
    // The tier this instance was admitted under, plus the ceiling it actually runs against --
    // relayed so a reader has a real denominator for the usage numbers above. isolationTier is
    // read straight off the descriptor the agent already holds; resourceLimit is deliberately NOT
    // the descriptor's own declared limit (see effectiveResourceLimit's own javadoc for why that
    // would mislead at TIER_1). Both are null only for a unit test that constructs a
    // SupervisedInstance with no descriptor behind it, and omitted entirely in that case rather
    // than sent as null.
    if (instance.descriptor != null) {
      observation.put("isolationTier", instance.descriptor.isolationTier().name());
      observation.put("resourceLimit", resourceSpecJson(effectiveResourceLimit(instance)));
    }
    return observation;
  }

  /**
   * The ceiling {@code instance} is actually running under, for {@link #observationJson} to report
   * as {@code resourceLimit}. At TIER_2 this instance owns its worker JVM outright, so {@code
   * workerLimit} is the exact same {@link ResourceSpec} its manifest declared -- reporting it is
   * unchanged from reporting the descriptor's own limit. At TIER_1 several instances share one
   * worker JVM sized by {@link Tier1WorkerBudget}, so {@code workerLimit} instead carries that
   * shared worker's real spawned {@code -Xmx}/CPU size, which is what a used/limit reader actually
   * needs -- the module's own declared request/limit bears no relation to the JVM this instance is
   * actually running inside (see {@link SupervisedInstance#workerLimit}'s own javadoc). Falls back
   * to the descriptor's own declared limit only when no real worker stands behind this instance at
   * all, which in practice means a unit test that never spawned one.
   */
  private static ResourceSpec effectiveResourceLimit(SupervisedInstance instance) {
    return instance.workerLimit != null
        ? instance.workerLimit
        : instance.descriptor.resourceLimit();
  }

  private static Map<String, Object> resourceSpecJson(ResourceSpec spec) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("memory", spec.memory());
    map.put("cpu", spec.cpu());
    return map;
  }

  /**
   * The vessel analog of {@link #observationJson} -- same {@code alive}/{@code ready} derivation
   * from {@link SupervisedVessel#lifecycleState} (kept driven from the exact same {@code
   * "STARTING"}/{@code "ACTIVE"}/{@code "FAILED"} vocabulary so both feed {@code HealthReconciler}
   * identically), zero for every metrics field a vessel has no in-JVM {@code MetricsReport} to ever
   * populate, and {@code ports} carrying its own agent-allocated/fixed port numbers -- what {@code
   * GET /endpoints/{deployment}} ultimately reads back out.
   */
  static Map<String, Object> vesselObservationJson(SupervisedVessel instance) {
    String state = instance.lifecycleState;
    boolean alive = !"FAILED".equals(state);
    boolean ready = "ACTIVE".equals(state);

    Map<String, Object> moduleId = new LinkedHashMap<>();
    moduleId.put("name", instance.assigned.moduleId().name());
    moduleId.put("version", instance.assigned.moduleId().version().toString());

    Map<String, Object> observation = new LinkedHashMap<>();
    observation.put("deploymentName", instance.assigned.deploymentName());
    observation.put("instanceIndex", instance.assigned.instanceIndex());
    observation.put("moduleId", moduleId);
    observation.put("lifecycleState", state);
    observation.put("alive", alive);
    observation.put("ready", ready);
    observation.put("cpuMillicoresUsed", 0L);
    observation.put("memoryBytesUsed", 0L);
    observation.put("requestRatePerSecond", 0.0);
    observation.put("errorRatePerSecond", 0.0);
    observation.put("queueDepth", 0);
    observation.put("ports", instance.allocatedPorts);
    instance.assigned.tenantId().ifPresent(tenantId -> observation.put("tenantId", tenantId));
    return observation;
  }

  static List<AssignedInstance> fetchAssignments(HttpClient httpClient, URI baseUrl, String nodeId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/nodes/" + nodeId + "/assignments"))
            .timeout(HTTP_REQUEST_TIMEOUT)
            .GET()
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    List<Object> raw = Json.asArray(Json.parse(response.body()));
    List<AssignedInstance> result = new ArrayList<>();
    for (Object entry : raw) {
      Map<String, Object> map = Json.asObject(entry);
      // Parsed one assignment at a time, and a failure here skips only that assignment. Letting it
      // escape would abort the whole fetch, so a single malformed spec would stop this node from
      // learning about every other tenant's instances too -- freezing its heartbeat cluster-wide
      // rather than failing the one workload actually at fault.
      try {
        result.add(parseAssignment(map));
      } catch (RuntimeException e) {
        reportUnparseableAssignment(httpClient, baseUrl, nodeId, map, e);
      }
    }
    return result;
  }

  /**
   * Names the offending workload in a durable timeline event as well as this node's own log: an
   * assignment the agent cannot even parse is otherwise invisible to an operator, who sees only a
   * deployment that never progresses and a node that looks healthy.
   */
  private static void reportUnparseableAssignment(
      HttpClient httpClient,
      URI baseUrl,
      String nodeId,
      Map<String, Object> map,
      RuntimeException e) {
    Object name = map.get("deploymentName");
    Object index = map.get("instanceIndex");
    log.error("skipping unparseable assignment {}[{}]: {}", name, index, e.getMessage(), e);
    if (!(name instanceof String deploymentName) || !(index instanceof Number instanceIndex)) {
      return;
    }
    postInstanceEvent(
        httpClient,
        baseUrl,
        nodeId,
        new InstanceEvent(
            UUID.randomUUID().toString(),
            deploymentName,
            instanceIndex.intValue(),
            InstanceEventKind.TRANSITION_FAILED,
            "assignment spec rejected by this node",
            Optional.of(String.valueOf(e.getMessage())),
            System.currentTimeMillis()));
  }

  /**
   * Names a refused or failed local start in the instance's own durable timeline, not only in this
   * node's log. A start this node cannot perform -- a worker ceiling that would overcommit the
   * machine's real memory, an unreadable artifact, an isolation tier this node cannot provide --
   * otherwise leaves an operator with a workload that reports desired state and nothing anywhere
   * saying why nothing is running: the only account of it lives in a node log they have no reason
   * to suspect is the place to look.
   *
   * <p>Reported once per distinct cause per instance, keyed through {@code reportedStartFailures}:
   * reconciliation is level-triggered, so an unfixable start is retried on every tick, and posting
   * each retry would push every other event out of that instance's own bounded timeline within
   * minutes. A cause that changes is reported again, since it is genuinely new information.
   */
  private static void reportStartFailure(
      HttpClient httpClient,
      URI baseUrl,
      String nodeId,
      AssignedInstance assigned,
      String key,
      Throwable failure,
      Map<String, String> reportedStartFailures) {
    String cause = String.valueOf(failure.getMessage());
    if (cause.equals(reportedStartFailures.put(key, cause))) {
      return;
    }
    postInstanceEvent(
        httpClient,
        baseUrl,
        nodeId,
        new InstanceEvent(
            UUID.randomUUID().toString(),
            assigned.deploymentName(),
            assigned.instanceIndex(),
            InstanceEventKind.TRANSITION_FAILED,
            "instance start refused by this node",
            Optional.of(cause),
            System.currentTimeMillis()));
  }

  private static AssignedInstance parseAssignment(Map<String, Object> map) {
    Map<String, Object> moduleIdMap = Json.asObject(map.get("moduleId"));
    ModuleId moduleId =
        new ModuleId(
            (String) moduleIdMap.get("name"), Version.parse((String) moduleIdMap.get("version")));
    Object tenantId = map.get("tenantId");
    Object renamedFrom = map.get("renamedFromInstanceIndex");
    Object rawConfigMapRefs = map.get("configMapRefs");
    List<String> configMapRefs =
        rawConfigMapRefs == null
            ? List.of()
            : Json.asArray(rawConfigMapRefs).stream().map(String.class::cast).toList();
    Object rawSecretMapRefs = map.get("secretMapRefs");
    List<String> secretMapRefs =
        rawSecretMapRefs == null
            ? List.of()
            : Json.asArray(rawSecretMapRefs).stream().map(String.class::cast).toList();
    Object rawVessel = map.get("vessel");
    return new AssignedInstance(
        (String) map.get("deploymentName"),
        ((Number) map.get("instanceIndex")).intValue(),
        moduleId,
        (String) map.get("artifactPath"),
        tenantId == null ? Optional.empty() : Optional.of((String) tenantId),
        renamedFrom == null
            ? OptionalInt.empty()
            : OptionalInt.of(((Number) renamedFrom).intValue()),
        rawVessel == null ? Optional.empty() : Optional.of(parseVessel(Json.asObject(rawVessel))),
        configMapRefs,
        secretMapRefs);
  }

  /**
   * The inverse of {@code ApiServer#vesselToJson} -- reconstructs a {@link VesselSpec} from the
   * wire shape the control plane's own {@code /nodes/{id}/assignments} response emits, since the
   * agent never resolves a vessel assignment against the manifest YAML directly (only the control
   * plane parses that).
   */
  private static VesselSpec parseVessel(Map<String, Object> map) {
    List<String> args = Json.asArray(map.get("args")).stream().map(String.class::cast).toList();
    List<String> jvmFlags =
        Json.asArray(map.get("jvmFlags")).stream().map(String.class::cast).toList();
    Map<String, VesselEnvValue> env = new LinkedHashMap<>();
    for (var e : Json.asObject(map.get("env")).entrySet()) {
      env.put(e.getKey(), parseVesselEnvValue(Json.asObject(e.getValue())));
    }
    List<VesselFileMount> files = new ArrayList<>();
    for (Map<String, Object> f : Json.asObjectList(map.get("files"))) {
      files.add(
          new VesselFileMount(
              (String) f.get("path"),
              Optional.ofNullable((String) f.get("config")),
              Optional.ofNullable((String) f.get("secret"))));
    }
    Map<String, Object> probesMap = Json.asObject(map.get("probes"));
    VesselProbes probes =
        new VesselProbes(
            probesMap.containsKey("liveness")
                ? Optional.of(parseVesselProbe(Json.asObject(probesMap.get("liveness"))))
                : Optional.empty(),
            probesMap.containsKey("readiness")
                ? Optional.of(parseVesselProbe(Json.asObject(probesMap.get("readiness"))))
                : Optional.empty());
    Map<String, Object> resourcesMap = Json.asObject(map.get("resources"));
    Map<String, Object> request = Json.asObject(resourcesMap.get("request"));
    Map<String, Object> limit = Json.asObject(resourcesMap.get("limit"));
    return new VesselSpec(
        args,
        jvmFlags,
        env,
        files,
        probes,
        new ResourceSpec((String) request.get("memory"), (String) request.get("cpu")),
        new ResourceSpec((String) limit.get("memory"), (String) limit.get("cpu")));
  }

  private static VesselEnvValue parseVesselEnvValue(Map<String, Object> map) {
    if (map.containsKey("value")) {
      return new VesselEnvValue.Literal((String) map.get("value"));
    }
    if (map.containsKey("secret")) {
      return new VesselEnvValue.SecretRef((String) map.get("secret"));
    }
    if (map.containsKey("volume")) {
      Map<String, Object> volume = Json.asObject(map.get("volume"));
      return new VesselEnvValue.VolumeMount(
          ((Number) volume.get("sizeBytes")).longValue(),
          ReclaimPolicy.valueOf((String) volume.get("reclaimPolicy")));
    }
    Object port = map.get("port");
    return new VesselEnvValue.PortAllocation(
        "dynamic".equals(port) ? OptionalInt.empty() : OptionalInt.of(((Number) port).intValue()));
  }

  private static VesselProbeSpec parseVesselProbe(Map<String, Object> map) {
    int initialDelaySeconds = ((Number) map.getOrDefault("initialDelaySeconds", 0)).intValue();
    // Carried through even though a single-port vessel never needs it: dropping it here is what
    // turns a perfectly well-formed multi-port manifest into an unresolvable one by the time the
    // agent reconstructs it.
    Optional<String> portName = Optional.ofNullable((String) map.get("port"));
    if (map.containsKey("http")) {
      return new VesselProbeSpec.Http((String) map.get("http"), portName, initialDelaySeconds);
    }
    return new VesselProbeSpec.Tcp(portName, initialDelaySeconds);
  }

  /**
   * Fetches this tenant's plain (non-Fafnir-managed) config entries, already decrypted server-side
   * by {@code ApiServer}: {@code GET /config/{tenantId}} returns every {@code ConfigEntry} for that
   * tenant, both plaintext and any legacy {@code encrypted=true} entry (unchanged from before
   * Fafnir's own extraction). Fafnir's own synthetic {@code key@meta}/{@code key@N}
   * secret-versioning entries are filtered out of this endpoint's response server-side -- {@link
   * #fetchSecretsForTenant} is the only path that ever returns them.
   */
  private static List<ConfigValue> fetchConfigForTenant(
      HttpClient httpClient, URI baseUrl, String tenantId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/config/" + tenantId))
            .timeout(HTTP_REQUEST_TIMEOUT)
            .GET()
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    // Checked before parsing: an error body is plain text ("forbidden"), and parsing it as JSON
    // would bury the actual status behind a misleading parse failure.
    if (response.statusCode() != 200) {
      throw GimleClusterException.unexpectedHttpStatus(
          "config fetch for tenant " + tenantId, response.statusCode(), response.body());
    }
    List<Object> raw = Json.asArray(Json.parse(response.body()));
    List<ConfigValue> result = new ArrayList<>();
    for (Object entry : raw) {
      Map<String, Object> map = Json.asObject(entry);
      result.add(
          new ConfigValue(
              (String) map.get("key"),
              (String) map.get("value"),
              Boolean.TRUE.equals(map.get("encrypted"))));
    }
    return result;
  }

  /**
   * Fetches only the ConfigMaps a Deployment actually {@code configMapRefs}'d, in one batched round
   * trip ({@code GET /configmaps/{tenantId}?names=a,b,c}), and flattens each returned ConfigMap's
   * own {@code data} map into the same {@link ConfigValue} shape {@link #fetchConfigForTenant}
   * returns -- {@code ctx.config(key)} on the module side stays a plain map lookup regardless of
   * which of the two fetch paths populated it. Never encrypted: a ConfigMap's plaintext data never
   * touches Fafnir (see the design's own non-goals).
   */
  private static List<ConfigValue> fetchConfigMaps(
      HttpClient httpClient, URI baseUrl, String tenantId, List<String> names)
      throws IOException, InterruptedException {
    String namesParam = URLEncoder.encode(String.join(",", names), StandardCharsets.UTF_8);
    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/configmaps/" + tenantId + "?names=" + namesParam))
            .timeout(HTTP_REQUEST_TIMEOUT)
            .GET()
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      throw GimleClusterException.unexpectedHttpStatus(
          "configmap fetch for tenant " + tenantId, response.statusCode(), response.body());
    }
    List<Object> raw = Json.asArray(Json.parse(response.body()));
    List<ConfigValue> result = new ArrayList<>();
    for (Object entry : raw) {
      Map<String, Object> configMap = Json.asObject(entry);
      Map<String, Object> data = Json.asObject(configMap.get("data"));
      for (Map.Entry<String, Object> keyValue : data.entrySet()) {
        result.add(new ConfigValue(keyValue.getKey(), String.valueOf(keyValue.getValue()), false));
      }
    }
    return result;
  }

  /**
   * Fetches this tenant's Fafnir-native secrets -- talked to directly, over this agent's own mTLS
   * node identity, never relayed through the control plane: Fafnir authorizes the request itself,
   * scoped to tenants this node actually has an active assignment for ({@code FafnirServer}'s own
   * node-tenant-scoping check), rather than trusting anything the control plane might have
   * forwarded. Soft-deleted secrets are skipped (a soft-deleted secret's latest-version read
   * returns 404, exactly the shape a real deletion should have from a consuming instance's point of
   * view).
   */
  private static List<ConfigValue> fetchSecretsForTenant(
      HttpClient httpClient, URI fafnirBaseUrl, String tenantId)
      throws IOException, InterruptedException {
    HttpRequest listRequest =
        HttpRequest.newBuilder(fafnirBaseUrl.resolve("/secrets/" + tenantId))
            .timeout(HTTP_REQUEST_TIMEOUT)
            .GET()
            .build();
    HttpResponse<String> listResponse =
        httpClient.send(listRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    // Same status-before-parse reasoning as fetchConfigForTenant.
    if (listResponse.statusCode() != 200) {
      throw GimleClusterException.unexpectedHttpStatus(
          "secret listing for tenant " + tenantId, listResponse.statusCode(), listResponse.body());
    }
    Map<String, Object> listBody = Json.asObject(Json.parse(listResponse.body()));
    List<Object> secrets = Json.asArray(listBody.get("secrets"));
    List<ConfigValue> result = new ArrayList<>();
    for (Object entry : secrets) {
      Map<String, Object> map = Json.asObject(entry);
      if (Boolean.TRUE.equals(map.get("deleted"))) {
        continue;
      }
      String key = (String) map.get("key");
      HttpRequest valueRequest =
          HttpRequest.newBuilder(fafnirBaseUrl.resolve("/secrets/" + tenantId + "/" + key))
              .timeout(HTTP_REQUEST_TIMEOUT)
              .GET()
              .build();
      HttpResponse<String> valueResponse =
          httpClient.send(valueRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      // Same status-before-parse reasoning as the listing call above: a soft-deleted-between-list-
      // and-fetch or otherwise-unreadable secret must not abort the whole tenant's config load.
      if (valueResponse.statusCode() != 200) {
        log.warn(
            "secret value fetch for {}/{} failed with status {}, skipping",
            tenantId,
            key,
            valueResponse.statusCode());
        continue;
      }
      Map<String, Object> valueBody = Json.asObject(Json.parse(valueResponse.body()));
      byte[] decoded = Base64.getDecoder().decode((String) valueBody.get("value"));
      result.add(new ConfigValue(key, new String(decoded, StandardCharsets.UTF_8), true));
    }
    return result;
  }

  /**
   * Fetches only the SecretMaps a Deployment actually {@code secretMapRefs}'d, in one batched round
   * trip ({@code GET /secretmaps/{tenantId}?names=a,b,c}) straight to Fafnir -- the identical
   * narrowing {@link #fetchConfigMaps} already gives the plain-config half, but for secrets. Every
   * returned name's {@code data} map is already decrypted server-side and base64-encoded the same
   * way a single {@code GET /secrets/{tenantId}/{key}} value is, so decoding here is identical to
   * {@link #fetchSecretsForTenant}'s own per-value decode.
   */
  private static List<ConfigValue> fetchSecretMaps(
      HttpClient httpClient, URI fafnirBaseUrl, String tenantId, List<String> names)
      throws IOException, InterruptedException {
    String namesParam = URLEncoder.encode(String.join(",", names), StandardCharsets.UTF_8);
    HttpRequest request =
        HttpRequest.newBuilder(
                fafnirBaseUrl.resolve("/secretmaps/" + tenantId + "?names=" + namesParam))
            .timeout(HTTP_REQUEST_TIMEOUT)
            .GET()
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      throw GimleClusterException.unexpectedHttpStatus(
          "secretmap fetch for tenant " + tenantId, response.statusCode(), response.body());
    }
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    Map<String, Object> secretMaps = Json.asObject(body.get("secretMaps"));
    List<ConfigValue> result = new ArrayList<>();
    for (Object secretMapObj : secretMaps.values()) {
      Map<String, Object> secretMap = Json.asObject(secretMapObj);
      Map<String, Object> data = Json.asObject(secretMap.get("data"));
      for (Map.Entry<String, Object> keyValue : data.entrySet()) {
        byte[] decoded = Base64.getDecoder().decode((String) keyValue.getValue());
        result.add(
            new ConfigValue(keyValue.getKey(), new String(decoded, StandardCharsets.UTF_8), true));
      }
    }
    return result;
  }

  record ConfigValue(String key, String value, boolean wasEncrypted) {}

  // ---- reconciling the locally-supervised set against the control plane's assignments ----

  static void reconcileAssignments(
      HttpClient httpClient,
      URI baseUrl,
      URI fafnirBaseUrl,
      List<URI> andvariBaseUrls,
      ArtifactPullCache artifactCache,
      SleipnirCache sleipnirCache,
      String muninnEndpoint,
      String nodeId,
      Map<String, SupervisedInstance> supervised,
      Map<String, SupervisedVessel> supervisedVessels,
      Map<String, List<MuninnShipper>> instanceShippers,
      Map<String, WorkerShipperPair> workerShippers,
      String javaExecutable,
      List<String> commandTail,
      ResourceLimiter resourceLimiter,
      VolumeManager volumeManager,
      CapacityTracker capacityTracker,
      CapacityTracker committedWorkerCapacity,
      GossipMember gossipMember,
      ServiceCatalog catalog,
      Path logRoot,
      int maxTier1Density,
      Tier1WorkerBudget tier1Budget,
      Map<String, String> reportedStartFailures)
      throws IOException, InterruptedException {
    List<AssignedInstance> assignments = fetchAssignments(httpClient, baseUrl, nodeId);
    Set<String> currentKeys = new LinkedHashSet<>();
    for (AssignedInstance fetched : assignments) {
      String key = instanceKey(fetched);
      currentKeys.add(key);
      // Resolved before the requiresReplacement check below, not inside the start path: the
      // supervised instance's own assigned record carries the resolved concrete path, so comparing
      // a still-blank registry-coordinate record against it would misread every coordinate
      // assignment as a replacement and restart the instance every tick.
      AssignedInstance assigned;
      try {
        assigned = resolveArtifactReference(httpClient, andvariBaseUrls, artifactCache, fetched);
      } catch (RuntimeException e) {
        log.error("failed to resolve artifact for instance {}: {}", key, e.getMessage());
        // Surfaced as a durable TRANSITION_FAILED timeline event, not only this replica-local log
        // line -- an unresolvable coordinate would otherwise reproduce exactly the invisible
        // "stuck, and nothing anywhere says why" failure shape registry resolution must not have.
        postInstanceEvent(
            httpClient,
            baseUrl,
            nodeId,
            new InstanceEvent(
                UUID.randomUUID().toString(),
                fetched.deploymentName(),
                fetched.instanceIndex(),
                InstanceEventKind.TRANSITION_FAILED,
                "artifact resolution failed",
                Optional.of(String.valueOf(e.getMessage())),
                System.currentTimeMillis()));
        continue;
      }
      // A vessel-flagged assignment never touches supervised/ModuleArtifactReader/the worker
      // handshake at all -- its own dedicated-process spawn/health path is entirely separate, see
      // reconcileVesselAssignment's own javadoc.
      if (assigned.vessel().isPresent()) {
        reconcileVesselAssignment(
            assigned,
            key,
            supervisedVessels,
            javaExecutable,
            resourceLimiter,
            capacityTracker,
            volumeManager,
            httpClient,
            baseUrl,
            fafnirBaseUrl,
            logRoot);
        continue;
      }
      // instanceKey() is deploymentName#index alone -- a rolling update (DeploymentReconciler
      // removing then immediately re-placing the very same index with a new moduleId, see its own
      // javadoc) reuses that identical key, so without this check supervised.containsKey(key)
      // below would read true and this loop would silently do nothing: the old worker keeps
      // running the old code forever, and every heartbeat this agent sends keeps reporting the
      // stale moduleId straight from the never-updated SupervisedInstance (see observationJson),
      // since a rolling update never actually reaches the worker. Treat a changed moduleId or
      // artifactPath at an already-supervised key as "stop the old one, then fall through to the
      // ordinary start path below" -- the same teardown stopInstance already performs for an index
      // no longer assigned at all.
      SupervisedInstance current = supervised.get(key);
      if (current != null && requiresReplacement(assigned, current)) {
        log.info(
            "instance {} reassigned from {} to {} -- stopping the old worker before starting the"
                + " new one",
            key,
            current.assigned.moduleId(),
            assigned.moduleId());
        // false: a rolling-update teardown-then-immediate-replace, not a permanent removal -- the
        // volume (if any) must stay put for the replacement about to be started below. See
        // stopInstance's own releaseVolume javadoc.
        stopInstance(
            key,
            supervised,
            capacityTracker,
            committedWorkerCapacity,
            instanceShippers,
            workerShippers,
            volumeManager,
            false,
            catalog,
            nodeId);
      }
      if (!supervised.containsKey(key)) {
        // A rename hint (DeploymentReconciler#handleSurge promoting a surge instance to a
        // permanent index) means the already-running instance at renamedFromInstanceIndex just
        // needs retargeting, not a fresh spawn -- findRenameSource only returns present when that
        // source is still there and already running exactly what this key now expects.
        Optional<SupervisedInstance> renameSource = findRenameSource(assigned, supervised);
        if (renameSource.isPresent()) {
          renameInPlace(
              key, assigned, renameSource.get(), supervised, instanceShippers, capacityTracker);
        } else {
          try {
            ModuleDescriptor descriptor =
                ModuleArtifactReader.read(Path.of(assigned.artifactPath())).descriptor();
            if (!resourceLimiter.supports(descriptor.isolationTier())) {
              throw GimleIsolationException.tierUnsupported(
                  assigned.moduleId(), descriptor.isolationTier());
            }
            Optional<SupervisedInstance> reusable =
                findReusableTier1Worker(
                    assigned, descriptor, supervised, maxTier1Density, tier1Budget);
            if (reusable.isPresent()) {
              installIntoExistingWorker(
                  assigned,
                  key,
                  descriptor,
                  reusable.get(),
                  supervised,
                  nodeId,
                  capacityTracker,
                  httpClient,
                  baseUrl,
                  fafnirBaseUrl,
                  muninnEndpoint,
                  instanceShippers,
                  logRoot,
                  volumeManager);
            } else {
              startInstance(
                  assigned,
                  key,
                  descriptor,
                  supervised,
                  nodeId,
                  javaExecutable,
                  commandTail,
                  resourceLimiter,
                  tier1Budget,
                  sleipnirCache,
                  capacityTracker,
                  committedWorkerCapacity,
                  gossipMember,
                  catalog,
                  httpClient,
                  baseUrl,
                  fafnirBaseUrl,
                  muninnEndpoint,
                  instanceShippers,
                  workerShippers,
                  logRoot,
                  volumeManager);
            }
            reportedStartFailures.remove(key);
          } catch (IOException | RuntimeException e) {
            log.error("failed to start instance {}: {}", key, e.getMessage(), e);
            reportStartFailure(
                httpClient, baseUrl, nodeId, assigned, key, e, reportedStartFailures);
          }
        }
      }
    }
    // Keeps the report ledger scoped to what this node is still assigned: an index that goes away
    // and later comes back must be free to report its own fresh failure rather than being
    // suppressed by the one its predecessor already reported.
    reportedStartFailures.keySet().retainAll(currentKeys);
    for (String key : List.copyOf(supervised.keySet())) {
      if (!currentKeys.contains(key)) {
        // true: genuinely no longer assigned anywhere -- a real scale-down or spec deletion, the
        // one case a volume's data is actually meant to go away. See stopInstance's own
        // releaseVolume javadoc.
        stopInstance(
            key,
            supervised,
            capacityTracker,
            committedWorkerCapacity,
            instanceShippers,
            workerShippers,
            volumeManager,
            true,
            catalog,
            nodeId);
      }
    }
    for (String key : List.copyOf(supervisedVessels.keySet())) {
      if (!currentKeys.contains(key)) {
        // Genuinely no longer assigned anywhere -- the same "only a real removal releases the
        // volume" rule stopInstance's releaseVolume parameter documents for module hosting.
        stopVesselInstance(
            key, supervisedVessels, resourceLimiter, capacityTracker, volumeManager, true);
      }
    }
    // Probed once per tick (the same 5-second cadence every other agent-side reconciliation runs
    // at) rather than on a dedicated loop -- a vessel's own health has no push mechanism analogous
    // to a worker's ModuleStateChanged message, so polling here is the only way this agent ever
    // learns whether one is actually up.
    for (SupervisedVessel instance : supervisedVessels.values()) {
      updateVesselHealth(instance, httpClient);
    }
  }

  /**
   * A vessel's own reconcile step, entirely separate from the module path above: no {@code
   * ModuleArtifactReader} read (there is no {@code gimle-module.yaml}), no Tier 1 density reuse (a
   * vessel is always its own dedicated process), no rename-in-place (a vessel surge/promotion
   * simply restarts, a documented simplification -- see this change's own final report). {@code
   * requiresVesselReplacement} mirrors {@link #requiresReplacement}'s moduleId/artifactPath
   * comparison but additionally compares {@code vessel()} itself: unlike a module, a vessel has no
   * {@code gimle-module.yaml} of its own, so its entire runtime config (env, args, jvmFlags, files,
   * probes, resource request/limit) lives directly in the manifest's own {@code vessel:} block and
   * would otherwise never be detected as changed.
   *
   * <p>A {@code current} entry whose {@link SupervisedVessel#restartBudgetExhausted} is set has
   * already burned through {@code VesselProcessSupervisor}'s restart budget for exactly this
   * assignment -- it stays in {@code supervisedVessels} (rather than being torn down) precisely so
   * this check can see it and decline to start a fresh supervisor with a fresh budget on the very
   * next tick, which would make the ERROR log that reported "giving up" false within seconds. Only
   * the replacement branch above clears it, since a changed assignment is the one thing that
   * legitimately deserves a clean budget again.
   */
  static void reconcileVesselAssignment(
      AssignedInstance assigned,
      String key,
      Map<String, SupervisedVessel> supervisedVessels,
      String javaExecutable,
      ResourceLimiter resourceLimiter,
      CapacityTracker capacityTracker,
      VolumeManager volumeManager,
      HttpClient httpClient,
      URI baseUrl,
      URI fafnirBaseUrl,
      Path logRoot) {
    SupervisedVessel current = supervisedVessels.get(key);
    if (current != null && requiresVesselReplacement(assigned, current)) {
      log.info(
          "vessel instance {} reassigned from {} to {} -- stopping the old process before"
              + " starting the new one",
          key,
          current.assigned.moduleId(),
          assigned.moduleId());
      // A replacement at the same key keeps its volumes -- the data must survive the swap.
      stopVesselInstance(
          key, supervisedVessels, resourceLimiter, capacityTracker, volumeManager, false);
      current = null;
    }
    if (current != null && current.restartBudgetExhausted) {
      return;
    }
    if (!supervisedVessels.containsKey(key)) {
      try {
        startVesselInstance(
            assigned,
            key,
            assigned.vessel().orElseThrow(),
            supervisedVessels,
            javaExecutable,
            resourceLimiter,
            capacityTracker,
            volumeManager,
            httpClient,
            baseUrl,
            fafnirBaseUrl,
            logRoot);
      } catch (IOException | RuntimeException e) {
        log.error("failed to start vessel instance {}: {}", key, e.getMessage(), e);
      }
    }
  }

  static boolean requiresVesselReplacement(AssignedInstance assigned, SupervisedVessel existing) {
    return !existing.assigned.moduleId().equals(assigned.moduleId())
        || !existing.assigned.artifactPath().equals(assigned.artifactPath())
        || !existing.assigned.vessel().equals(assigned.vessel());
  }

  /**
   * Spawns a vessel's own {@code java -jar} process directly -- reuses {@link ResourceLimiter} (the
   * exact same {@code -Xmx}/{@code ActiveProcessorCount}-equivalent flags a dedicated worker JVM
   * gets) and {@link VesselProcessSupervisor} (the {@code ProcessBuilder} spawn/restart-on-crash
   * machinery {@link WorkerProcessSupervisor} already established) but skips every step that only
   * makes sense for a real Gimlé worker: no control socket, no {@code InstallModule}/{@code
   * ResolveModule}/{@code StartModule} handshake, no volume allocation, no fabric/gossip
   * registration.
   */
  private static void startVesselInstance(
      AssignedInstance assigned,
      String key,
      VesselSpec vessel,
      Map<String, SupervisedVessel> supervisedVessels,
      String javaExecutable,
      ResourceLimiter resourceLimiter,
      CapacityTracker capacityTracker,
      VolumeManager volumeManager,
      HttpClient httpClient,
      URI baseUrl,
      URI fafnirBaseUrl,
      Path logRoot)
      throws IOException {
    if (!resourceLimiter.supports(IsolationTier.TIER_2)) {
      throw GimleIsolationException.tierUnsupported(assigned.moduleId(), IsolationTier.TIER_2);
    }
    ResourceLimitHandle handle = resourceLimiter.prepare(key, vessel.resourceLimit());
    Path vesselRoot = logRoot.resolve("workers").resolve(key);

    Map<String, Integer> allocatedPorts = allocateVesselPorts(vessel);
    List<VolumeHandle> volumeHandles = allocateVesselVolumes(volumeManager, assigned, vessel);
    Map<String, String> volumePaths = new LinkedHashMap<>();
    for (VolumeHandle volumeHandle : volumeHandles) {
      volumePaths.put(volumeHandle.volumeName(), volumeManager.hostPath(volumeHandle).toString());
    }
    Map<String, String> env =
        new LinkedHashMap<>(
            resolveVesselEnv(
                vessel,
                allocatedPorts,
                volumePaths,
                assigned.tenantId(),
                httpClient,
                fafnirBaseUrl));
    // Always exported so the process can locate its rendered vessel.files without guessing: a
    // bundle's working directory must stay the bundle's own workdir (and the unpacked bundle is a
    // shared, presence-trusted cache directory nothing may write into), so for a bundle this
    // variable is the only path back to the per-instance root the files render under.
    env.putIfAbsent("GIMLE_INSTANCE_ROOT", vesselRoot.toAbsolutePath().toString());
    Files.createDirectories(vesselRoot);
    renderVesselFiles(vessel, vesselRoot, assigned.tenantId(), httpClient, baseUrl, fafnirBaseUrl);

    // A bundle resolved from the registry is an unpacked directory; a single-jar vessel (local
    // path or registry-resolved) is a regular file. The directory carries its own launch
    // descriptor, re-read here at spawn time -- a cheap local read, and the workdir escape check
    // below must run against the actual filesystem anyway.
    Path artifactPath = Path.of(assigned.artifactPath());
    List<String> command;
    Optional<Path> workingDirectory;
    if (Files.isDirectory(artifactPath)) {
      VesselEntrypoint entrypoint = VesselEntrypointParser.parseFromBundleRoot(artifactPath);
      command = buildBundleCommand(javaExecutable, resourceLimiter, handle, entrypoint, vessel);
      workingDirectory = Optional.of(resolveBundleWorkdir(artifactPath, entrypoint));
    } else {
      // Absolutized because the process no longer launches in the agent's own working directory,
      // which is what a relative artifactPath would otherwise resolve against.
      command =
          buildVesselCommand(
              javaExecutable,
              resourceLimiter,
              handle,
              vessel,
              artifactPath.toAbsolutePath().toString());
      // The per-instance root, so a relative vessel.files path resolves from inside the process
      // exactly as declared in the manifest -- launching in the agent's own working directory
      // would strand every rendered file somewhere the process can't see.
      workingDirectory = Optional.of(vesselRoot);
    }
    Path applicationLogFile =
        vesselRoot
            .resolve("instances")
            .resolve(assigned.deploymentName() + "-" + assigned.instanceIndex() + ".log");
    RestartTracker restartTracker = defaultRestartTracker();

    VesselProcessSupervisor supervisor =
        new VesselProcessSupervisor(
            key,
            command,
            env,
            workingDirectory,
            restartTracker,
            exhaustedKey -> {
              log.error(
                  "vessel {} exhausted its restart budget on this node; giving up locally until"
                      + " its assignment changes",
                  exhaustedKey);
              resourceLimiter.release(handle);
              capacityTracker.release(exhaustedKey);
              // Left in supervisedVessels, not removed: reconcileVesselAssignment reads
              // restartBudgetExhausted to decline starting a fresh supervisor (with a fresh
              // budget) for this same unchanged assignment on the very next poll tick, which is
              // what actually makes "giving up" true rather than a false claim contradicted a few
              // seconds later. updateVesselHealth keeps reporting this dead process as FAILED same
              // as always.
              SupervisedVessel exhausted = supervisedVessels.get(exhaustedKey);
              if (exhausted != null) {
                exhausted.restartBudgetExhausted = true;
              }
            },
            applicationLogFile,
            respawnedKey -> onVesselRespawned(respawnedKey, supervisedVessels));

    SupervisedVessel instance =
        new SupervisedVessel(
            assigned, vessel, supervisor, handle, allocatedPorts, volumeHandles, Instant.now());
    supervisedVessels.put(key, instance);
    capacityTracker.tryAssign(key, vessel.resourceRequest());
    supervisor.start();
  }

  private static void stopVesselInstance(
      String key,
      Map<String, SupervisedVessel> supervisedVessels,
      ResourceLimiter resourceLimiter,
      CapacityTracker capacityTracker,
      VolumeManager volumeManager,
      boolean releaseVolumes) {
    SupervisedVessel instance = supervisedVessels.remove(key);
    if (instance == null) {
      return;
    }
    instance.supervisor.close();
    // An exhausted instance already released both of these from its own onRestartBudgetExhausted
    // callback -- releasing again would double-release against whatever ResourceLimiter/
    // CapacityTracker implementation is in play, harmless for today's stateless portable limiter
    // but not a safe assumption to bake in here.
    if (!instance.restartBudgetExhausted) {
      resourceLimiter.release(instance.resourceLimitHandle);
      capacityTracker.release(key);
    }
    if (releaseVolumes) {
      instance.volumeHandles.forEach(volumeManager::release);
    }
  }

  /**
   * A crash-triggered respawn restarts the same initial-delay clock every probe rung honors --
   * {@link SupervisedVessel#startedAt} is intentionally not {@code final} for exactly this reason.
   * Mirrors {@code onWorkerRespawned}'s own "the process is a blank slate again" reasoning, just
   * without any handshake to redrive since a vessel never had one.
   */
  private static void onVesselRespawned(
      String key, Map<String, SupervisedVessel> supervisedVessels) {
    SupervisedVessel instance = supervisedVessels.get(key);
    if (instance == null) {
      return; // torn down (undeploy, reassignment) in the window between crash and respawn.
    }
    instance.startedAt = Instant.now();
    instance.lifecycleState = "STARTING";
  }

  /**
   * Refreshes {@link SupervisedVessel#lifecycleState} from the process's own OS-level liveness plus
   * whichever probe rungs the vessel declared -- {@code alive} false (process gone, or a declared
   * liveness probe failing) reports {@code FAILED}, matching the same exclusion-check {@code
   * observationJson} already uses for a module instance, so {@code HealthReconciler} treats a dead
   * vessel exactly like a dead module instance without needing to know the difference.
   */
  static void updateVesselHealth(SupervisedVessel instance, HttpClient httpClient) {
    Process process = instance.supervisor.process();
    boolean processAlive = process != null && process.isAlive();
    boolean alive =
        processAlive
            && evaluateProbe(instance.vessel.probes().liveness(), instance, httpClient, true);
    boolean ready =
        alive && evaluateProbe(instance.vessel.probes().readiness(), instance, httpClient, false);
    instance.lifecycleState = !alive ? "FAILED" : (ready ? "ACTIVE" : "STARTING");
  }

  /**
   * Evaluates one probe rung, honoring its own {@code initialDelaySeconds} against {@link
   * SupervisedVessel#startedAt}: before that delay elapses the rung hasn't run at all yet, so it
   * reports {@code beforeDelayDefault} (true for liveness -- a not-yet-checked process isn't
   * considered unhealthy; false for readiness -- Kubernetes' own "not ready until proven ready"
   * posture). An absent rung (the process-alive-only floor) always reports {@code true}: this
   * specific check never blocks health on its own.
   */
  private static boolean evaluateProbe(
      Optional<VesselProbeSpec> probeSpec,
      SupervisedVessel instance,
      HttpClient httpClient,
      boolean beforeDelayDefault) {
    if (probeSpec.isEmpty()) {
      return true;
    }
    VesselProbeSpec probe = probeSpec.get();
    if (Instant.now().isBefore(instance.startedAt.plusSeconds(probe.initialDelaySeconds()))) {
      return beforeDelayDefault;
    }
    // VesselSpec's own compact constructor already guarantees this resolves: it rejects a probe
    // rung that doesn't name a port whenever more than one is declared, and requires at least one
    // declared port whenever a tcp/http rung is present at all.
    Optional<Integer> port =
        instance.vessel.declaredPortNameFor(probe).map(instance.allocatedPorts::get);
    if (port.isEmpty()) {
      return true;
    }
    return switch (probe) {
      case VesselProbeSpec.Tcp ignored -> VesselProber.tcp("localhost", port.get());
      case VesselProbeSpec.Http http ->
          VesselProber.http(httpClient, "localhost", port.get(), http.path());
    };
  }

  /**
   * The vessel's full command line: {@code java <ResourceLimiter flags> <vessel.jvmFlags> -jar
   * <resolvedJarPath> <vessel.args>} -- the jar's own launcher, untouched. Pure and
   * side-effect-free so it's independently unit-testable, matching {@link #buildWorkerCommand}'s
   * own precedent.
   */
  static List<String> buildVesselCommand(
      String javaExecutable,
      ResourceLimiter resourceLimiter,
      ResourceLimitHandle handle,
      VesselSpec vessel,
      String resolvedJarPath) {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable);
    command.addAll(resourceLimiter.jvmFlags(handle));
    command.addAll(vessel.jvmFlags());
    command.add("-jar");
    command.add(resolvedJarPath);
    command.addAll(vessel.args());
    return command;
  }

  /**
   * A bundle's full command line: the entrypoint's own fixed argv, with the manifest's {@code
   * vessel.args} appended -- never overriding it. {@link ResourceLimiter}'s {@code -Xmx}/{@code
   * ActiveProcessorCount}-equivalent flags are JVM flags, so they're spliced in only when the
   * entrypoint actually launches a JVM: a bare {@code java} in the entrypoint is replaced by this
   * agent's own {@code javaExecutable} (same JVM the agent would launch itself) with the limiter
   * flags right after it, and an entrypoint already naming that exact executable gets the same
   * splice. Any other program ({@code bin/run}, a native-image binary) has no {@code -Xmx} to
   * accept -- the limiter flags have nowhere to attach, and the process runs with no JVM-flag
   * ceiling, the honest current limit of the portable resource-limiting mechanism. Pure and
   * side-effect-free so it's independently unit-testable, matching {@link #buildVesselCommand}.
   */
  static List<String> buildBundleCommand(
      String javaExecutable,
      ResourceLimiter resourceLimiter,
      ResourceLimitHandle handle,
      VesselEntrypoint entrypoint,
      VesselSpec vessel) {
    List<String> entry = entrypoint.command();
    List<String> command = new ArrayList<>();
    String program = entry.get(0);
    boolean launchesJvm = program.equals("java") || program.equals(javaExecutable);
    command.add(launchesJvm ? javaExecutable : program);
    if (launchesJvm) {
      command.addAll(resourceLimiter.jvmFlags(handle));
    }
    command.addAll(entry.subList(1, entry.size()));
    command.addAll(vessel.args());
    return command;
  }

  /**
   * The launched process's working directory: the unpacked bundle root, or the subdirectory the
   * entrypoint's {@code workdir} names inside it. Re-verified against the real filesystem here --
   * not only at parse time -- because the registry deliberately never inspects what it stores, so a
   * hand-crafted archive can reach this agent without ever passing through the CLI's own generation
   * path.
   */
  static Path resolveBundleWorkdir(Path bundleRoot, VesselEntrypoint entrypoint) {
    Path normalizedRoot = bundleRoot.normalize();
    Path resolved = normalizedRoot.resolve(entrypoint.workdir()).normalize();
    if (!resolved.startsWith(normalizedRoot)) {
      throw new GimleManifestException(
          "bundle entrypoint workdir escapes the bundle root: " + entrypoint.workdir());
    }
    return resolved;
  }

  /**
   * Every {@code {port: dynamic}}/{@code {port: <fixed>}} entry in {@code vessel.env}, resolved to
   * a concrete port number -- a fixed port is used exactly as declared (no availability check
   * beyond what the process itself discovers trying to bind it); a dynamic one is allocated via a
   * bind-then-immediately-release on an ephemeral port, the same well-known (and equally
   * well-known-racy) technique every JVM test harness that needs a free port already relies on.
   */
  static Map<String, Integer> allocateVesselPorts(VesselSpec vessel) {
    Map<String, Integer> ports = new LinkedHashMap<>();
    for (Map.Entry<String, VesselEnvValue> entry : vessel.env().entrySet()) {
      if (entry.getValue() instanceof VesselEnvValue.PortAllocation portAllocation) {
        ports.put(
            entry.getKey(), portAllocation.fixedPort().orElseGet(AgentMain::allocateFreePort));
      }
    }
    return ports;
  }

  /**
   * One persistent volume per {@code {volume: ...}} env entry, keyed by the instance's placement
   * identity plus the entry's own env-var name -- exactly {@code allocateVolumesIfNeeded}'s module
   * shape, including its "a failed allocation degrades to no volume rather than blocking the start"
   * posture.
   */
  private static List<VolumeHandle> allocateVesselVolumes(
      VolumeManager volumeManager, AssignedInstance assigned, VesselSpec vessel) {
    List<VolumeHandle> handles = new ArrayList<>();
    for (Map.Entry<String, VesselEnvValue> entry : vessel.env().entrySet()) {
      if (!(entry.getValue() instanceof VesselEnvValue.VolumeMount volumeMount)) {
        continue;
      }
      try {
        handles.add(
            volumeManager.allocate(
                assigned.tenantId(),
                assigned.deploymentName(),
                assigned.instanceIndex(),
                entry.getKey(),
                new VolumeRequest(volumeMount.sizeBytes(), volumeMount.reclaimPolicy())));
      } catch (RuntimeException e) {
        log.error(
            "failed to allocate vessel volume {} for {}#{}: {}",
            entry.getKey(),
            assigned.deploymentName(),
            assigned.instanceIndex(),
            e.getMessage());
      }
    }
    return List.copyOf(handles);
  }

  private static int allocateFreePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException("failed to allocate a free port for a vessel instance", e);
    }
  }

  /**
   * Resolves every {@code vessel.env} entry to its final string value: a literal passes through
   * unchanged, a port allocation becomes {@code allocatedPorts}' own number as text, and a secret
   * reference is fetched from Fafnir over this agent's own mTLS node identity -- the identical path
   * {@link #fetchSecretsForTenant} already uses for module secret delivery, reused rather than a
   * second implementation. Secrets are fetched at most once per spawn (lazily, only if at least one
   * {@code SecretRef} entry exists) regardless of how many secret-backed variables are declared.
   */
  private static Map<String, String> resolveVesselEnv(
      VesselSpec vessel,
      Map<String, Integer> allocatedPorts,
      Map<String, String> volumePaths,
      Optional<String> tenantId,
      HttpClient httpClient,
      URI fafnirBaseUrl) {
    Map<String, String> env = new LinkedHashMap<>();
    Map<String, String> secrets = null;
    for (Map.Entry<String, VesselEnvValue> entry : vessel.env().entrySet()) {
      String name = entry.getKey();
      switch (entry.getValue()) {
        case VesselEnvValue.Literal literal -> env.put(name, literal.value());
        case VesselEnvValue.PortAllocation ignored ->
            env.put(name, String.valueOf(allocatedPorts.get(name)));
        case VesselEnvValue.VolumeMount ignored -> {
          // Absent from volumePaths when its allocation failed -- the variable is then simply not
          // exported, the same degrade-don't-block posture allocateVesselVolumes documents.
          String volumePath = volumePaths.get(name);
          if (volumePath != null) {
            env.put(name, volumePath);
          }
        }
        case VesselEnvValue.SecretRef secretRef -> {
          if (secrets == null) {
            secrets = fetchVesselSecretsByKey(tenantId, httpClient, fafnirBaseUrl);
          }
          String value = secrets.get(secretRef.key());
          if (value == null) {
            throw GimleSecretsException.secretNotFound(
                tenantId.orElse("(untenanted)"), secretRef.key());
          }
          env.put(name, value);
        }
      }
    }
    return env;
  }

  private static Map<String, String> fetchVesselSecretsByKey(
      Optional<String> tenantId, HttpClient httpClient, URI fafnirBaseUrl) {
    if (tenantId.isEmpty() || fafnirBaseUrl == null) {
      return Map.of();
    }
    try {
      Map<String, String> byKey = new LinkedHashMap<>();
      for (ConfigValue entry : fetchSecretsForTenant(httpClient, fafnirBaseUrl, tenantId.get())) {
        byKey.put(entry.key(), entry.value());
      }
      return byKey;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Map.of();
    } catch (IOException e) {
      log.warn(
          "failed to fetch secrets for vessel env resolution (tenant {}): {}",
          tenantId.get(),
          e.getMessage());
      return Map.of();
    }
  }

  /**
   * Renders every {@code vessel.files} entry to disk before the process starts: fetches the
   * tenant's plain config the same way {@link #deliverConfig}'s own plain-config half already does
   * ({@code GET /config/{tenantId}}) and, when any entry is secret-backed, the tenant's secrets
   * over this agent's own mTLS node identity ({@link #fetchVesselSecretsByKey}, the same path
   * secret-backed env vars already take); then writes each declared value's raw content verbatim --
   * no templating -- to {@code path}, relative to this instance's own per-instance root, or used
   * as-is if already absolute. A secret-backed file additionally gets owner-only permissions, via
   * the portable {@code File.setReadable}/{@code setWritable} calls rather than any POSIX-specific
   * API -- by declaration its content is sensitive, so nothing but the vessel's own user may read
   * it back.
   */
  private static void renderVesselFiles(
      VesselSpec vessel,
      Path instanceRoot,
      Optional<String> tenantId,
      HttpClient httpClient,
      URI baseUrl,
      URI fafnirBaseUrl) {
    if (vessel.files().isEmpty()) {
      return;
    }
    Map<String, String> config = new LinkedHashMap<>();
    if (tenantId.isPresent()) {
      try {
        for (ConfigValue entry : fetchConfigForTenant(httpClient, baseUrl, tenantId.get())) {
          config.put(entry.key(), entry.value());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (IOException | RuntimeException e) {
        log.warn(
            "failed to fetch config for vessel file rendering (tenant {}): {}",
            tenantId.get(),
            e.getMessage());
      }
    }
    Map<String, String> secrets = null;
    for (VesselFileMount mount : vessel.files()) {
      String value;
      boolean secretBacked = mount.secretKey().isPresent();
      if (secretBacked) {
        if (secrets == null) {
          secrets = fetchVesselSecretsByKey(tenantId, httpClient, fafnirBaseUrl);
        }
        value = secrets.get(mount.secretKey().orElseThrow());
        if (value == null) {
          throw new GimleManifestException(
              "vessel.files references secret key '"
                  + mount.secretKey().orElseThrow()
                  + "', which has no value for tenant "
                  + tenantId.orElse("(untenanted)"));
        }
      } else {
        value = config.get(mount.configKey().orElseThrow());
        if (value == null) {
          throw new GimleManifestException(
              "vessel.files references config key '"
                  + mount.configKey().orElseThrow()
                  + "', which has no value for tenant "
                  + tenantId.orElse("(untenanted)"));
        }
      }
      Path target = Path.of(mount.path());
      if (!target.isAbsolute()) {
        target = instanceRoot.resolve(target);
      }
      try {
        Path parent = target.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        Files.writeString(target, value, StandardCharsets.UTF_8);
        if (secretBacked) {
          restrictToOwner(target);
        }
      } catch (IOException e) {
        throw new UncheckedIOException("failed to render vessel file " + target, e);
      }
    }
  }

  /**
   * Owner-only read/write via {@code java.io.File}'s portable permission setters -- works on every
   * platform the JVM does, unlike {@code PosixFilePermissions}. Best-effort: a filesystem that
   * cannot express the restriction (some FAT mounts) logs rather than fails the spawn, since the
   * file itself rendered correctly.
   */
  private static void restrictToOwner(Path target) {
    java.io.File file = target.toFile();
    boolean restricted =
        file.setReadable(false, false)
            & file.setWritable(false, false)
            & file.setExecutable(false, false)
            & file.setReadable(true, true)
            & file.setWritable(true, true);
    if (!restricted) {
      log.warn("could not fully restrict permissions on secret-backed file {}", target);
    }
  }

  /**
   * Tier 1 density: reuse an already-running worker for {@code assigned} instead of spawning a new
   * JVM, when doing so is safe -- deliberately narrow (agent-local, node-implicit) scope, see
   * {@link #DEFAULT_MAX_TIER1_DENSITY}'s own javadoc. Groups {@code supervised} by {@code
   * workerKey} -- every instance sharing one worker carries the same owning key, either its own (a
   * freshly spawned worker's own instance) or the key of whichever instance spawned it (a packed
   * one) -- rather than by {@link WorkerConnection} identity. Connection identity looks equivalent
   * at first glance (every instance sharing a worker does end up sharing one connection) but is
   * filled in asynchronously once the spawned worker JVM actually connects, so a batch of brand-new
   * Tier-1 instances arriving in the very same reconcile tick would each see every sibling's
   * connection as still null and spawn its own worker -- exactly the "one worker per instance, zero
   * sharing" failure this method exists to prevent. {@code workerKey} is set synchronously at
   * construction time instead, so a same-tick sibling is visible immediately.
   */
  static Optional<SupervisedInstance> findReusableTier1Worker(
      AssignedInstance assigned,
      ModuleDescriptor descriptor,
      Map<String, SupervisedInstance> supervised,
      int maxTier1Density,
      Tier1WorkerBudget tier1Budget) {
    if (descriptor.isolationTier() != IsolationTier.TIER_1) {
      return Optional.empty();
    }
    Map<String, List<SupervisedInstance>> byWorkerKey = new LinkedHashMap<>();
    for (SupervisedInstance existing : supervised.values()) {
      if (existing.workerKey != null) {
        byWorkerKey.computeIfAbsent(existing.workerKey, k -> new ArrayList<>()).add(existing);
      }
    }
    for (Map.Entry<String, List<SupervisedInstance>> entry : byWorkerKey.entrySet()) {
      // The owning instance -- the one whose own key equals this group's workerKey -- is always
      // the source of truth for connection/workerLimit, whether or not it has connected yet; a
      // packed instance's own copies of those fields may still be catching up (see
      // installIntoExistingWorker). Absent only when that owner has since been stopped or renamed
      // away while packed siblings remain, an edge case this treats as "nothing to reuse" rather
      // than reasoning about a worker with no owner left to ask.
      SupervisedInstance owner = supervised.get(entry.getKey());
      if (owner == null) {
        continue;
      }
      List<SupervisedInstance> group = entry.getValue();
      boolean allTier1 =
          group.stream().allMatch(i -> i.descriptor.isolationTier() == IsolationTier.TIER_1);
      boolean sameTenant = owner.assigned.tenantId().equals(assigned.tenantId());
      // Two replicas of one deployment are welcome to share a worker: TIER_1 is by definition
      // classloader isolation with a shared crash domain, and an operator who wants separate
      // domains asks for TIER_2 or anti-affinity. What must never share a worker is one instance
      // with itself -- the same placement arriving twice -- since the two would then contend for a
      // single set of the worker's per-instance state.
      boolean noInstanceConflict =
          group.stream().noneMatch(i -> i.moduleInstanceId.equals(moduleInstanceIdOf(assigned)));
      boolean underDensityLimit = group.size() < maxTier1Density;
      // The density cap counts instances; this weighs them. Four 8Mi modules and four 900Mi
      // modules are the same to a count and nothing alike to a heap, so a worker is reused only
      // while every resident's declared limit plus the candidate's still fits inside the heap that
      // worker was actually spawned with. Overflow is not a rejection: the caller falls through to
      // spawning a fresh worker, which is where a claim that fits nowhere already goes.
      boolean fitsWorkerBudget =
          tier1Budget.admits(
              workerSizeOf(owner, tier1Budget),
              group.stream().map(i -> i.descriptor).toList(),
              descriptor);
      if (allTier1 && sameTenant && noInstanceConflict && underDensityLimit && fitsWorkerBudget) {
        return Optional.of(owner);
      }
    }
    return Optional.empty();
  }

  /**
   * The heap and CPU the worker behind {@code owner} was spawned with, recorded on the owning
   * instance so the answer stays correct even once other instances have joined it. Falls back to
   * the budget's nominal size only for an instance assembled without a real spawn behind it, which
   * in practice means a unit test.
   */
  private static ResourceSpec workerSizeOf(
      SupervisedInstance owner, Tier1WorkerBudget tier1Budget) {
    if (owner.workerLimit != null) {
      return owner.workerLimit;
    }
    return new ResourceSpec(
        ResourceSpec.formatMemory(tier1Budget.heapBytes()),
        ResourceSpec.formatCpu(tier1Budget.cpuMillicores()));
  }

  /**
   * How long a Tier-1 instance packed onto an already-starting shared worker (one {@link
   * #findReusableTier1Worker} found before the worker it belongs to had finished connecting) waits
   * for that connection before giving up. Bounded, unlike {@link ControlChannelServer#accept()}
   * itself: the *owning* instance's own {@link WorkerProcessSupervisor} already retries a worker
   * that never connects, forever, on its own restart budget, but a joining instance has no such
   * retry of its own to fall back on -- an unbounded wait here would leak this thread forever if
   * the owner's worker never starts at all.
   */
  private static final Duration SHARED_WORKER_JOIN_TIMEOUT = Duration.ofSeconds(30);

  private static final Duration SHARED_WORKER_JOIN_POLL_INTERVAL = Duration.ofMillis(50);

  /**
   * Installs {@code assigned} into {@code existing}'s worker -- no new {@link
   * WorkerProcessSupervisor}, {@link ControlChannelServer}, or {@link ResourceLimitHandle}: the
   * shared worker keeps the {@code -Xmx} it was spawned with, which under {@link Tier1WorkerBudget}
   * is the node's declared shared-worker size rather than any one instance's own limit. What makes
   * that sound is the caller: {@link #findReusableTier1Worker} only offers a worker whose remaining
   * budget still covers this instance's declared limit, so arriving here means the claim already
   * fits. It is a reservation, not a partition -- one JVM has one heap, and a module that overruns
   * its declared limit can still exhaust the worker its co-tenants are running in.
   *
   * <p>{@code existing} (the worker's owning instance, per {@link #findReusableTier1Worker}) may
   * not have connected yet -- packing now happens as soon as a worker exists, not once it has
   * finished its handshake, so that instances arriving in the same reconcile tick as the worker
   * they are joining still get packed instead of each spawning their own (see {@link
   * #findReusableTier1Worker}'s own javadoc). When a connection is already open, this proceeds
   * synchronously exactly as before; otherwise it waits for one on a dedicated thread rather than
   * blocking this tick's own loop, the same "never block assignment-poll on a slow-starting JVM"
   * posture {@link #driveInstanceUp} already takes for a freshly spawned worker's owning instance.
   */
  static void installIntoExistingWorker(
      AssignedInstance assigned,
      String key,
      ModuleDescriptor descriptor,
      SupervisedInstance existing,
      Map<String, SupervisedInstance> supervised,
      String nodeId,
      CapacityTracker capacityTracker,
      HttpClient httpClient,
      URI baseUrl,
      URI fafnirBaseUrl,
      String muninnEndpoint,
      Map<String, List<MuninnShipper>> instanceShippers,
      Path logRoot,
      VolumeManager volumeManager) {
    SupervisedInstance instance =
        new SupervisedInstance(
            assigned,
            existing.supervisor,
            existing.server,
            descriptor,
            existing.workerKey,
            existing.workerLimit);
    supervised.put(key, instance);
    capacityTracker.tryAssign(key, descriptor.resourceRequest());
    startShippingInstanceLogs(muninnEndpoint, instanceShippers, key, assigned, logRoot);
    WorkerConnection connection = existing.connection;
    if (connection != null) {
      copyFabricIdentity(instance, existing, connection);
      completeSharedWorkerInstall(
          instance,
          connection,
          key,
          supervised,
          capacityTracker,
          httpClient,
          baseUrl,
          fafnirBaseUrl,
          volumeManager,
          instanceShippers);
      return;
    }
    Thread.ofVirtual()
        .name("gimle-instance-joiner-" + key)
        .start(
            () ->
                joinSharedWorkerOnceConnected(
                    instance,
                    existing,
                    key,
                    nodeId,
                    supervised,
                    capacityTracker,
                    httpClient,
                    baseUrl,
                    fafnirBaseUrl,
                    volumeManager,
                    instanceShippers));
  }

  private static void copyFabricIdentity(
      SupervisedInstance instance, SupervisedInstance owner, WorkerConnection connection) {
    instance.connection = connection;
    instance.fabricWorkerId = owner.fabricWorkerId;
    instance.fabricUdsPath = owner.fabricUdsPath;
    instance.fabricTcpAddress = owner.fabricTcpAddress;
  }

  /**
   * Waits (bounded, see {@link #SHARED_WORKER_JOIN_TIMEOUT}) for {@code owner}'s worker to finish
   * connecting, then finishes installing {@code instance} into it -- the deferred half of {@link
   * #installIntoExistingWorker} for the case where the worker being joined hadn't connected yet at
   * pack time. Bails out early, doing nothing, if {@code instance} is no longer the live occupant
   * of {@code key} by the time the wait ends (stopped, replaced, or renamed away while this thread
   * was waiting) -- exactly the same "supervised may have moved on" guard {@link
   * #onWorkerRespawned} and friends already apply before touching a captured instance.
   */
  private static void joinSharedWorkerOnceConnected(
      SupervisedInstance instance,
      SupervisedInstance owner,
      String key,
      String nodeId,
      Map<String, SupervisedInstance> supervised,
      CapacityTracker capacityTracker,
      HttpClient httpClient,
      URI baseUrl,
      URI fafnirBaseUrl,
      VolumeManager volumeManager,
      Map<String, List<MuninnShipper>> instanceShippers) {
    Instant deadline = Instant.now().plus(SHARED_WORKER_JOIN_TIMEOUT);
    WorkerConnection connection = owner.connection;
    while (connection == null
        && Instant.now().isBefore(deadline)
        && supervised.get(key) == instance) {
      try {
        Thread.sleep(SHARED_WORKER_JOIN_POLL_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      connection = owner.connection;
    }
    if (supervised.get(key) != instance) {
      return;
    }
    if (connection == null) {
      log.error("instance {} timed out waiting for its shared worker to finish connecting", key);
      supervised.remove(key);
      capacityTracker.release(key);
      stopShippingInstanceLogs(instanceShippers, key);
      if ("INSTALLED".equals(instance.lifecycleState)) {
        instance.lifecycleState = "FAILED";
      }
      postInstanceEvent(
          httpClient,
          baseUrl,
          nodeId,
          new InstanceEvent(
              UUID.randomUUID().toString(),
              instance.assigned.deploymentName(),
              instance.assigned.instanceIndex(),
              InstanceEventKind.TRANSITION_FAILED,
              "timed out waiting for shared worker to connect",
              Optional.empty(),
              System.currentTimeMillis()));
      return;
    }
    copyFabricIdentity(instance, owner, connection);
    completeSharedWorkerInstall(
        instance,
        connection,
        key,
        supervised,
        capacityTracker,
        httpClient,
        baseUrl,
        fafnirBaseUrl,
        volumeManager,
        instanceShippers);
  }

  /**
   * The tail shared by both the synchronous (already-connected) and deferred (joined-later) paths
   * through {@link #installIntoExistingWorker}: send the install sequence, and on failure undo the
   * bookkeeping registered for {@code key} above, the same cleanup {@link #startInstance}'s own
   * failure path performs.
   */
  private static void completeSharedWorkerInstall(
      SupervisedInstance instance,
      WorkerConnection connection,
      String key,
      Map<String, SupervisedInstance> supervised,
      CapacityTracker capacityTracker,
      HttpClient httpClient,
      URI baseUrl,
      URI fafnirBaseUrl,
      VolumeManager volumeManager,
      Map<String, List<MuninnShipper>> instanceShippers) {
    try {
      sendInstallStartSequence(
          instance, key, connection, httpClient, baseUrl, fafnirBaseUrl, volumeManager);
    } catch (IOException e) {
      log.error("failed to install {} into shared worker: {}", key, e.getMessage());
      supervised.remove(key);
      capacityTracker.release(key);
      stopShippingInstanceLogs(instanceShippers, key);
    }
  }

  static void startInstance(
      AssignedInstance assigned,
      String key,
      ModuleDescriptor descriptor,
      Map<String, SupervisedInstance> supervised,
      String nodeId,
      String javaExecutable,
      List<String> commandTail,
      ResourceLimiter resourceLimiter,
      Tier1WorkerBudget tier1Budget,
      SleipnirCache sleipnirCache,
      CapacityTracker capacityTracker,
      CapacityTracker committedWorkerCapacity,
      GossipMember gossipMember,
      ServiceCatalog catalog,
      HttpClient httpClient,
      URI baseUrl,
      URI fafnirBaseUrl,
      String muninnEndpoint,
      Map<String, List<MuninnShipper>> instanceShippers,
      Map<String, WorkerShipperPair> workerShippers,
      Path logRoot,
      VolumeManager volumeManager)
      throws IOException {
    Path socketPath = Files.createTempDirectory("gimle-worker-uds-").resolve("c.sock");
    ControlChannelServer server = new ControlChannelServer(socketPath);
    ResourceLimitHandle handle =
        prepareResourceLimit(resourceLimiter, key, descriptor, tier1Budget);
    Path workerLogRoot = logRoot.resolve("workers").resolve(key);
    // Sleipnir: aotCacheKey is fixed for this instance's whole lifetime (computed once, purely a
    // function of commandTail), but aotCachePath is re-resolved by the supplier below on every
    // spawn -- including a crash-triggered respawn -- rather than snapshotted here, since training
    // can still be running in the background at this exact moment and a later respawn must be able
    // to pick up a cache that only finished training after this instance's first spawn. Absent,
    // this spawns exactly like it always has (AOTMode=auto means a worker is never blocked on, or
    // broken by, whether Sleipnir has finished training yet).
    Optional<String> aotCacheKey = sleipnirCache.keyFor(commandTail);
    // Issued before the first spawn, over this agent's own mTLS client: the worker's certificate
    // is what every other worker's FabricServer reads its tenant off, so a worker never starts
    // without one in TLS mode. A refused issuance fails this start, which the next reconcile
    // tick simply retries -- the same level-triggered retry every other start failure gets.
    Optional<WorkerCertificates.Material> tlsMaterial =
        issueWorkerCertificate(httpClient, baseUrl, nodeId, key, assigned.tenantId());
    Supplier<List<String>> baseCommand =
        () ->
            buildWorkerCommand(
                javaExecutable,
                commandTail,
                resourceLimiter,
                handle,
                workerLogRoot,
                nodeId,
                assigned,
                sleipnirCache.cacheFor(commandTail),
                tlsMaterial);

    RestartTracker restartTracker = defaultRestartTracker();
    Path systemLogFile = logRoot.resolve("workers").resolve(key + "-system.log");
    WorkerProcessSupervisor supervisor =
        new WorkerProcessSupervisor(
            key,
            baseCommand,
            socketPath,
            restartTracker,
            exhaustedKey -> {
              log.error(
                  "instance {} exhausted its restart budget on this node; giving up locally",
                  exhaustedKey);
              resourceLimiter.release(handle);
              capacityTracker.release(exhaustedKey);
              committedWorkerCapacity.release(exhaustedKey);
              supervised.remove(exhaustedKey);
            },
            Optional.of(systemLogFile),
            WorkerProcessSupervisor.DEFAULT_STABLE_UPTIME_THRESHOLD,
            Optional.of(workerLogRoot),
            crash -> onWorkerCrash(crash, key, supervised, catalog, httpClient, baseUrl, nodeId),
            spawnedWorkerId ->
                onWorkerRespawned(
                    spawnedWorkerId,
                    supervised,
                    gossipMember,
                    catalog,
                    httpClient,
                    baseUrl,
                    fafnirBaseUrl,
                    nodeId,
                    volumeManager,
                    muninnEndpoint,
                    workerShippers));

    SupervisedInstance instance =
        new SupervisedInstance(assigned, supervisor, server, descriptor, key, handle.limit());
    instance.aotCacheKey = aotCacheKey;
    supervised.put(key, instance);
    try {
      capacityTracker.tryAssign(key, descriptor.resourceRequest());
      // Real committed memory, not the tiny declared request tryAssign above tracks -- the
      // check that actually catches a node overcommitting its real machine memory across
      // accumulated shared-worker ceilings. Checked (and reserved) before supervisor.start()
      // below actually forks the process, so a refusal here never spawns anything to clean up.
      if (!committedWorkerCapacity.tryAssign(key, handle.limit())) {
        CapacityTracker.Snapshot committed = committedWorkerCapacity.snapshot();
        String refusal =
            "refusing to spawn worker "
                + key
                + ": committing its "
                + ResourceSpec.formatMemory(handle.limit().memoryBytes())
                + " ceiling would exceed this node's own real memory budget (already committed: "
                + ResourceSpec.formatMemory(committed.assignedMemoryBytes())
                + ", node total: "
                + ResourceSpec.formatMemory(committed.totalMemoryBytes())
                + ")";
        log.error(refusal);
        throw new IOException(refusal);
      }
      startShippingInstanceLogs(muninnEndpoint, instanceShippers, key, assigned, logRoot);
      supervisor.start();
    } catch (IOException | RuntimeException e) {
      // Undo everything registered above so a start failure leaves no trace behind -- mirrors
      // installIntoExistingWorker's own failure cleanup, plus closing the control channel server
      // this method (unlike that one) freshly bound. committedWorkerCapacity.release is a safe
      // no-op when the refusal above never actually reserved anything.
      supervised.remove(key);
      capacityTracker.release(key);
      committedWorkerCapacity.release(key);
      stopShippingInstanceLogs(instanceShippers, key);
      try {
        server.close();
      } catch (IOException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }

    Thread.ofVirtual()
        .name("gimle-instance-starter-" + key)
        .start(
            () ->
                driveInstanceUp(
                    instance,
                    key,
                    gossipMember,
                    catalog,
                    httpClient,
                    baseUrl,
                    fafnirBaseUrl,
                    nodeId,
                    supervised,
                    volumeManager,
                    muninnEndpoint,
                    workerShippers));
  }

  /**
   * Relays a {@link CrashInfo} classification to every {@code SupervisedInstance} the crashed
   * worker hosted -- under Tier 1 density that can be more than one, all sharing the same {@link
   * WorkerProcessSupervisor}, so this can't just look up {@code spawnedWorkerId} alone. Reuses
   * {@link InstanceEventKind#TRANSITION_FAILED} rather than a new kind: adding {@code CRASHED}
   * would break the documented 1:1 mirror with {@code gimle-module}'s own {@code LifecycleEvent}
   * variants for no benefit a {@code causeSummary} doesn't already give a reader.
   *
   * <p>Also evicts the crashed worker's own entries from this agent's shared {@link ServiceCatalog}
   * via {@link ServiceCatalog#evictWorker} -- called here rather than waiting for {@link
   * #onWorkerRespawned}'s {@link #resetForRespawn} because this callback fires the moment the crash
   * is detected, before the restart backoff delay and the respawn itself, so every other cluster
   * member stops routing to the dead worker as promptly as this node can tell it. Uses {@code
   * instance.fabricWorkerId} -- the id the worker itself reported at its Hello handshake and the
   * one every catalog entry is actually keyed by -- not {@code spawnedWorkerId}, which is this
   * agent's own supervision key. Every hosted instance shares the same {@code fabricWorkerId}, so
   * the first non-null one found is enough; {@code null} means the crashed worker never completed
   * its Hello handshake and so never registered anything into the catalog to evict.
   */
  static void onWorkerCrash(
      CrashInfo crash,
      String spawnedWorkerId,
      Map<String, SupervisedInstance> supervised,
      ServiceCatalog catalog,
      HttpClient httpClient,
      URI baseUrl,
      String nodeId) {
    String causeSummary =
        switch (crash.cause()) {
          case OOM -> "worker OOM (exit code " + crash.exitCode() + ")";
          case NATIVE_CRASH ->
              "worker native crash (exit code "
                  + crash.exitCode()
                  + "), dump at "
                  + crash.hsErrLog().orElseThrow();
          case UNKNOWN -> "worker exited unexpectedly (exit code " + crash.exitCode() + ")";
        };
    String fabricWorkerId = null;
    for (SupervisedInstance instance : supervised.values()) {
      if (!instance.supervisor.workerId().equals(spawnedWorkerId)) {
        continue;
      }
      if (fabricWorkerId == null) {
        fabricWorkerId = instance.fabricWorkerId;
      }
      InstanceEvent event =
          new InstanceEvent(
              UUID.randomUUID().toString(),
              instance.assigned.deploymentName(),
              instance.assigned.instanceIndex(),
              InstanceEventKind.TRANSITION_FAILED,
              "worker crashed",
              Optional.of(causeSummary),
              Instant.now().toEpochMilli());
      postInstanceEvent(httpClient, baseUrl, nodeId, event);
    }
    if (fabricWorkerId != null) {
      catalog.evictWorker(nodeId, fabricWorkerId);
    }
  }

  /**
   * Re-runs the {@code InstallModule}/{@code ResolveModule}/{@code StartModule} handshake against a
   * worker JVM that just came back up after a crash-triggered respawn ({@link
   * WorkerProcessSupervisor}'s {@code onRespawned} callback). A freshly-spawned process is a blank
   * slate -- no installed module, no resolved layer, nothing started -- even though it shares its
   * predecessor's {@code workerId} and control-socket path, so every {@code SupervisedInstance} the
   * crashed worker hosted (more than one under Tier 1 density, all sharing one {@link
   * ControlChannelServer}) is reset to its pre-connection state and re-driven together over the one
   * freshly-accepted connection, exactly like a brand-new worker's first start.
   *
   * <p>{@code instance.volumeHandles} is deliberately left untouched by the reset: {@link
   * #allocateVolumesIfNeeded}, called again as part of {@link #sendInstallStartSequence}, resolves
   * to the same on-disk directory for the same {@code (deploymentName, instanceIndex)} pair (see
   * {@code LocalDiskVolumeManager#allocate}'s idempotent {@code createDirectories}), so re-deriving
   * it here would be redundant, not incorrect -- but skipping it keeps this method's intent (reset
   * only what the crash actually invalidated) honest.
   */
  private static void onWorkerRespawned(
      String spawnedWorkerId,
      Map<String, SupervisedInstance> supervised,
      GossipMember gossipMember,
      ServiceCatalog catalog,
      HttpClient httpClient,
      URI baseUrl,
      URI fafnirBaseUrl,
      String nodeId,
      VolumeManager volumeManager,
      String muninnEndpoint,
      Map<String, WorkerShipperPair> workerShippers) {
    List<Map.Entry<String, SupervisedInstance>> hosted =
        supervised.entrySet().stream()
            .filter(entry -> entry.getValue().supervisor.workerId().equals(spawnedWorkerId))
            .toList();
    if (hosted.isEmpty()) {
      // Every instance the crashed worker hosted was torn down (undeploy, rename, scale-down)
      // in the window between the crash and this respawn notification -- nothing left to redrive.
      return;
    }
    for (Map.Entry<String, SupervisedInstance> entry : hosted) {
      resetForRespawn(entry.getValue());
    }
    Map.Entry<String, SupervisedInstance> first = hosted.get(0);
    try {
      WorkerConnection connection = first.getValue().server.accept();
      for (Map.Entry<String, SupervisedInstance> entry : hosted) {
        entry.getValue().connection = connection;
      }
      Thread.ofVirtual()
          .name("gimle-instance-reader-" + first.getKey())
          .start(
              () ->
                  readLoop(
                      first.getValue(),
                      first.getKey(),
                      gossipMember,
                      catalog,
                      httpClient,
                      baseUrl,
                      nodeId,
                      supervised,
                      muninnEndpoint,
                      workerShippers));
      for (Map.Entry<String, SupervisedInstance> entry : hosted) {
        sendInstallStartSequence(
            entry.getValue(),
            entry.getKey(),
            connection,
            httpClient,
            baseUrl,
            fafnirBaseUrl,
            volumeManager);
      }
    } catch (IOException e) {
      log.error("failed to redrive worker {} after respawn: {}", spawnedWorkerId, e.getMessage());
    }
  }

  /**
   * Rolls one {@code SupervisedInstance} back to the state {@link #startInstance} would have given
   * it before its worker ever connected the first time -- everything a respawned worker's blank
   * slate has invalidated. Never touches {@link SupervisedInstance#volumeHandles} (see {@link
   * #onWorkerRespawned}'s own javadoc) or {@link SupervisedInstance#assigned}/{@code supervisor}/
   * {@code server}/{@code descriptor}, none of which the crash changed.
   */
  private static void resetForRespawn(SupervisedInstance instance) {
    instance.connection = null;
    instance.lifecycleState = "INSTALLED";
    instance.fabricWorkerId = null;
    instance.fabricUdsPath = "";
    instance.fabricTcpAddress = null;
    instance.cpuMillicoresUsed = 0;
    instance.memoryBytesUsed = 0;
    instance.requestRatePerSecond = 0;
    instance.errorRatePerSecond = 0;
    instance.queueDepth = 0;
    instance.ports = Map.of();
  }

  /**
   * The size a worker JVM is spawned under ({@code -Xmx}, {@code ActiveProcessorCount}) -- never
   * {@code resourceRequest}, which is the deliberately different scheduling/capacity-accounting
   * figure {@code capacityTracker.tryAssign} uses. The one choke point where that size is decided,
   * extracted so a test can assert on it directly rather than only on the limiter's own output
   * (which is correct either way {@code PortableJvmFlagsResourceLimiterTest} already proves).
   */
  static ResourceLimitHandle prepareResourceLimit(
      ResourceLimiter resourceLimiter,
      String key,
      ModuleDescriptor descriptor,
      Tier1WorkerBudget tier1Budget) {
    // A Tier 2 worker hosts exactly one instance, so its declared limit is the worker's limit. A
    // Tier 1 worker hosts several behind one heap, so no single instance's limit can size it --
    // it takes the node's declared shared-worker budget instead, and the instances packed into it
    // are admitted against that size rather than silently reshaping it.
    ResourceSpec limit =
        descriptor.isolationTier() == IsolationTier.TIER_1
            ? tier1Budget.sizeFor(descriptor)
            : descriptor.resourceLimit();
    return resourceLimiter.prepare(key, limit);
  }

  /**
   * Builds a spawned worker JVM's full command line. Pure and side-effect-free (no process
   * spawning, no {@link ResourceLimitHandle} lifecycle concerns) so it can be unit-tested directly,
   * separately from {@link #startInstance} which owns those concerns.
   *
   * <p>{@code -Dgimle.log.root=<workerLogRoot>} scopes this worker's own default {@code
   * gimle.log.root} ("gimle-logs", relative to wherever it would otherwise inherit its CWD) to a
   * directory unique to this worker -- without it, {@code WorkerMain}'s {@code worker-platform.log}
   * would land somewhere {@link AgentLogServer} never looks, and every worker this agent supervises
   * would additionally collide on one shared filename. {@code -XX:ErrorFile=...} scopes a native
   * crash's {@code hs_err_pid<pid>.log} the same way -- {@code %p} is HotSpot's own
   * PID-substitution token, so a respawn after a crash ({@code RestartTracker}) doesn't overwrite
   * the previous dump. {@code nodeId} and {@code tenantId} are appended last: {@code WorkerMain}
   * expects {@code <nodeId> <tenantId-or-empty> <control-socket-path>}, in that order, and {@code
   * WorkerProcessSupervisor} always appends the control-socket path last, so tenantId must be
   * appended here, right after {@code nodeId}.
   */
  /**
   * The subset of every worker's flags that never vary with which worker it is -- excludes {@code
   * -Dgimle.log.root}/{@code -XX:ErrorFile} (both derived from {@code workerLogRoot}, unique per
   * worker) and {@code resourceLimiter.jvmFlags(handle)} (unique per worker's resource limit). TLS
   * material is excluded for the same reason: each worker presents its own certificate (see {@link
   * WorkerCertificates}), so those paths are per-instance too. {@link SleipnirCache}'s cache key is
   * computed from exactly this list plus the classpath -- extracted here rather than duplicated
   * there so the key and the real command can never silently drift apart.
   */
  static List<String> stableWorkerFlags() {
    List<String> flags =
        new ArrayList<>(
            List.of(
                LEAK_DETECTION_JFR_FLAG,
                // Makes an OOM exit unambiguous (exit code 3, HotSpot's own code for this flag)
                // rather than indistinguishable from any other unexpected exit --
                // WorkerProcessSupervisor's crash classification depends on this being set on
                // every worker, unconditionally.
                "-XX:+ExitOnOutOfMemoryError",
                // Forwarded unconditionally (defaulting to this agent's own unset-property
                // "false") rather than only when explicitly set, so every worker this agent
                // spawns gets an explicit, consistent value instead of silently inheriting
                // whatever WorkerMain's own default happens to be.
                "-Dgimle.fabric.defaultDenyCrossTenant="
                    + System.getProperty("gimle.fabric.defaultDenyCrossTenant", "false"),
                // Same reasoning: an explicit, consistent per-worker fabric connection ceiling
                // rather than each worker silently falling back to FabricServer's own default.
                "-Dgimle.fabric.maxConnections="
                    + System.getProperty("gimle.fabric.maxConnections", "512"),
                // A worker starts once per module instance, not once per node/replica lifecycle
                // like every other process role -- printing GimleBanner's ASCII-art banner on
                // every one of those spawns would just be log noise at scale, so this agent
                // unconditionally suppresses it for every worker it spawns. WorkerMain still
                // prints when run directly (its own default stays enabled).
                "-Dgimle.banner.enabled=false",
                // ConsoleLogEncoder defaults to colored text now (see its own javadoc), which is
                // exactly wrong for a piped subprocess: WorkerProcessSupervisor.drainOutput
                // JSON-sniffs this worker's raw stdout to tell a structured line (already
                // captured by its own PlatformFileAppender, so safe to skip) apart from
                // unstructured output worth capturing separately. Forced explicitly here rather
                // than left to a tty-detection guess, so the sniffing stays correct by
                // construction.
                "-Dgimle.log.console=json"));
    return List.copyOf(flags);
  }

  /**
   * Forwards this agent's own already-resolved transport posture onto the worker it spawns. {@link
   * TransportProtocol#fromConfig()}/{@link TlsSettings#fromConfig()} read {@code -D} system
   * properties that don't cross a {@code ProcessBuilder} spawn boundary -- a worker is a separate
   * child JVM, not a thread -- so without this, a worker JVM (hosting the gateway module's
   * TLS-terminating {@code HttpsServer} and {@code gimle-fabric}'s cross-machine {@code
   * FabricServer}/{@code FabricClient}) always resolves {@link TransportProtocol#PLAINTEXT}
   * regardless of the cluster's real posture. The certificate and key are the worker's own ({@code
   * tlsMaterial}, issued by {@link WorkerCertificates} before the spawn), never this agent's node
   * certificate: what a worker presents on the fabric is how a receiving listener learns its
   * tenant, and a node identity handed to every worker would let hosted-module code act as the
   * node. Only the cluster CA file is shared. Empty in plaintext mode, the common case, where no
   * material was ever issued.
   */
  private static List<String> workerTlsFlags(Optional<WorkerCertificates.Material> tlsMaterial) {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT || tlsMaterial.isEmpty()) {
      return List.of();
    }
    TlsSettings settings = TlsSettings.fromConfig();
    return List.of(
        "-Dgimle.transport.protocol=tls",
        "-Dgimle.tls.certFile=" + tlsMaterial.get().certFile(),
        "-Dgimle.tls.keyFile=" + tlsMaterial.get().keyFile(),
        "-Dgimle.tls.caFile=" + settings.caFile());
  }

  /**
   * The worker's own certificate for a spawn under {@code workerKey}, issued now if it doesn't
   * exist yet or is due -- empty in plaintext mode. A refused or failed issuance surfaces as an
   * {@link IOException} so the instance start fails visibly rather than spawning a worker that
   * presents no identity at all.
   */
  private static Optional<WorkerCertificates.Material> issueWorkerCertificate(
      HttpClient httpClient,
      URI baseUrl,
      String nodeId,
      String workerKey,
      Optional<String> tenantId)
      throws IOException {
    Optional<WorkerCertificates> certificates =
        WorkerCertificates.fromConfig(httpClient, baseUrl, resolveAdvertisedHost());
    if (certificates.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(certificates.get().ensureIssued(nodeId, workerKey, tenantId));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException(
          "interrupted while requesting a worker certificate for " + workerKey);
    }
  }

  /**
   * Tick-time renewal of every supervised worker's certificate, one entry per distinct worker (a
   * Tier 1 density-packed worker hosts several instances under one {@code workerKey}, all of the
   * same tenant). The worker notices the rewritten file itself through its own {@code
   * FabricServerTlsWatcher}; nothing here has to tell it.
   */
  private static void renewWorkerCertificates(
      HttpClient httpClient,
      URI baseUrl,
      String nodeId,
      Map<String, SupervisedInstance> supervised) {
    Optional<WorkerCertificates> certificates =
        WorkerCertificates.fromConfig(httpClient, baseUrl, resolveAdvertisedHost());
    if (certificates.isEmpty()) {
      return;
    }
    Map<String, Optional<String>> tenantByWorkerKey = new LinkedHashMap<>();
    for (SupervisedInstance instance : supervised.values()) {
      tenantByWorkerKey.putIfAbsent(instance.workerKey, instance.assigned.tenantId());
    }
    Set<String> renewed = certificates.get().renewDue(nodeId, tenantByWorkerKey);
    if (!renewed.isEmpty()) {
      log.info("renewed worker certificates for {}", renewed);
    }
  }

  static List<String> buildWorkerCommand(
      String javaExecutable,
      List<String> commandTail,
      ResourceLimiter resourceLimiter,
      ResourceLimitHandle handle,
      Path workerLogRoot,
      String nodeId,
      AssignedInstance assigned,
      Optional<Path> aotCachePath,
      Optional<WorkerCertificates.Material> tlsMaterial) {
    List<String> baseCommand = new ArrayList<>();
    baseCommand.add(javaExecutable);
    baseCommand.addAll(stableWorkerFlags());
    baseCommand.addAll(workerTlsFlags(tlsMaterial));
    baseCommand.add("-Dgimle.log.root=" + workerLogRoot);
    baseCommand.add("-XX:ErrorFile=" + workerLogRoot.resolve("hs_err_pid%p.log").toAbsolutePath());
    baseCommand.addAll(resourceLimiter.jvmFlags(handle));
    // AOTMode=auto: a mismatched or corrupt cache degrades to a normal start -- a worker must
    // never fail to spawn because of Sleipnir. -Xlog:aot=warning is silent when healthy; on
    // fallback the warning line lands in this worker's own captured stdout, observable through the
    // existing Logs surface with zero new plumbing.
    aotCachePath.ifPresent(
        path -> {
          baseCommand.add("-XX:AOTCache=" + path);
          baseCommand.add("-XX:AOTMode=auto");
          baseCommand.add("-Xlog:aot=warning");
        });
    baseCommand.addAll(commandTail);
    baseCommand.add(nodeId);
    baseCommand.add(assigned.tenantId().orElse(""));
    return baseCommand;
  }

  private static void driveInstanceUp(
      SupervisedInstance instance,
      String key,
      GossipMember gossipMember,
      ServiceCatalog catalog,
      HttpClient httpClient,
      URI baseUrl,
      URI fafnirBaseUrl,
      String nodeId,
      Map<String, SupervisedInstance> supervised,
      VolumeManager volumeManager,
      String muninnEndpoint,
      Map<String, WorkerShipperPair> workerShippers) {
    try {
      WorkerConnection connection = instance.server.accept();
      instance.connection = connection;
      Thread.ofVirtual()
          .name("gimle-instance-reader-" + key)
          .start(
              () ->
                  readLoop(
                      instance,
                      key,
                      gossipMember,
                      catalog,
                      httpClient,
                      baseUrl,
                      nodeId,
                      supervised,
                      muninnEndpoint,
                      workerShippers));
      sendInstallStartSequence(
          instance, key, connection, httpClient, baseUrl, fafnirBaseUrl, volumeManager);
    } catch (IOException e) {
      // This is the sole caller of sendInstallStartSequence for this instance -- nothing retries
      // it on a later reconcile tick, so a failure here strands the instance forever at whatever
      // lifecycle state it last reached (often RESOLVED, before StartModule ever went out).
      // e.getMessage() is frequently null for a bare connection failure, which previously made
      // this log line read as just "...: null" with no way to diagnose it; including the
      // exception's own class name, and surfacing a durable TRANSITION_FAILED timeline event the
      // same way artifact-resolution failures already do above, gives an operator something to
      // act on instead of a silently stuck instance.
      log.error(
          "failed to bring up instance {}: {}: {}",
          key,
          e.getClass().getSimpleName(),
          e.getMessage());
      // Same guard and rationale as the install-phase Nack handling in readLoop above: only
      // overwrite the default "INSTALLED" state, never a more advanced one this failure raced
      // against, and flipping to FAILED is what makes the health reconciler's own alive check
      // (via observationJson) treat this instance as needing to be healed instead of merely
      // still-starting forever.
      if ("INSTALLED".equals(instance.lifecycleState)) {
        instance.lifecycleState = "FAILED";
      }
      postInstanceEvent(
          httpClient,
          baseUrl,
          nodeId,
          new InstanceEvent(
              UUID.randomUUID().toString(),
              instance.assigned.deploymentName(),
              instance.assigned.instanceIndex(),
              InstanceEventKind.TRANSITION_FAILED,
              "failed to bring up instance",
              Optional.of(e.getClass().getSimpleName() + ": " + e.getMessage()),
              System.currentTimeMillis()));
    }
  }

  /**
   * The {@code InstallModule}/{@code ResolveModule}/(config)/{@code StartModule} sequence a fresh
   * worker gets right after connecting ({@link #driveInstanceUp}) and a shared worker gets when a
   * new Tier-1 instance joins it ({@link #installIntoExistingWorker}) -- identical either way, the
   * only difference is whether the connection was just accepted or already open.
   *
   * <p>Also the single choke point that resolves this instance's persistent volume, if its
   * descriptor declares one -- {@code allocateVolumeIfNeeded} runs before {@code ResolveModule} is
   * built, since that message is what carries the resolved host path down to the worker, which
   * needs it no later than {@code resolve()} time (the worker's own {@code ModuleContext} is
   * created there, before {@code onInstall} fires).
   *
   * <p>{@code key} is recorded against each of this sequence's own correlation ids in {@link
   * #pendingLifecycleCorrelations} -- see that field's own javadoc for why {@link #readLoop} needs
   * it to attribute a {@link ControlMessage.Nack} to the right instance under Tier 1 density.
   */
  static void sendInstallStartSequence(
      SupervisedInstance instance,
      String key,
      WorkerConnection connection,
      HttpClient httpClient,
      URI baseUrl,
      URI fafnirBaseUrl,
      VolumeManager volumeManager)
      throws IOException {
    String installCorrelationId = nextCorrelationId();
    pendingLifecycleCorrelations.put(installCorrelationId, key);
    connection.send(
        new ControlMessage.InstallModule(
            installCorrelationId,
            instance.assigned.artifactPath(),
            instance.assigned.deploymentName(),
            instance.assigned.instanceIndex()));
    instance.volumeHandles = allocateVolumesIfNeeded(volumeManager, instance);
    Map<String, String> dataDirectories = new LinkedHashMap<>();
    for (VolumeHandle handle : instance.volumeHandles) {
      dataDirectories.put(handle.volumeName(), volumeManager.hostPath(handle).toString());
    }
    String resolveCorrelationId = nextCorrelationId();
    pendingLifecycleCorrelations.put(resolveCorrelationId, key);
    connection.send(
        new ControlMessage.ResolveModule(
            resolveCorrelationId, instance.moduleInstanceId, dataDirectories));
    // Delivered after Resolve (which is when the worker's ModuleContext is created) and before
    // Start, over this same ordered channel, so every module hook's config(key) lookups are
    // already backed by real values from the moment it starts.
    deliverConfig(instance, connection, httpClient, baseUrl, fafnirBaseUrl);
    String startCorrelationId = nextCorrelationId();
    pendingLifecycleCorrelations.put(startCorrelationId, key);
    connection.send(new ControlMessage.StartModule(startCorrelationId, instance.moduleInstanceId));
  }

  /**
   * Empty for every module that declares no {@code volumes:} (the common case) -- {@code
   * volumeManager.allocate} is only ever called for a {@code StatefulSet}-shaped instance's own
   * descriptor. A failed allocation (insufficient disk space, an I/O error) is logged and that
   * named volume is treated as absent rather than blocking the instance from starting at all --
   * matches {@code prepareResourceLimit}'s own sibling failure posture for CPU/memory, and leaves
   * {@code ModuleContext.dataDirectory(name)} empty for a hook to detect and react to on its own
   * terms.
   */
  private static List<VolumeHandle> allocateVolumesIfNeeded(
      VolumeManager volumeManager, SupervisedInstance instance) {
    Map<String, VolumeRequest> requests = instance.descriptor.volumes();
    if (requests.isEmpty()) {
      return List.of();
    }
    List<VolumeHandle> handles = new ArrayList<>();
    for (Map.Entry<String, VolumeRequest> entry : requests.entrySet()) {
      try {
        handles.add(
            volumeManager.allocate(
                instance.assigned.tenantId(),
                instance.assigned.deploymentName(),
                instance.assigned.instanceIndex(),
                entry.getKey(),
                entry.getValue()));
      } catch (RuntimeException e) {
        log.error(
            "failed to allocate volume {} for {}#{}: {}",
            entry.getKey(),
            instance.assigned.deploymentName(),
            instance.assigned.instanceIndex(),
            e.getMessage());
      }
    }
    return List.copyOf(handles);
  }

  /**
   * Plain config still comes from {@code ApiServer}'s own {@code /config/{tenantId}}, decrypted
   * server-side exactly as before -- unaffected by this split, since only where decryption happens
   * changed, not this call. When the assignment declares {@code configMapRefs}, though, this
   * fetches only those named ConfigMaps ({@code GET /configmaps/{tenantId}?names=...}) instead of
   * the tenant's entire flat config set, flattening each one's own key/value data into the same
   * {@link ConfigValue} shape -- {@code ctx.config(key)} on the module side is unaffected either
   * way. Secret values, by contrast, are fetched directly from Fafnir, authorized by this agent's
   * own node identity certificate rather than relayed through the control plane, so a compromised
   * or buggy control-plane replica is never in a position to see a decrypted secret value pass
   * through it. {@code secretMapRefs} narrows secret delivery the identical way {@code
   * configMapRefs} narrows the plain-config half: when declared, only those named SecretMaps' keys
   * are fetched ({@code GET /secretmaps/{tenantId}?names=...}, straight to Fafnir) instead of the
   * tenant's entire secret set. {@code fafnirBaseUrl} is {@code null} when {@code
   * -Dgimle.agent.fafnirEndpoint} was never configured -- instances still start, simply without any
   * secret values delivered, exactly like a tenant that never uses secrets.
   */
  static void deliverConfig(
      SupervisedInstance instance,
      WorkerConnection connection,
      HttpClient httpClient,
      URI baseUrl,
      URI fafnirBaseUrl)
      throws IOException {
    for (ConfigValue entry : fetchConfigEntries(instance, httpClient, baseUrl, fafnirBaseUrl)) {
      connection.send(
          new ControlMessage.ConfigDelivered(entry.key(), entry.value(), entry.wasEncrypted()));
    }
  }

  /**
   * The fetch half of {@link #deliverConfig}, shared with {@link ConfigRelay} so a later change to
   * a value (or a rotated secret) is re-fetched by the identical logic that fetched it at instance
   * start -- same tenant scoping, same {@code configMapRefs}/{@code secretMapRefs} narrowing, same
   * "a failed half never takes the other half down" posture.
   */
  static List<ConfigValue> fetchConfigEntries(
      SupervisedInstance instance, HttpClient httpClient, URI baseUrl, URI fafnirBaseUrl) {
    Optional<String> tenantId = instance.assigned.tenantId();
    if (tenantId.isEmpty()) {
      return List.of();
    }
    List<String> configMapRefs = instance.assigned.configMapRefs();
    List<ConfigValue> entries = new ArrayList<>();
    try {
      entries.addAll(
          configMapRefs.isEmpty()
              ? fetchConfigForTenant(httpClient, baseUrl, tenantId.get())
              : fetchConfigMaps(httpClient, baseUrl, tenantId.get(), configMapRefs));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of();
    } catch (IOException | RuntimeException e) {
      // No early return: secret delivery below is deliberately independent of the control
      // plane's own /config surface (see this method's javadoc), so a denied or failed plain
      // config fetch must never take the tenant's secrets down with it. Under mTLS a node
      // principal is not authorized for /config at all, making this the normal path, not an
      // edge case. IOException caught alongside RuntimeException, not left to propagate: a
      // network-level failure here (the control plane briefly unreachable) must not abort the
      // whole install sequence and leave the instance stuck mid-Resolve forever -- the same
      // "log and keep starting" posture this method already takes for a denied/malformed fetch.
      log.warn(
          "failed to fetch config for tenant {}: {}; continuing to secret delivery",
          tenantId.get(),
          e.getMessage());
    }
    List<String> secretMapRefs = instance.assigned.secretMapRefs();
    if (fafnirBaseUrl != null) {
      try {
        entries.addAll(
            secretMapRefs.isEmpty()
                ? fetchSecretsForTenant(httpClient, fafnirBaseUrl, tenantId.get())
                : fetchSecretMaps(httpClient, fafnirBaseUrl, tenantId.get(), secretMapRefs));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return List.copyOf(entries);
      } catch (IOException | RuntimeException e) {
        // Same reasoning as the config fetch above: Fafnir being briefly unreachable (or not
        // configured to match a stale -Dgimle.agent.fafnirEndpoint) must not abort instance
        // startup either -- the instance starts without secret values, exactly as documented
        // for fafnirBaseUrl == null above.
        log.warn(
            "failed to fetch secrets for tenant {}: {}; instance will start without them",
            tenantId.get(),
            e.getMessage());
      }
    }
    return List.copyOf(entries);
  }

  /**
   * One reader thread per connection, not per instance -- when Tier 1 density packs several
   * instances onto one worker, only the instance that actually accepted the connection ({@code
   * instance} here) has a reader thread; every message naming a {@code ModuleId} ({@code
   * ModuleStateChanged}/{@code ServiceRegistered}/{@code ServiceUnregistered}/{@code
   * MetricsReport}) is demuxed via {@link #findByModuleId} to whichever sibling {@code
   * SupervisedInstance} it actually belongs to, since starting a second reader on the same
   * connection would race two threads over one socket. {@code Hello} is the one message that isn't
   * module-scoped -- it fires once, before any sibling could have joined this connection, so it's
   * always safe to apply directly to {@code instance}.
   */
  static void readLoop(
      SupervisedInstance instance,
      String key,
      GossipMember gossipMember,
      ServiceCatalog catalog,
      HttpClient httpClient,
      URI baseUrl,
      String nodeId,
      Map<String, SupervisedInstance> supervised,
      String muninnEndpoint,
      Map<String, WorkerShipperPair> workerShippers) {
    WorkerConnection connection = instance.connection;
    try {
      Optional<ControlMessage> received;
      while ((received = connection.receive()).isPresent()) {
        ControlMessage message = received.get();
        if (message instanceof ControlMessage.ModuleStateChanged changed) {
          findByModuleId(supervised, connection, changed.id())
              .ifPresent(
                  target -> {
                    target.lifecycleState = changed.state();
                    // A readiness reading from before this transition (e.g. the previous ACTIVE
                    // window, before a restart) must not leak into the new one -- observationJson
                    // falls back to its own ACTIVE-derived default until this instance's probe
                    // loop reports fresh, exactly as if it had never reported at all.
                    target.readinessReported = Optional.empty();
                  });
        } else if (message instanceof ControlMessage.HealthReport health) {
          findByModuleId(supervised, connection, health.id())
              .ifPresent(target -> target.readinessReported = Optional.of(health.ready()));
        } else if (message instanceof ControlMessage.Ack ack) {
          pendingLifecycleCorrelations.remove(ack.correlationId());
        } else if (message instanceof ControlMessage.Nack nack) {
          // Nack carries no ModuleId, so under Tier 1 density the connection-owning `instance`
          // parameter is not necessarily who this actually belongs to --
          // pendingLifecycleCorrelations
          // (populated by sendInstallStartSequence/stopInstance) names the real target by the
          // correlation id both sides already agree on. Falling back to `instance`/`key` only for a
          // correlation id this agent never tracked (e.g. a test driving the wire by hand).
          String targetKey = pendingLifecycleCorrelations.remove(nack.correlationId());
          SupervisedInstance target = targetKey != null ? supervised.get(targetKey) : instance;
          String loggedKey = targetKey != null ? targetKey : key;
          log.warn("instance {} nacked {}: {}", loggedKey, nack.correlationId(), nack.reason());
          // An install-phase nack (the module never left its initial INSTALLED state -- e.g. the
          // worker couldn't read the jar, or a Tier 1 density collision with a stale ModuleId
          // already resident in this shared worker) used to leave lifecycleState at "INSTALLED"
          // forever: the instance looked merely still-starting, indefinitely, in the console and
          // `gimle deployment status`. FAILED makes the failure visible and, via observationJson's
          // own alive derivation, hands it to the health reconciler to heal. A nack after a
          // successful start keeps the last real lifecycle state rather than clobbering it.
          if (target != null && "INSTALLED".equals(target.lifecycleState)) {
            target.lifecycleState = "FAILED";
            postInstanceEvent(
                httpClient,
                baseUrl,
                nodeId,
                new InstanceEvent(
                    UUID.randomUUID().toString(),
                    target.assigned.deploymentName(),
                    target.assigned.instanceIndex(),
                    InstanceEventKind.TRANSITION_FAILED,
                    "install sequence nacked",
                    Optional.of(nack.reason()),
                    System.currentTimeMillis()));
          }
        } else if (message instanceof ControlMessage.InstanceEventOccurred occurred) {
          postInstanceEvent(httpClient, baseUrl, nodeId, occurred.event());
        } else if (message instanceof ControlMessage.Hello hello) {
          instance.fabricWorkerId = hello.workerId();
          instance.fabricUdsPath = hello.fabricUdsPath();
          instance.fabricTcpAddress =
              new InetSocketAddress(hello.fabricTcpHost(), hello.fabricTcpPort());
          // Sync this worker's fresh FabricServiceRegistry cache with everything this agent
          // already knows: the gossip-driven onDelta relay only fires for a delta applied *after*
          // its listener was registered, so anything learned before this worker connected would
          // otherwise never reach it.
          syncCatalogToWorker(instance, catalog);
          startShippingWorkerMetricsAndTraces(
              muninnEndpoint, workerShippers, nodeId, hello.workerId());
        } else if (message instanceof ControlMessage.MetricsSnapshot snapshot) {
          WorkerShipperPair shippers = workerShippers.get(snapshot.workerId());
          if (shippers != null) {
            shippers.metrics().shipPreparedBatch(snapshot.ndjsonPayload());
          }
        } else if (message instanceof ControlMessage.TracesSnapshot snapshot) {
          WorkerShipperPair shippers = workerShippers.get(snapshot.workerId());
          if (shippers != null) {
            shippers.traces().shipPreparedBatch(snapshot.ndjsonPayload());
          }
        } else if (message instanceof ControlMessage.ServiceRegistered registered) {
          findByModuleId(supervised, connection, registered.moduleId())
              .ifPresent(
                  target ->
                      registerIntoCatalog(
                          target,
                          gossipMember,
                          catalog,
                          registered.moduleId(),
                          registered.export(),
                          true));
        } else if (message instanceof ControlMessage.ServiceUnregistered unregistered) {
          findByModuleId(supervised, connection, unregistered.moduleId())
              .ifPresent(
                  target ->
                      registerIntoCatalog(
                          target,
                          gossipMember,
                          catalog,
                          unregistered.moduleId(),
                          unregistered.export(),
                          false));
        } else if (message instanceof ControlMessage.MetricsReport metrics) {
          findByModuleId(supervised, connection, metrics.id())
              .ifPresent(
                  target -> {
                    target.cpuMillicoresUsed = metrics.cpuMillicoresUsed();
                    target.memoryBytesUsed = metrics.memoryBytesUsed();
                    target.requestRatePerSecond = metrics.requestRatePerSecond();
                    target.errorRatePerSecond = metrics.errorRatePerSecond();
                    target.queueDepth = metrics.queueDepth();
                    target.ports = metrics.ports();
                  });
        } else if (message instanceof ControlMessage.RelayControlPlaneRead relayRead) {
          handleRelayRead(relayRead, connection, httpClient, baseUrl, instance, nodeId);
        } else if (message instanceof ControlMessage.RelayResourceStatusPut statusPut) {
          handleRelayStatusPut(statusPut, connection, httpClient, baseUrl, instance, nodeId);
        }
      }
      log.info("instance {} control channel closed", key);
    } catch (IOException e) {
      log.warn("instance {} control channel failed: {}", key, e.getMessage());
    }
  }

  /**
   * The trust boundary for a hosted module's whitelisted read-back into the control plane's own
   * HTTP API: independently re-validates {@code request.path()} against {@link
   * #RELAY_WHITELIST_PATTERN} before making any real call -- the worker (and the module running
   * inside it) is never trusted to only ask for something already allowed. A non-whitelisted path
   * never reaches the control plane at all, answered locally with a synthesized {@code 403}. For an
   * allowed path, this makes the real call the same way {@link #fetchAssignments}/{@link
   * #fetchConfigForTenant} already do -- this agent's own current {@code httpClient}, already
   * carrying its mTLS identity. A transport failure (control plane unreachable, timed out) is
   * synthesized as a {@code 502} rather than left to propagate out of this method and take this
   * connection's whole read loop down with it.
   */
  /**
   * A path is relayable in one of two ways. A <em>tenanted</em> instance relays under its own
   * workload identity: this agent mints (and caches) a per-{@code deploymentName#nodeId} token from
   * the control plane and attaches it as a bearer credential, and the control plane's own RBAC then
   * governs what that workload principal may read -- any {@code GET} path is forwarded, the server
   * decides. The token is mandatory on this path: without one the request is refused locally rather
   * than relayed bare, so a hosted module can never ride this agent's own node identity. An
   * <em>untenanted</em> instance has no tenant for a workload identity to scope to (the mint
   * endpoint refuses it), so it keeps the original hard-coded {@link #RELAY_WHITELIST_PATTERN}
   * instead -- exactly {@code GET /endpoints/{name}}, the pre-identity contract, unchanged. Under
   * Tier 1 density the attributed identity is the connection-owning instance's deployment -- a
   * packed sibling of a different deployment (same tenant, by construction) relays under the
   * hosting instance's identity, an accepted coarseness the wire protocol doesn't carry enough to
   * refine.
   */
  private static void handleRelayRead(
      ControlMessage.RelayControlPlaneRead request,
      WorkerConnection connection,
      HttpClient httpClient,
      URI baseUrl,
      SupervisedInstance instance,
      String nodeId) {
    Optional<String> workloadToken = Optional.empty();
    if (instance.assigned.tenantId().isPresent()) {
      if (!RELAYABLE_PATH_PATTERN.matcher(request.path()).matches()
          || request.path().contains("..")) {
        log.warn("rejecting malformed control-plane relay path: {}", request.path());
        sendRelayResult(
            connection, request.correlationId(), 400, "malformed relay path: " + request.path());
        return;
      }
      workloadToken = workloadTokenFor(instance, nodeId, httpClient, baseUrl);
      if (workloadToken.isEmpty()) {
        sendRelayResult(
            connection,
            request.correlationId(),
            502,
            "no workload identity available for this instance; relay refused");
        return;
      }
    } else if (!RELAY_WHITELIST_PATTERN.matcher(request.path()).matches()) {
      log.warn("rejecting non-whitelisted control-plane relay path: {}", request.path());
      sendRelayResult(
          connection,
          request.correlationId(),
          403,
          "path not whitelisted for control-plane relay: " + request.path());
      return;
    }
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(baseUrl.resolve(request.path()))
              .timeout(HTTP_REQUEST_TIMEOUT)
              .GET();
      workloadToken.ifPresent(token -> builder.header("Authorization", "Bearer " + token));
      HttpResponse<String> response =
          httpClient.send(
              builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      sendRelayResult(connection, request.correlationId(), response.statusCode(), response.body());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.warn("control-plane relay request to {} failed: {}", request.path(), e.getMessage());
      sendRelayResult(
          connection,
          request.correlationId(),
          502,
          "agent could not reach the control plane: " + e.getMessage());
    }
  }

  /**
   * Sanity shape for a workload-identified relay path (the RBAC decision itself is the control
   * plane's): one absolute, printable-ASCII path with an optional query string, no fragment -- plus
   * the separate {@code ".."} rejection at the call site above.
   */
  private static final Pattern RELAYABLE_PATH_PATTERN = Pattern.compile("^/[!-~&&[^#]]*$");

  /**
   * One path segment of a relayed status put's typed fields (kind name, resource name, tenant id):
   * allowed identifier characters only, and never a whole segment that resolves as "here"/"up a
   * level" -- the same shape discipline {@link #RELAY_WHITELIST_PATTERN} applies, enforced per
   * field since this agent assembles the real path itself.
   */
  private static final Pattern STATUS_PUT_SEGMENT_PATTERN =
      Pattern.compile("^(?!\\.{1,2}$)[a-zA-Z0-9._-]+$");

  /**
   * The one write the relay mechanism carries: a hosted operator's status report for one custom
   * resource, assembled into {@code PUT /resources/{kind}/{name}/status} by this agent from the
   * message's typed fields -- never a raw worker-supplied path -- and always under the instance's
   * own workload-identity token, so the control plane's separate {@code {kind}/status} RBAC grant
   * is what actually decides. An untenanted instance has no workload identity to mint (the mint
   * endpoint refuses it), so it is refused locally: there is no anonymous status-reporting path.
   */
  private static void handleRelayStatusPut(
      ControlMessage.RelayResourceStatusPut request,
      WorkerConnection connection,
      HttpClient httpClient,
      URI baseUrl,
      SupervisedInstance instance,
      String nodeId) {
    if (instance.assigned.tenantId().isEmpty()) {
      sendRelayResult(
          connection,
          request.correlationId(),
          403,
          "status reporting requires a workload identity; this instance is untenanted");
      return;
    }
    if (!STATUS_PUT_SEGMENT_PATTERN.matcher(request.kindName()).matches()
        || !STATUS_PUT_SEGMENT_PATTERN.matcher(request.name()).matches()
        || (!request.tenantId().isEmpty()
            && !STATUS_PUT_SEGMENT_PATTERN.matcher(request.tenantId()).matches())) {
      log.warn(
          "rejecting malformed status-put fields: kind={} name={} tenant={}",
          request.kindName(),
          request.name(),
          request.tenantId());
      sendRelayResult(
          connection, request.correlationId(), 400, "malformed status-put kind/name/tenant field");
      return;
    }
    Optional<String> workloadToken = workloadTokenFor(instance, nodeId, httpClient, baseUrl);
    if (workloadToken.isEmpty()) {
      sendRelayResult(
          connection,
          request.correlationId(),
          502,
          "no workload identity available for this instance; status report refused");
      return;
    }
    String path =
        "/resources/"
            + request.kindName()
            + "/"
            + request.name()
            + "/status"
            + (request.tenantId().isEmpty() ? "" : "?tenant=" + request.tenantId());
    try {
      HttpRequest httpRequest =
          HttpRequest.newBuilder(baseUrl.resolve(path))
              .timeout(HTTP_REQUEST_TIMEOUT)
              .header("Authorization", "Bearer " + workloadToken.get())
              .PUT(
                  HttpRequest.BodyPublishers.ofString(request.statusJson(), StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> response =
          httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      sendRelayResult(connection, request.correlationId(), response.statusCode(), response.body());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.warn("status-put relay to {} failed: {}", path, e.getMessage());
      sendRelayResult(
          connection,
          request.correlationId(),
          502,
          "agent could not reach the control plane: " + e.getMessage());
    }
  }

  /** A minted workload token and when it stops verifying -- see {@link #workloadTokenFor}. */
  private record MintedWorkloadToken(String token, long expiresAtEpochMilli) {}

  /** Refresh a cached token this long before it would expire, so a relay never races expiry. */
  private static final Duration WORKLOAD_TOKEN_REFRESH_MARGIN = Duration.ofMinutes(5);

  private static final Map<String, MintedWorkloadToken> workloadTokenCache =
      new ConcurrentHashMap<>();

  /**
   * The cached-or-freshly-minted workload token for {@code instance}'s deployment on this node --
   * {@code POST /workload-tokens}, authorized by this agent's own node identity plus the store's
   * assignment check on the control-plane side. A failed mint falls back to a still-unexpired
   * cached token if one exists (the control plane briefly unreachable must not break relays that a
   * valid token can still serve), and otherwise resolves empty -- the caller refuses the relay.
   */
  private static Optional<String> workloadTokenFor(
      SupervisedInstance instance, String nodeId, HttpClient httpClient, URI baseUrl) {
    // Tenant-scoped for the same reason instanceKey() is: two different tenants' identically-named
    // workload on this same node must never share a cached token minted for only one of them. "#"
    // rather than a NUL separator for the identical reason instanceKey() uses it --
    // deploymentName's
    // own validation regex guarantees it can never contain "#", so this stays unambiguous.
    String cacheKey =
        instance.assigned.tenantId().orElse("")
            + "#"
            + instance.assigned.deploymentName()
            + "#"
            + nodeId;
    long now = System.currentTimeMillis();
    MintedWorkloadToken cached = workloadTokenCache.get(cacheKey);
    if (cached != null
        && now < cached.expiresAtEpochMilli() - WORKLOAD_TOKEN_REFRESH_MARGIN.toMillis()) {
      return Optional.of(cached.token());
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("deploymentName", instance.assigned.deploymentName());
    body.put("nodeId", nodeId);
    try {
      HttpRequest request =
          HttpRequest.newBuilder(baseUrl.resolve("/workload-tokens"))
              .timeout(HTTP_REQUEST_TIMEOUT)
              .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        log.warn("workload token mint for {} answered {}", cacheKey, response.statusCode());
        return stillValid(cached, now);
      }
      Map<String, Object> result = Json.asObject(Json.parse(response.body()));
      MintedWorkloadToken minted =
          new MintedWorkloadToken(
              String.valueOf(result.get("token")),
              ((Number) result.get("expiresAtEpochMilli")).longValue());
      workloadTokenCache.put(cacheKey, minted);
      return Optional.of(minted.token());
    } catch (IOException | RuntimeException e) {
      log.warn("workload token mint for {} failed: {}", cacheKey, e.getMessage());
      return stillValid(cached, now);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return stillValid(cached, now);
    }
  }

  private static Optional<String> stillValid(MintedWorkloadToken cached, long now) {
    return cached != null && now < cached.expiresAtEpochMilli()
        ? Optional.of(cached.token())
        : Optional.empty();
  }

  private static void sendRelayResult(
      WorkerConnection connection, String correlationId, int status, String body) {
    try {
      connection.send(new ControlMessage.RelayControlPlaneResult(correlationId, status, body));
    } catch (IOException e) {
      log.warn("failed to send control-plane relay result back to worker: {}", e.getMessage());
    }
  }

  /** Every {@code SupervisedInstance} sharing {@code connection} whose module is {@code id}. */
  /**
   * Which supervised instance a worker's report is about. Matched on the full instance identity,
   * not just the module coordinate: two replicas of one deployment sharing a worker report the same
   * coordinate, and a coordinate-only match would hand every one of the second replica's reports to
   * the first.
   */
  private static Optional<SupervisedInstance> findByModuleId(
      Map<String, SupervisedInstance> supervised,
      WorkerConnection connection,
      ModuleInstanceId id) {
    return supervised.values().stream()
        .filter(candidate -> candidate.connection == connection)
        .filter(candidate -> candidate.moduleInstanceId.equals(id))
        .findFirst();
  }

  /**
   * The identity the worker will key this instance by, derived here from the assignment the agent
   * already holds. Computed identically on both sides -- there is nothing to send, and nothing that
   * can drift.
   */
  static ModuleInstanceId moduleInstanceIdOf(AssignedInstance assigned) {
    return ModuleInstanceId.of(
        assigned.moduleId(),
        assigned.tenantId().orElse(""),
        assigned.deploymentName(),
        assigned.instanceIndex());
  }

  private static void syncCatalogToWorker(SupervisedInstance instance, ServiceCatalog catalog) {
    WorkerConnection connection = instance.connection;
    List<CatalogDelta> deltas = catalog.allPresentDeltas();
    log.debug("syncing {} known catalog delta(s) to a newly-connected worker", deltas.size());
    for (CatalogDelta delta : deltas) {
      try {
        connection.send(toCatalogUpdate(delta));
      } catch (IOException e) {
        log.warn("failed to sync catalog state to a newly-connected worker: {}", e.getMessage());
        return;
      }
    }
  }

  private static ControlMessage.CatalogUpdate toCatalogUpdate(CatalogDelta delta) {
    return new ControlMessage.CatalogUpdate(
        delta.nodeId(),
        delta.workerId(),
        delta.moduleId(),
        delta.export(),
        delta.version(),
        delta.present(),
        delta.udsPath().orElse(""),
        delta.tcpAddress().getHostString(),
        delta.tcpAddress().getPort());
  }

  private static void registerIntoCatalog(
      SupervisedInstance instance,
      GossipMember gossipMember,
      ServiceCatalog catalog,
      ModuleInstanceId moduleId,
      ServiceExport export,
      boolean present) {
    if (instance.fabricWorkerId == null) {
      log.warn(
          "instance reported a service export before its Hello handshake; dropping catalog update"
              + " for {}",
          moduleId);
      return;
    }
    Optional<String> udsPath =
        instance.fabricUdsPath.isEmpty() ? Optional.empty() : Optional.of(instance.fabricUdsPath);
    if (present) {
      catalog.localRegister(
          gossipMember.self(),
          instance.fabricWorkerId,
          moduleId,
          export,
          udsPath,
          instance.fabricTcpAddress);
    } else {
      catalog.localUnregister(gossipMember.self(), instance.fabricWorkerId, moduleId, export);
    }
  }

  /**
   * {@code releaseVolume} distinguishes a genuinely permanent removal (real scale-down, or the
   * whole spec deleted -- {@code true}, called from the "no longer in {@code currentKeys}" sweep)
   * from a rolling-update teardown-then-immediate-replace at the very same key ({@code false},
   * called from {@code requiresReplacement}'s branch) -- see {@code VolumeManager}'s own javadoc
   * for why the latter must never release: the whole point of sticky placement is that the data at
   * {@code volumeHandles}' host paths survive exactly that case.
   */
  static void stopInstance(
      String key,
      Map<String, SupervisedInstance> supervised,
      CapacityTracker capacityTracker,
      CapacityTracker committedWorkerCapacity,
      Map<String, List<MuninnShipper>> instanceShippers,
      Map<String, WorkerShipperPair> workerShippers,
      VolumeManager volumeManager,
      boolean releaseVolume,
      ServiceCatalog catalog,
      String nodeId) {
    // Not removed from `supervised` until after the graceful-uninstall wait below: the
    // connection's own readLoop finds this instance (to apply an incoming ModuleStateChanged)
    // by searching `supervised`, so evicting it here would make that wait unable to ever observe
    // the UNINSTALLED confirmation it's polling for.
    SupervisedInstance instance = supervised.get(key);
    if (instance == null) {
      stopShippingInstanceLogs(instanceShippers, key);
      return;
    }
    if (releaseVolume) {
      instance.volumeHandles.forEach(volumeManager::release);
    }
    WorkerConnection connection = instance.connection;
    if (connection != null) {
      try {
        // StopModule alone drives ACTIVE -> STOPPING -> UNINSTALLED in one call on the worker
        // side (ModuleController#stop already finishes with its own uninstall) -- no separate
        // UninstallModule follow-up needed or wanted here.
        String stopCorrelationId = nextCorrelationId();
        pendingLifecycleCorrelations.put(stopCorrelationId, key);
        connection.send(
            new ControlMessage.StopModule(stopCorrelationId, instance.moduleInstanceId));
        if (!awaitGracefulUninstall(instance, key) && instance.fabricWorkerId != null) {
          // The worker never confirmed UNINSTALLED within its grace period, so its own
          // ServiceUnregistered notification for this export -- WorkerRuntime#onUninstalled's
          // job, reached only once ModuleController#stop's drain-and-uninstall sequence actually
          // finishes -- may never have been sent before the force-kill below tears its process
          // (and the control channel carrying that notification) down. Without this, the fabric
          // catalog would be left with no path to learn this endpoint is gone beyond its circuit
          // breaker eventually tripping against it -- the same backstop a genuine worker crash
          // already gets via onWorkerCrash's own evictWorker call.
          catalog.evictWorker(nodeId, instance.fabricWorkerId);
        }
      } catch (IOException e) {
        log.warn("failed to send StopModule to instance {}: {}", key, e.getMessage());
      }
    }
    supervised.remove(key);
    stopShippingInstanceLogs(instanceShippers, key);
    // Tier 1 density: a worker hosting more than one instance must survive this one's teardown --
    // killing the process would take every sibling down with it. Only the last instance on a
    // worker (the common case, density or not) actually tears the process down.
    boolean sharedWithAnotherInstance =
        connection != null
            && supervised.values().stream().anyMatch(other -> other.connection == connection);
    if (!sharedWithAnotherInstance) {
      instance.supervisor.close();
      try {
        instance.server.close();
      } catch (IOException e) {
        log.warn("failed to close control channel server for instance {}: {}", key, e.getMessage());
      }
      // The worker-scoped metrics/traces shipper pair survives as long as any instance still
      // shares this worker's connection under Tier 1 density -- only tear it down
      // the same tick the worker process itself is going away. instance.fabricWorkerId is null for
      // a worker that never completed its Hello handshake (e.g. it crashed before connecting), in
      // which case nothing was ever started to stop.
      stopShippingWorkerMetricsAndTraces(workerShippers, instance.fabricWorkerId);
      // Only the worker JVM actually going away frees its real committed memory -- released under
      // workerKey (the key whichever instance's own startInstance call originally reserved it
      // under), not this instance's own key, since the last instance to leave a Tier 1
      // density-packed worker is very often a packed sibling rather than the owner itself (see
      // findReusableTier1Worker's own javadoc on the owner-stopped-while-siblings-remain case).
      // null only for the handful of unit tests that construct a SupervisedInstance with no real
      // worker behind it.
      if (instance.workerKey != null) {
        committedWorkerCapacity.release(instance.workerKey);
      }
    }
    capacityTracker.release(key);
  }

  /**
   * Blocks up to {@link #STOP_GRACE_PERIOD}, polling {@link SupervisedInstance#lifecycleState}
   * every {@link #STOP_GRACE_POLL_INTERVAL}, for the worker to report {@code UNINSTALLED} in
   * response to the {@code StopModule} {@link #stopInstance} just sent it.
   *
   * <p>Without this wait, {@link #stopInstance} used to force-kill the worker process in the same
   * breath it sent {@code StopModule} -- essentially always before {@code ModuleController#stop}'s
   * own drain wait, {@code WorkerRuntime#onUninstalled}, and {@code FabricServiceRegistry#remove}'s
   * resulting {@code ServiceUnregistered} message (sent back over this exact connection, strictly
   * before the {@code ModuleStateChanged("UNINSTALLED")} this method polls for -- see {@code
   * WorkerMain#handleUninstalled}'s own send order) had any real chance to complete. The worker
   * process died mid-shutdown essentially every time, so that deregistration message was never sent
   * or never read -- leaving this instance's exports permanently stuck in this agent's shared
   * {@link ServiceCatalog} pointing at a worker that no longer exists, for every future lookup to
   * keep resolving to and failing against. Every rolling update or scale-down of a Tier 2 module
   * hit this, not just a Job-kind one.
   *
   * <p>Gives up past the deadline and returns {@code false} anyway, logging a warning -- {@link
   * #stopInstance} force-kills the worker either way; the return value only tells the caller
   * whether that kill had a fair chance to lose the race against a graceful shutdown already well
   * underway, so it knows whether it must fall back to {@link ServiceCatalog#evictWorker} itself
   * rather than trusting the worker's own {@code ServiceUnregistered} to have gotten out in time. A
   * worker that's simply gone (crashed, never connected) is handled by {@code connection == null}
   * in the caller and never reaches this method to begin with.
   *
   * @return {@code true} once the worker actually confirmed {@code UNINSTALLED} within the grace
   *     period, {@code false} if the deadline (or an interrupt) was hit first
   */
  private static boolean awaitGracefulUninstall(SupervisedInstance instance, String key) {
    Instant deadline = Instant.now().plus(STOP_GRACE_PERIOD);
    while (!"UNINSTALLED".equals(instance.lifecycleState) && Instant.now().isBefore(deadline)) {
      try {
        Thread.sleep(STOP_GRACE_POLL_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    boolean confirmed = "UNINSTALLED".equals(instance.lifecycleState);
    if (!confirmed) {
      log.warn(
          "instance {} did not confirm graceful uninstall within {}; force-killing its worker"
              + " anyway",
          key,
          STOP_GRACE_PERIOD);
    }
    return confirmed;
  }

  /**
   * Starts shipping this instance's worker's own {@code PLATFORM} log and this instance's own
   * {@code APPLICATION} log to Muninn -- a no-op when {@code muninnEndpoint} is unset. Mirrors
   * {@code AgentLogServer.handleInstanceLogs}'s own path derivation exactly (same {@code
   * workerLogRoot}, same two file names per category) so a shipped line and a live read of the
   * identical {@code /logs/instances/{deploymentName}/{instanceIndex}?category=} request agree,
   * including for a Tier 1-density instance installed into another instance's already-running
   * worker: its own {@code workerLogRoot} won't hold a real {@code worker-platform.log} of its own
   * in that case (the shared worker's platform log lives under the *originating* instance's own key
   * instead), and shipping simply finds nothing there each tick -- the identical "no data for this
   * path" outcome a live read against that same path already produces today.
   */
  static void startShippingInstanceLogs(
      String muninnEndpoint,
      Map<String, List<MuninnShipper>> instanceShippers,
      String key,
      AssignedInstance assigned,
      Path logRoot) {
    if (muninnEndpoint == null) {
      return;
    }
    List<String> muninnEndpoints = MuninnShipper.parseEndpoints(muninnEndpoint);
    Path workerLogRoot = logRoot.resolve("workers").resolve(key);
    String instancePathPrefix =
        "/ingest/logs/instances/" + assigned.deploymentName() + "/" + assigned.instanceIndex();

    MuninnShipper platformShipper =
        new MuninnShipper(muninnEndpoints, instancePathPrefix + "/PLATFORM", MUNINN_SHIP_INTERVAL);
    platformShipper.startShippingLogFile(
        workerLogRoot.resolve("worker-platform.log"), LogFileReader.configuredMaxFiles());

    MuninnShipper applicationShipper =
        new MuninnShipper(
            muninnEndpoints, instancePathPrefix + "/APPLICATION", MUNINN_SHIP_INTERVAL);
    applicationShipper.startShippingLogFile(
        workerLogRoot
            .resolve("instances")
            .resolve(assigned.deploymentName() + "-" + assigned.instanceIndex() + ".log"),
        LogFileReader.configuredMaxFiles());

    instanceShippers.put(key, List.of(platformShipper, applicationShipper));
  }

  static void stopShippingInstanceLogs(
      Map<String, List<MuninnShipper>> instanceShippers, String key) {
    List<MuninnShipper> shippers = instanceShippers.remove(key);
    if (shippers != null) {
      shippers.forEach(MuninnShipper::close);
    }
  }

  /**
   * Establishes this worker JVM's own metrics/traces shipper pair the moment its {@code Hello}
   * arrives -- a no-op when {@code muninnEndpoint} is unset, or when {@code workerId} already has a
   * pair (Tier 1 density: a second/third instance sharing this already-connected worker never sends
   * a second {@code Hello}, so this only ever fires once per worker process). {@code processId}
   * matches {@code MuninnServer}'s own {@code {nodeId}:{workerId}} shape for the new {@code WORKER}
   * processKind -- worker JVMs have no {@code host:port} of their own the way
   * ControlPlane/Fafnir/Mimir/Agent do.
   */
  static void startShippingWorkerMetricsAndTraces(
      String muninnEndpoint,
      Map<String, WorkerShipperPair> workerShippers,
      String nodeId,
      String workerId) {
    if (muninnEndpoint == null || workerShippers.containsKey(workerId)) {
      return;
    }
    String processId = nodeId + ":" + workerId;
    MuninnShipper metricsShipper =
        new MuninnShipper(
            muninnEndpoint, "/ingest/metrics/WORKER/" + processId, MUNINN_SHIP_INTERVAL);
    MuninnShipper tracesShipper =
        new MuninnShipper(
            muninnEndpoint, "/ingest/traces/WORKER/" + processId, MUNINN_SHIP_INTERVAL);
    workerShippers.put(workerId, new WorkerShipperPair(metricsShipper, tracesShipper));
  }

  /** {@code workerId} is {@code null} for an instance whose worker never completed its Hello. */
  static void stopShippingWorkerMetricsAndTraces(
      Map<String, WorkerShipperPair> workerShippers, String workerId) {
    if (workerId == null) {
      return;
    }
    WorkerShipperPair shippers = workerShippers.remove(workerId);
    if (shippers != null) {
      shippers.close();
    }
  }

  /**
   * The metrics/traces shipper pair for one worker JVM -- unlike {@code instanceShippers}' {@code
   * List<MuninnShipper>}, a named pair so {@code readLoop}'s relay cases can route a {@code
   * MetricsSnapshot} vs. a {@code TracesSnapshot} to the right one without a fragile positional
   * index.
   */
  record WorkerShipperPair(MuninnShipper metrics, MuninnShipper traces) implements AutoCloseable {
    @Override
    public void close() {
      metrics.close();
      traces.close();
    }
  }

  /**
   * The {@code workers/<key>} subdirectory holding the log files of the instance a request names.
   * For an instance with its own worker JVM that is the key this agent filed it under; for a Tier 1
   * instance density-packed onto an already-running worker it is the key of whichever instance that
   * worker was spawned for, since no worker was ever spawned under this one's own name.
   *
   * <p>An instance this node doesn't supervise falls back to the key it would have had, which names
   * a directory that does not exist -- an empty log page, which is the honest answer, rather than a
   * path pointing at some other instance's files.
   */
  static String workerDirectoryKey(
      Map<String, SupervisedInstance> supervised,
      Map<String, SupervisedVessel> supervisedVessels,
      Optional<String> tenantId,
      String deploymentName,
      int instanceIndex) {
    SupervisedInstance instance =
        findSupervised(supervised, tenantId, deploymentName, instanceIndex);
    if (instance != null) {
      return instance.workerKey != null ? instance.workerKey : instanceKey(instance.assigned);
    }
    // A vessel is supervised under its own map and runs as its own process rather than inside a
    // worker JVM, so it has no worker key to inherit -- but it files its logs under the same
    // instance key, and a caller naming it only by name must reach them the same way one naming a
    // module instance does.
    SupervisedVessel vessel =
        findSupervisedVessel(supervisedVessels, tenantId, deploymentName, instanceIndex);
    if (vessel != null) {
      return instanceKey(vessel.assigned);
    }
    return instanceKey(tenantId, deploymentName, instanceIndex);
  }

  /** {@link #findSupervised}'s counterpart for the vessels this node supervises. */
  private static SupervisedVessel findSupervisedVessel(
      Map<String, SupervisedVessel> supervisedVessels,
      Optional<String> tenantId,
      String deploymentName,
      int instanceIndex) {
    if (tenantId.isPresent()) {
      return supervisedVessels.get(instanceKey(tenantId, deploymentName, instanceIndex));
    }
    return supervisedVessels.values().stream()
        .filter(
            vessel ->
                vessel.assigned.deploymentName().equals(deploymentName)
                    && vessel.assigned.instanceIndex() == instanceIndex)
        .findFirst()
        .orElse(null);
  }

  /**
   * The instance this node supervises under {@code deploymentName} at {@code instanceIndex}, or
   * {@code null} if it hosts none. A declared {@code tenantId} addresses exactly one supervision
   * key; an absent one means the caller did not say -- which is the ordinary case for an HTTP
   * caller naming a workload the only way it knows it, by name -- and is answered by scanning what
   * this node actually hosts rather than by guessing at a key. The scan is over a single node's
   * live instances, and only a same-name-same-index collision between two tenants on one node can
   * make it ambiguous, which is exactly what declaring the tenant resolves.
   *
   * <p>Not {@code supervised.get(deploymentName + "#" + instanceIndex)}: the supervision key is
   * tenant-scoped and its shape is this class's own business, so a caller composing one by hand
   * silently stops matching the moment that shape changes.
   */
  private static SupervisedInstance findSupervised(
      Map<String, SupervisedInstance> supervised,
      Optional<String> tenantId,
      String deploymentName,
      int instanceIndex) {
    if (tenantId.isPresent()) {
      return supervised.get(instanceKey(tenantId, deploymentName, instanceIndex));
    }
    return supervised.values().stream()
        .filter(
            instance ->
                instance.assigned.deploymentName().equals(deploymentName)
                    && instance.assigned.instanceIndex() == instanceIndex)
        .findFirst()
        .orElse(null);
  }

  static String instanceKey(AssignedInstance assigned) {
    return instanceKey(assigned.tenantId(), assigned.deploymentName(), assigned.instanceIndex());
  }

  /**
   * Tenant-scoped: two different tenants' identically-named workload at the same index must never
   * collapse onto the same {@code supervised}/{@code instanceShippers}/{@code capacityTracker} key.
   * Before {@code tenantId} was part of this key, whichever tenant's assignment this agent
   * processed first "owned" the bare {@code deploymentName#index} slot -- the control plane still
   * believed both were placed here (both showed up in {@code get node-assignments}), but the second
   * one's own {@code containsKey} check below always read true against the first one's already-
   * running {@code SupervisedInstance}, so this agent never started a real worker for it at all,
   * permanently, even after the first tenant's own workload was deleted and the key genuinely freed
   * -- nothing ever re-evaluates a key already believed occupied. Never used as a boundary a
   * rolling update needs to cross: a workload's own tenant cannot change mid-rollout, so the
   * deliberate same-key reuse {@code requiresReplacement}'s own javadoc describes is untouched by
   * this.
   *
   * <p>{@code "#"} joins every segment, not a NUL byte: this same string is also used verbatim as a
   * single {@code workers/<key>} directory name ({@code logRoot.resolve("workers").resolve(key)}),
   * and a NUL byte in a path segment is illegal on every OS this platform runs on -- the worker
   * spawn itself throws before ever reaching the handshake. {@code "#"} is filesystem-safe (the
   * pre-existing {@code deploymentName#index} half of this key already proved that) and unambiguous
   * -- {@code deploymentName}'s own validation regex excludes it, so it can never appear inside
   * either segment and collide with itself.
   */
  static String instanceKey(Optional<String> tenantId, String deploymentName, int instanceIndex) {
    return tenantId.orElse("") + "#" + deploymentName + "#" + instanceIndex;
  }

  /**
   * Turns one fetched assignment into the concrete-local-path form everything downstream (the
   * descriptor read, {@code requiresReplacement}, the worker's own {@code InstallModule}) consumes:
   * an explicit {@code artifactPath} passes through untouched -- the unchanged local-file escape
   * hatch -- while a blank one resolves the module coordinate from Andvari through this node's
   * pull-through cache, a no-network cache hit on every tick after the first. The worker never sees
   * the difference; it always receives a concrete local path.
   */
  private static AssignedInstance resolveArtifactReference(
      HttpClient httpClient,
      List<URI> andvariBaseUrls,
      ArtifactPullCache artifactCache,
      AssignedInstance fetched) {
    if (ArtifactReference.isLocalPath(fetched.artifactPath())) {
      return fetched;
    }
    if (andvariBaseUrls.isEmpty()) {
      throw new GimleManifestException(
          "assignment "
              + fetched.deploymentName()
              + "#"
              + fetched.instanceIndex()
              + " resolves module "
              + fetched.moduleId()
              + " from the artifact registry, but this agent has no"
              + " -Dgimle.agent.andvariEndpoint configured");
    }
    ResolvedArtifact resolved =
        artifactCache.resolve(httpClient, andvariBaseUrls, fetched.moduleId());
    if (resolved.kind() == ArtifactKind.BUNDLE) {
      // Admission already rejects both of these; re-checked here because the registry is written
      // independently of the control plane, so a coordinate's kind can differ from what admission
      // saw by the time this node actually pulls it.
      if (fetched.vessel().isEmpty()) {
        throw new GimleManifestException(
            "artifact "
                + fetched.moduleId()
                + " is a bundle; bundle artifacts are vessel-only and cannot be loaded as a"
                + " module");
      }
      if (!fetched.vessel().get().jvmFlags().isEmpty()) {
        throw new GimleManifestException(
            "vessel jvmFlags cannot apply to bundle artifact "
                + fetched.moduleId()
                + " -- its entrypoint command decides how (and whether) a JVM is launched");
      }
    }
    return new AssignedInstance(
        fetched.deploymentName(),
        fetched.instanceIndex(),
        fetched.moduleId(),
        resolved.path().toString(),
        fetched.tenantId(),
        fetched.renamedFromInstanceIndex(),
        fetched.vessel(),
        fetched.configMapRefs(),
        fetched.secretMapRefs());
  }

  /**
   * True when {@code assigned} (the control plane's current desired state for this key) no longer
   * matches what {@code existing} is actually running -- a {@code moduleId} change is a rolling
   * update; an {@code artifactPath} change with the same {@code moduleId} is the same version
   * republished at a different path. Either way {@code instanceKey} alone (deploymentName#index)
   * can't tell the two assignments apart, so this is the check that decides whether an
   * already-supervised key still needs replacing rather than being left alone.
   */
  static boolean requiresReplacement(AssignedInstance assigned, SupervisedInstance existing) {
    return !existing.assigned.moduleId().equals(assigned.moduleId())
        || !existing.assigned.artifactPath().equals(assigned.artifactPath());
  }

  /**
   * A rename is only honored when the source instance is still present under its old key (a
   * previous poll may have already completed the rename, or the source may genuinely be gone --
   * both fall through to the ordinary start path, always correct, just without the optimization)
   * and already running exactly what {@code assigned} now expects: {@code requiresReplacement}
   * false means moduleId/artifactPath already match, so retargeting it is a pure rename, never a
   * way to silently skip a real upgrade this key still needs.
   */
  static Optional<SupervisedInstance> findRenameSource(
      AssignedInstance assigned, Map<String, SupervisedInstance> supervised) {
    if (assigned.renamedFromInstanceIndex().isEmpty()) {
      return Optional.empty();
    }
    String sourceKey =
        instanceKey(
            assigned.tenantId(),
            assigned.deploymentName(),
            assigned.renamedFromInstanceIndex().getAsInt());
    SupervisedInstance source = supervised.get(sourceKey);
    if (source == null || requiresReplacement(assigned, source)) {
      return Optional.empty();
    }
    return Optional.of(source);
  }

  /**
   * Retargets an already-running, already-healthy instance onto a new key in place: re-keys {@code
   * supervised}/{@code instanceShippers}/{@code capacityTracker} and updates the instance's own
   * {@link SupervisedInstance#assigned}, then tells the worker (if already connected) its {@code
   * InstanceIdentityRegistry} entry changed too -- no {@code stopInstance}/{@code startInstance}
   * anywhere in this path, the entire point being that the live worker process and its module state
   * are never touched. If the worker hasn't connected yet (a genuine race: the rename source only
   * just started), there is nothing to notify -- {@code sendInstallStartSequence}'s own {@code
   * InstallModule} reads {@code instance.assigned} live once the connection completes, so it picks
   * up the already-updated identity on its own, with no separate propagation needed.
   */
  static void renameInPlace(
      String newKey,
      AssignedInstance assigned,
      SupervisedInstance instance,
      Map<String, SupervisedInstance> supervised,
      Map<String, List<MuninnShipper>> instanceShippers,
      CapacityTracker capacityTracker) {
    String oldKey = instanceKey(instance.assigned);
    log.info("instance {} retargeted to {} -- renaming in place, no restart", oldKey, newKey);
    instance.assigned = assigned;
    supervised.remove(oldKey);
    supervised.put(newKey, instance);
    List<MuninnShipper> shippers = instanceShippers.remove(oldKey);
    if (shippers != null) {
      instanceShippers.put(newKey, shippers);
    }
    // The resource footprint doesn't change (requiresReplacement already proved moduleId/
    // artifactPath match), only the bookkeeping key does -- see CapacityTracker#rekey's own
    // javadoc for why a plain release/tryAssign pair here would be wrong.
    capacityTracker.rekey(oldKey, newKey);
    WorkerConnection connection = instance.connection;
    if (connection != null) {
      try {
        connection.send(
            // Addressed by the identity the worker installed it under, which a rename does not
            // change -- what changes is the deployment name and index it reports itself as.
            new ControlMessage.RenameInstance(
                nextCorrelationId(),
                instance.moduleInstanceId,
                assigned.deploymentName(),
                assigned.instanceIndex()));
      } catch (IOException e) {
        // Best-effort, matching syncCatalogToWorker's own posture: the rename already committed
        // in this agent's own bookkeeping above (supervised/instanceShippers, and instance.assigned
        // itself, which is what this agent's own heartbeats report from), so a lost notification
        // here never desyncs the control plane's view. Its only casualty is the worker's own
        // InstanceIdentityRegistry-driven MDC log tagging staying stale at the old index
        // indefinitely -- nothing re-sends RenameInstance on a later tick once this key is already
        // renamed agent-side -- a cosmetic gap in that worker's own log lines, not a functional
        // one.
        log.warn(
            "failed to notify worker of instance {} rename to {}: {}",
            oldKey,
            newKey,
            e.getMessage());
      }
    }
  }

  private static String nextCorrelationId() {
    return "c" + CORRELATION_COUNTER.incrementAndGet();
  }
}
