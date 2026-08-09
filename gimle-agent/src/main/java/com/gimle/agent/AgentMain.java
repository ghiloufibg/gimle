package com.gimle.agent;

import com.gimle.core.exception.GimleIsolationException;
import com.gimle.core.exception.GimleTlsException;
import com.gimle.core.logging.GimleLogging;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
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
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.fabric.cluster.GossipConfig;
import com.gimle.fabric.cluster.GossipMember;
import com.gimle.fabric.cluster.MemberId;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.os.ResourceLimitHandle;
import com.gimle.os.ResourceLimiter;
import com.gimle.os.portable.PortableJvmFlagsResourceLimiter;
import com.gimle.pki.CertificateSigningRequests;
import com.gimle.pki.Pem;
import com.gimle.pki.RenewalSchedule;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
   * Tier 1 density cap: the most Tier-1 instances this agent will pack into one shared worker JVM
   * before preferring a fresh one -- a simple constant to start, not a configurable knob yet. See
   * {@link #findReusableTier1Worker} for the rest of the reuse decision (same node implicitly,
   * since this only ever scans this agent's own {@code supervised} map; same tenant or both
   * untenanted; never two instances of the same module, which would corrupt {@code WorkerRuntime}'s
   * per-{@code ModuleId} keying).
   */
  private static final int MAX_TIER1_DENSITY = 4;

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

  private AgentMain() {}

  public static void main(String[] args) throws IOException, InterruptedException {
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

    System.setProperty("gimle.process.role", "AGENT");
    System.setProperty("gimle.node.id", nodeId);
    Path logRoot = Path.of(System.getProperty("gimle.log.root", "gimle-logs"));
    GimleLogging.attachPlatformFileAppender(logRoot.resolve("agent-platform.log"));

    AgentLogServer logServer = new AgentLogServer(logRoot, 0);
    logServer.start();
    String apiAddress = resolveAdvertisedHost() + ":" + logServer.port();
    log.info("agent {} serving logs at {}", nodeId, apiAddress);

    ResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    CapacityTracker capacityTracker = CapacityTracker.ofThisMachine();
    bootstrapCertificateIfNeeded(nodeId, baseUrl);
    HttpClient httpClient = buildHttpClient();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();

    MemberId self = new MemberId(nodeId, gossipBindAddress);
    GossipMember gossipMember = new GossipMember(self, GossipConfig.defaults());
    ServiceCatalog catalog = new ServiceCatalog();
    gossipMember.attachCatalog(catalog);
    catalog.onDelta(delta -> relayCatalogDelta(delta, supervised));
    gossipMember.start();
    gossipMember.join(seeds);
    log.info("agent {} gossip member listening at {}", nodeId, gossipMember.self().gossipAddress());

    register(httpClient, baseUrl, nodeId, resourceLimiter, apiAddress);
    log.info("agent {} registered with control plane at {}", nodeId, baseUrl);

    while (!Thread.currentThread().isInterrupted()) {
      try {
        reconcileAssignments(
            httpClient,
            baseUrl,
            nodeId,
            supervised,
            javaExecutable,
            commandTail,
            resourceLimiter,
            capacityTracker,
            gossipMember,
            catalog,
            logRoot);
        sendHeartbeat(httpClient, baseUrl, nodeId, supervised, capacityTracker);
        RotationOutcome rotationOutcome = rotateCertificateIfDue(httpClient, baseUrl);
        httpClient = rotationOutcome.httpClient();
        if (rotationOutcome.rotated()) {
          gossipMember.reloadDtlsMaterial();
        }
      } catch (RuntimeException | IOException e) {
        log.error("agent tick failed: {}", e.getMessage(), e);
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
   * Relays a newly-applied catalog delta -- local or gossip-learned -- to every supervised worker's
   * own locally-cached catalog.
   */
  private static void relayCatalogDelta(
      com.gimle.fabric.catalog.CatalogDelta delta, Map<String, SupervisedInstance> supervised) {
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

  // ---- TLS bootstrap (§4) and rotation (§4b) ----

  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";
  private static final String BOOTSTRAP_TOKEN_PROPERTY = "gimle.tls.bootstrapToken";

  private static HttpClient buildHttpClient() {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return HttpClient.newHttpClient();
    }
    return HttpClient.newBuilder()
        .sslContext(SslContexts.forMutualTls(TlsSettings.fromConfig()))
        .build();
  }

  /**
   * On first startup with {@code gimle.transport.protocol=tls} and no local cert/key files present
   * yet, generates a key pair and CSR in-process and submits it (plus the one-time bootstrap token
   * an operator provisioned this agent with) to {@code POST /bootstrap/csr}, per {@code
   * claudedocs/tls-transport-security-design.md} §4. Reachable over server-authenticated-only TLS
   * (the agent already has {@code gimle.tls.caFile}, handed to it out of band -- same as every
   * other {@code gimle.tls.*} property -- so it can verify the control plane's identity before it
   * has one of its own). No-op if the cert/key files already exist (a redeploy of an
   * already-bootstrapped node) or if TLS isn't enabled at all.
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
    HttpClient bootstrapClient = HttpClient.newBuilder().sslContext(trustOnly).build();
    Map<String, Object> body =
        csrSubmissionToJson(
            new CsrSubmission(
                CsrPurpose.NODE_CLIENT, Pem.encodeCsr(csr), Optional.of(bootstrapToken)));
    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/bootstrap/csr"))
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
   * §6's "did rotation actually happen this tick" signal: {@code rotateCertificateIfDue} has three
   * distinct not-rotated exits (plaintext, not due, request failed) plus one success exit, and the
   * caller needs to tell them apart to know whether to also refresh {@code gossipMember}'s own DTLS
   * material -- a raw {@link HttpClient} return gives no such signal.
   */
  private record RotationOutcome(HttpClient httpClient, boolean rotated) {}

  /**
   * Checked once per tick (§4b): if the agent's currently-loaded leaf certificate is due for
   * renewal, submits a same-subject/fresh-key-pair rotation CSR over its *current* (still-valid)
   * mTLS connection, writes the new cert/key, and returns a freshly-built {@link HttpClient} for
   * the caller to use from then on -- unlike {@code ApiServer}, the agent isn't a TLS *server*
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
      X500Name subject = new X500Name(certificate.getSubjectX500Principal().getName());
      PKCS10CertificationRequest csr = CertificateSigningRequests.generate(keyPair, subject);
      Map<String, Object> body =
          csrSubmissionToJson(new CsrSubmission(CsrPurpose.NODE_CLIENT, Pem.encodeCsr(csr)));
      HttpRequest request =
          HttpRequest.newBuilder(baseUrl.resolve("/bootstrap/csr"))
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
      // Key written *before* cert, deliberately: gimle-worker's FabricServerTlsWatcher (§6.2)
      // polls only certFile's mtime to detect a rotation happened, from a separate process with no
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

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("capacity", capacity);
    body.put("instances", instances);

    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/nodes/" + nodeId + "/heartbeat"))
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

  private static List<AssignedInstance> fetchAssignments(
      HttpClient httpClient, URI baseUrl, String nodeId) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/nodes/" + nodeId + "/assignments")).GET().build();
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
      result.add(
          new AssignedInstance(
              (String) map.get("deploymentName"),
              ((Number) map.get("instanceIndex")).intValue(),
              moduleId,
              (String) map.get("artifactPath"),
              tenantId == null ? Optional.empty() : Optional.of((String) tenantId)));
    }
    return result;
  }

  /**
   * Fetches this tenant's entire tenant-scoped config/secret set, already decrypted server-side:
   * {@code GET /config/{tenantId}} returns every {@code ConfigEntry} for that tenant as plaintext,
   * since the control plane alone holds the secrets key file.
   */
  private static List<ConfigValue> fetchConfigForTenant(
      HttpClient httpClient, URI baseUrl, String tenantId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/config/" + tenantId)).GET().build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

  private record ConfigValue(String key, String value, boolean wasEncrypted) {}

  // ---- reconciling the locally-supervised set against the control plane's assignments ----

  private static void reconcileAssignments(
      HttpClient httpClient,
      URI baseUrl,
      String nodeId,
      Map<String, SupervisedInstance> supervised,
      String javaExecutable,
      List<String> commandTail,
      ResourceLimiter resourceLimiter,
      CapacityTracker capacityTracker,
      GossipMember gossipMember,
      ServiceCatalog catalog,
      Path logRoot)
      throws IOException, InterruptedException {
    List<AssignedInstance> assignments = fetchAssignments(httpClient, baseUrl, nodeId);
    Set<String> currentKeys = new LinkedHashSet<>();
    for (AssignedInstance assigned : assignments) {
      String key = instanceKey(assigned);
      currentKeys.add(key);
      if (!supervised.containsKey(key)) {
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
                baseUrl);
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
                logRoot);
          }
        } catch (IOException | RuntimeException e) {
          log.error("failed to start instance {}: {}", key, e.getMessage(), e);
        }
      }
    }
    for (String key : List.copyOf(supervised.keySet())) {
      if (!currentKeys.contains(key)) {
        stopInstance(key, supervised, capacityTracker);
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
      URI baseUrl) {
    SupervisedInstance instance =
        new SupervisedInstance(assigned, existing.supervisor, existing.server, descriptor);
    instance.connection = existing.connection;
    instance.fabricWorkerId = existing.fabricWorkerId;
    instance.fabricUdsPath = existing.fabricUdsPath;
    instance.fabricTcpAddress = existing.fabricTcpAddress;
    supervised.put(key, instance);
    capacityTracker.tryAssign(key, descriptor.resourceRequest());
    try {
      sendInstallStartSequence(instance, instance.connection, httpClient, baseUrl);
    } catch (IOException e) {
      log.error("failed to install {} into shared worker: {}", key, e.getMessage());
      supervised.remove(key);
      capacityTracker.release(key);
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
      Path logRoot)
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
            crash -> onWorkerCrash(crash, key, supervised, httpClient, baseUrl, nodeId));

    SupervisedInstance instance = new SupervisedInstance(assigned, supervisor, server, descriptor);
    supervised.put(key, instance);
    capacityTracker.tryAssign(key, descriptor.resourceRequest());
    supervisor.start();

    Thread.ofVirtual()
        .name("gimle-instance-starter-" + key)
        .start(
            () ->
                driveInstanceUp(
                    instance, key, gossipMember, catalog, httpClient, baseUrl, nodeId, supervised));
  }

  /**
   * Relays a {@link CrashInfo} classification to every {@code SupervisedInstance} the crashed
   * worker hosted -- under Tier 1 density (P1-5) that can be more than one, all sharing the same
   * {@link WorkerProcessSupervisor}, so this can't just look up {@code spawnedWorkerId} alone.
   * Reuses {@link InstanceEventKind#TRANSITION_FAILED} rather than a new kind: adding {@code
   * CRASHED} would break the documented 1:1 mirror with {@code gimle-module}'s own {@code
   * LifecycleEvent} variants for no benefit a {@code causeSummary} doesn't already give a reader.
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
    // P2-3: makes an OOM exit unambiguous (exit code 3, HotSpot's own code for this flag) rather
    // than indistinguishable from any other unexpected exit -- WorkerProcessSupervisor's crash
    // classification depends on this being set on every worker, unconditionally.
    baseCommand.add("-XX:+ExitOnOutOfMemoryError");
    baseCommand.add("-Dgimle.log.root=" + workerLogRoot);
    // P2-17: forwarded unconditionally (defaulting to this agent's own unset-property "false")
    // rather than only when explicitly set, so every worker this agent spawns gets an explicit,
    // consistent value instead of silently inheriting whatever WorkerMain's own default happens
    // to be.
    baseCommand.add(
        "-Dgimle.fabric.defaultDenyCrossTenant="
            + System.getProperty("gimle.fabric.defaultDenyCrossTenant", "false"));
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
      String nodeId,
      Map<String, SupervisedInstance> supervised) {
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
                      supervised));
      sendInstallStartSequence(instance, connection, httpClient, baseUrl);
    } catch (IOException e) {
      log.error("failed to bring up instance {}: {}", key, e.getMessage());
    }
  }

  /**
   * The {@code InstallModule}/{@code ResolveModule}/(config)/{@code StartModule} sequence a fresh
   * worker gets right after connecting ({@link #driveInstanceUp}) and a shared worker gets when a
   * new Tier-1 instance joins it ({@link #installIntoExistingWorker}) -- identical either way, the
   * only difference is whether the connection was just accepted or already open.
   */
  private static void sendInstallStartSequence(
      SupervisedInstance instance, WorkerConnection connection, HttpClient httpClient, URI baseUrl)
      throws IOException {
    connection.send(
        new ControlMessage.InstallModule(
            nextCorrelationId(),
            instance.assigned.artifactPath(),
            instance.assigned.deploymentName(),
            instance.assigned.instanceIndex()));
    connection.send(
        new ControlMessage.ResolveModule(nextCorrelationId(), instance.assigned.moduleId()));
    // Delivered after Resolve (which is when the worker's ModuleContext is created) and before
    // Start, over this same ordered channel, so every module hook's config(key) lookups are
    // already backed by real values from the moment it starts.
    deliverConfig(instance, connection, httpClient, baseUrl);
    connection.send(
        new ControlMessage.StartModule(nextCorrelationId(), instance.assigned.moduleId()));
  }

  private static void deliverConfig(
      SupervisedInstance instance, WorkerConnection connection, HttpClient httpClient, URI baseUrl)
      throws IOException {
    Optional<String> tenantId = instance.assigned.tenantId();
    if (tenantId.isEmpty()) {
      return;
    }
    List<ConfigValue> entries;
    try {
      entries = fetchConfigForTenant(httpClient, baseUrl, tenantId.get());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    } catch (RuntimeException e) {
      log.warn(
          "failed to fetch config for tenant {}: {}; instance will start without it",
          tenantId.get(),
          e.getMessage());
      return;
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
  private static void readLoop(
      SupervisedInstance instance,
      String key,
      GossipMember gossipMember,
      ServiceCatalog catalog,
      HttpClient httpClient,
      URI baseUrl,
      String nodeId,
      Map<String, SupervisedInstance> supervised) {
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
        }
      }
      log.info("instance {} control channel closed", key);
    } catch (IOException e) {
      log.warn("instance {} control channel failed: {}", key, e.getMessage());
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
    List<com.gimle.fabric.catalog.CatalogDelta> deltas = catalog.allPresentDeltas();
    log.debug("syncing {} known catalog delta(s) to a newly-connected worker", deltas.size());
    for (com.gimle.fabric.catalog.CatalogDelta delta : deltas) {
      try {
        connection.send(toCatalogUpdate(delta));
      } catch (IOException e) {
        log.warn("failed to sync catalog state to a newly-connected worker: {}", e.getMessage());
        return;
      }
    }
  }

  private static ControlMessage.CatalogUpdate toCatalogUpdate(
      com.gimle.fabric.catalog.CatalogDelta delta) {
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
      com.gimle.core.module.ServiceExport export,
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

  private static void stopInstance(
      String key, Map<String, SupervisedInstance> supervised, CapacityTracker capacityTracker) {
    SupervisedInstance instance = supervised.remove(key);
    if (instance == null) {
      return;
    }
    WorkerConnection connection = instance.connection;
    if (connection != null) {
      try {
        // StopModule alone drives ACTIVE -> STOPPING -> UNINSTALLED in one call on the worker
        // side (ModuleController#stop already finishes with its own uninstall) -- no separate
        // UninstallModule follow-up needed or wanted here.
        connection.send(
            new ControlMessage.StopModule(nextCorrelationId(), instance.assigned.moduleId()));
      } catch (IOException e) {
        log.warn("failed to send StopModule to instance {}: {}", key, e.getMessage());
      }
    }
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
    }
    capacityTracker.release(key);
  }

  private static String instanceKey(AssignedInstance assigned) {
    return assigned.deploymentName() + "#" + assigned.instanceIndex();
  }

  private static String nextCorrelationId() {
    return "c" + CORRELATION_COUNTER.incrementAndGet();
  }
}
