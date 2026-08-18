package com.gimle.agent;

import com.gimle.agent.bifrost.BifrostProxy;
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
import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.core.module.VolumeRequest;
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
import com.gimle.core.vessel.VesselEnvValue;
import com.gimle.core.vessel.VesselFileMount;
import com.gimle.core.vessel.VesselProbeSpec;
import com.gimle.core.vessel.VesselSpec;
import com.gimle.fabric.catalog.CatalogDelta;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.fabric.cluster.GossipConfig;
import com.gimle.fabric.cluster.GossipMember;
import com.gimle.fabric.cluster.MemberId;
import com.gimle.module.artifact.ArtifactPullCache;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.observability.AgentMetrics;
import com.gimle.observability.MuninnShipper;
import com.gimle.os.ResourceLimitHandle;
import com.gimle.os.ResourceLimiter;
import com.gimle.os.VolumeHandle;
import com.gimle.os.VolumeManager;
import com.gimle.os.localdisk.LocalDiskVolumeManager;
import com.gimle.os.portable.PortableJvmFlagsResourceLimiter;
import com.gimle.pki.CertificateSigningRequests;
import com.gimle.pki.Pem;
import com.gimle.pki.RenewalSchedule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
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
import java.util.regex.Pattern;
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
   * Tier 1 density cap: the most Tier-1 instances this agent will pack into one shared worker JVM
   * before preferring a fresh one -- a simple constant to start, not a configurable knob yet. See
   * {@link #findReusableTier1Worker} for the rest of the reuse decision (same node implicitly,
   * since this only ever scans this agent's own {@code supervised} map; same tenant or both
   * untenanted; never two instances of the same module, which would corrupt {@code WorkerRuntime}'s
   * per-{@code ModuleId} keying).
   */
  private static final int MAX_TIER1_DENSITY = 4;

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
    // Off by default -- an agent that never sets this property behaves exactly as it did before
    // Bifrost existed. Poll interval is its own separate property so an operator can tune
    // convergence latency without touching the control-plane-loop TICK_INTERVAL above, which
    // governs unrelated work.
    boolean bifrostEnabled =
        Boolean.parseBoolean(System.getProperty("gimle.agent.bifrostEnabled", "false"));
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
    AgentLogServer logServer =
        new AgentLogServer(
            logRoot,
            0,
            key -> {
              SupervisedInstance instance = supervised.get(key);
              return instance != null && instance.workerKey != null ? instance.workerKey : key;
            });
    logServer.start();
    String apiAddress = resolveAdvertisedHost() + ":" + logServer.port();
    log.info("agent {} serving logs at {}", nodeId, apiAddress);

    ResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    // StatefulSet-kind persistent storage -- a sibling data root to gimle.log.root above,
    // defaulting alongside it rather than under it, matching the same
    // "own top-level directory, own property" convention gimle.log.root itself established.
    VolumeManager volumeManager =
        new LocalDiskVolumeManager(Path.of(System.getProperty("gimle.data.root", "gimle-data")));
    // Registry-pulled jars land beside the volume roots under the same gimle.data.root -- one
    // node-local data directory, not a second property.
    ArtifactPullCache artifactCache =
        new ArtifactPullCache(
            Path.of(System.getProperty("gimle.data.root", "gimle-data")).resolve("artifact-cache"));
    CapacityTracker capacityTracker = CapacityTracker.ofThisMachine();
    HttpClient httpClient = buildHttpClient();
    // Tracked separately from supervised: a vessel instance has no ControlChannelServer/
    // ModuleDescriptor/worker connection at all, so stretching SupervisedInstance to cover both
    // shapes would leave every module-only field meaningless for a vessel. Both maps are keyed
    // identically (deploymentName#instanceIndex) and both contribute to the same heartbeat.
    Map<String, SupervisedVessel> supervisedVessels = new ConcurrentHashMap<>();
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

    register(httpClient, baseUrl, nodeId, resourceLimiter, apiAddress);
    log.info("agent {} registered with control plane at {}", nodeId, baseUrl);

    // Independent of the tick loop below (its own self-scheduled poller, same shape as the
    // MuninnShipper construction above): a caller on this node dials a service's synthesized
    // loopback ClusterIP, and Bifrost forwards to one of that service's live endpoints.
    if (bifrostEnabled) {
      BifrostProxy bifrostProxy =
          new BifrostProxy(new HttpServiceSource(httpClient, baseUrl), bifrostPollInterval);
      bifrostProxy.start();
      log.info("agent {} started bifrost service proxy", nodeId);
    }

    // Same self-scheduled-poller shape as BifrostProxy just above, relaying down to every
    // supervised worker's own FabricServer instead of binding local listeners -- see
    // NetworkPolicyRelay's own javadoc for why it isn't itself in the bifrost package.
    NetworkPolicyRelay networkPolicyRelay =
        new NetworkPolicyRelay(
            new HttpNetworkPolicySource(httpClient, baseUrl),
            networkPolicyPollInterval,
            supervised);
    networkPolicyRelay.start();
    log.info("agent {} started network policy relay", nodeId);

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
            gossipMember,
            catalog,
            logRoot);
        sendHeartbeat(httpClient, baseUrl, nodeId, supervised, supervisedVessels, capacityTracker);
        RotationOutcome rotationOutcome = rotateCertificateIfDue(httpClient, baseUrl);
        httpClient = rotationOutcome.httpClient();
        if (rotationOutcome.rotated()) {
          gossipMember.reloadDtlsMaterial();
        }
      } catch (RuntimeException | IOException e) {
        tickFailed = true;
        log.error("agent tick failed: {}", e.getMessage(), e);
      } finally {
        agentMetrics.recordTick(Duration.ofNanos(System.nanoTime() - tickStartNanos), tickFailed);
      }
      Thread.sleep(TICK_INTERVAL.toMillis());
    }
  }

  private static InetSocketAddress parseHostPort(String text) {
    int at = text.lastIndexOf(':');
    if (at < 0) {
      throw new IllegalArgumentException("expected host:port, got: " + text);
    }
    return new InetSocketAddress(text.substring(0, at), Integer.parseInt(text.substring(at + 1)));
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
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
            .build();
    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
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
      return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
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
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).sslContext(trustOnly).build();
    Map<String, Object> body =
        csrSubmissionToJson(
            new CsrSubmission(
                CsrPurpose.NODE_CLIENT, Pem.encodeCsr(csr), Optional.of(bootstrapToken)));
    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/bootstrap/csr"))
            .timeout(Duration.ofSeconds(10))
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
   * The "did rotation actually happen this tick" signal: {@code rotateCertificateIfDue} has three
   * distinct not-rotated exits (plaintext, not due, request failed) plus one success exit, and the
   * caller needs to tell them apart to know whether to also refresh {@code gossipMember}'s own DTLS
   * material -- a raw {@link HttpClient} return gives no such signal.
   */
  private record RotationOutcome(HttpClient httpClient, boolean rotated) {}

  /**
   * Checked once per tick: if the agent's currently-loaded leaf certificate is due for renewal,
   * submits a same-subject/fresh-key-pair rotation CSR over its *current* (still-valid) mTLS
   * connection, writes the new cert/key, and returns a freshly-built {@link HttpClient} for the
   * caller to use from then on -- unlike {@code ApiServer}, the agent isn't a TLS *server*
   * anywhere, so "hot-swap" here is just handing back a new outbound client, not the JDK
   * listening-socket rebuild {@code ApiServer#reloadTlsMaterial} needs. Returns {@code current}
   * unchanged (no-op) in plaintext mode, when not yet due, or if the rotation request fails --
   * failures are logged and retried on a later tick, not fatal to this one.
   */
  private static RotationOutcome rotateCertificateIfDue(HttpClient current, URI baseUrl) {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return new RotationOutcome(current, false);
    }
    try {
      TlsSettings settings = TlsSettings.fromConfig();
      X509Certificate certificate =
          Pem.decodeCertificate(Files.readString(settings.certFile(), StandardCharsets.US_ASCII));
      if (!RenewalSchedule.of(certificate).isDue(Instant.now())) {
        return new RotationOutcome(current, false);
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
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> response =
          current.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        log.warn(
            "certificate rotation request rejected with status {}: {}",
            response.statusCode(),
            response.body());
        return new RotationOutcome(current, false);
      }
      CsrResult result = csrResultFromJson(Json.asObject(Json.parse(response.body())));
      // Key written *before* cert, deliberately: gimle-worker's FabricServerTlsWatcher polls
      // only certFile's mtime to detect a rotation happened, from a separate process with no
      // synchronization with this one. Writing the key first guarantees that by the time the
      // watcher ever observes certFile's mtime move, the matching key is already fully on disk --
      // otherwise a poll landing between the two writes could pair a fresh cert with the stale key.
      Files.writeString(
          settings.keyFile(),
          Pem.encodePrivateKey(keyPair.getPrivate()),
          StandardCharsets.US_ASCII);
      Files.writeString(
          settings.certFile(), result.certificatePem().orElseThrow(), StandardCharsets.US_ASCII);
      log.info("agent certificate rotated");
      return new RotationOutcome(buildHttpClient(), true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new RotationOutcome(current, false);
    } catch (IOException | RuntimeException e) {
      log.warn("certificate rotation check failed: {}", e.getMessage());
      return new RotationOutcome(current, false);
    }
  }

  private static Path requiredPathProperty(String property) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      throw GimleTlsException.missingProperty(property);
    }
    return Path.of(value);
  }

  private static Map<String, Object> csrSubmissionToJson(CsrSubmission submission) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("purpose", submission.purpose().name());
    map.put("csrPem", submission.csrPem());
    submission.bootstrapToken().ifPresent(token -> map.put("bootstrapToken", token));
    return map;
  }

  private static CsrResult csrResultFromJson(Map<String, Object> json) {
    CsrRequestStatus status = CsrRequestStatus.valueOf((String) json.get("status"));
    Optional<String> requestId = Optional.ofNullable((String) json.get("requestId"));
    Optional<String> certificatePem = Optional.ofNullable((String) json.get("certificatePem"));
    Optional<String> caCertificatePem = Optional.ofNullable((String) json.get("caCertificatePem"));
    return new CsrResult(status, requestId, certificatePem, caCertificatePem);
  }

  private static KeyPair generateRsaKeyPair() {
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

  private static void sendHeartbeat(
      HttpClient httpClient,
      URI baseUrl,
      String nodeId,
      Map<String, SupervisedInstance> supervised,
      Map<String, SupervisedVessel> supervisedVessels,
      CapacityTracker capacityTracker)
      throws IOException, InterruptedException {
    CapacityTracker.Snapshot snapshot = capacityTracker.snapshot();
    Map<String, Object> capacity = new LinkedHashMap<>();
    capacity.put("totalMemoryBytes", snapshot.totalMemoryBytes());
    capacity.put("assignedMemoryBytes", snapshot.assignedMemoryBytes());
    capacity.put("totalCpuMillicores", snapshot.totalCpuMillicores());
    capacity.put("assignedCpuMillicores", snapshot.assignedCpuMillicores());

    List<Map<String, Object>> instances = new ArrayList<>();
    for (SupervisedInstance instance : supervised.values()) {
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
            .timeout(Duration.ofSeconds(10))
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
              .timeout(Duration.ofSeconds(10))
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

  static Map<String, Object> observationJson(SupervisedInstance instance) {
    String state = instance.lifecycleState;
    // alive is an EXCLUSION check ("not known-crashed"), not an inclusion check ("is one of the
    // states I expect") -- deliberately, so a COMPLETED job already reports alive=true without
    // this line needing to change: a successfully finished Job is not a crash HealthReconciler
    // should reschedule. Rewriting this as an inclusion list (e.g.
    // "ACTIVE".equals(state) || "COMPLETED".equals(state)) would silently break for the next
    // terminal state this file doesn't yet know about -- keep it an exclusion check.
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
    observation.put("cpuMillicoresUsed", instance.cpuMillicoresUsed);
    observation.put("memoryBytesUsed", instance.memoryBytesUsed);
    observation.put("requestRatePerSecond", instance.requestRatePerSecond);
    observation.put("errorRatePerSecond", instance.errorRatePerSecond);
    observation.put("queueDepth", instance.queueDepth);
    return observation;
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
    return observation;
  }

  private static List<AssignedInstance> fetchAssignments(
      HttpClient httpClient, URI baseUrl, String nodeId) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/nodes/" + nodeId + "/assignments"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    List<Object> raw = Json.asArray(Json.parse(response.body()));
    List<AssignedInstance> result = new ArrayList<>();
    for (Object entry : raw) {
      Map<String, Object> map = Json.asObject(entry);
      Map<String, Object> moduleIdMap = Json.asObject(map.get("moduleId"));
      ModuleId moduleId =
          new ModuleId(
              (String) moduleIdMap.get("name"), Version.parse((String) moduleIdMap.get("version")));
      Object tenantId = map.get("tenantId");
      Object renamedFrom = map.get("renamedFromInstanceIndex");
      result.add(
          new AssignedInstance(
              (String) map.get("deploymentName"),
              ((Number) map.get("instanceIndex")).intValue(),
              moduleId,
              (String) map.get("artifactPath"),
              tenantId == null ? Optional.empty() : Optional.of((String) tenantId),
              renamedFrom == null
                  ? OptionalInt.empty()
                  : OptionalInt.of(((Number) renamedFrom).intValue())));
    }
    return result;
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
            .timeout(Duration.ofSeconds(10))
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
            .timeout(Duration.ofSeconds(10))
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
              .timeout(Duration.ofSeconds(10))
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

  private record ConfigValue(String key, String value, boolean wasEncrypted) {}

  // ---- reconciling the locally-supervised set against the control plane's assignments ----

  private static void reconcileAssignments(
      HttpClient httpClient,
      URI baseUrl,
      URI fafnirBaseUrl,
      List<URI> andvariBaseUrls,
      ArtifactPullCache artifactCache,
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
      GossipMember gossipMember,
      ServiceCatalog catalog,
      Path logRoot)
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
            instanceShippers,
            workerShippers,
            volumeManager,
            false);
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
                findReusableTier1Worker(assigned, descriptor, supervised);
            if (reusable.isPresent()) {
              installIntoExistingWorker(
                  assigned,
                  key,
                  descriptor,
                  reusable.get(),
                  supervised,
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
                  capacityTracker,
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
          } catch (IOException | RuntimeException e) {
            log.error("failed to start instance {}: {}", key, e.getMessage(), e);
          }
        }
      }
    }
    for (String key : List.copyOf(supervised.keySet())) {
      if (!currentKeys.contains(key)) {
        // true: genuinely no longer assigned anywhere -- a real scale-down or spec deletion, the
        // one case a volume's data is actually meant to go away. See stopInstance's own
        // releaseVolume javadoc.
        stopInstance(
            key,
            supervised,
            capacityTracker,
            instanceShippers,
            workerShippers,
            volumeManager,
            true);
      }
    }
    for (String key : List.copyOf(supervisedVessels.keySet())) {
      if (!currentKeys.contains(key)) {
        stopVesselInstance(key, supervisedVessels, resourceLimiter, capacityTracker);
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
   * requiresVesselReplacement} mirrors {@link #requiresReplacement}'s exact moduleId/artifactPath
   * comparison.
   */
  private static void reconcileVesselAssignment(
      AssignedInstance assigned,
      String key,
      Map<String, SupervisedVessel> supervisedVessels,
      String javaExecutable,
      ResourceLimiter resourceLimiter,
      CapacityTracker capacityTracker,
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
      stopVesselInstance(key, supervisedVessels, resourceLimiter, capacityTracker);
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
        || !existing.assigned.artifactPath().equals(assigned.artifactPath());
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
    Map<String, String> env =
        resolveVesselEnv(vessel, allocatedPorts, assigned.tenantId(), httpClient, fafnirBaseUrl);
    renderVesselFiles(vessel, vesselRoot, assigned.tenantId(), httpClient, baseUrl);

    List<String> command =
        buildVesselCommand(
            javaExecutable, resourceLimiter, handle, vessel, assigned.artifactPath());
    Path applicationLogFile =
        vesselRoot
            .resolve("instances")
            .resolve(assigned.deploymentName() + "-" + assigned.instanceIndex() + ".log");
    RestartTracker restartTracker =
        new RestartTracker(
            Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30), 5, Duration.ofMinutes(10));

    VesselProcessSupervisor supervisor =
        new VesselProcessSupervisor(
            key,
            command,
            env,
            restartTracker,
            exhaustedKey -> {
              log.error(
                  "vessel {} exhausted its restart budget on this node; giving up locally",
                  exhaustedKey);
              resourceLimiter.release(handle);
              capacityTracker.release(exhaustedKey);
              supervisedVessels.remove(exhaustedKey);
            },
            applicationLogFile,
            respawnedKey -> onVesselRespawned(respawnedKey, supervisedVessels));

    SupervisedVessel instance =
        new SupervisedVessel(assigned, vessel, supervisor, handle, allocatedPorts, Instant.now());
    supervisedVessels.put(key, instance);
    capacityTracker.tryAssign(key, vessel.resourceRequest());
    supervisor.start();
  }

  private static void stopVesselInstance(
      String key,
      Map<String, SupervisedVessel> supervisedVessels,
      ResourceLimiter resourceLimiter,
      CapacityTracker capacityTracker) {
    SupervisedVessel instance = supervisedVessels.remove(key);
    if (instance == null) {
      return;
    }
    instance.supervisor.close();
    resourceLimiter.release(instance.resourceLimitHandle);
    capacityTracker.release(key);
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
    // VesselSpec's own compact constructor already guarantees at least one declared port exists
    // whenever a tcp/http rung is present -- firstDeclaredPortName() is only empty here in a state
    // that construction should have made unreachable.
    Optional<Integer> port =
        instance.vessel.firstDeclaredPortName().map(instance.allocatedPorts::get);
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
   * ({@code GET /config/{tenantId}}), then writes each declared config value's raw content verbatim
   * -- no templating -- to {@code path}, relative to this instance's own per-instance root, or used
   * as-is if already absolute.
   */
  private static void renderVesselFiles(
      VesselSpec vessel,
      Path instanceRoot,
      Optional<String> tenantId,
      HttpClient httpClient,
      URI baseUrl) {
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
    for (VesselFileMount mount : vessel.files()) {
      String value = config.get(mount.configKey());
      if (value == null) {
        throw new GimleManifestException(
            "vessel.files references config key '"
                + mount.configKey()
                + "', which has no value for tenant "
                + tenantId.orElse("(untenanted)"));
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
      } catch (IOException e) {
        throw new UncheckedIOException("failed to render vessel file " + target, e);
      }
    }
  }

  /**
   * Tier 1 density: reuse an already-running worker for {@code assigned} instead of spawning a new
   * JVM, when doing so is safe -- deliberately narrow (agent-local, node-implicit) scope, see
   * {@link #MAX_TIER1_DENSITY}'s own javadoc. Groups {@code supervised} by connection identity
   * (every instance sharing one worker shares one {@link WorkerConnection} reference) rather than
   * scanning candidates independently, since the module-conflict and density checks are properties
   * of the *worker as a whole*, not of any single instance already on it.
   */
  static Optional<SupervisedInstance> findReusableTier1Worker(
      AssignedInstance assigned,
      ModuleDescriptor descriptor,
      Map<String, SupervisedInstance> supervised) {
    if (descriptor.isolationTier() != IsolationTier.TIER_1) {
      return Optional.empty();
    }
    Map<WorkerConnection, List<SupervisedInstance>> byConnection = new LinkedHashMap<>();
    for (SupervisedInstance existing : supervised.values()) {
      if (existing.connection != null) {
        byConnection.computeIfAbsent(existing.connection, c -> new ArrayList<>()).add(existing);
      }
    }
    for (List<SupervisedInstance> group : byConnection.values()) {
      SupervisedInstance representative = group.get(0);
      boolean allTier1 =
          group.stream().allMatch(i -> i.descriptor.isolationTier() == IsolationTier.TIER_1);
      boolean sameTenant = representative.assigned.tenantId().equals(assigned.tenantId());
      // Installing the same ModuleId twice into one worker would corrupt WorkerRuntime's
      // per-ModuleId keying (registry, identityRegistry, per-module schedulers) -- this can happen
      // even with anti-affinity off, since two replicas of one module landing on the same node is
      // already legal today; density must never let them land in the same *worker* too.
      boolean noModuleConflict =
          group.stream().noneMatch(i -> i.assigned.moduleId().equals(assigned.moduleId()));
      boolean underDensityLimit = group.size() < MAX_TIER1_DENSITY;
      if (allTier1 && sameTenant && noModuleConflict && underDensityLimit) {
        return Optional.of(representative);
      }
    }
    return Optional.empty();
  }

  /**
   * Installs {@code assigned} into {@code existing}'s already-running worker over its already-open
   * connection -- no new {@link WorkerProcessSupervisor}, {@link ControlChannelServer}, or {@link
   * ResourceLimitHandle}: the shared worker's {@code -Xmx} stays whatever it was sized for at spawn
   * time (per-worker resource-limit subdivision is out of scope for this reduced form of density).
   * {@code fabricWorkerId}/{@code fabricUdsPath}/{@code fabricTcpAddress} are copied from {@code
   * existing} rather than waiting on a second {@code Hello} that will never arrive -- the worker
   * already sent its one {@code Hello} for this connection before {@code existing} was ever placed.
   */
  private static void installIntoExistingWorker(
      AssignedInstance assigned,
      String key,
      ModuleDescriptor descriptor,
      SupervisedInstance existing,
      Map<String, SupervisedInstance> supervised,
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
            assigned, existing.supervisor, existing.server, descriptor, existing.workerKey);
    instance.connection = existing.connection;
    instance.fabricWorkerId = existing.fabricWorkerId;
    instance.fabricUdsPath = existing.fabricUdsPath;
    instance.fabricTcpAddress = existing.fabricTcpAddress;
    supervised.put(key, instance);
    capacityTracker.tryAssign(key, descriptor.resourceRequest());
    startShippingInstanceLogs(muninnEndpoint, instanceShippers, key, assigned, logRoot);
    try {
      sendInstallStartSequence(
          instance, instance.connection, httpClient, baseUrl, fafnirBaseUrl, volumeManager);
    } catch (IOException e) {
      log.error("failed to install {} into shared worker: {}", key, e.getMessage());
      supervised.remove(key);
      capacityTracker.release(key);
      stopShippingInstanceLogs(instanceShippers, key);
    }
  }

  private static void startInstance(
      AssignedInstance assigned,
      String key,
      ModuleDescriptor descriptor,
      Map<String, SupervisedInstance> supervised,
      String nodeId,
      String javaExecutable,
      List<String> commandTail,
      ResourceLimiter resourceLimiter,
      CapacityTracker capacityTracker,
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
    ResourceLimitHandle handle = prepareResourceLimit(resourceLimiter, key, descriptor);
    Path workerLogRoot = logRoot.resolve("workers").resolve(key);
    List<String> baseCommand =
        buildWorkerCommand(
            javaExecutable, commandTail, resourceLimiter, handle, workerLogRoot, nodeId, assigned);

    RestartTracker restartTracker =
        new RestartTracker(
            Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30), 5, Duration.ofMinutes(10));
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
              supervised.remove(exhaustedKey);
            },
            Optional.of(systemLogFile),
            WorkerProcessSupervisor.DEFAULT_STABLE_UPTIME_THRESHOLD,
            Optional.of(workerLogRoot),
            crash -> onWorkerCrash(crash, key, supervised, httpClient, baseUrl, nodeId),
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
        new SupervisedInstance(assigned, supervisor, server, descriptor, key);
    supervised.put(key, instance);
    try {
      capacityTracker.tryAssign(key, descriptor.resourceRequest());
      startShippingInstanceLogs(muninnEndpoint, instanceShippers, key, assigned, logRoot);
      supervisor.start();
    } catch (IOException | RuntimeException e) {
      // Undo everything registered above so a start failure leaves no trace behind -- mirrors
      // installIntoExistingWorker's own failure cleanup, plus closing the control channel server
      // this method (unlike that one) freshly bound.
      supervised.remove(key);
      capacityTracker.release(key);
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
   */
  private static void onWorkerCrash(
      CrashInfo crash,
      String spawnedWorkerId,
      Map<String, SupervisedInstance> supervised,
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
    for (SupervisedInstance instance : supervised.values()) {
      if (!instance.supervisor.workerId().equals(spawnedWorkerId)) {
        continue;
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
   * <p>{@code instance.volumeHandle} is deliberately left untouched by the reset: {@link
   * #allocateVolumeIfNeeded}, called again as part of {@link #sendInstallStartSequence}, resolves
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
            entry.getValue(), connection, httpClient, baseUrl, fafnirBaseUrl, volumeManager);
      }
    } catch (IOException e) {
      log.error("failed to redrive worker {} after respawn: {}", spawnedWorkerId, e.getMessage());
    }
  }

  /**
   * Rolls one {@code SupervisedInstance} back to the state {@link #startInstance} would have given
   * it before its worker ever connected the first time -- everything a respawned worker's blank
   * slate has invalidated. Never touches {@link SupervisedInstance#volumeHandle} (see {@link
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
  }

  /**
   * The manifest's *limit* is the hard ceiling a worker JVM must be spawned under (-Xmx, {@code
   * ActiveProcessorCount}) -- {@code resourceRequest} is the deliberately different
   * scheduling/capacity-accounting figure {@code capacityTracker.tryAssign} uses. A single-line
   * choke point, extracted so a test can assert directly on which of the descriptor's two {@code
   * ResourceSpec} fields reaches the limiter, rather than only being able to observe the limiter's
   * own output (which is correct either way {@code PortableJvmFlagsResourceLimiterTest} already
   * proves).
   */
  static ResourceLimitHandle prepareResourceLimit(
      ResourceLimiter resourceLimiter, String key, ModuleDescriptor descriptor) {
    return resourceLimiter.prepare(key, descriptor.resourceLimit());
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
  static List<String> buildWorkerCommand(
      String javaExecutable,
      List<String> commandTail,
      ResourceLimiter resourceLimiter,
      ResourceLimitHandle handle,
      Path workerLogRoot,
      String nodeId,
      AssignedInstance assigned) {
    List<String> baseCommand = new ArrayList<>();
    baseCommand.add(javaExecutable);
    baseCommand.add(LEAK_DETECTION_JFR_FLAG);
    // Makes an OOM exit unambiguous (exit code 3, HotSpot's own code for this flag) rather than
    // indistinguishable from any other unexpected exit -- WorkerProcessSupervisor's crash
    // classification depends on this being set on every worker, unconditionally.
    baseCommand.add("-XX:+ExitOnOutOfMemoryError");
    baseCommand.add("-Dgimle.log.root=" + workerLogRoot);
    // Forwarded unconditionally (defaulting to this agent's own unset-property "false") rather
    // than only when explicitly set, so every worker this agent spawns gets an explicit,
    // consistent value instead of silently inheriting whatever WorkerMain's own default happens
    // to be.
    baseCommand.add(
        "-Dgimle.fabric.defaultDenyCrossTenant="
            + System.getProperty("gimle.fabric.defaultDenyCrossTenant", "false"));
    // A worker starts once per module instance, not once per node/replica lifecycle like every
    // other process role -- printing GimleBanner's ASCII-art banner on every one of those spawns
    // would just be log noise at scale, so this agent unconditionally suppresses it for every
    // worker it spawns. WorkerMain still prints when run directly (its own default stays enabled).
    baseCommand.add("-Dgimle.banner.enabled=false");
    // ConsoleLogEncoder defaults to colored text now (see its own javadoc), which is exactly wrong
    // for a piped subprocess: WorkerProcessSupervisor.drainOutput JSON-sniffs this worker's raw
    // stdout to tell a structured line (already captured by its own PlatformFileAppender, so safe
    // to skip) apart from unstructured output worth capturing separately. Forced explicitly here
    // rather than left to a tty-detection guess, so the sniffing stays correct by construction.
    baseCommand.add("-Dgimle.log.console=json");
    baseCommand.add("-XX:ErrorFile=" + workerLogRoot.resolve("hs_err_pid%p.log").toAbsolutePath());
    baseCommand.addAll(resourceLimiter.jvmFlags(handle));
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
          instance, connection, httpClient, baseUrl, fafnirBaseUrl, volumeManager);
    } catch (IOException e) {
      log.error("failed to bring up instance {}: {}", key, e.getMessage());
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
   */
  private static void sendInstallStartSequence(
      SupervisedInstance instance,
      WorkerConnection connection,
      HttpClient httpClient,
      URI baseUrl,
      URI fafnirBaseUrl,
      VolumeManager volumeManager)
      throws IOException {
    connection.send(
        new ControlMessage.InstallModule(
            nextCorrelationId(),
            instance.assigned.artifactPath(),
            instance.assigned.deploymentName(),
            instance.assigned.instanceIndex()));
    instance.volumeHandle = allocateVolumeIfNeeded(volumeManager, instance);
    String dataDirectory =
        instance.volumeHandle.map(volumeManager::hostPath).map(Path::toString).orElse("");
    connection.send(
        new ControlMessage.ResolveModule(
            nextCorrelationId(), instance.assigned.moduleId(), dataDirectory));
    // Delivered after Resolve (which is when the worker's ModuleContext is created) and before
    // Start, over this same ordered channel, so every module hook's config(key) lookups are
    // already backed by real values from the moment it starts.
    deliverConfig(instance, connection, httpClient, baseUrl, fafnirBaseUrl);
    connection.send(
        new ControlMessage.StartModule(nextCorrelationId(), instance.assigned.moduleId()));
  }

  /**
   * {@code Optional.empty()} for every module that doesn't declare {@code volume:} (the common
   * case) -- {@code volumeManager.allocate} is only ever called for a {@code StatefulSet}-shaped
   * instance's own descriptor. A failed allocation (insufficient disk space, an I/O error) is
   * logged and treated as "no volume" rather than blocking the instance from starting at all --
   * matches {@code prepareResourceLimit}'s own sibling failure posture for CPU/memory, and leaves
   * {@code ModuleContext.dataDirectory()} empty for a hook to detect and react to on its own terms.
   */
  private static Optional<VolumeHandle> allocateVolumeIfNeeded(
      VolumeManager volumeManager, SupervisedInstance instance) {
    Optional<VolumeRequest> request = instance.descriptor.volume();
    if (request.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          volumeManager.allocate(
              instance.assigned.deploymentName(),
              instance.assigned.instanceIndex(),
              request.get()));
    } catch (RuntimeException e) {
      log.error(
          "failed to allocate volume for {}#{}: {}",
          instance.assigned.deploymentName(),
          instance.assigned.instanceIndex(),
          e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Plain config still comes from {@code ApiServer}'s own {@code /config/{tenantId}}, decrypted
   * server-side exactly as before -- unaffected by this split, since only where decryption happens
   * changed, not this call. Secret values, by contrast, are fetched directly from Fafnir,
   * authorized by this agent's own node identity certificate rather than relayed through the
   * control plane, so a compromised or buggy control-plane replica is never in a position to see a
   * decrypted secret value pass through it. {@code fafnirBaseUrl} is {@code null} when {@code
   * -Dgimle.agent.fafnirEndpoint} was never configured -- instances still start, simply without any
   * secret values delivered, exactly like a tenant that never uses secrets.
   */
  private static void deliverConfig(
      SupervisedInstance instance,
      WorkerConnection connection,
      HttpClient httpClient,
      URI baseUrl,
      URI fafnirBaseUrl)
      throws IOException {
    Optional<String> tenantId = instance.assigned.tenantId();
    if (tenantId.isEmpty()) {
      return;
    }
    List<ConfigValue> entries = new ArrayList<>();
    try {
      entries.addAll(fetchConfigForTenant(httpClient, baseUrl, tenantId.get()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    } catch (RuntimeException e) {
      // No early return: secret delivery below is deliberately independent of the control
      // plane's own /config surface (see this method's javadoc), so a denied or failed plain
      // config fetch must never take the tenant's secrets down with it. Under mTLS a node
      // principal is not authorized for /config at all, making this the normal path, not an
      // edge case.
      log.warn(
          "failed to fetch plain config for tenant {}: {}; continuing to secret delivery",
          tenantId.get(),
          e.getMessage());
    }
    if (fafnirBaseUrl != null) {
      try {
        entries.addAll(fetchSecretsForTenant(httpClient, fafnirBaseUrl, tenantId.get()));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        log.warn(
            "failed to fetch secrets for tenant {}: {}; instance will start without them",
            tenantId.get(),
            e.getMessage());
      }
    }
    for (ConfigValue entry : entries) {
      connection.send(
          new ControlMessage.ConfigDelivered(entry.key(), entry.value(), entry.wasEncrypted()));
    }
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
              .ifPresent(target -> target.lifecycleState = changed.state());
        } else if (message instanceof ControlMessage.Nack nack) {
          log.warn("instance {} nacked {}: {}", key, nack.correlationId(), nack.reason());
          // An install-phase nack (the module never left its initial INSTALLED state -- e.g. the
          // worker couldn't read the jar) used to leave lifecycleState at "INSTALLED" forever:
          // the instance looked merely still-starting, indefinitely, in the console and
          // `gimle deployment status`. FAILED makes the failure visible and, via observationJson's
          // own alive derivation, hands it to the health reconciler to heal. A nack after a
          // successful start keeps the last real lifecycle state rather than clobbering it.
          if ("INSTALLED".equals(instance.lifecycleState)) {
            instance.lifecycleState = "FAILED";
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
                  });
        } else if (message instanceof ControlMessage.RelayControlPlaneRead relayRead) {
          handleRelayRead(relayRead, connection, httpClient, baseUrl);
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
  private static void handleRelayRead(
      ControlMessage.RelayControlPlaneRead request,
      WorkerConnection connection,
      HttpClient httpClient,
      URI baseUrl) {
    if (!RELAY_WHITELIST_PATTERN.matcher(request.path()).matches()) {
      log.warn("rejecting non-whitelisted control-plane relay path: {}", request.path());
      sendRelayResult(
          connection,
          request.correlationId(),
          403,
          "path not whitelisted for control-plane relay: " + request.path());
      return;
    }
    try {
      HttpRequest httpRequest =
          HttpRequest.newBuilder(baseUrl.resolve(request.path()))
              .timeout(Duration.ofSeconds(10))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

  private static void sendRelayResult(
      WorkerConnection connection, String correlationId, int status, String body) {
    try {
      connection.send(new ControlMessage.RelayControlPlaneResult(correlationId, status, body));
    } catch (IOException e) {
      log.warn("failed to send control-plane relay result back to worker: {}", e.getMessage());
    }
  }

  /** Every {@code SupervisedInstance} sharing {@code connection} whose module is {@code id}. */
  private static Optional<SupervisedInstance> findByModuleId(
      Map<String, SupervisedInstance> supervised, WorkerConnection connection, ModuleId id) {
    return supervised.values().stream()
        .filter(candidate -> candidate.connection == connection)
        .filter(candidate -> candidate.assigned.moduleId().equals(id))
        .findFirst();
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
      ModuleId moduleId,
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
   * {@code volumeHandle}'s host path survives exactly that case.
   */
  private static void stopInstance(
      String key,
      Map<String, SupervisedInstance> supervised,
      CapacityTracker capacityTracker,
      Map<String, List<MuninnShipper>> instanceShippers,
      Map<String, WorkerShipperPair> workerShippers,
      VolumeManager volumeManager,
      boolean releaseVolume) {
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
      instance.volumeHandle.ifPresent(volumeManager::release);
    }
    WorkerConnection connection = instance.connection;
    if (connection != null) {
      try {
        // StopModule alone drives ACTIVE -> STOPPING -> UNINSTALLED in one call on the worker
        // side (ModuleController#stop already finishes with its own uninstall) -- no separate
        // UninstallModule follow-up needed or wanted here.
        connection.send(
            new ControlMessage.StopModule(nextCorrelationId(), instance.assigned.moduleId()));
        awaitGracefulUninstall(instance, key);
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
   * WorkerMain#handleLifecycleEvent}'s own send order) had any real chance to complete. The worker
   * process died mid-shutdown essentially every time, so that deregistration message was never sent
   * or never read -- leaving this instance's exports permanently stuck in this agent's shared
   * {@link ServiceCatalog} pointing at a worker that no longer exists, for every future lookup to
   * keep resolving to and failing against. Every rolling update or scale-down of a Tier 2 module
   * hit this, not just a Job-kind one.
   *
   * <p>Gives up past the deadline and returns anyway, logging a warning -- {@link #stopInstance}
   * force-kills the worker either way; this only decides whether that kill has a fair chance to
   * lose the race against a graceful shutdown already well underway, not whether one happens at
   * all. A worker that's simply gone (crashed, never connected) is handled by {@code connection ==
   * null} in the caller and never reaches this method to begin with.
   */
  private static void awaitGracefulUninstall(SupervisedInstance instance, String key) {
    Instant deadline = Instant.now().plus(STOP_GRACE_PERIOD);
    while (!"UNINSTALLED".equals(instance.lifecycleState) && Instant.now().isBefore(deadline)) {
      try {
        Thread.sleep(STOP_GRACE_POLL_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    if (!"UNINSTALLED".equals(instance.lifecycleState)) {
      log.warn(
          "instance {} did not confirm graceful uninstall within {}; force-killing its worker"
              + " anyway",
          key,
          STOP_GRACE_PERIOD);
    }
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

  private static String instanceKey(AssignedInstance assigned) {
    return instanceKey(assigned.deploymentName(), assigned.instanceIndex());
  }

  private static String instanceKey(String deploymentName, int instanceIndex) {
    return deploymentName + "#" + instanceIndex;
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
    Path jar = artifactCache.resolve(httpClient, andvariBaseUrls, fetched.moduleId());
    return new AssignedInstance(
        fetched.deploymentName(),
        fetched.instanceIndex(),
        fetched.moduleId(),
        jar.toString(),
        fetched.tenantId(),
        fetched.renamedFromInstanceIndex(),
        fetched.vessel());
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
        instanceKey(assigned.deploymentName(), assigned.renamedFromInstanceIndex().getAsInt());
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
            new ControlMessage.RenameInstance(
                nextCorrelationId(),
                assigned.moduleId(),
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
