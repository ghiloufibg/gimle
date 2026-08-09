package com.gimle.controlplane.api;

import com.gimle.controlplane.authz.Authorizer;
import com.gimle.controlplane.authz.BootstrapAccountFile;
import com.gimle.controlplane.pki.BootstrapTokenRegistry;
import com.gimle.controlplane.pki.CaKeyMaterial;
import com.gimle.controlplane.pki.PendingCsrStore;
import com.gimle.controlplane.secret.KeyFileManager;
import com.gimle.controlplane.secret.SecretCipher;
import com.gimle.controlplane.secret.SessionTokens;
import com.gimle.controlplane.tenant.TenantUsage;
import com.gimle.core.authz.Account;
import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.authz.PasswordHashes;
import com.gimle.core.authz.Permission;
import com.gimle.core.authz.Principal;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.exception.GimleRaftException;
import com.gimle.core.logging.LogFileReader;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.CsrPurpose;
import com.gimle.core.protocol.CsrResult;
import com.gimle.core.protocol.CsrSubmission;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.Json;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.mimir.manifest.DeploymentManifestParser;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.OwnCertificateRotator;
import com.gimle.pki.Pem;
import com.gimle.pki.Subjects;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import javax.crypto.SecretKey;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The control plane's HTTP surface: {@code com.sun.net.httpserver.HttpServer}, JDK-bundled, no
 * framework dependency -- matches the project's explicit non-goal of pulling in
 * Spring/Netty/Quarkus for something this small. Deployment manifests travel as YAML bodies
 * (matching {@code gimle-module.yaml}'s own convention); node registration/heartbeat/assignment
 * traffic travels as hand-rolled JSON (see {@link Json}) -- different audiences, same reasoning
 * {@code ControlMessage}'s text codec used to justify differing from {@code gimle-fabric}'s
 * eventual binary codec.
 */
public final class ApiServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ApiServer.class);

  private static final String SESSION_COOKIE_NAME = "gimle_session";
  private static final Duration SESSION_TTL = Duration.ofHours(12);

  private final StoreClient storeClient;
  private final SecretKey secretKey;
  // Signs/verifies console session cookies -- deliberately a second key, not a reuse of
  // secretKey's AES material, for key separation between two unrelated crypto purposes (see
  // SessionTokens' own javadoc).
  private final SecretKey sessionSigningKey;
  private final Authorizer authorizer;
  // HTTP/1.1 explicitly: agents speak plain HttpServer-based HTTP/1.1, never HTTP/2, and pinning
  // avoids HttpClient spending a round trip on an upgrade negotiation that could never succeed.
  private final HttpClient agentHttpClient =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  private final BootstrapTokenRegistry bootstrapTokenRegistry = new BootstrapTokenRegistry();
  private final PendingCsrStore pendingCsrStore = new PendingCsrStore();
  // Absent in plaintext mode, or in TLS mode when gimle.pki.caKeyFile isn't configured on this
  // node -- either way, /bootstrap/csr and its siblings simply aren't registered (see
  // #registerContexts). This node's CA key never rotates (only leaf certs do), so unlike
  // sslContextHolder-adjacent state this is loaded once and never reloaded.
  private final Optional<CertificateAuthority> certificateAuthority =
      loadCertificateAuthorityIfConfigured();

  // Not final: §4b rotation of this node's own leaf certificate needs to stop and rebuild the
  // whole HttpsServer (see #reloadTlsMaterial) -- confirmed against the real JDK implementation
  // that HttpsConfigurator#getSSLContext() is read exactly once, when setHttpsConfigurator() is
  // called, and cached from then on by ServerImpl; there is no supported way to swap key/trust
  // material into an already-running HttpsServer.
  private volatile HttpServer server;
  // The actually-bound port, resolved once from whatever the constructor was given (which may be
  // 0, an ephemeral port, in tests) -- every rebuild must rebind to this same real port, never to
  // the original constructor argument again.
  private final int boundPort;
  private volatile Optional<Path> consoleStaticRoot = Optional.empty();

  /**
   * Ephemeral in-memory key, never persisted -- fine for tests and any caller that doesn't need
   * secrets to survive a restart, but not real deployments (see the two-argument constructor).
   */
  public ApiServer(StoreClient storeClient, int port) throws IOException {
    this(storeClient, port, ephemeralKeyPath());
  }

  /**
   * {@code secretKeyFilePath} is the control plane's persistent AES-256 secrets master key,
   * generated on first run if absent. {@code storeClient} is this replica's already-constructed
   * client against the store cluster (etcd-store-extraction design doc) -- unlike the pre-split
   * {@code RaftNode}-based constructors this replaces, there is no "auto-build a trivial single-
   * node store" convenience here: standing up even a single {@code StoreNode} requires a real
   * listener, which is the caller's job (production: {@code ControlPlaneMain}; tests: a small
   * in-process fixture spinning up exactly one).
   */
  public ApiServer(StoreClient storeClient, int port, Path secretKeyFilePath) throws IOException {
    this(
        storeClient,
        port,
        KeyFileManager.loadOrCreate(secretKeyFilePath),
        KeyFileManager.loadOrCreate(secretKeyFilePath.resolveSibling("session.key")));
  }

  private ApiServer(
      StoreClient storeClient, int port, SecretKey secretKey, SecretKey sessionSigningKey)
      throws IOException {
    this.storeClient = storeClient;
    this.secretKey = secretKey;
    this.sessionSigningKey = sessionSigningKey;
    this.authorizer = new Authorizer(storeClient);
    this.server = createHttpServer(port);
    this.boundPort = server.getAddress().getPort();
    registerContexts(server);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
  }

  private void registerContexts(HttpServer target) throws IOException {
    target.createContext("/deployments/", this::handleDeployment);
    target.createContext("/deployments", this::handleDeploymentsList);
    target.createContext("/metrics", this::handleMetrics);
    target.createContext("/events", this::handleEvents);
    target.createContext("/nodes/", this::handleNode);
    target.createContext("/nodes", this::handleNodesList);
    target.createContext("/tenants/", this::handleTenant);
    target.createContext("/tenants", this::handleTenantsList);
    target.createContext("/config/", this::handleConfig);
    target.createContext("/logs/", this::handleLogs);
    target.createContext("/roles/", this::handleRole);
    target.createContext("/roles", this::handleRolesList);
    target.createContext("/rolebindings/", this::handleRoleBinding);
    target.createContext("/rolebindings", this::handleRoleBindingsList);
    target.createContext("/accounts/", this::handleAccount);
    target.createContext("/accounts", this::handleAccountsList);
    target.createContext("/auth/login", this::handleAuthLogin);
    target.createContext("/auth/logout", this::handleAuthLogout);
    target.createContext("/auth/session", this::handleAuthSession);
    if (certificateAuthority.isPresent()) {
      target.createContext("/bootstrap/csr", this::handleBootstrapCsrSubmit);
      target.createContext("/bootstrap/csr/", this::handleBootstrapCsrSubResource);
      target.createContext("/bootstrap/tokens", this::handleBootstrapTokens);
    }
    if (consoleStaticRoot.isPresent()) {
      registerConsole(target, consoleStaticRoot.get());
    }
  }

  /**
   * Registers a static-file context at {@code /console} serving the built SPA under {@code
   * staticRoot}, with client-side-route fallback to whichever shell file the SPA's tooling produced
   * -- {@code _shell.html} if present (TanStack Start's SPA mode), else the conventional {@code
   * index.html}. Opt-in: no constructor calls this, so every existing caller/test is unaffected
   * until something explicitly wires a console directory in. Remembered on {@link
   * #consoleStaticRoot} so a later {@link #reloadTlsMaterial} rebuild re-registers it too.
   */
  public void serveConsole(Path staticRoot) throws IOException {
    consoleStaticRoot = Optional.of(staticRoot);
    registerConsole(server, staticRoot);
  }

  private static void registerConsole(HttpServer target, Path staticRoot) throws IOException {
    String shellFileName =
        Files.isRegularFile(staticRoot.resolve("_shell.html")) ? "_shell.html" : "index.html";
    target.createContext("/console", new ConsoleStaticHandler(staticRoot, shellFileName));
  }

  /** A fresh temp path per JVM run -- the ephemeral constructor never intends key reuse anyway. */
  private static Path ephemeralKeyPath() throws IOException {
    Path dir = Files.createTempDirectory("gimle-apiserver-ephemeral-key-");
    return dir.resolve("secret.key");
  }

  private static Optional<CertificateAuthority> loadCertificateAuthorityIfConfigured() {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return Optional.empty();
    }
    return CaKeyMaterial.loadIfConfigured(TlsSettings.fromConfig().caFile());
  }

  /**
   * {@link TransportProtocol#PLAINTEXT} (the default) is untouched: a plain {@link HttpServer},
   * exactly what every existing caller/test already gets. {@link TransportProtocol#TLS} swaps in
   * {@link HttpsServer} instead -- the JDK-bundled, direct drop-in the design doc calls out as the
   * smallest, lowest-risk change in the whole TLS rollout (see {@code
   * claudedocs/tls-transport-security-design.md} §2). {@code wantClientAuth}, not {@code
   * needClientAuth}: {@link HttpsConfigurator}/{@link HttpsParameters} negotiate once per
   * *connection*, before the HTTP request path is ever read, so there's no way to make client-auth
   * conditional on path at this layer -- every handler enforces it itself instead, via {@link
   * #requireAuthorized}, except the deliberately bootstrap-token-authenticated {@code
   * /bootstrap/csr} endpoints.
   */
  private static HttpServer createHttpServer(int port) throws IOException {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return HttpServer.create(new InetSocketAddress(port), 0);
    }
    SSLContext sslContext = SslContexts.forMutualTls(TlsSettings.fromConfig());
    HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
    httpsServer.setHttpsConfigurator(
        new HttpsConfigurator(sslContext) {
          @Override
          public void configure(HttpsParameters params) {
            // Order matters: setSSLParameters(...) copies its argument's own wantClientAuth value
            // onto params, so setting it here -- not via params.setWantClientAuth(...) separately,
            // and not before this call -- is the only ordering that actually sticks.
            SSLParameters sslParameters = getSSLContext().getDefaultSSLParameters();
            sslParameters.setWantClientAuth(true);
            params.setSSLParameters(sslParameters);
          }
        });
    return httpsServer;
  }

  public void start() {
    server.start();
  }

  public int port() {
    return boundPort;
  }

  @Override
  public void close() {
    server.stop(0);
  }

  /**
   * §4b rotation's hot-swap point for this node's own leaf certificate: there is no supported way
   * to swap key material into an already-running {@link HttpsServer} (see the field javadoc on
   * {@link #server}), so this stops the current one and rebuilds a fresh {@link HttpsServer} bound
   * to the same {@link #boundPort} from whatever certificate material now sits at {@code
   * gimle.tls.certFile}/{@code keyFile} (already overwritten by the caller before this runs), with
   * every context -- including {@code /console} if {@link #serveConsole} was ever called --
   * re-registered. New connection attempts during the brief stop-to-restart window fail and should
   * be retried by the caller; already-established connections are unaffected (normal TLS behavior,
   * not specific to this rebuild).
   */
  public synchronized void reloadTlsMaterial() throws IOException {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return;
    }
    HttpServer previous = server;
    previous.stop(0);
    HttpServer rebuilt = createHttpServer(boundPort);
    registerContexts(rebuilt);
    rebuilt.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    rebuilt.start();
    server = rebuilt;
    log.info("reloaded TLS material and rebuilt HttpsServer on port {}", boundPort);
  }

  /**
   * Checked periodically by {@code ControlPlaneMain}'s reconcile ticker (unconditionally, not
   * leader-gated -- a follower needs its own cert fresh too), per §4b. Delegates the actual
   * check-and-rotate-over-mTLS logic to {@link OwnCertificateRotator}, shared with {@code
   * StoreMain} once the etcd-store-extraction split needed the identical mechanism a second caller
   * -- this method's own job is just knowing *where* to submit the rotation CSR (its own loopback
   * {@code /bootstrap/csr}, since this process is the CA-signing authority itself) and reloading
   * its own {@code HttpsServer} afterward. No-op in plaintext mode. Returns {@code true} iff a
   * rotation actually happened this call -- §6's own listener-owning components ({@code
   * RaftTransport}, {@code GossipMember}) key their own reload off this same on-disk material, so
   * the caller needs to know whether to refresh them too, not just whether the check ran.
   */
  public boolean checkAndRotateOwnCertificateIfDue() {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return false;
    }
    TlsSettings settings = TlsSettings.fromConfig();
    URI ownCsrEndpoint = URI.create("https://127.0.0.1:" + boundPort + "/bootstrap/csr");
    boolean rotated = OwnCertificateRotator.checkAndRotateIfDue(settings, ownCsrEndpoint);
    if (rotated) {
      try {
        reloadTlsMaterial();
      } catch (IOException e) {
        log.warn("failed to reload TLS material after rotation: {}", e.getMessage(), e);
      }
    }
    return rotated;
  }

  /**
   * Checked every reconcile tick by {@code ControlPlaneMain}, gated by the reconciler-leader lease
   * there exactly like every other reconciler -- seeds the one bootstrap {@link Account} {@code
   * gimle-pki}'s {@code PkiBootstrapMain} wrote to disk, exactly once: a no-op the instant {@code
   * storeClient.listAccounts()} is non-empty, so this stays safe to call on every future tick
   * forever after. This method itself no longer checks leadership -- {@code storeClient.propose}
   * already follows the store's current leader internally (etcd-store-extraction design doc
   * §4.4/§4.6), and *which* {@code ApiServer} replica calls this at all is the caller's lease-based
   * election to decide, not a concern of the method being called.
   */
  public void seedBootstrapAccountIfNeeded() {
    if (!storeClient.listAccounts().isEmpty()) {
      return;
    }
    BootstrapAccountFile.loadIfConfigured()
        .ifPresent(account -> storeClient.propose(new StateMutation.PutAccount(account)));
  }

  // ---- /deployments/{name} ----

  private void handleDeployment(HttpExchange exchange) {
    try {
      String name = pathSegmentAfter(exchange, "/deployments/");
      if (name.isBlank()) {
        respond(exchange, 400, "missing deployment name");
        return;
      }
      // Unscoped (Optional.empty() tenant) for every verb here -- tenant-scoped deployment
      // permissions would need this handler to resolve an existing deployment's own tenantId (or
      // a PUT's requested one) before authorizing, real additional plumbing not built this round;
      // a known, deliberate gap, not a silent omission (claudedocs/authn-authz-design.md
      // refinement #3).
      switch (exchange.getRequestMethod()) {
        case "PUT" -> {
          if (requireAuthorized(exchange, ResourceKind.DEPLOYMENT, Verb.WRITE, Optional.empty())) {
            handlePutDeployment(exchange, name);
          }
        }
        case "GET" -> {
          if (requireAuthorized(exchange, ResourceKind.DEPLOYMENT, Verb.READ, Optional.empty())) {
            handleGetDeployment(exchange, name);
          }
        }
        case "DELETE" -> {
          if (requireAuthorized(exchange, ResourceKind.DEPLOYMENT, Verb.DELETE, Optional.empty())) {
            handleDeleteDeployment(exchange, name);
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (GimleManifestException | IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("deployment request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handlePutDeployment(HttpExchange exchange, String name) throws IOException {
    DeploymentSpec parsedSpec = DeploymentManifestParser.parse(exchange.getRequestBody());
    if (!parsedSpec.name().equals(name)) {
      respond(
          exchange,
          400,
          "manifest name '" + parsedSpec.name() + "' does not match URL path '" + name + "'");
      return;
    }
    // P2-18: computed here, once, regardless of tenancy -- never trusted from the submitted
    // manifest itself (DeploymentManifestParser only parses artifactSha256 back out of StateStore's
    // own previously-written YAML on reload, never treats a caller-supplied value as
    // authoritative).
    // Optional.empty() if the artifact is unreadable at admission time, the same tolerant posture
    // untenanted deployments already had before this field existed -- DeploymentReconciler still
    // catches an unreadable artifact every tick regardless.
    Optional<ModuleArtifact> artifact = readArtifactIfPossible(parsedSpec.artifactPath());
    DeploymentSpec spec = withArtifactSha256(parsedSpec, artifact.map(ModuleArtifact::sha256));
    Optional<String> quotaRejection = checkTenantQuota(spec, artifact);
    if (quotaRejection.isPresent()) {
      respond(exchange, 409, quotaRejection.get());
      return;
    }
    storeClient.propose(new StateMutation.PutDeployment(spec));
    respond(exchange, 200, "ok");
  }

  private static Optional<ModuleArtifact> readArtifactIfPossible(String artifactPath) {
    try {
      return Optional.of(ModuleArtifactReader.read(Path.of(artifactPath)));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static DeploymentSpec withArtifactSha256(DeploymentSpec spec, Optional<String> sha256) {
    return new DeploymentSpec(
        spec.name(),
        spec.moduleId(),
        spec.artifactPath(),
        spec.replicas(),
        spec.placement(),
        spec.autoscale(),
        spec.tenantId(),
        sha256);
  }

  /**
   * Admission-time quota check: absent if the deployment is untenanted (no check to run) or would
   * keep the tenant within quota; present with a rejection reason otherwise. {@code artifact} is
   * already read by the caller (once, for every deployment regardless of tenancy, to compute {@link
   * DeploymentSpec#artifactSha256}) -- reused here rather than reading the same jar a second time.
   * An unreadable artifact rejects the submission outright for a *tenanted* deployment specifically
   * (unlike {@code DeploymentReconciler}, which just retries next tick with nothing yet at stake),
   * since admission can't safely let a submission through it has no way to verify against the
   * tenant's quota.
   */
  private Optional<String> checkTenantQuota(
      DeploymentSpec spec, Optional<ModuleArtifact> artifact) {
    if (spec.tenantId().isEmpty()) {
      return Optional.empty();
    }
    String tenantId = spec.tenantId().get();
    Optional<Tenant> tenant = storeClient.getTenant(tenantId);
    if (tenant.isEmpty()) {
      return Optional.of("unknown tenantId: " + tenantId);
    }
    if (artifact.isEmpty()) {
      return Optional.of(
          "cannot verify tenant quota: artifact unreadable at " + spec.artifactPath());
    }
    ModuleDescriptor descriptor = artifact.get().descriptor();
    TenantUsage.Usage existing = TenantUsage.currentlyAssigned(storeClient, tenantId, spec.name());
    TenantUsage.Usage withThisSubmission =
        existing.plus(
            descriptor.resourceRequest().memoryBytes() * spec.replicas(),
            descriptor.resourceRequest().cpuMillicores() * spec.replicas(),
            spec.replicas());
    if (withThisSubmission.exceeds(tenant.get().quota())) {
      return Optional.of(
          "deployment "
              + spec.name()
              + " would push tenant "
              + tenantId
              + " past its resource quota");
    }
    return Optional.empty();
  }

  private void handleGetDeployment(HttpExchange exchange, String name) throws IOException {
    Optional<DeploymentSpec> spec = storeClient.getDeployment(name);
    if (spec.isEmpty()) {
      respond(exchange, 404, "no such deployment: " + name);
      return;
    }
    respondJson(exchange, 200, deploymentStatus(spec.get()));
  }

  private void handleDeleteDeployment(HttpExchange exchange, String name) throws IOException {
    storeClient.propose(new StateMutation.RemoveDeployment(name));
    respond(exchange, 200, "ok");
  }

  /** Every deployment, in the same shape {@link #handleGetDeployment} returns for one. */
  private void handleDeploymentsList(HttpExchange exchange) {
    try {
      if (!requireAuthorized(exchange, ResourceKind.DEPLOYMENT, Verb.READ, Optional.empty())) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange,
          200,
          storeClient.listDeployments().stream().map(this::deploymentStatus).toList());
    } catch (IOException | RuntimeException e) {
      log.warn("deployments list request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private Map<String, Object> deploymentStatus(DeploymentSpec spec) {
    Map<String, Object> specMap = new LinkedHashMap<>();
    specMap.put("name", spec.name());
    specMap.put("moduleId", moduleIdToJson(spec.moduleId()));
    specMap.put("artifactPath", spec.artifactPath());
    specMap.put("replicas", spec.replicas());
    spec.tenantId().ifPresent(tenantId -> specMap.put("tenantId", tenantId));

    List<Map<String, Object>> instances = new ArrayList<>();
    for (InstanceAssignment assignment : storeClient.listAssignmentsFor(spec.name())) {
      Map<String, Object> instance = new LinkedHashMap<>();
      instance.put("instanceIndex", assignment.instanceIndex());
      instance.put("nodeId", assignment.nodeId());
      findObservation(assignment)
          .ifPresent(obs -> instance.put("observation", observationToJson(obs)));
      instances.add(instance);
    }

    Map<String, Object> status = new LinkedHashMap<>();
    status.put("spec", specMap);
    status.put("instances", instances);
    status.put("unplacedCount", spec.replicas() - instances.size());
    status.put("quotaViolating", storeClient.isQuotaViolating(spec.name()));
    return status;
  }

  /**
   * A per-deployment rollup of the same real request/error-rate data {@link #deploymentStatus}
   * already surfaces per-instance -- average request rate, average error rate, and how many
   * instances contributed a reading, one row per deployment. Instances with no observation yet
   * (never heartbeated, or heartbeated but not yet reporting metrics) simply don't contribute to
   * the average rather than dragging it toward zero, the same "degrade, don't fail" posture {@link
   * #findObservation} already has for a missing reading.
   */
  private void handleMetrics(HttpExchange exchange) {
    try {
      if (!requireAuthorized(exchange, ResourceKind.DEPLOYMENT, Verb.READ, Optional.empty())) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      for (DeploymentSpec spec : storeClient.listDeployments()) {
        List<InstanceObservation> observations = new ArrayList<>();
        for (InstanceAssignment assignment : storeClient.listAssignmentsFor(spec.name())) {
          findObservation(assignment).ifPresent(observations::add);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("deploymentName", spec.name());
        row.put("instanceCount", observations.size());
        row.put(
            "avgRequestRatePerSecond",
            average(observations, InstanceObservation::requestRatePerSecond));
        row.put(
            "avgErrorRatePerSecond",
            average(observations, InstanceObservation::errorRatePerSecond));
        rows.add(row);
      }
      respondJson(exchange, 200, rows);
    } catch (IOException | RuntimeException e) {
      log.warn("metrics rollup request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private static double average(
      List<InstanceObservation> observations,
      java.util.function.ToDoubleFunction<InstanceObservation> extractor) {
    if (observations.isEmpty()) {
      return 0.0;
    }
    return observations.stream().mapToDouble(extractor).average().orElse(0.0);
  }

  private Optional<InstanceObservation> findObservation(InstanceAssignment assignment) {
    return storeClient
        .getNodeHeartbeat(assignment.nodeId())
        .map(ObservedHeartbeat::heartbeat)
        .flatMap(
            heartbeat ->
                heartbeat.instances().stream()
                    .filter(
                        obs ->
                            obs.deploymentName().equals(assignment.deploymentName())
                                && obs.instanceIndex() == assignment.instanceIndex())
                    .findFirst());
  }

  // ---- /nodes/{nodeId}/... ----

  private void handleNode(HttpExchange exchange) {
    try {
      String path = exchange.getRequestURI().getPath();
      String tail = path.substring("/nodes/".length());
      int slash = tail.indexOf('/');
      if (slash < 0) {
        respond(exchange, 400, "expected /nodes/{nodeId}/register|heartbeat|assignments");
        return;
      }
      String nodeId = tail.substring(0, slash);
      String action = tail.substring(slash + 1);
      if (nodeId.isBlank()) {
        respond(exchange, 400, "missing nodeId");
        return;
      }
      // targetId=nodeId is what lets a gimle:nodes principal reach exactly its own subresources
      // (Authorizer's node self-service short-circuit) with no RoleBinding needing to exist for
      // it -- and nothing else.
      Verb verb = "assignments".equals(action) ? Verb.READ : Verb.WRITE;
      if (!requireAuthorized(
          exchange, ResourceKind.NODE, verb, Optional.empty(), Optional.of(nodeId))) {
        return;
      }
      switch (action) {
        case "register" -> handleRegister(exchange, nodeId);
        case "heartbeat" -> handleHeartbeat(exchange, nodeId);
        case "assignments" -> handleAssignments(exchange, nodeId);
        case "cordon" -> handleCordon(exchange, nodeId, true);
        case "uncordon" -> handleCordon(exchange, nodeId, false);
        case "events" -> handleAppendInstanceEvent(exchange);
        default -> respond(exchange, 404, "unknown node endpoint: " + action);
      }
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("node request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleRegister(HttpExchange exchange, String nodeId) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    NodeCapabilities capabilities = capabilitiesFromJson((Map<?, ?>) body.get("capabilities"));
    Object apiAddress = body.get("apiAddress");
    storeClient.propose(
        new StateMutation.PutNodeRegistration(
            new NodeRegistration(
                nodeId,
                capabilities,
                apiAddress == null ? Optional.empty() : Optional.of((String) apiAddress))));
    respond(exchange, 200, "ok");
  }

  /**
   * Heartbeats are deliberately never Raft-replicated: high-frequency, tolerate a brief gap after a
   * leader change, and replicating every one would make the log's write rate scale with cluster
   * size for no correctness benefit. Only the store's current leader ever receives them directly --
   * {@link StoreClient#putHeartbeat} follows the leader internally (etcd-store-extraction design
   * doc §4.4/§4.6) the same way {@code storeClient.propose} does, throwing {@link
   * GimleRaftException} on the same store-unavailable response every other write uses if no leader
   * could be reached, even though this path never touches the Raft log.
   */
  private void handleHeartbeat(HttpExchange exchange, String nodeId) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    ResourceUsageSnapshot capacity = capacityFromJson((Map<?, ?>) body.get("capacity"));
    List<InstanceObservation> instances = new ArrayList<>();
    for (Object entry : (List<?>) body.get("instances")) {
      instances.add(observationFromJson((Map<?, ?>) entry));
    }
    storeClient.putHeartbeat(new NodeHeartbeat(nodeId, capacity, instances));
    respond(exchange, 200, "ok");
  }

  private void handleAssignments(HttpExchange exchange, String nodeId) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    List<Map<String, Object>> assigned = new ArrayList<>();
    for (InstanceAssignment assignment : storeClient.listAssignments()) {
      if (!assignment.nodeId().equals(nodeId)) {
        continue;
      }
      Optional<DeploymentSpec> spec = storeClient.getDeployment(assignment.deploymentName());
      if (spec.isEmpty()) {
        continue; // stale assignment; DeploymentReconciler will remove it shortly
      }
      // moduleId/artifactPath come from the assignment itself, not the deployment's current spec:
      // mid-rolling-update, an index that hasn't migrated yet must keep telling its agent to run
      // whatever it was actually placed with, not the spec's already-advanced target version. An
      // assignment that never specified its own (the three-argument constructor, predating rolling
      // updates) falls back to the spec's, matching the only behavior that existed before.
      ModuleId moduleId =
          assignment.moduleId().equals(InstanceAssignment.UNSPECIFIED_MODULE)
              ? spec.get().moduleId()
              : assignment.moduleId();
      String artifactPath =
          assignment.artifactPath().isBlank()
              ? spec.get().artifactPath()
              : assignment.artifactPath();
      AssignedInstance instance =
          new AssignedInstance(
              assignment.deploymentName(),
              assignment.instanceIndex(),
              moduleId,
              artifactPath,
              spec.get().tenantId());
      assigned.add(assignedInstanceToJson(instance));
    }
    respondJson(exchange, 200, assigned);
  }

  /**
   * Sets or clears the operator cordon flag {@code Scheduler} excludes from future placement --
   * never evicts an instance already running on {@code nodeId}, only keeps new ones off it (see
   * {@code Scheduler}'s own javadoc). Idempotent: cordoning an already-cordoned node, or
   * uncordoning an already-uncordoned one, is a no-op success.
   */
  private void handleCordon(HttpExchange exchange, String nodeId, boolean cordoned)
      throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    storeClient.propose(new StateMutation.PutNodeCordon(nodeId, cordoned));
    respond(exchange, 200, "ok");
  }

  /**
   * Relays one worker-reported {@link InstanceEvent}, forwarded by its agent, into the durable
   * per-instance event log -- the {@code nodeId} in the URL is only used for the {@code NODE:WRITE}
   * self-service authorization {@link #handleNode} already applied; the event itself carries its
   * own deployment/instance identity, unrelated to which node happened to relay it.
   */
  private void handleAppendInstanceEvent(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    Object causeSummary = body.get("causeSummary");
    InstanceEvent event =
        new InstanceEvent(
            (String) body.get("id"),
            (String) body.get("deploymentName"),
            ((Number) body.get("instanceIndex")).intValue(),
            InstanceEventKind.valueOf((String) body.get("kind")),
            (String) body.get("message"),
            causeSummary == null ? Optional.empty() : Optional.of((String) causeSummary),
            ((Number) body.get("occurredAtEpochMilli")).longValue());
    storeClient.propose(new StateMutation.AppendInstanceEvent(event));
    respond(exchange, 200, "ok");
  }

  /**
   * {@code GET /events?deployment=<name>&instance=<index>} -- an instance's own timeline,
   * newest-first, capped at {@code StateStore}'s own per-instance retention window. Authorized the
   * same as every other per-deployment read ({@code /deployments}, the {@code /metrics} rollup):
   * {@code DEPLOYMENT:READ}, unscoped -- events carry no tenant of their own to scope against.
   */
  private void handleEvents(HttpExchange exchange) {
    try {
      if (!requireAuthorized(exchange, ResourceKind.DEPLOYMENT, Verb.READ, Optional.empty())) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Map<String, String> query = parseQuery(exchange);
      String deploymentName = query.get("deployment");
      String instanceParam = query.get("instance");
      if (deploymentName == null || deploymentName.isBlank() || instanceParam == null) {
        respond(exchange, 400, "expected ?deployment=<name>&instance=<index>");
        return;
      }
      int instanceIndex = Integer.parseInt(instanceParam);
      List<Map<String, Object>> events = new ArrayList<>();
      for (InstanceEvent event : storeClient.listInstanceEvents(deploymentName, instanceIndex)) {
        events.add(instanceEventToJson(event));
      }
      respondJson(exchange, 200, events);
    } catch (NumberFormatException e) {
      respondQuietly(exchange, 400, "instance must be an integer");
    } catch (IOException | RuntimeException e) {
      log.warn("events request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private static Map<String, Object> instanceEventToJson(InstanceEvent event) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", event.id());
    map.put("deploymentName", event.deploymentName());
    map.put("instanceIndex", event.instanceIndex());
    map.put("kind", event.kind().name());
    map.put("message", event.message());
    event.causeSummary().ifPresent(summary -> map.put("causeSummary", summary));
    map.put("occurredAtEpochMilli", event.occurredAtEpochMilli());
    return map;
  }

  /** Every registered node, with its capabilities and last-heartbeat time if it's ever sent one. */
  private void handleNodesList(HttpExchange exchange) {
    try {
      if (!requireAuthorized(exchange, ResourceKind.NODE, Verb.READ, Optional.empty())) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      List<Map<String, Object>> nodes = new ArrayList<>();
      for (NodeRegistration registration : storeClient.listNodeRegistrations()) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("nodeId", registration.nodeId());
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put(
            "supportedTiers",
            registration.capabilities().supportedTiers().stream().map(Enum::name).toList());
        capabilities.put("labels", List.copyOf(registration.capabilities().labels()));
        node.put("capabilities", capabilities);
        node.put("cordoned", storeClient.isNodeCordoned(registration.nodeId()));
        storeClient
            .getNodeHeartbeat(registration.nodeId())
            .ifPresent(
                observed -> {
                  node.put("lastHeartbeatAt", observed.receivedAt().toString());
                  node.put("capacity", capacityToJson(observed.heartbeat().capacity()));
                });
        nodes.add(node);
      }
      respondJson(exchange, 200, nodes);
    } catch (IOException | RuntimeException e) {
      log.warn("nodes list request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  // ---- (de)serialization ----

  private static Map<String, Object> moduleIdToJson(ModuleId id) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", id.name());
    map.put("version", id.version().toString());
    return map;
  }

  private static ModuleId moduleIdFromJson(Map<?, ?> map) {
    return new ModuleId((String) map.get("name"), Version.parse((String) map.get("version")));
  }

  private static NodeCapabilities capabilitiesFromJson(Map<?, ?> map) {
    List<?> tiers = (List<?>) map.get("supportedTiers");
    Set<IsolationTier> supportedTiers = new LinkedHashSet<>();
    for (Object tier : tiers) {
      supportedTiers.add(IsolationTier.valueOf((String) tier));
    }
    // "labels" is absent from an older agent build's registration request -- default to no
    // labels rather than fail the registration outright, matching this class's existing
    // degrade-don't-fail posture for other optional/newer fields (see NodeRegistration's own
    // apiAddress javadoc).
    Object rawLabels = map.get("labels");
    Set<String> labels = new LinkedHashSet<>();
    if (rawLabels instanceof List<?> list) {
      for (Object label : list) {
        labels.add((String) label);
      }
    }
    return new NodeCapabilities(supportedTiers, labels);
  }

  private static Map<String, Object> capacityToJson(ResourceUsageSnapshot capacity) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("totalMemoryBytes", capacity.totalMemoryBytes());
    map.put("assignedMemoryBytes", capacity.assignedMemoryBytes());
    map.put("totalCpuMillicores", capacity.totalCpuMillicores());
    map.put("assignedCpuMillicores", capacity.assignedCpuMillicores());
    return map;
  }

  private static ResourceUsageSnapshot capacityFromJson(Map<?, ?> map) {
    return new ResourceUsageSnapshot(
        ((Number) map.get("totalMemoryBytes")).longValue(),
        ((Number) map.get("assignedMemoryBytes")).longValue(),
        ((Number) map.get("totalCpuMillicores")).longValue(),
        ((Number) map.get("assignedCpuMillicores")).longValue());
  }

  private static InstanceObservation observationFromJson(Map<?, ?> map) {
    return new InstanceObservation(
        (String) map.get("deploymentName"),
        ((Number) map.get("instanceIndex")).intValue(),
        moduleIdFromJson((Map<?, ?>) map.get("moduleId")),
        (String) map.get("lifecycleState"),
        (Boolean) map.get("alive"),
        (Boolean) map.get("ready"),
        numberField(map, "requestRatePerSecond", 0.0).doubleValue(),
        numberField(map, "queueDepth", 0).intValue(),
        numberField(map, "cpuMillicoresUsed", 0L).longValue(),
        numberField(map, "memoryBytesUsed", 0L).longValue(),
        numberField(map, "errorRatePerSecond", 0.0).doubleValue());
  }

  private static Number numberField(Map<?, ?> map, String key, Number defaultValue) {
    Object value = map.get(key);
    return value instanceof Number number ? number : defaultValue;
  }

  private static Map<String, Object> observationToJson(InstanceObservation obs) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("moduleId", moduleIdToJson(obs.moduleId()));
    map.put("lifecycleState", obs.lifecycleState());
    map.put("alive", obs.alive());
    map.put("ready", obs.ready());
    map.put("requestRatePerSecond", obs.requestRatePerSecond());
    map.put("errorRatePerSecond", obs.errorRatePerSecond());
    map.put("queueDepth", obs.queueDepth());
    map.put("cpuMillicoresUsed", obs.cpuMillicoresUsed());
    map.put("memoryBytesUsed", obs.memoryBytesUsed());
    return map;
  }

  private static Map<String, Object> assignedInstanceToJson(AssignedInstance instance) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("deploymentName", instance.deploymentName());
    map.put("instanceIndex", instance.instanceIndex());
    map.put("moduleId", moduleIdToJson(instance.moduleId()));
    map.put("artifactPath", instance.artifactPath());
    instance.tenantId().ifPresent(tenantId -> map.put("tenantId", tenantId));
    return map;
  }

  // ---- /tenants and /tenants/{id} ----

  private void handleTenantsList(HttpExchange exchange) {
    try {
      if (!requireAuthorized(exchange, ResourceKind.TENANT, Verb.READ, Optional.empty())) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange, 200, storeClient.listTenants().stream().map(ApiServer::tenantToJson).toList());
    } catch (IOException | RuntimeException e) {
      log.warn("tenants list request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleTenant(HttpExchange exchange) {
    try {
      String id = pathSegmentAfter(exchange, "/tenants/");
      if (id.isBlank()) {
        respond(exchange, 400, "missing tenant id");
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "PUT" -> {
          if (requireAuthorized(exchange, ResourceKind.TENANT, Verb.WRITE, Optional.of(id))) {
            handlePutTenant(exchange, id);
          }
        }
        case "GET" -> {
          if (requireAuthorized(exchange, ResourceKind.TENANT, Verb.READ, Optional.of(id))) {
            handleGetTenant(exchange, id);
          }
        }
        case "DELETE" -> {
          if (requireAuthorized(exchange, ResourceKind.TENANT, Verb.DELETE, Optional.of(id))) {
            handleDeleteTenant(exchange, id);
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("tenant request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handlePutTenant(HttpExchange exchange, String id) throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    Map<?, ?> quotaMap = (Map<?, ?>) body.get("quota");
    ResourceQuota quota =
        new ResourceQuota(
            ((Number) quotaMap.get("maxMemoryBytes")).longValue(),
            ((Number) quotaMap.get("maxCpuMillicores")).longValue(),
            ((Number) quotaMap.get("maxInstances")).intValue());
    storeClient.propose(new StateMutation.PutTenant(new Tenant(id, quota)));
    respond(exchange, 200, "ok");
  }

  private void handleGetTenant(HttpExchange exchange, String id) throws IOException {
    Optional<Tenant> tenant = storeClient.getTenant(id);
    if (tenant.isEmpty()) {
      respond(exchange, 404, "no such tenant: " + id);
      return;
    }
    respondJson(exchange, 200, tenantToJson(tenant.get()));
  }

  private void handleDeleteTenant(HttpExchange exchange, String id) throws IOException {
    storeClient.propose(new StateMutation.RemoveTenant(id));
    respond(exchange, 200, "ok");
  }

  private static Map<String, Object> tenantToJson(Tenant tenant) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", tenant.id());
    Map<String, Object> quota = new LinkedHashMap<>();
    quota.put("maxMemoryBytes", tenant.quota().maxMemoryBytes());
    quota.put("maxCpuMillicores", tenant.quota().maxCpuMillicores());
    quota.put("maxInstances", tenant.quota().maxInstances());
    map.put("quota", quota);
    return map;
  }

  // ---- /config/{tenantId} and /config/{tenantId}/{key} ----

  private void handleConfig(HttpExchange exchange) {
    try {
      String tail = pathSegmentAfter(exchange, "/config/");
      if (tail.isBlank()) {
        respond(exchange, 400, "expected /config/{tenantId} or /config/{tenantId}/{key}");
        return;
      }
      int slash = tail.indexOf('/');
      String tenantId = slash < 0 ? tail : tail.substring(0, slash);
      if (tenantId.isBlank()) {
        respond(exchange, 400, "missing tenantId");
        return;
      }
      if (slash < 0) {
        if (!"GET".equals(exchange.getRequestMethod())) {
          respond(exchange, 405, "method not allowed");
          return;
        }
        handleListConfig(exchange, tenantId);
        return;
      }
      String key = tail.substring(slash + 1);
      if (key.isBlank()) {
        respond(exchange, 400, "missing config key");
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "PUT" -> {
          // The resource kind an entry is written under depends on the request body's own
          // `encrypted` flag, so the body must be read before authorizing -- unlike every other
          // write in this class, which authorizes purely off the URL.
          Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
          String value = (String) body.get("value");
          boolean encrypted = Boolean.TRUE.equals(body.get("encrypted"));
          ResourceKind resource = encrypted ? ResourceKind.SECRET : ResourceKind.CONFIG;
          if (requireAuthorized(exchange, resource, Verb.WRITE, Optional.of(tenantId))) {
            handlePutConfig(exchange, tenantId, key, value, encrypted);
          }
        }
        case "DELETE" -> {
          Optional<ConfigEntry> existing = findConfigEntry(tenantId, key);
          if (existing.isEmpty()) {
            respond(exchange, 404, "no such config entry: " + key);
            return;
          }
          ResourceKind resource =
              existing.get().encrypted() ? ResourceKind.SECRET : ResourceKind.CONFIG;
          if (requireAuthorized(exchange, resource, Verb.DELETE, Optional.of(tenantId))) {
            handleDeleteConfig(exchange, tenantId, key);
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("config request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private Optional<ConfigEntry> findConfigEntry(String tenantId, String key) {
    return storeClient.listConfigEntriesFor(tenantId).stream()
        .filter(e -> e.key().equals(key))
        .findFirst();
  }

  private void handlePutConfig(
      HttpExchange exchange, String tenantId, String key, String value, boolean encrypted)
      throws IOException {
    byte[] stored =
        encrypted
            ? SecretCipher.encrypt(value.getBytes(StandardCharsets.UTF_8), secretKey)
            : value.getBytes(StandardCharsets.UTF_8);
    storeClient.propose(
        new StateMutation.PutConfigEntry(new ConfigEntry(tenantId, key, stored, encrypted)));
    respond(exchange, 200, "ok");
  }

  private void handleDeleteConfig(HttpExchange exchange, String tenantId, String key)
      throws IOException {
    storeClient.propose(new StateMutation.RemoveConfigEntry(tenantId, key));
    respond(exchange, 200, "ok");
  }

  /**
   * Returns every entry for {@code tenantId} the caller can see, decrypted -- the node agent's
   * fetch point: an agent fetches a deployment's tenant-scoped {@code ConfigEntry} set from the
   * control plane, already decrypted server-side. Plaintext leaves this process only over the same
   * authenticated control-plane connection every other agent request already uses.
   *
   * <p>Unlike every other list endpoint in this class, access here is per-entry rather than
   * uniform: a caller holding only {@code CONFIG:READ} sees plaintext entries, one holding only
   * {@code SECRET:READ} sees encrypted entries, and one holding neither is refused outright.
   */
  private void handleListConfig(HttpExchange exchange, String tenantId) throws IOException {
    boolean canReadConfig;
    boolean canReadSecrets;
    if (exchange instanceof HttpsExchange) {
      Optional<Principal> principal = resolvePrincipal(exchange);
      if (principal.isEmpty()) {
        respondQuietly(exchange, 401, "authentication required");
        return;
      }
      canReadConfig =
          authorizer.authorize(
              principal.get(),
              ResourceKind.CONFIG,
              Verb.READ,
              Optional.of(tenantId),
              Optional.empty());
      canReadSecrets =
          authorizer.authorize(
              principal.get(),
              ResourceKind.SECRET,
              Verb.READ,
              Optional.of(tenantId),
              Optional.empty());
      if (!canReadConfig && !canReadSecrets) {
        respondQuietly(exchange, 403, "forbidden");
        return;
      }
    } else {
      canReadConfig = true;
      canReadSecrets = true;
    }
    List<Map<String, Object>> list = new ArrayList<>();
    for (ConfigEntry entry : storeClient.listConfigEntriesFor(tenantId)) {
      if (entry.encrypted() ? !canReadSecrets : !canReadConfig) {
        continue;
      }
      byte[] plaintext =
          entry.encrypted() ? SecretCipher.decrypt(entry.value(), secretKey) : entry.value();
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("key", entry.key());
      m.put("value", new String(plaintext, StandardCharsets.UTF_8));
      m.put("encrypted", entry.encrypted());
      list.add(m);
    }
    respondJson(exchange, 200, list);
  }

  // ---- /roles and /roles/{name} ----

  private void handleRolesList(HttpExchange exchange) {
    try {
      if (!requireAuthorized(exchange, ResourceKind.ROLE, Verb.READ, Optional.empty())) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange, 200, storeClient.listRoles().stream().map(ApiServer::roleToJson).toList());
    } catch (IOException | RuntimeException e) {
      log.warn("roles list request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleRole(HttpExchange exchange) {
    try {
      String name = pathSegmentAfter(exchange, "/roles/");
      if (name.isBlank()) {
        respond(exchange, 400, "missing role name");
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "PUT" -> {
          if (requireAuthorized(exchange, ResourceKind.ROLE, Verb.WRITE, Optional.empty())) {
            handlePutRole(exchange, name);
          }
        }
        case "GET" -> {
          if (requireAuthorized(exchange, ResourceKind.ROLE, Verb.READ, Optional.empty())) {
            handleGetRole(exchange, name);
          }
        }
        case "DELETE" -> {
          if (requireAuthorized(exchange, ResourceKind.ROLE, Verb.DELETE, Optional.empty())) {
            handleDeleteRole(exchange, name);
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("role request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handlePutRole(HttpExchange exchange, String name) throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    storeClient.propose(new StateMutation.PutRole(roleFromJson(name, body)));
    respond(exchange, 200, "ok");
  }

  private void handleGetRole(HttpExchange exchange, String name) throws IOException {
    Optional<Role> role = storeClient.getRole(name);
    if (role.isEmpty()) {
      respond(exchange, 404, "no such role: " + name);
      return;
    }
    respondJson(exchange, 200, roleToJson(role.get()));
  }

  private void handleDeleteRole(HttpExchange exchange, String name) throws IOException {
    storeClient.propose(new StateMutation.RemoveRole(name));
    respond(exchange, 200, "ok");
  }

  private static Map<String, Object> roleToJson(Role role) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", role.name());
    List<Map<String, Object>> permissions = new ArrayList<>();
    for (Permission p : role.permissions()) {
      Map<String, Object> pm = new LinkedHashMap<>();
      pm.put("resource", p.resource().name());
      pm.put("verb", p.verb().name());
      p.tenantScope().ifPresent(t -> pm.put("tenantScope", t));
      permissions.add(pm);
    }
    map.put("permissions", permissions);
    return map;
  }

  private static Role roleFromJson(String name, Map<?, ?> body) {
    List<?> permissionsList = (List<?>) body.get("permissions");
    Set<Permission> permissions = new LinkedHashSet<>();
    if (permissionsList != null) {
      for (Object o : permissionsList) {
        Map<?, ?> pm = (Map<?, ?>) o;
        ResourceKind resource = ResourceKind.valueOf((String) pm.get("resource"));
        Verb verb = Verb.valueOf((String) pm.get("verb"));
        Object tenantScope = pm.get("tenantScope");
        permissions.add(
            new Permission(
                resource,
                verb,
                tenantScope == null ? Optional.empty() : Optional.of((String) tenantScope)));
      }
    }
    return new Role(name, permissions);
  }

  // ---- /rolebindings and /rolebindings/{id} ----

  private void handleRoleBindingsList(HttpExchange exchange) {
    try {
      if (!requireAuthorized(exchange, ResourceKind.ROLE_BINDING, Verb.READ, Optional.empty())) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange,
          200,
          storeClient.listRoleBindings().stream().map(ApiServer::roleBindingToJson).toList());
    } catch (IOException | RuntimeException e) {
      log.warn("role bindings list request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleRoleBinding(HttpExchange exchange) {
    try {
      String id = pathSegmentAfter(exchange, "/rolebindings/");
      if (id.isBlank()) {
        respond(exchange, 400, "missing role binding id");
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "PUT" -> {
          if (requireAuthorized(
              exchange, ResourceKind.ROLE_BINDING, Verb.WRITE, Optional.empty())) {
            handlePutRoleBinding(exchange, id);
          }
        }
        case "GET" -> {
          if (requireAuthorized(exchange, ResourceKind.ROLE_BINDING, Verb.READ, Optional.empty())) {
            handleGetRoleBinding(exchange, id);
          }
        }
        case "DELETE" -> {
          if (requireAuthorized(
              exchange, ResourceKind.ROLE_BINDING, Verb.DELETE, Optional.empty())) {
            handleDeleteRoleBinding(exchange, id);
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("role binding request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handlePutRoleBinding(HttpExchange exchange, String id) throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    RoleBinding binding =
        new RoleBinding(id, (String) body.get("subject"), (String) body.get("roleName"));
    storeClient.propose(new StateMutation.PutRoleBinding(binding));
    respond(exchange, 200, "ok");
  }

  private void handleGetRoleBinding(HttpExchange exchange, String id) throws IOException {
    Optional<RoleBinding> binding = storeClient.getRoleBinding(id);
    if (binding.isEmpty()) {
      respond(exchange, 404, "no such role binding: " + id);
      return;
    }
    respondJson(exchange, 200, roleBindingToJson(binding.get()));
  }

  private void handleDeleteRoleBinding(HttpExchange exchange, String id) throws IOException {
    storeClient.propose(new StateMutation.RemoveRoleBinding(id));
    respond(exchange, 200, "ok");
  }

  private static Map<String, Object> roleBindingToJson(RoleBinding binding) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", binding.id());
    map.put("subject", binding.subject());
    map.put("roleName", binding.roleName());
    return map;
  }

  // ---- /accounts and /accounts/{username} ----

  private void handleAccountsList(HttpExchange exchange) {
    try {
      if (!requireAuthorized(exchange, ResourceKind.ACCOUNT, Verb.READ, Optional.empty())) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange,
          200,
          storeClient.listAccounts().stream().map(ApiServer::accountToJson).toList());
    } catch (IOException | RuntimeException e) {
      log.warn("accounts list request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleAccount(HttpExchange exchange) {
    try {
      String username = pathSegmentAfter(exchange, "/accounts/");
      if (username.isBlank()) {
        respond(exchange, 400, "missing username");
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "PUT" -> {
          if (requireAuthorized(exchange, ResourceKind.ACCOUNT, Verb.WRITE, Optional.empty())) {
            handlePutAccount(exchange, username);
          }
        }
        case "GET" -> {
          if (requireAuthorized(exchange, ResourceKind.ACCOUNT, Verb.READ, Optional.empty())) {
            handleGetAccount(exchange, username);
          }
        }
        case "DELETE" -> {
          if (requireAuthorized(exchange, ResourceKind.ACCOUNT, Verb.DELETE, Optional.empty())) {
            handleDeleteAccount(exchange, username);
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("account request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * Takes a raw {@code password} in the request body, never a pre-hashed value -- hashing happens
   * here, server-side, via {@link PasswordHashes}, the same reason the CLI's {@code set account}
   * never touches password-hashing logic itself. Doubles as create-or-reset (no separate "reset
   * password" verb), matching {@code set tenant}/{@code set config}'s existing create-or-update
   * convention.
   */
  private void handlePutAccount(HttpExchange exchange, String username) throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    String password = (String) body.get("password");
    if (password == null || password.isBlank()) {
      respond(exchange, 400, "missing password");
      return;
    }
    byte[] passwordHash = PasswordHashes.hash(password.toCharArray());
    storeClient.propose(new StateMutation.PutAccount(new Account(username, passwordHash)));
    respond(exchange, 200, "ok");
  }

  private void handleGetAccount(HttpExchange exchange, String username) throws IOException {
    Optional<Account> account = storeClient.getAccount(username);
    if (account.isEmpty()) {
      respond(exchange, 404, "no such account: " + username);
      return;
    }
    respondJson(exchange, 200, accountToJson(account.get()));
  }

  private void handleDeleteAccount(HttpExchange exchange, String username) throws IOException {
    storeClient.propose(new StateMutation.RemoveAccount(username));
    respond(exchange, 200, "ok");
  }

  /** Never includes {@code passwordHash} -- this is the one field an API response never leaks. */
  private static Map<String, Object> accountToJson(Account account) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("username", account.username());
    return map;
  }

  // ---- /auth/login, /auth/logout, /auth/session ----

  /**
   * No {@link #requireAuthorized} call in any of these three, deliberately: {@code /auth/login} and
   * {@code /auth/session} must both be reachable with no identity yet (that's the whole point of a
   * login endpoint, and how the console tells "logged out" apart from "logged in" -- {@code
   * claudedocs/authn-authz-design.md} §6a), and {@code /auth/logout} only ever clears whatever
   * cookie is presented, authenticated or not.
   */
  private void handleAuthLogin(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
      String username = (String) body.get("username");
      String password = (String) body.get("password");
      Optional<Account> account =
          username == null ? Optional.empty() : storeClient.getAccount(username);
      if (account.isEmpty()
          || password == null
          || !PasswordHashes.verify(password.toCharArray(), account.get().passwordHash())) {
        // Deliberately the same message either way -- distinguishing "unknown username" from
        // "wrong password" would let this endpoint enumerate valid usernames.
        respondQuietly(exchange, 401, "invalid username or password");
        return;
      }
      String token = SessionTokens.issue(username, sessionSigningKey, SESSION_TTL);
      exchange
          .getResponseHeaders()
          .add("Set-Cookie", sessionCookieHeader(token, SESSION_TTL.toSeconds()));
      respondJson(exchange, 200, principalToJson(new Principal(username, Set.of())));
    } catch (IOException | RuntimeException e) {
      log.warn("login request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleAuthLogout(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      exchange.getResponseHeaders().add("Set-Cookie", sessionCookieHeader("", 0));
      respond(exchange, 200, "ok");
    } catch (IOException e) {
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /** Polled by the console on load to tell "already logged in" apart from "show the login page". */
  private void handleAuthSession(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Optional<Principal> principal = resolvePrincipal(exchange);
      if (principal.isEmpty()) {
        respondQuietly(exchange, 401, "not authenticated");
        return;
      }
      respondJson(exchange, 200, principalToJson(principal.get()));
    } catch (IOException e) {
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private static Map<String, Object> principalToJson(Principal principal) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("username", principal.name());
    map.put("groups", List.copyOf(principal.groups()));
    return map;
  }

  // ---- /logs/controlplane, /logs/nodes/{nodeId}, /logs/instances/{name}/{idx} ----

  /**
   * Log reads are GETs against whichever control-plane replica receives them, which then makes its
   * own direct call to the target agent -- no write/consensus involved, so §5's leader-redirect
   * handling doesn't apply here (matches {@code log-explorer-design.md} §6).
   */
  private void handleLogs(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      String tail = pathSegmentAfter(exchange, "/logs/");
      // A node's own logs (/logs/nodes/{nodeId}) are the one log target a gimle:nodes principal
      // may reach via self-service -- everything else (controlplane, instances) needs a real
      // permission, matching handleNode's own targetId convention.
      Optional<String> targetNodeId =
          tail.startsWith("nodes/")
              ? Optional.of(tail.substring("nodes/".length()))
              : Optional.empty();
      if (!requireAuthorized(
          exchange, ResourceKind.LOGS, Verb.READ, Optional.empty(), targetNodeId)) {
        return;
      }
      if (tail.equals("controlplane")) {
        handleControlPlaneLogs(exchange);
      } else if (tail.startsWith("nodes/")) {
        handleNodeLogsProxy(exchange, tail.substring("nodes/".length()));
      } else if (tail.startsWith("instances/")) {
        handleInstanceLogsProxy(exchange, tail.substring("instances/".length()));
      } else {
        respond(exchange, 404, "unknown logs endpoint: " + tail);
      }
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("logs request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /** Served directly from this process's own platform log -- it's the process answering. */
  private void handleControlPlaneLogs(HttpExchange exchange) throws IOException {
    Map<String, String> query = parseQuery(exchange);
    String category = query.getOrDefault("category", "PLATFORM");
    if (!"PLATFORM".equals(category)) {
      respond(exchange, 400, "controlplane logs only support category=PLATFORM");
      return;
    }
    Path file =
        Path.of(System.getProperty("gimle.log.root", "gimle-logs"))
            .resolve("controlplane-platform.log");
    respondLogFile(exchange, file, query);
  }

  private void handleNodeLogsProxy(HttpExchange exchange, String nodeId) throws IOException {
    if (nodeId.isBlank()) {
      respond(exchange, 400, "missing nodeId");
      return;
    }
    proxyToAgent(exchange, nodeId, "/logs/nodes/" + nodeId);
  }

  private void handleInstanceLogsProxy(HttpExchange exchange, String tail) throws IOException {
    // limit=3: deploymentName, instanceIndex, and an optional sub-path (e.g. AgentLogServer's
    // "crashdumps" or "crashdumps/<name>") -- a plain 2-way split on the first slash used to
    // swallow anything past the instanceIndex into a failed Integer.parseInt, breaking any
    // sub-path entirely.
    String[] parts = tail.split("/", 3);
    if (parts.length < 2) {
      respond(exchange, 400, "expected /logs/instances/{deploymentName}/{instanceIndex}[/...]");
      return;
    }
    String deploymentName = parts[0];
    int instanceIndex;
    try {
      instanceIndex = Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      respond(exchange, 400, "invalid instanceIndex");
      return;
    }
    String nodeId =
        storeClient.listAssignmentsFor(deploymentName).stream()
            .filter(a -> a.instanceIndex() == instanceIndex)
            .map(InstanceAssignment::nodeId)
            .findFirst()
            .orElse(null);
    if (nodeId == null) {
      respond(exchange, 404, "no placement found for " + deploymentName + "#" + instanceIndex);
      return;
    }
    // Forward the original tail verbatim (not reconstructed from just name/index) so any sub-path
    // -- crashdumps, crashdumps/<name> -- survives the proxy hop unchanged.
    proxyToAgent(exchange, nodeId, "/logs/instances/" + tail);
  }

  /** Looks up the owning node's self-reported log-server address and forwards the request as-is. */
  private void proxyToAgent(HttpExchange exchange, String nodeId, String path) throws IOException {
    Optional<NodeRegistration> registration = storeClient.getNodeRegistration(nodeId);
    if (registration.isEmpty()) {
      respond(exchange, 404, "unknown node: " + nodeId);
      return;
    }
    Optional<String> apiAddress = registration.flatMap(NodeRegistration::apiAddress);
    if (apiAddress.isEmpty()) {
      // A known node whose agent hasn't self-advertised a log-server address yet -- still a
      // legitimate "upstream not ready" gateway condition, unlike the truly-unknown-node case
      // above, which isn't a gateway problem at all.
      respond(exchange, 502, "node " + nodeId + " has no known log-server address");
      return;
    }
    String query = exchange.getRequestURI().getRawQuery();
    URI target =
        URI.create("http://" + apiAddress.get() + path + (query != null ? "?" + query : ""));
    HttpRequest request = HttpRequest.newBuilder(target).GET().build();

    if (query != null && query.contains("follow=true")) {
      proxyFollowToAgent(exchange, apiAddress.get(), request);
      return;
    }

    HttpResponse<InputStream> response;
    try {
      response = agentHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      respond(exchange, 502, "interrupted while proxying to agent " + apiAddress.get());
      return;
    } catch (IOException e) {
      respond(
          exchange, 502, "failed to reach agent at " + apiAddress.get() + ": " + e.getMessage());
      return;
    }
    String contentType =
        response.headers().firstValue("Content-Type").orElse("application/octet-stream");
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(response.statusCode(), 0);
    try (InputStream body = response.body();
        OutputStream out = exchange.getResponseBody()) {
      body.transferTo(out);
    }
  }

  /**
   * {@code follow=true} means an indefinite chunked response with no end in sight, and {@code
   * HttpClient.send()} + {@code BodyHandlers.ofInputStream()} -- which works fine for the bounded,
   * non-follow proxy path above -- never delivers a single byte for this shape of response
   * (confirmed empirically: a direct request straight to the agent's own log server streams
   * immediately, the identical request through this proxy using {@code send()}/{@code
   * ofInputStream()} sat silent for the whole test window). {@code ofByteArrayConsumer} sidesteps
   * that: it's driven by the reactive {@code Flow} subscription underneath, invoked as chunks
   * genuinely arrive rather than via a blocking synchronous read, so bytes reach the exchange's
   * output stream as they're produced instead of never at all.
   */
  private void proxyFollowToAgent(HttpExchange exchange, String apiAddress, HttpRequest request)
      throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson; charset=utf-8");
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream out = exchange.getResponseBody()) {
      agentHttpClient
          .sendAsync(
              request,
              HttpResponse.BodyHandlers.ofByteArrayConsumer(
                  chunk -> {
                    if (chunk.isPresent()) {
                      try {
                        out.write(chunk.get());
                        out.flush();
                      } catch (IOException e) {
                        throw new UncheckedIOException(e);
                      }
                    }
                  }))
          .join();
    } catch (CompletionException | UncheckedIOException e) {
      log.debug(
          "follow proxy session to agent {} ended: {}", apiAddress, String.valueOf(e.getMessage()));
    }
  }

  private static void respondLogFile(HttpExchange exchange, Path file, Map<String, String> query)
      throws IOException {
    boolean follow = "true".equals(query.get("follow"));
    String cursor = query.get("cursor");
    int limit = parseLimit(query.get("limit"));
    int maxFiles = LogFileReader.configuredMaxFiles();
    if (follow) {
      exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson; charset=utf-8");
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream out = exchange.getResponseBody()) {
        LogFileReader.streamFollow(file, maxFiles, cursor, Duration.ofMillis(500), out);
      } catch (IOException e) {
        log.debug("controlplane log follow session ended: {}", e.getMessage());
      }
      return;
    }
    // "since" (readAfter, forward polling: "what's new since my last poll") is a genuinely
    // different operation from "cursor" (readOlder, backward paging: "Load older") -- see
    // AgentLogServer.readPage's javadoc for the real duplication bug this distinction fixes.
    String since = query.get("since");
    LogFileReader.LogPage page;
    if (since != null) {
      List<Map<String, Object>> lines = LogFileReader.readAfter(file, maxFiles, since);
      String newerCursor =
          lines.isEmpty() ? since : String.valueOf(lines.get(lines.size() - 1).get("timestamp"));
      page = new LogFileReader.LogPage(lines, null, newerCursor);
    } else {
      page = LogFileReader.readOlder(file, maxFiles, cursor, limit);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("lines", page.lines());
    body.put("olderCursor", page.olderCursor());
    body.put("newerCursor", page.newerCursor());
    respondJson(exchange, 200, body);
  }

  private static int parseLimit(String raw) {
    if (raw == null) {
      return 200;
    }
    try {
      return Math.max(1, Integer.parseInt(raw));
    } catch (NumberFormatException e) {
      return 200;
    }
  }

  private static Map<String, String> parseQuery(HttpExchange exchange) {
    Map<String, String> result = new LinkedHashMap<>();
    String query = exchange.getRequestURI().getRawQuery();
    if (query == null || query.isBlank()) {
      return result;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq < 0) {
        continue;
      }
      String key = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
      String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      result.put(key, value);
    }
    return result;
  }

  // ---- /bootstrap/csr, /bootstrap/csr/{id}[/approve], /bootstrap/tokens ----

  private static final Duration LEAF_VALIDITY = Duration.ofDays(397);

  /**
   * No {@link #requireAuthorized} call here, deliberately: this is the one endpoint that by design
   * must be reachable without a client certificate (§4) -- it exists specifically to issue the cert
   * that makes mTLS possible everywhere else. Three distinct auth contexts, distinguished entirely
   * by what the request carries: a verified peer certificate present at all means rotation (§4b,
   * subject must match); none present and {@code purpose == NODE_CLIENT} means a node join,
   * authenticated by a one-time bootstrap token (§4); none present and {@code purpose ==
   * OPERATOR_CLIENT} means a human operator request, never auto-approved (§4a).
   */
  private void handleBootstrapCsrSubmit(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      CsrSubmission submission =
          csrSubmissionFromJson(Json.asObject(Json.parse(readBody(exchange))));
      PKCS10CertificationRequest csr = Pem.decodeCsr(submission.csrPem());

      Optional<X509Certificate> presented = peerCertificate(exchange);
      if (presented.isPresent()) {
        handleRotationRequest(exchange, csr, presented.get());
        return;
      }
      switch (submission.purpose()) {
        case NODE_CLIENT -> handleNodeJoinRequest(exchange, csr, submission.bootstrapToken());
        case OPERATOR_CLIENT -> handleOperatorJoinRequest(exchange, csr);
      }
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("bootstrap CSR submission failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * Safe to auto-approve unconditionally once the Subject matches: this grants no new trust, it
   * only extends trust already established by the caller's own still-valid certificate. A mismatch
   * would let an already-trusted identity mint a certificate for a *different* Subject, which is
   * exactly the case this check exists to block.
   */
  private void handleRotationRequest(
      HttpExchange exchange, PKCS10CertificationRequest csr, X509Certificate presented)
      throws IOException {
    // Compared as raw DER bytes, never as a re-parsed string:
    // X509Certificate#getSubjectX500Principal
    // ().getName() renders in RFC 2253 canonical order (most-specific RDN, CN, first), which does
    // not match the ASN.1 encoding order csr.getSubject() preserves once a subject carries more
    // than one RDN (every operator/node certificate does now, CN= plus O=) -- re-parsing that
    // reordered string back into an X500Name silently changed which RDN sequence it held, making
    // this check reject a subject against itself. getEncoded() sidesteps the round trip entirely.
    boolean subjectsMatch;
    try {
      subjectsMatch =
          java.util.Arrays.equals(
              csr.getSubject().getEncoded(), presented.getSubjectX500Principal().getEncoded());
    } catch (IOException e) {
      subjectsMatch = false;
    }
    if (!subjectsMatch) {
      respond(
          exchange,
          403,
          "rotation CSR subject does not match the authenticating certificate's own subject");
      return;
    }
    // Preserves the exact prior subject (O= included) -- csr.getSubject() as its own override is
    // exactly what makes rotation carry a principal's group membership forward unchanged, with no
    // separate re-derivation from purpose needed.
    respondSigned(exchange, 200, csr, csr.getSubject());
  }

  private void handleNodeJoinRequest(
      HttpExchange exchange, PKCS10CertificationRequest csr, Optional<String> bootstrapToken)
      throws IOException {
    if (bootstrapToken.isEmpty() || !bootstrapTokenRegistry.tryConsume(bootstrapToken.get())) {
      respond(exchange, 401, "missing or invalid bootstrap token");
      return;
    }
    // Server-stamped O=, never the CSR's own -- claudedocs/authn-authz-design.md §2a: a
    // NODE_CLIENT CSR that self-declared O=gimle:operators must not be signed with it.
    respondSigned(
        exchange, 200, csr, Subjects.withOrganization(csr.getSubject(), BuiltinRoles.GROUP_NODES));
  }

  private void handleOperatorJoinRequest(HttpExchange exchange, PKCS10CertificationRequest csr)
      throws IOException {
    String requestId = pendingCsrStore.submit(Pem.encodeCsr(csr));
    respondJson(exchange, 202, csrResultToJson(CsrResult.pending(requestId)));
  }

  private void respondSigned(
      HttpExchange exchange, int status, PKCS10CertificationRequest csr, X500Name subjectOverride)
      throws IOException {
    CertificateAuthority ca = certificateAuthority.orElseThrow();
    X509Certificate signed = ca.signCertificateRequest(csr, subjectOverride, LEAF_VALIDITY);
    respondJson(
        exchange,
        status,
        csrResultToJson(
            CsrResult.approved(
                Pem.encodeCertificate(signed), Pem.encodeCertificate(ca.certificate()))));
  }

  /** {@code GET /bootstrap/csr/{id}} (status poll) or {@code POST /bootstrap/csr/{id}/approve}. */
  private void handleBootstrapCsrSubResource(HttpExchange exchange) {
    try {
      String tail = pathSegmentAfter(exchange, "/bootstrap/csr/");
      if (tail.endsWith("/approve")) {
        if (!requireAuthorized(
            exchange, ResourceKind.CERTIFICATE_REQUEST, Verb.APPROVE, Optional.empty())) {
          return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
          respond(exchange, 405, "method not allowed");
          return;
        }
        handleApprove(exchange, tail.substring(0, tail.length() - "/approve".length()));
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      handleStatus(exchange, tail);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("bootstrap CSR status/approve request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleStatus(HttpExchange exchange, String requestId) throws IOException {
    Optional<PendingCsrStore.Entry> entry = pendingCsrStore.get(requestId);
    if (entry.isEmpty()) {
      respond(exchange, 404, "no such pending request: " + requestId);
      return;
    }
    CertificateAuthority ca = certificateAuthority.orElseThrow();
    CsrResult result =
        entry
            .get()
            .signedCertificate()
            .map(
                signed ->
                    CsrResult.approved(
                        Pem.encodeCertificate(signed), Pem.encodeCertificate(ca.certificate())))
            .orElseGet(() -> CsrResult.pending(requestId));
    respondJson(exchange, 200, csrResultToJson(result));
  }

  /**
   * §4a: {@link #handleBootstrapCsrSubResource}'s {@code /approve} branch already requires {@code
   * CERTIFICATE_REQUEST:APPROVE} before this runs -- by default only a {@code
   * group:gimle:operators} principal has it, via the built-in {@code cluster-admin} binding, so
   * this is behavior-preserving for today's only-operators-exist clusters, no longer "any cert
   * holder" as a matter of policy rather than incidental fact.
   */
  private void handleApprove(HttpExchange exchange, String requestId) throws IOException {
    Optional<PendingCsrStore.Entry> entry = pendingCsrStore.get(requestId);
    if (entry.isEmpty()) {
      respond(exchange, 404, "no such pending request: " + requestId);
      return;
    }
    PKCS10CertificationRequest csr = Pem.decodeCsr(entry.get().csrPem());
    CertificateAuthority ca = certificateAuthority.orElseThrow();
    // Server-stamped O=, mirroring handleNodeJoinRequest -- an OPERATOR_CLIENT CSR's own Subject
    // is never trusted verbatim either.
    X509Certificate signed =
        ca.signCertificateRequest(
            csr,
            Subjects.withOrganization(csr.getSubject(), BuiltinRoles.GROUP_OPERATORS),
            LEAF_VALIDITY);
    pendingCsrStore.approve(requestId, signed);
    respondJson(
        exchange,
        200,
        csrResultToJson(
            CsrResult.approved(
                Pem.encodeCertificate(signed), Pem.encodeCertificate(ca.certificate()))));
  }

  private void handleBootstrapTokens(HttpExchange exchange) {
    try {
      if (!requireAuthorized(
          exchange, ResourceKind.BOOTSTRAP_TOKEN, Verb.WRITE, Optional.empty())) {
        return;
      }
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Map<String, Object> body = Json.asObject(Json.parse(readBody(exchange)));
      long ttlSeconds = ((Number) body.getOrDefault("ttlSeconds", 3600L)).longValue();
      String token = bootstrapTokenRegistry.issue(Duration.ofSeconds(ttlSeconds));
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("token", token);
      respondJson(exchange, 200, response);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("bootstrap token request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private static Optional<X509Certificate> peerCertificate(HttpExchange exchange) {
    if (!(exchange instanceof HttpsExchange httpsExchange)) {
      return Optional.empty();
    }
    try {
      Certificate[] certificates = httpsExchange.getSSLSession().getPeerCertificates();
      return certificates.length > 0 && certificates[0] instanceof X509Certificate x509
          ? Optional.of(x509)
          : Optional.empty();
    } catch (SSLPeerUnverifiedException e) {
      return Optional.empty();
    }
  }

  private static CsrSubmission csrSubmissionFromJson(Map<String, Object> json) {
    CsrPurpose purpose = CsrPurpose.valueOf((String) json.get("purpose"));
    String csrPem = (String) json.get("csrPem");
    Optional<String> bootstrapToken = Optional.ofNullable((String) json.get("bootstrapToken"));
    return new CsrSubmission(purpose, csrPem, bootstrapToken);
  }

  private static Map<String, Object> csrResultToJson(CsrResult result) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("status", result.status().name());
    result.requestId().ifPresent(id -> map.put("requestId", id));
    result.certificatePem().ifPresent(cert -> map.put("certificatePem", cert));
    result.caCertificatePem().ifPresent(ca -> map.put("caCertificatePem", ca));
    return map;
  }

  // ---- HTTP plumbing ----

  /**
   * {@code createHttpServer} sets {@code wantClientAuth} rather than {@code needClientAuth} (a
   * client certificate is optional at the TLS handshake, not enforced there) because {@code
   * HttpsConfigurator}/{@code HttpsParameters} negotiate per *connection*, before the HTTP request
   * (and therefore the path) is ever read -- there is no JDK API to make client-auth conditional on
   * which path is about to be requested. So every handler that needs an authenticated, *authorized*
   * identity enforces it itself, here, instead: {@code true} immediately in plaintext mode
   * (unchanged behavior, no enforcement, matching today's baseline -- see {@code
   * claudedocs/authn-authz-design.md} §7), else resolves a {@link Principal} from either a verified
   * peer certificate or a verified session cookie and checks it against {@link #authorizer}. Two
   * distinct status codes where the pre-RBAC {@code requireClientCertificate} this replaces only
   * ever wrote one: {@code 401} when there is no usable identity at all, {@code 403} when the
   * identity is known but lacks the permission. Writes the response itself on failure so every call
   * site can just {@code return} without duplicating it.
   */
  private boolean requireAuthorized(
      HttpExchange exchange,
      ResourceKind resource,
      Verb verb,
      Optional<String> tenant,
      Optional<String> targetId) {
    if (!(exchange instanceof HttpsExchange)) {
      return true;
    }
    Optional<Principal> principal = resolvePrincipal(exchange);
    if (principal.isEmpty()) {
      respondQuietly(exchange, 401, "authentication required");
      return false;
    }
    if (!authorizer.authorize(principal.get(), resource, verb, tenant, targetId)) {
      respondQuietly(exchange, 403, "forbidden");
      return false;
    }
    return true;
  }

  /** {@code targetId}-less convenience overload for the majority of call sites that need none. */
  private boolean requireAuthorized(
      HttpExchange exchange, ResourceKind resource, Verb verb, Optional<String> tenant) {
    return requireAuthorized(exchange, resource, verb, tenant, Optional.empty());
  }

  /**
   * A verified client certificate wins over a session cookie when both are somehow present (mTLS is
   * the stronger proof) -- in practice only one is ever offered by a given caller (the CLI/node
   * agents never send a session cookie, the console never presents a client certificate).
   */
  private Optional<Principal> resolvePrincipal(HttpExchange exchange) {
    Optional<X509Certificate> certificate = peerCertificate(exchange);
    if (certificate.isPresent()) {
      return Optional.of(principalFromCertificate(certificate.get()));
    }
    return sessionCookie(exchange)
        .flatMap(token -> SessionTokens.verify(token, sessionSigningKey))
        .map(username -> new Principal(username, Set.of()));
  }

  /**
   * {@code CN=} becomes the principal's name, every {@code O=} an entry in its groups -- see {@code
   * claudedocs/authn-authz-design.md} §2/§2a for why {@code O=} is trustworthy here: it is stamped
   * server-side at issuance ({@link #handleBootstrapCsrSubmit}), never taken verbatim from a
   * client's own CSR.
   */
  private static Principal principalFromCertificate(X509Certificate certificate) {
    X500Name subject = new X500Name(certificate.getSubjectX500Principal().getName());
    RDN[] commonNames = subject.getRDNs(BCStyle.CN);
    if (commonNames.length == 0) {
      throw new IllegalStateException("certificate subject carries no CN=: " + subject);
    }
    Set<String> groups = new LinkedHashSet<>();
    for (RDN rdn : subject.getRDNs(BCStyle.O)) {
      groups.add(rdn.getFirst().getValue().toString());
    }
    return new Principal(commonNames[0].getFirst().getValue().toString(), groups);
  }

  private static Optional<String> sessionCookie(HttpExchange exchange) {
    List<String> cookieHeaders = exchange.getRequestHeaders().get("Cookie");
    if (cookieHeaders == null) {
      return Optional.empty();
    }
    String prefix = SESSION_COOKIE_NAME + "=";
    for (String header : cookieHeaders) {
      for (String part : header.split(";")) {
        String trimmed = part.trim();
        if (trimmed.startsWith(prefix)) {
          return Optional.of(trimmed.substring(prefix.length()));
        }
      }
    }
    return Optional.empty();
  }

  /**
   * {@code HttpOnly} (never readable by the console's own JS -- an XSS in the SPA can't exfiltrate
   * it), {@code SameSite=Strict} (never attached to a request originating from another site -- a
   * CSRF mitigation, since auth here is cookie- not header-based), {@code Secure} only in TLS mode
   * (a plaintext connection can't set a cookie the browser would ever actually send back over
   * plaintext anyway). {@code maxAgeSeconds} of {@code 0} is how {@link #handleAuthLogout} clears
   * it.
   */
  private static String sessionCookieHeader(String token, long maxAgeSeconds) {
    StringBuilder header =
        new StringBuilder(SESSION_COOKIE_NAME)
            .append('=')
            .append(token)
            .append("; Path=/; HttpOnly; SameSite=Strict; Max-Age=")
            .append(maxAgeSeconds);
    if (TransportProtocol.fromConfig() == TransportProtocol.TLS) {
      header.append("; Secure");
    }
    return header.toString();
  }

  private static String pathSegmentAfter(HttpExchange exchange, String prefix) {
    String path = exchange.getRequestURI().getPath();
    return path.substring(prefix.length());
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    try (InputStream body = exchange.getRequestBody()) {
      return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void respondJson(HttpExchange exchange, int status, Object value)
      throws IOException {
    byte[] bytes = Json.write(value).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void respondQuietly(HttpExchange exchange, int status, String body) {
    try {
      respond(exchange, status, body);
    } catch (IOException e) {
      log.warn("failed to write error response: {}", e.getMessage());
    }
  }

  /**
   * A write that {@code storeClient} couldn't get any store endpoint to serve -- every configured
   * endpoint was tried, including one leader-follow retry against a {@code NotLeader} hint (design
   * doc §4.4/§4.6), before {@link GimleRaftException} was thrown. {@code 503}, not the pre-split
   * {@code 307} redirect: leader routing is now entirely internal to {@code StoreClient}, invisible
   * to the HTTP caller, so this process has no leader address left to redirect anyone to -- a
   * simplification of the client contract, not a lesser response, per the design doc's own framing.
   */
  private void respondStoreUnavailable(HttpExchange exchange) {
    respondQuietly(exchange, 503, "store temporarily unavailable; retry shortly");
  }
}
