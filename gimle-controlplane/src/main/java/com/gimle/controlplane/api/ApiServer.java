package com.gimle.controlplane.api;

import com.gimle.controlplane.admission.AdmissionChain;
import com.gimle.controlplane.admission.AdmissionDecision;
import com.gimle.controlplane.admission.PolicyConfigPlugin;
import com.gimle.controlplane.admission.TenantQuotaPlugin;
import com.gimle.controlplane.andvari.AndvariClient;
import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.controlplane.authz.BootstrapAccountFile;
import com.gimle.controlplane.fafnir.FafnirClient;
import com.gimle.controlplane.muninn.MuninnClient;
import com.gimle.controlplane.pki.BootstrapTokenRegistry;
import com.gimle.controlplane.pki.CaKeyMaterial;
import com.gimle.controlplane.pki.PendingCsrStore;
import com.gimle.controlplane.reconcile.CronJobReconciler;
import com.gimle.controlplane.service.ServiceEndpoint;
import com.gimle.controlplane.service.ServiceEndpointResolver;
import com.gimle.controlplane.service.ServiceRegistry;
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
import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.AuditEvent;
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
import com.gimle.core.session.SessionKeyFileManager;
import com.gimle.core.session.SessionTokens;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.core.throttle.LoginThrottle;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.core.vessel.VesselArtifacts;
import com.gimle.core.vessel.VesselEnvValue;
import com.gimle.core.vessel.VesselFileMount;
import com.gimle.core.vessel.VesselProbeSpec;
import com.gimle.core.vessel.VesselSpec;
import com.gimle.core.web.SpaStaticHandler;
import com.gimle.mimir.authz.Authorizer;
import com.gimle.mimir.manifest.AutoscalePolicy;
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.DisruptionBudget;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.ManifestParser;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.manifest.WorkloadSpec;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.JobRun;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.observability.ApiServerMetrics;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.OwnCertificateRotator;
import com.gimle.pki.Pem;
import com.gimle.pki.Subjects;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
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
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import javax.crypto.SecretKey;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.bouncycastle.asn1.x500.X500Name;
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
  // Generous on purpose: gimle-system hosts the platform's own self-hosted extensions, not a
  // workload a human sizes deployment-by-deployment, so these ceilings just need enough headroom
  // that an operator never has to tune them for ordinary platform-extension traffic.
  private static final ResourceQuota RESERVED_SYSTEM_TENANT_QUOTA =
      new ResourceQuota(64L * 1024 * 1024 * 1024, 32_000, 1000);

  private final StoreClient storeClient;
  // Constructed from storeClient alone (it implements both StoreReader and MutationSink) rather
  // than taking a constructor parameter -- CronJobReconciler is genuinely stateless beyond that,
  // so a fresh materialization decision per /cronjobs/{name}/trigger call needs no shared instance
  // with ControlPlaneMain's own scheduled-tick reconciler, only the same store.
  private final CronJobReconciler cronJobReconciler;
  // gimle-fafnir owns the master key ring and every encrypt/decrypt/rotate operation now -- this
  // client is a thin, pure-HTTP caller against it, replacing the in-process
  // SecretCipher/KeyFileManager/KeyRing calls this field's predecessor made directly.
  private final FafnirClient fafnirClient;
  // Nullable, unlike fafnirClient: Muninn's /logs/* fallback is
  // genuinely optional -- a cluster with none configured just keeps today's 404/502 behavior for
  // a gone node/instance, the same "opt in, degrade gracefully" posture gimle-agent's own
  // muninnEndpoint already has.
  private final MuninnClient muninnClient;
  // Never null (a null constructor argument collapses to ArtifactResolver.localOnly()): local
  // artifactPath references behave identically with or without a registry, and only
  // registry-coordinate manifests need the Andvari client this may or may not carry.
  private final ArtifactResolver artifactResolver;
  // Per-endpoint request/error/latency metrics, the same shape
  // FafnirServer's own metrics field already established -- see #instrument. Not exposed through
  // any public constructor parameter (no test/caller has ever needed to inject a custom
  // registry); a same-package test reads it back through #metrics().
  private final ApiServerMetrics metrics = new ApiServerMetrics();
  // The ordered admission chain a PUT /deployments submission runs through before it's proposed
  // to the store -- today the tenant-quota check {@code checkTenantQuota} used to perform inline,
  // plus {@code PolicyConfigPlugin}'s opt-in organization-specific rules -- a real extension point
  // new plugins can join without ApiServer growing another hardcoded check. Not exposed through any
  // public constructor parameter, same reasoning as {@code metrics} above: no test/caller has ever
  // needed to inject a custom plugin list. Built in the constructor body (not a field initializer)
  // because TenantQuotaPlugin needs this.artifactResolver, which instance field initializers run
  // before the constructor body assigns.
  private final AdmissionChain<DeploymentSpec> deploymentAdmissionChain;
  // Signs/verifies console session cookies -- deliberately a separate key from anything Fafnir
  // manages, for key separation between two unrelated crypto purposes (see SessionTokens' own
  // javadoc). Never rotated -- a session token's own short TTL already bounds its exposure window,
  // unlike a secret's config-entry value which persists indefinitely. Stays local to ApiServer
  // precisely because it's not secret-value material Fafnir's own security boundary is about.
  private final SecretKey sessionSigningKey;
  private final Authorizer authorizer;
  // Per-resource-kind opt-in for auditing READ decisions too -- WRITE/DELETE are
  // always audited (see #requireAuthorized), but a console page-load's worth of GETs would dwarf
  // the mutating-action volume by default, matching Kubernetes' own Metadata-level audit policy.
  // Empty (the default: no property set) reproduces that exact pre-existing behavior. Comma-
  // separated ResourceKind names, e.g.
  // "-Dgimle.controlplane.audit.readResourceKinds=CONFIG,SECRET".
  private final Set<ResourceKind> auditReadResourceKinds =
      parseAuditReadResourceKinds(
          System.getProperty("gimle.controlplane.audit.readResourceKinds", ""));
  // HTTP/1.1 explicitly: agents speak plain HttpServer-based HTTP/1.1, never HTTP/2, and pinning
  // avoids HttpClient spending a round trip on an upgrade negotiation that could never succeed.
  private final HttpClient agentHttpClient =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .build();
  private final BootstrapTokenRegistry bootstrapTokenRegistry = new BootstrapTokenRegistry();
  // See ServiceRegistry's own javadoc: specs persist through storeClient into the Raft-replicated
  // store, the same as every other resource kind here -- only its endpoint cache stays in-memory/
  // per-replica, the same non-durable posture loginThrottle below has. Shared with
  // ControlPlaneMain's
  // own ServiceReconciler via #serviceRegistry() the same way metrics() is shared, so both
  // read/write
  // the identical instance this replica's routes do.
  private final ServiceRegistry serviceRegistry;
  // Throttles /auth/login by username and by remote address independently -- see the
  // class's own javadoc for why in-memory/per-replica is the right call here, not a StateMutation.
  private final LoginThrottle loginThrottle = new LoginThrottle();
  private final PendingCsrStore pendingCsrStore = new PendingCsrStore();
  // Absent in plaintext mode, or in TLS mode when gimle.pki.caKeyFile isn't configured on this
  // node -- either way, /bootstrap/csr and its siblings simply aren't registered (see
  // #registerContexts). This node's CA key never rotates (only leaf certs do), so unlike
  // sslContextHolder-adjacent state this is loaded once and never reloaded.
  private final Optional<CertificateAuthority> certificateAuthority =
      loadCertificateAuthorityIfConfigured();

  // Not final: rotating this node's own leaf certificate needs to stop and rebuild the
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
   * Ephemeral session-signing key, never persisted -- fine for tests and any caller that doesn't
   * need session cookies to survive a restart, but not real deployments (see the four-argument
   * constructor). {@code fafnirClient} is still real either way -- it's Fafnir, not this
   * constructor's own key handling, that owns whether secret-value material persists.
   */
  public ApiServer(StoreClient storeClient, int port, FafnirClient fafnirClient)
      throws IOException {
    this(storeClient, port, ephemeralKeyPath(), fafnirClient);
  }

  /**
   * Same as the three-argument constructor, plus a real {@code muninnClient} for tests/callers that
   * want the {@code /logs/*} Muninn fallback exercised -- every other constructor here passes
   * {@code null}, matching {@code muninnClient}'s own field javadoc (a cluster with none configured
   * simply never attempts the fallback).
   */
  public ApiServer(
      StoreClient storeClient, int port, FafnirClient fafnirClient, MuninnClient muninnClient)
      throws IOException {
    this(storeClient, port, ephemeralKeyPath(), fafnirClient, muninnClient);
  }

  /**
   * {@code secretKeyFilePath} is only this replica's own persistent AES-256 session-signing key
   * anymore (generated on first run if absent) -- the secrets master key ring it used to also name
   * now lives entirely in Fafnir, reached through {@code fafnirClient}. {@code storeClient} is this
   * replica's already-constructed client against the store cluster -- unlike the pre-split {@code
   * RaftNode}-based constructors this replaces, there is no "auto-build a trivial single-node
   * store" convenience here: standing up even a single {@code StoreNode} requires a real listener,
   * which is the caller's job (production: {@code ControlPlaneMain}; tests: a small in-process
   * fixture spinning up exactly one).
   */
  public ApiServer(
      StoreClient storeClient, int port, Path secretKeyFilePath, FafnirClient fafnirClient)
      throws IOException {
    this(storeClient, port, secretKeyFilePath, fafnirClient, null);
  }

  /** Same as the four-argument constructor, plus a real {@code muninnClient} -- see above. */
  public ApiServer(
      StoreClient storeClient,
      int port,
      Path secretKeyFilePath,
      FafnirClient fafnirClient,
      MuninnClient muninnClient)
      throws IOException {
    this(storeClient, port, secretKeyFilePath, fafnirClient, muninnClient, null);
  }

  /**
   * Same as the five-argument constructor, plus the {@code artifactResolver} a control plane with a
   * configured {@code --andvari-endpoint} passes -- {@code null} (or {@link
   * ArtifactResolver#localOnly()}) means registry-coordinate manifests are rejected at admission,
   * while plain {@code artifactPath} manifests behave identically either way.
   */
  public ApiServer(
      StoreClient storeClient,
      int port,
      Path secretKeyFilePath,
      FafnirClient fafnirClient,
      MuninnClient muninnClient,
      ArtifactResolver artifactResolver)
      throws IOException {
    this(
        storeClient,
        port,
        fafnirClient,
        muninnClient,
        artifactResolver,
        SessionKeyFileManager.loadOrCreate(secretKeyFilePath.resolveSibling("session.key")));
  }

  private ApiServer(
      StoreClient storeClient,
      int port,
      FafnirClient fafnirClient,
      MuninnClient muninnClient,
      ArtifactResolver artifactResolver,
      SecretKey sessionSigningKey)
      throws IOException {
    this.storeClient = storeClient;
    this.cronJobReconciler = new CronJobReconciler(storeClient, storeClient);
    this.serviceRegistry = new ServiceRegistry(storeClient, storeClient);
    this.fafnirClient = fafnirClient;
    this.muninnClient = muninnClient;
    this.artifactResolver =
        artifactResolver == null ? ArtifactResolver.localOnly() : artifactResolver;
    this.deploymentAdmissionChain =
        new AdmissionChain<>(
            List.of(new TenantQuotaPlugin(this.artifactResolver), new PolicyConfigPlugin()));
    this.sessionSigningKey = sessionSigningKey;
    this.authorizer = new Authorizer(storeClient);
    seedReservedSystemTenantIfAbsent();
    this.server = createHttpServer(port);
    this.boundPort = server.getAddress().getPort();
    registerContexts(server);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Ensures {@link Tenant#RESERVED_SYSTEM_TENANT_ID} exists as a real, persisted row before any
   * request is ever served -- never through the {@code /tenants/*} HTTP path itself, so {@link
   * #rejectIfReservedSystemTenant} can reject every non-operator caller there with no "but let the
   * bootstrap request through" carve-out. Check-then-propose, not unconditional: a later restart of
   * this same replica must not clobber a quota an operator has since adjusted through the ordinary
   * API.
   */
  private void seedReservedSystemTenantIfAbsent() {
    if (storeClient.getTenant(Tenant.RESERVED_SYSTEM_TENANT_ID).isPresent()) {
      return;
    }
    storeClient.propose(
        new StateMutation.PutTenant(
            new Tenant(Tenant.RESERVED_SYSTEM_TENANT_ID, RESERVED_SYSTEM_TENANT_QUOTA)));
  }

  private void registerContexts(HttpServer target) throws IOException {
    target.createContext("/deployments/", instrument("deployments", this::handleDeployment));
    target.createContext("/deployments", instrument("deployments", this::handleDeploymentsList));
    target.createContext("/jobs/", instrument("jobs", this::handleJob));
    target.createContext("/jobs", instrument("jobs", this::handleJobsList));
    target.createContext("/cronjobs/", instrument("cronjobs", this::handleCronJob));
    target.createContext("/cronjobs", instrument("cronjobs", this::handleCronJobsList));
    target.createContext("/daemonsets/", instrument("daemonsets", this::handleDaemonSet));
    target.createContext("/daemonsets", instrument("daemonsets", this::handleDaemonSetsList));
    target.createContext("/statefulsets/", instrument("statefulsets", this::handleStatefulSet));
    target.createContext("/statefulsets", instrument("statefulsets", this::handleStatefulSetsList));
    target.createContext("/endpoints/", instrument("endpoints", this::handleEndpoints));
    target.createContext("/services/", instrument("services", this::handleService));
    target.createContext("/services", instrument("services", this::handleServicesCollection));
    target.createContext("/metrics", instrument("metrics", this::handleMetrics));
    target.createContext("/events", instrument("events", this::handleEvents));
    target.createContext("/audit", instrument("audit", this::handleAudit));
    target.createContext("/nodes/", instrument("nodes", this::handleNode));
    target.createContext("/nodes", instrument("nodes", this::handleNodesList));
    target.createContext("/tenants/", instrument("tenants", this::handleTenant));
    target.createContext("/tenants", instrument("tenants", this::handleTenantsList));
    target.createContext("/config/", instrument("config", this::handleConfig));
    target.createContext("/logs/", instrument("logs", this::handleLogs));
    target.createContext(
        "/metrics-history/", instrument("metrics-history", this::handleMetricsHistory));
    target.createContext(
        "/traces-history/", instrument("traces-history", this::handleTracesHistory));
    target.createContext("/roles/", instrument("roles", this::handleRole));
    target.createContext("/roles", instrument("roles", this::handleRolesList));
    target.createContext("/rolebindings/", instrument("rolebindings", this::handleRoleBinding));
    target.createContext("/rolebindings", instrument("rolebindings", this::handleRoleBindingsList));
    target.createContext("/accounts/", instrument("accounts", this::handleAccount));
    target.createContext("/accounts", instrument("accounts", this::handleAccountsList));
    target.createContext(
        "/secrets/rotate-key", instrument("secrets-rotate-key", this::handleRotateSecretsKey));
    target.createContext("/secrets/", instrument("secrets", this::handleSecretsProxy));
    // One bare-prefix context with an in-handler path check rather than "/artifacts/" +
    // "/artifacts" pair: the catalog listing lives at the bare path, and the JDK server's
    // prefix matching would otherwise let "/artifactsX" through -- same defense AndvariServer's
    // own routing documents.
    target.createContext("/artifacts", instrument("artifacts", this::handleArtifactsProxy));
    target.createContext("/auth/login", instrument("auth-login", this::handleAuthLogin));
    target.createContext("/auth/logout", instrument("auth-logout", this::handleAuthLogout));
    target.createContext("/auth/session", instrument("auth-session", this::handleAuthSession));
    if (certificateAuthority.isPresent()) {
      target.createContext(
          "/bootstrap/csr", instrument("bootstrap-csr", this::handleBootstrapCsrSubmit));
      target.createContext(
          "/bootstrap/csr/", instrument("bootstrap-csr", this::handleBootstrapCsrSubResource));
      target.createContext(
          "/bootstrap/tokens", instrument("bootstrap-tokens", this::handleBootstrapTokens));
    }
    if (consoleStaticRoot.isPresent()) {
      registerConsole(target, consoleStaticRoot.get());
    }
  }

  /**
   * Wraps a handler with request-count/latency/error Micrometer recording, at context-registration
   * time rather than inside each handler body -- the identical pattern {@code
   * FafnirServer.instrument} already established, copied rather than shared since the two classes
   * have no common base to hang it on. {@code error} is read from the exchange's own response code
   * after the delegate finishes, not from an escaping exception -- every handler here already sends
   * a real status and closes the exchange itself.
   */
  private HttpHandler instrument(String endpoint, HttpHandler delegate) {
    return exchange -> {
      String verb = exchange.getRequestMethod();
      long startNanos = System.nanoTime();
      try {
        delegate.handle(exchange);
      } finally {
        Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
        int status = exchange.getResponseCode();
        boolean error = status <= 0 || status >= 400;
        metrics.recordRequest(endpoint, verb, latency, error);
      }
    };
  }

  /**
   * Public so {@code ControlPlaneMain} can hand this registry to a {@code MuninnShipper} when
   * {@code --muninn-endpoint} is configured, and so a same-package test can assert on it directly
   * -- see {@link #metrics}'s own field javadoc for why this isn't a constructor parameter instead.
   */
  public ApiServerMetrics metrics() {
    return metrics;
  }

  /**
   * Public so {@code ControlPlaneMain} can hand this same registry to a {@code ServiceReconciler}
   * ticking alongside every other reconciler -- see {@link #serviceRegistry}'s own field javadoc
   * for why this isn't a constructor parameter instead.
   */
  public ServiceRegistry serviceRegistry() {
    return serviceRegistry;
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
    target.createContext("/console", new SpaStaticHandler(staticRoot, shellFileName));
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
   * {@link HttpsServer} instead -- the JDK-bundled, direct drop-in, the smallest, lowest-risk
   * change in the whole TLS rollout. {@code wantClientAuth}, not {@code needClientAuth}: {@link
   * HttpsConfigurator}/{@link HttpsParameters} negotiate once per *connection*, before the HTTP
   * request path is ever read, so there's no way to make client-auth conditional on path at this
   * layer -- every handler enforces it itself instead, via {@link #requireAuthorized}, except the
   * deliberately bootstrap-token-authenticated {@code /bootstrap/csr} endpoints.
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
   * The hot-swap point for rotating this node's own leaf certificate: there is no supported way to
   * swap key material into an already-running {@link HttpsServer} (see the field javadoc on {@link
   * #server}), so this stops the current one and rebuilds a fresh {@link HttpsServer} bound to the
   * same {@link #boundPort} from whatever certificate material now sits at {@code
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
   * leader-gated -- a follower needs its own cert fresh too). Delegates the actual
   * check-and-rotate-over-mTLS logic to {@link OwnCertificateRotator}, shared with {@code
   * StoreMain} once the store-extraction split needed the identical mechanism for a second caller
   * -- this method's own job is just knowing *where* to submit the rotation CSR (its own loopback
   * {@code /bootstrap/csr}, since this process is the CA-signing authority itself) and reloading
   * its own {@code HttpsServer} afterward. No-op in plaintext mode. Returns {@code true} iff a
   * rotation actually happened this call -- other listener-owning components ({@code
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
   * already follows the store's current leader internally, and *which* {@code ApiServer} replica
   * calls this at all is the caller's lease-based election to decide, not a concern of the method
   * being called.
   */
  public void seedBootstrapAccountIfNeeded() {
    if (!storeClient.listAccounts().isEmpty()) {
      return;
    }
    BootstrapAccountFile.loadIfConfigured()
        .ifPresent(account -> storeClient.propose(new StateMutation.PutAccount(account)));
  }

  // ---- /deployments/{name} ----

  /**
   * Tenant-scoped for every verb here, resolved by {@link #dispatchResourceRequest} rather than
   * hardcoded {@code Optional.empty()}: GET/DELETE authorize against the resource's own currently
   * stored {@code tenantId} (via {@code existingTenant}), and PUT authorizes against the submitted
   * manifest's own {@code tenantId} -- so a permission scoped to one tenant now actually authorizes
   * that tenant's workloads, not just cluster-wide grants. Every {@code handle{Deployment,Job,
   * CronJob,DaemonSet,StatefulSet}} singleton-resource handler shares this same posture.
   */
  private void handleDeployment(HttpExchange exchange) {
    dispatchResourceRequest(
        exchange,
        ResourceKind.DEPLOYMENT,
        "missing deployment name",
        "deployment",
        ex -> Optional.of(pathSegmentAfter(ex, "/deployments/")),
        name -> storeClient.getDeployment(name).map(DeploymentSpec::tenantId),
        this::handlePutDeployment,
        this::handleGetDeployment,
        this::handleDeleteDeployment);
  }

  /** A {@code (HttpExchange, String name)} action that may itself throw {@link IOException}. */
  @FunctionalInterface
  private interface ResourceAction {
    void run(HttpExchange exchange, String name) throws IOException;
  }

  /**
   * A PUT action that receives the manifest {@link #dispatchResourceRequest} already parsed --
   * once, to resolve the tenant to authorize the write against -- rather than re-reading {@code
   * exchange}'s own request body itself, which can only be consumed once.
   */
  @FunctionalInterface
  private interface PutResourceAction {
    void run(HttpExchange exchange, String name, WorkloadSpec submitted) throws IOException;
  }

  /**
   * Resolves an existing resource's own {@code tenantId} by name -- {@code Optional.empty()} only
   * when no such resource currently exists at all, {@code Optional.of(Optional.empty())} when it
   * exists but is untenanted. Distinguishing those two is exactly what lets a PUT's re-tenanting
   * guard (see {@link #dispatchResourceRequest}) apply only when there is an actual existing tenant
   * assignment to protect -- never to a brand-new resource being created for the first time, which
   * has no "existing tenant" to check at all.
   */
  @FunctionalInterface
  private interface TenantLookup {
    Optional<Optional<String>> lookup(String name);
  }

  /**
   * A resource-name resolver that may itself throw {@link IOException} (a submitted manifest read
   * mid-resolution) and may fully handle the request itself rather than returning a name to
   * dispatch on -- see {@link #dispatchResourceRequest}'s own javadoc.
   */
  @FunctionalInterface
  private interface ResourceNameResolver {
    Optional<String> resolve(HttpExchange exchange) throws IOException;
  }

  /**
   * Shared by every {@code handle{Deployment,Job,CronJob,DaemonSet,StatefulSet}} singleton-
   * resource handler above and below: resolves the resource name via {@code nameResolver},
   * authorizes and dispatches PUT/GET/DELETE to the three per-kind handlers, and applies the one
   * error-mapping/exchange-close policy every one of these routes shares. {@code nameResolver} may
   * itself fully handle the request -- the {@code /cronjobs/{name}/trigger} sub-route is not a
   * plain PUT/GET/DELETE-by-name at all -- in which case it returns {@code Optional.empty()} to
   * signal "already handled, skip the switch below" rather than a resolved (possibly blank) name.
   *
   * <p>PUT parses the submitted manifest here, before authorizing -- the only way to know which
   * tenant to check a write against is to look at what the write itself declares, the same
   * body-before-authorize order {@code handleConfigEntry}'s PUT already uses for its own {@code
   * encrypted}-flag-dependent resource kind. Authorization is then two checks, not one, when a PUT
   * would change an existing resource's tenant: the caller needs write access under the *submitted*
   * tenant (creation, or an update keeping the same tenant) and, only if that differs from what is
   * currently stored, write access under the *existing* tenant too -- otherwise a grant scoped to
   * one tenant could silently steal a resource out of another tenant it has no write access to, or
   * abandon one into a tenant it has no write access to place it in.
   */
  private void dispatchResourceRequest(
      HttpExchange exchange,
      ResourceKind kind,
      String missingNameMessage,
      String requestNoun,
      ResourceNameResolver nameResolver,
      TenantLookup existingTenant,
      PutResourceAction put,
      ResourceAction get,
      ResourceAction delete) {
    try {
      Optional<String> resolved = nameResolver.resolve(exchange);
      if (resolved.isEmpty()) {
        return;
      }
      String name = resolved.get();
      if (name.isBlank()) {
        respond(exchange, 400, missingNameMessage);
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "PUT" -> {
          WorkloadSpec submitted = ManifestParser.parse(exchange.getRequestBody());
          Optional<String> submittedTenant = submitted.tenantId();
          Optional<Optional<String>> existing = existingTenant.lookup(name);
          boolean authorized = requireAuthorized(exchange, kind, Verb.WRITE, submittedTenant);
          if (authorized && existing.isPresent() && !existing.get().equals(submittedTenant)) {
            authorized = requireAuthorized(exchange, kind, Verb.WRITE, existing.get());
          }
          if (authorized && !rejectIfReservedSystemTenant(exchange, submittedTenant)) {
            put.run(exchange, name, submitted);
          }
        }
        case "GET" -> {
          Optional<String> tenant = existingTenant.lookup(name).orElse(Optional.empty());
          if (requireAuthorized(exchange, kind, Verb.READ, tenant)) {
            get.run(exchange, name);
          }
        }
        case "DELETE" -> {
          Optional<String> tenant = existingTenant.lookup(name).orElse(Optional.empty());
          if (requireAuthorized(exchange, kind, Verb.DELETE, tenant)) {
            delete.run(exchange, name);
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (GimleManifestException | IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("{} request failed: {}", requestNoun, e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handlePutDeployment(HttpExchange exchange, String name, WorkloadSpec parsed)
      throws IOException {
    // Parsed and kind-checked at the real operator-facing admission surface by ManifestParser,
    // already done once by dispatchResourceRequest to resolve the tenant to authorize against;
    // DeploymentManifestParser itself stays kind-agnostic (see its own updated javadoc), still used
    // directly only by StateStore's own reload-on-restart path and this class's parser-shape unit
    // tests.
    if (!(parsed instanceof DeploymentSpec parsedSpec)) {
      respond(
          exchange,
          400,
          "manifest kind does not match /deployments route (expected kind:" + " Deployment)");
      return;
    }
    if (!parsedSpec.name().equals(name)) {
      respond(
          exchange,
          400,
          "manifest name '" + parsedSpec.name() + "' does not match URL path '" + name + "'");
      return;
    }
    // Computed here, once, regardless of tenancy -- never trusted from the submitted
    // manifest itself (DeploymentManifestParser only parses artifactSha256 back out of StateStore's
    // own previously-written YAML on reload, never treats a caller-supplied value as
    // authoritative).
    // Optional.empty() if the artifact is unreadable at admission time, the same tolerant posture
    // untenanted deployments already had before this field existed -- DeploymentReconciler still
    // catches an unreadable artifact every tick regardless.
    AdmissionArtifact admitted =
        admissionArtifact(parsedSpec.artifactPath(), parsedSpec.moduleId(), parsedSpec.vessel());
    if (admitted.rejection().isPresent()) {
      respond(exchange, 400, admitted.rejection().get());
      return;
    }
    DeploymentSpec spec = withArtifactSha256(parsedSpec, admitted.sha256());
    AdmissionDecision<DeploymentSpec> decision =
        deploymentAdmissionChain.admit(
            ResourceKind.DEPLOYMENT, Verb.WRITE, spec, storeClient, admitted.artifact());
    switch (decision) {
      case AdmissionDecision.Reject<DeploymentSpec> reject ->
          respond(exchange, 409, reject.reason());
      case AdmissionDecision.Allow<DeploymentSpec> allow -> {
        storeClient.propose(new StateMutation.PutDeployment(allow.spec()));
        respond(exchange, 200, "ok");
      }
    }
  }

  private static Optional<ModuleArtifact> readArtifactIfPossible(
      String artifactPath, ModuleId moduleId, Optional<VesselSpec> vessel) {
    try {
      return Optional.of(
          vessel.isPresent()
              ? VesselArtifacts.readVesselArtifact(Path.of(artifactPath), moduleId, vessel.get())
              : ModuleArtifactReader.read(Path.of(artifactPath)));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * Admission-time artifact processing shared by every workload kind. A local {@code artifactPath}
   * keeps the historical tolerant posture exactly: unreadable means "admit with no recorded
   * digest," and the reconcilers keep catching it every tick. A registry-coordinate reference is
   * checked against Andvari instead, with three deliberately distinct outcomes: definitively absent
   * rejects the manifest (a typo'd version should fail loudly at submit time, not sit unplaceable
   * forever), an unreachable registry admits with no recorded digest (level-triggered -- the
   * reconcilers converge once it's back), and present records the registry's own digest while
   * best-effort pulling the jar through the local cache so the tenant-quota plugin still gets a
   * descriptor to charge against.
   */
  /**
   * {@code vessel}, when present, routes the local-path/registry-coordinate read through {@link
   * VesselArtifacts} instead of {@link ModuleArtifactReader} -- see {@link
   * ArtifactResolver#resolve(String, ModuleId, Optional)}'s own javadoc for why this is the one
   * place every admission path needs to know a spec is vessel-hosted, and nowhere else does.
   */
  private AdmissionArtifact admissionArtifact(
      String artifactPath, ModuleId moduleId, Optional<VesselSpec> vessel) {
    if (ArtifactReference.isLocalPath(artifactPath)) {
      Optional<ModuleArtifact> artifact = readArtifactIfPossible(artifactPath, moduleId, vessel);
      return new AdmissionArtifact(
          Optional.empty(), artifact, artifact.map(ModuleArtifact::sha256));
    }
    Optional<AndvariClient> registry = artifactResolver.registryClient();
    if (registry.isEmpty()) {
      return AdmissionArtifact.rejection(
          "manifest omits artifactPath, which resolves module "
              + moduleId.name()
              + ":"
              + moduleId.version()
              + " from the artifact registry -- but this control plane has no --andvari-endpoint"
              + " configured");
    }
    return switch (registry.get().head(moduleId)) {
      case AndvariClient.HeadOutcome.NotFound ignored ->
          AdmissionArtifact.rejection(
              "artifact "
                  + moduleId.name()
                  + ":"
                  + moduleId.version()
                  + " is not in the artifact registry; push it first (gimle artifact push)");
      case AndvariClient.HeadOutcome.Unreachable unreachable -> {
        log.warn(
            "artifact registry unreachable at admission for {}:{} ({}); admitting with no"
                + " recorded digest",
            moduleId.name(),
            moduleId.version(),
            unreachable.reason());
        yield new AdmissionArtifact(Optional.empty(), Optional.empty(), Optional.empty());
      }
      case AndvariClient.HeadOutcome.Found found ->
          new AdmissionArtifact(
              Optional.empty(),
              artifactResolver.resolveIfPossible(artifactPath, moduleId, vessel),
              Optional.of(found.sha256()));
    };
  }

  /**
   * One admission artifact decision: a rejection message, or the artifact/digest pair to record.
   */
  private record AdmissionArtifact(
      Optional<String> rejection, Optional<ModuleArtifact> artifact, Optional<String> sha256) {

    static AdmissionArtifact rejection(String reason) {
      return new AdmissionArtifact(Optional.of(reason), Optional.empty(), Optional.empty());
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
        sha256,
        spec.disruption(),
        spec.vessel());
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

  // ---- /services, /services/{name}, /services/{name}/endpoints ----

  /**
   * {@code POST /services} (create/replace by the name the submitted body carries) and {@code GET
   * /services} (list every one) -- collected onto the bare {@code /services} context the same way
   * {@code /deployments} separates its own collection route from {@code /deployments/{name}}'s
   * per-resource one. {@code POST}, not {@code PUT}, because a {@link ServiceSpec} names itself in
   * the request body rather than the URL path -- unlike every {@code WorkloadSpec} kind, which
   * travels as a YAML manifest {@link ManifestParser} already dispatches on {@code kind:}, a
   * Service travels as plain JSON (matching this class's own "hand-rolled JSON for traffic that
   * isn't an operator-facing manifest" convention) since it isn't a {@code WorkloadSpec} itself.
   */
  private void handleServicesCollection(HttpExchange exchange) {
    try {
      switch (exchange.getRequestMethod()) {
        case "POST" -> handlePostService(exchange);
        case "GET" -> handleServicesList(exchange);
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("services request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * Same two-check re-tenanting guard {@link #dispatchResourceRequest}'s own PUT branch applies: a
   * caller needs write access under the submitted tenant, and, only if a same-named Service already
   * exists under a different tenant, write access under that existing tenant too -- otherwise a
   * grant scoped to one tenant could silently steal a Service out of another it has no access to.
   * Field validation itself is entirely {@link ServiceSpec}'s own constructor's job; an {@link
   * IllegalArgumentException} it throws is caught by {@link #handleServicesCollection} and mapped
   * to 400, the same mapping {@link #dispatchResourceRequest} gives every other kind's own manifest
   * validation failure.
   */
  private void handlePostService(HttpExchange exchange) throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    String name = (String) body.get("name");
    if (name == null || name.isBlank()) {
      respond(exchange, 400, "missing service name");
      return;
    }
    Optional<String> tenantId =
        body.get("tenantId") instanceof String s ? Optional.of(s) : Optional.empty();
    Set<String> deploymentNames = new LinkedHashSet<>();
    if (body.get("deploymentNames") instanceof List<?> rawNames) {
      for (Object rawName : rawNames) {
        deploymentNames.add(String.valueOf(rawName));
      }
    }
    int port = ((Number) body.get("port")).intValue();
    int targetPort = body.get("targetPort") instanceof Number n ? n.intValue() : port;

    Optional<Optional<String>> existingTenant =
        serviceRegistry.get(name).map(ServiceSpec::tenantId);
    boolean authorized = requireAuthorized(exchange, ResourceKind.SERVICE, Verb.WRITE, tenantId);
    if (authorized && existingTenant.isPresent() && !existingTenant.get().equals(tenantId)) {
      authorized =
          requireAuthorized(exchange, ResourceKind.SERVICE, Verb.WRITE, existingTenant.get());
    }
    if (authorized && !rejectIfReservedSystemTenant(exchange, tenantId)) {
      ServiceSpec spec = new ServiceSpec(name, tenantId, deploymentNames, port, targetPort);
      serviceRegistry.put(spec);
      respond(exchange, 200, "ok");
    }
  }

  /** Every Service, in the same shape {@link #handleGetService} returns for one. */
  private void handleServicesList(HttpExchange exchange) throws IOException {
    if (!requireAuthorized(exchange, ResourceKind.SERVICE, Verb.READ, Optional.empty())) {
      return;
    }
    if (!"GET".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    respondJson(
        exchange, 200, serviceRegistry.list().stream().map(ApiServer::serviceToJson).toList());
  }

  /**
   * {@code GET}/{@code DELETE /services/{name}}, plus the {@code /services/{name}/endpoints}
   * sub-route -- resolved the same way {@code /cronjobs/{name}/trigger} carries a second path
   * segment under one context.
   */
  private void handleService(HttpExchange exchange) {
    try {
      String tail = pathSegmentAfter(exchange, "/services/");
      int slash = tail.indexOf('/');
      String name = slash < 0 ? tail : tail.substring(0, slash);
      if (name.isBlank()) {
        respond(exchange, 400, "missing service name");
        return;
      }
      if (slash >= 0) {
        String subResource = tail.substring(slash + 1);
        if (!"endpoints".equals(subResource)) {
          respond(exchange, 404, "unknown service endpoint: " + subResource);
          return;
        }
        handleServiceEndpoints(exchange, name);
        return;
      }
      Optional<String> tenant =
          serviceRegistry.get(name).map(ServiceSpec::tenantId).orElse(Optional.empty());
      switch (exchange.getRequestMethod()) {
        case "GET" -> {
          if (requireAuthorized(exchange, ResourceKind.SERVICE, Verb.READ, tenant)) {
            handleGetService(exchange, name);
          }
        }
        case "DELETE" -> {
          if (requireAuthorized(exchange, ResourceKind.SERVICE, Verb.DELETE, tenant)) {
            serviceRegistry.remove(name);
            respond(exchange, 200, "ok");
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("service request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleGetService(HttpExchange exchange, String name) throws IOException {
    Optional<ServiceSpec> spec = serviceRegistry.get(name);
    if (spec.isEmpty()) {
      respond(exchange, 404, "no such service: " + name);
      return;
    }
    respondJson(exchange, 200, serviceToJson(spec.get()));
  }

  /**
   * Computed live off the current store snapshot via {@link ServiceEndpointResolver}, exactly like
   * {@code GET /endpoints/{name}} already does for every other workload kind -- never served from
   * {@code ServiceRegistry}'s own reconciler-populated cache, so a caller never sees a result stale
   * by up to one reconcile interval. An empty {@code endpoints} array is a valid 200, not a 404 --
   * "no live backing instance yet" is a normal transient state as long as the Service itself
   * exists.
   */
  private void handleServiceEndpoints(HttpExchange exchange, String name) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    Optional<ServiceSpec> spec = serviceRegistry.get(name);
    if (spec.isEmpty()) {
      respond(exchange, 404, "no such service: " + name);
      return;
    }
    if (!authorizeEndpointsRead(exchange, ResourceKind.SERVICE, spec.get().tenantId())) {
      return;
    }
    respondJson(exchange, 200, serviceEndpointsToJson(spec.get()));
  }

  private Map<String, Object> serviceEndpointsToJson(ServiceSpec spec) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", spec.name());
    map.put("port", spec.port());
    map.put("targetPort", spec.targetPort());
    List<Map<String, Object>> endpoints = new ArrayList<>();
    for (ServiceEndpoint endpoint : ServiceEndpointResolver.resolve(storeClient, spec)) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("host", endpoint.host());
      entry.put("port", endpoint.port());
      endpoints.add(entry);
    }
    map.put("endpoints", endpoints);
    return map;
  }

  private static Map<String, Object> serviceToJson(ServiceSpec spec) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", spec.name());
    spec.tenantId().ifPresent(tenantId -> map.put("tenantId", tenantId));
    map.put("deploymentNames", List.copyOf(spec.deploymentNames()));
    map.put("port", spec.port());
    map.put("targetPort", spec.targetPort());
    return map;
  }

  // ---- /jobs/{name}, /jobs ----

  private void handleJob(HttpExchange exchange) {
    dispatchResourceRequest(
        exchange,
        ResourceKind.JOB,
        "missing job name",
        "job",
        ex -> Optional.of(pathSegmentAfter(ex, "/jobs/")),
        name -> storeClient.getJobSpec(name).map(JobSpec::tenantId),
        this::handlePutJob,
        this::handleGetJob,
        this::handleDeleteJob);
  }

  private void handlePutJob(HttpExchange exchange, String name, WorkloadSpec parsed)
      throws IOException {
    if (!(parsed instanceof JobSpec parsedSpec)) {
      respond(exchange, 400, "manifest kind does not match /jobs route (expected kind: Job)");
      return;
    }
    if (!parsedSpec.name().equals(name)) {
      respond(
          exchange,
          400,
          "manifest name '" + parsedSpec.name() + "' does not match URL path '" + name + "'");
      return;
    }
    // Same reasoning as handlePutDeployment's own identical step: never trusted
    // from the submitted manifest, always recomputed server-side at admission.
    AdmissionArtifact admitted =
        admissionArtifact(parsedSpec.artifactPath(), parsedSpec.moduleId(), parsedSpec.vessel());
    if (admitted.rejection().isPresent()) {
      respond(exchange, 400, admitted.rejection().get());
      return;
    }
    JobSpec spec = withArtifactSha256(parsedSpec, admitted.sha256());
    // No tenant-quota check here (unlike handlePutDeployment's admission chain): TenantUsage's
    // accounting model is deployment-replica-shaped (resourceRequest * replicas) and has no Job
    // equivalent yet -- a tenanted Job is accepted regardless of that tenant's quota today, a
    // real, undocumented-elsewhere gap worth flagging here rather than silently matching
    // handlePutDeployment's shape without actually doing the check.
    storeClient.propose(new StateMutation.PutJobSpec(spec));
    respond(exchange, 200, "ok");
  }

  private static JobSpec withArtifactSha256(JobSpec spec, Optional<String> sha256) {
    return new JobSpec(
        spec.name(),
        spec.moduleId(),
        spec.artifactPath(),
        spec.placement(),
        spec.activeDeadline(),
        spec.backoffLimit(),
        spec.tenantId(),
        sha256,
        spec.vessel());
  }

  private void handleGetJob(HttpExchange exchange, String name) throws IOException {
    Optional<JobSpec> spec = storeClient.getJobSpec(name);
    if (spec.isEmpty()) {
      respond(exchange, 404, "no such job: " + name);
      return;
    }
    respondJson(exchange, 200, jobStatus(spec.get()));
  }

  private void handleDeleteJob(HttpExchange exchange, String name) throws IOException {
    storeClient.propose(new StateMutation.RemoveJobSpec(name));
    respond(exchange, 200, "ok");
  }

  /** Every job, in the same shape {@link #handleGetJob} returns for one. */
  private void handleJobsList(HttpExchange exchange) {
    try {
      if (!requireAuthorized(exchange, ResourceKind.JOB, Verb.READ, Optional.empty())) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(exchange, 200, storeClient.listJobSpecs().stream().map(this::jobStatus).toList());
    } catch (IOException | RuntimeException e) {
      log.warn("jobs list request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private Map<String, Object> jobStatus(JobSpec spec) {
    Map<String, Object> specMap = new LinkedHashMap<>();
    specMap.put("name", spec.name());
    specMap.put("moduleId", moduleIdToJson(spec.moduleId()));
    specMap.put("artifactPath", spec.artifactPath());
    spec.activeDeadline().ifPresent(d -> specMap.put("activeDeadlineSeconds", d.toSeconds()));
    specMap.put("backoffLimit", spec.backoffLimit());
    spec.tenantId().ifPresent(tenantId -> specMap.put("tenantId", tenantId));
    spec.vessel().ifPresent(v -> specMap.put("vessel", vesselToJson(v)));

    Map<String, Object> status = new LinkedHashMap<>();
    status.put("spec", specMap);
    // "RUNNING" mirrors JobPhase's own default (StateStore#getJobPhase's javadoc: absent means
    // not yet terminal) -- a job with no explicit phase recorded yet is running, not stateless.
    status.put("phase", storeClient.getJobPhase(spec.name()).map(Enum::name).orElse("RUNNING"));

    storeClient.listJobRunsFor(spec.name()).stream()
        .max(Comparator.comparingInt(JobRun::attempt))
        .ifPresent(
            run -> {
              Map<String, Object> runMap = new LinkedHashMap<>();
              runMap.put("attempt", run.attempt());
              runMap.put("nodeId", run.nodeId());
              findObservationForJobRun(run)
                  .ifPresent(obs -> runMap.put("observation", observationToJson(obs)));
              status.put("currentRun", runMap);
            });
    return status;
  }

  private Optional<InstanceObservation> findObservationForJobRun(JobRun run) {
    return findObservation(
        run.nodeId(),
        obs -> obs.deploymentName().equals(run.jobName()) && obs.instanceIndex() == run.attempt());
  }

  // ---- /cronjobs/{name}, /cronjobs, /cronjobs/{name}/trigger ----

  private void handleCronJob(HttpExchange exchange) {
    dispatchResourceRequest(
        exchange,
        ResourceKind.JOB,
        "missing cronjob name",
        "cronjob",
        this::resolveCronJobNameOrHandleSubRoute,
        this::cronJobTenant,
        this::handlePutCronJob,
        this::handleGetCronJob,
        this::handleDeleteCronJob);
  }

  private Optional<Optional<String>> cronJobTenant(String name) {
    return storeClient.getCronJobSpec(name).map(CronJobSpec::tenantId);
  }

  /**
   * {@code /cronjobs/{name}} name resolution is one segment, exactly like {@link
   * #handleDeployment}'s own {@code pathSegmentAfter} call -- except a CronJob path may carry a
   * second segment, {@code /cronjobs/{name}/trigger}, which isn't a plain PUT/GET/DELETE-by-name at
   * all. A blank name is returned rather than handled here (regardless of whether a second segment
   * follows it) so {@link #dispatchResourceRequest}'s own blank-name check reports it the same way
   * every other resource kind's does; a present, non-blank second segment is handled entirely here,
   * returning {@code Optional.empty()} to tell the caller "already handled, skip the ordinary
   * dispatch."
   */
  private Optional<String> resolveCronJobNameOrHandleSubRoute(HttpExchange exchange)
      throws IOException {
    String tail = pathSegmentAfter(exchange, "/cronjobs/");
    int slash = tail.indexOf('/');
    String name = slash < 0 ? tail : tail.substring(0, slash);
    if (name.isBlank() || slash < 0) {
      return Optional.of(name);
    }
    String action = tail.substring(slash + 1);
    if (!"trigger".equals(action)) {
      respond(exchange, 404, "unknown cronjob endpoint: " + action);
      return Optional.empty();
    }
    if (requireAuthorized(
        exchange, ResourceKind.JOB, Verb.WRITE, cronJobTenant(name).orElse(Optional.empty()))) {
      handleCronJobTrigger(exchange, name);
    }
    return Optional.empty();
  }

  private void handlePutCronJob(HttpExchange exchange, String name, WorkloadSpec parsed)
      throws IOException {
    if (!(parsed instanceof CronJobSpec spec)) {
      respond(
          exchange, 400, "manifest kind does not match /cronjobs route (expected kind: CronJob)");
      return;
    }
    if (!spec.name().equals(name)) {
      respond(
          exchange,
          400,
          "manifest name '" + spec.name() + "' does not match URL path '" + name + "'");
      return;
    }
    storeClient.propose(new StateMutation.PutCronJobSpec(spec));
    respond(exchange, 200, "ok");
  }

  private void handleGetCronJob(HttpExchange exchange, String name) throws IOException {
    Optional<CronJobSpec> spec = storeClient.getCronJobSpec(name);
    if (spec.isEmpty()) {
      respond(exchange, 404, "no such cronjob: " + name);
      return;
    }
    respondJson(exchange, 200, cronJobStatus(spec.get()));
  }

  private void handleDeleteCronJob(HttpExchange exchange, String name) throws IOException {
    storeClient.propose(new StateMutation.RemoveCronJobSpec(name));
    respond(exchange, 200, "ok");
  }

  /**
   * The {@code gimle cronjob trigger <name>} verb's server-side implementation -- fires
   * immediately, bypassing the schedule entirely (still subject to {@code concurrencyPolicy}), via
   * the same {@link CronJobReconciler#triggerNow} the scheduled tick path shares. 404 if the
   * CronJob doesn't exist; 409 if {@code concurrencyPolicy: FORBID} blocked it against a
   * still-running previous firing -- distinguishable from "doesn't exist" so a caller isn't left
   * guessing which happened.
   */
  private void handleCronJobTrigger(HttpExchange exchange, String name) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    if (storeClient.getCronJobSpec(name).isEmpty()) {
      respond(exchange, 404, "no such cronjob: " + name);
      return;
    }
    Optional<String> generatedJobName = cronJobReconciler.triggerNow(name);
    if (generatedJobName.isEmpty()) {
      respond(exchange, 409, "cronjob " + name + " not triggered: concurrencyPolicy forbids it");
      return;
    }
    respondJson(exchange, 200, Map.of("jobName", generatedJobName.get()));
  }

  /** Every cronjob, in the same shape {@link #handleGetCronJob} returns for one. */
  private void handleCronJobsList(HttpExchange exchange) {
    try {
      if (!requireAuthorized(exchange, ResourceKind.JOB, Verb.READ, Optional.empty())) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange, 200, storeClient.listCronJobSpecs().stream().map(this::cronJobStatus).toList());
    } catch (IOException | RuntimeException e) {
      log.warn("cronjobs list request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private Map<String, Object> cronJobStatus(CronJobSpec spec) {
    Map<String, Object> specMap = new LinkedHashMap<>();
    specMap.put("name", spec.name());
    specMap.put("schedule", spec.schedule());
    Map<String, Object> template = new LinkedHashMap<>();
    template.put("moduleId", moduleIdToJson(spec.jobTemplate().moduleId()));
    template.put("artifactPath", spec.jobTemplate().artifactPath());
    template.put("backoffLimit", spec.jobTemplate().backoffLimit());
    spec.jobTemplate()
        .activeDeadline()
        .ifPresent(d -> template.put("activeDeadlineSeconds", d.toSeconds()));
    spec.jobTemplate().vessel().ifPresent(v -> template.put("vessel", vesselToJson(v)));
    specMap.put("jobTemplate", template);
    spec.startingDeadline().ifPresent(d -> specMap.put("startingDeadlineSeconds", d.toSeconds()));
    specMap.put("concurrencyPolicy", spec.concurrencyPolicy().name());
    spec.tenantId().ifPresent(tenantId -> specMap.put("tenantId", tenantId));

    Map<String, Object> status = new LinkedHashMap<>();
    status.put("spec", specMap);
    storeClient
        .getCronJobLastSchedule(spec.name())
        .ifPresent(t -> status.put("lastScheduleTime", t.toString()));
    return status;
  }

  // ---- /daemonsets/{name}, /daemonsets ----

  private void handleDaemonSet(HttpExchange exchange) {
    dispatchResourceRequest(
        exchange,
        ResourceKind.DAEMONSET,
        "missing daemonset name",
        "daemonset",
        ex -> Optional.of(pathSegmentAfter(ex, "/daemonsets/")),
        name -> storeClient.getDaemonSetSpec(name).map(DaemonSetSpec::tenantId),
        this::handlePutDaemonSet,
        this::handleGetDaemonSet,
        this::handleDeleteDaemonSet);
  }

  private void handlePutDaemonSet(HttpExchange exchange, String name, WorkloadSpec parsed)
      throws IOException {
    if (!(parsed instanceof DaemonSetSpec parsedSpec)) {
      respond(
          exchange,
          400,
          "manifest kind does not match /daemonsets route (expected kind: DaemonSet)");
      return;
    }
    if (!parsedSpec.name().equals(name)) {
      respond(
          exchange,
          400,
          "manifest name '" + parsedSpec.name() + "' does not match URL path '" + name + "'");
      return;
    }
    AdmissionArtifact admitted =
        admissionArtifact(parsedSpec.artifactPath(), parsedSpec.moduleId(), parsedSpec.vessel());
    if (admitted.rejection().isPresent()) {
      respond(exchange, 400, admitted.rejection().get());
      return;
    }
    DaemonSetSpec spec = withArtifactSha256(parsedSpec, admitted.sha256());
    // No tenant-quota check here, same documented gap handlePutJob's own identical comment
    // explains -- TenantUsage's accounting model is replica-count-shaped and has no per-node
    // equivalent yet.
    storeClient.propose(new StateMutation.PutDaemonSetSpec(spec));
    respond(exchange, 200, "ok");
  }

  private static DaemonSetSpec withArtifactSha256(DaemonSetSpec spec, Optional<String> sha256) {
    return new DaemonSetSpec(
        spec.name(),
        spec.moduleId(),
        spec.artifactPath(),
        spec.placement(),
        spec.tenantId(),
        sha256,
        spec.disruption(),
        spec.vessel());
  }

  private void handleGetDaemonSet(HttpExchange exchange, String name) throws IOException {
    Optional<DaemonSetSpec> spec = storeClient.getDaemonSetSpec(name);
    if (spec.isEmpty()) {
      respond(exchange, 404, "no such daemonset: " + name);
      return;
    }
    respondJson(exchange, 200, daemonSetStatus(spec.get()));
  }

  private void handleDeleteDaemonSet(HttpExchange exchange, String name) throws IOException {
    storeClient.propose(new StateMutation.RemoveDaemonSetSpec(name));
    respond(exchange, 200, "ok");
  }

  /** Every daemonset, in the same shape {@link #handleGetDaemonSet} returns for one. */
  private void handleDaemonSetsList(HttpExchange exchange) {
    try {
      if (!requireAuthorized(exchange, ResourceKind.DAEMONSET, Verb.READ, Optional.empty())) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange,
          200,
          storeClient.listDaemonSetSpecs().stream().map(this::daemonSetStatus).toList());
    } catch (IOException | RuntimeException e) {
      log.warn("daemonsets list request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private Map<String, Object> daemonSetStatus(DaemonSetSpec spec) {
    Map<String, Object> specMap = new LinkedHashMap<>();
    specMap.put("name", spec.name());
    specMap.put("moduleId", moduleIdToJson(spec.moduleId()));
    specMap.put("artifactPath", spec.artifactPath());
    Map<String, Object> placement = new LinkedHashMap<>();
    spec.placement()
        .requiredNodeLabels()
        .ifPresent(labels -> placement.put("requiredLabels", new ArrayList<>(labels)));
    specMap.put("placement", placement);
    spec.tenantId().ifPresent(tenantId -> specMap.put("tenantId", tenantId));
    spec.vessel().ifPresent(v -> specMap.put("vessel", vesselToJson(v)));

    List<Map<String, Object>> instances = new ArrayList<>();
    for (DaemonSetAssignment assignment : storeClient.listDaemonSetAssignmentsFor(spec.name())) {
      Map<String, Object> instance = new LinkedHashMap<>();
      instance.put("nodeId", assignment.nodeId());
      findObservationForDaemonSetAssignment(assignment)
          .ifPresent(obs -> instance.put("observation", observationToJson(obs)));
      instances.add(instance);
    }

    Map<String, Object> status = new LinkedHashMap<>();
    status.put("spec", specMap);
    status.put("instances", instances);
    return status;
  }

  private Optional<InstanceObservation> findObservationForDaemonSetAssignment(
      DaemonSetAssignment assignment) {
    return findObservation(
        assignment.nodeId(),
        obs -> obs.deploymentName().equals(assignment.daemonSetName()) && obs.instanceIndex() == 0);
  }

  // ---- /statefulsets/{name}, /statefulsets ----

  private void handleStatefulSet(HttpExchange exchange) {
    dispatchResourceRequest(
        exchange,
        ResourceKind.STATEFULSET,
        "missing statefulset name",
        "statefulset",
        ex -> Optional.of(pathSegmentAfter(ex, "/statefulsets/")),
        name -> storeClient.getStatefulSetSpec(name).map(StatefulSetSpec::tenantId),
        this::handlePutStatefulSet,
        this::handleGetStatefulSet,
        this::handleDeleteStatefulSet);
  }

  private void handlePutStatefulSet(HttpExchange exchange, String name, WorkloadSpec parsed)
      throws IOException {
    if (!(parsed instanceof StatefulSetSpec parsedSpec)) {
      respond(
          exchange,
          400,
          "manifest kind does not match /statefulsets route (expected kind: StatefulSet)");
      return;
    }
    if (!parsedSpec.name().equals(name)) {
      respond(
          exchange,
          400,
          "manifest name '" + parsedSpec.name() + "' does not match URL path '" + name + "'");
      return;
    }
    AdmissionArtifact admitted =
        admissionArtifact(parsedSpec.artifactPath(), parsedSpec.moduleId(), parsedSpec.vessel());
    if (admitted.rejection().isPresent()) {
      respond(exchange, 400, admitted.rejection().get());
      return;
    }
    StatefulSetSpec spec = withArtifactSha256(parsedSpec, admitted.sha256());
    // No tenant-quota check here, same documented gap handlePutJob's/handlePutDaemonSet's own
    // identical comment explains -- unlike those two, a StatefulSet's replicas *would* map onto
    // TenantUsage's existing replica-count-shaped accounting cleanly, but wiring only this one
    // kind in would still leave Job under-counted; making TenantUsage genuinely multi-kind-aware
    // is real, separate scope, not a StatefulSet-specific fix.
    storeClient.propose(new StateMutation.PutStatefulSetSpec(spec));
    respond(exchange, 200, "ok");
  }

  private static StatefulSetSpec withArtifactSha256(StatefulSetSpec spec, Optional<String> sha256) {
    return new StatefulSetSpec(
        spec.name(),
        spec.moduleId(),
        spec.artifactPath(),
        spec.replicas(),
        spec.placement(),
        spec.tenantId(),
        sha256,
        spec.vessel());
  }

  private void handleGetStatefulSet(HttpExchange exchange, String name) throws IOException {
    Optional<StatefulSetSpec> spec = storeClient.getStatefulSetSpec(name);
    if (spec.isEmpty()) {
      respond(exchange, 404, "no such statefulset: " + name);
      return;
    }
    respondJson(exchange, 200, statefulSetStatus(spec.get()));
  }

  private void handleDeleteStatefulSet(HttpExchange exchange, String name) throws IOException {
    storeClient.propose(new StateMutation.RemoveStatefulSetSpec(name));
    respond(exchange, 200, "ok");
  }

  /** Every statefulset, in the same shape {@link #handleGetStatefulSet} returns for one. */
  private void handleStatefulSetsList(HttpExchange exchange) {
    try {
      if (!requireAuthorized(exchange, ResourceKind.STATEFULSET, Verb.READ, Optional.empty())) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange,
          200,
          storeClient.listStatefulSetSpecs().stream().map(this::statefulSetStatus).toList());
    } catch (IOException | RuntimeException e) {
      log.warn("statefulsets list request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * {@code instances[].nodeId} is surfaced explicitly and unconditionally (not just folded into
   * {@code observation}) -- the one place across every workload kind's own status JSON where doing
   * so is more than a convenience: it makes the sticky-placement contract visible to an operator,
   * not just implemented silently, the same reasoning behind why the CLI's own {@code get
   * statefulsets} output must show it too.
   */
  private Map<String, Object> statefulSetStatus(StatefulSetSpec spec) {
    Map<String, Object> specMap = new LinkedHashMap<>();
    specMap.put("name", spec.name());
    specMap.put("moduleId", moduleIdToJson(spec.moduleId()));
    specMap.put("artifactPath", spec.artifactPath());
    specMap.put("replicas", spec.replicas());
    spec.tenantId().ifPresent(tenantId -> specMap.put("tenantId", tenantId));
    spec.vessel().ifPresent(v -> specMap.put("vessel", vesselToJson(v)));

    List<Map<String, Object>> instances = new ArrayList<>();
    for (StatefulSetAssignment assignment :
        storeClient.listStatefulSetAssignmentsFor(spec.name())) {
      Map<String, Object> instance = new LinkedHashMap<>();
      instance.put("instanceIndex", assignment.instanceIndex());
      instance.put("nodeId", assignment.nodeId());
      findObservationForStatefulSetAssignment(assignment)
          .ifPresent(obs -> instance.put("observation", observationToJson(obs)));
      instances.add(instance);
    }

    Map<String, Object> status = new LinkedHashMap<>();
    status.put("spec", specMap);
    status.put("instances", instances);
    status.put("unplacedCount", spec.replicas() - instances.size());
    return status;
  }

  private Optional<InstanceObservation> findObservationForStatefulSetAssignment(
      StatefulSetAssignment assignment) {
    return findObservation(
        assignment.nodeId(),
        obs ->
            obs.deploymentName().equals(assignment.statefulSetName())
                && obs.instanceIndex() == assignment.instanceIndex());
  }

  private Map<String, Object> deploymentStatus(DeploymentSpec spec) {
    Map<String, Object> specMap = new LinkedHashMap<>();
    specMap.put("name", spec.name());
    specMap.put("moduleId", moduleIdToJson(spec.moduleId()));
    specMap.put("artifactPath", spec.artifactPath());
    specMap.put("replicas", spec.replicas());
    spec.autoscale().ifPresent(policy -> specMap.put("autoscale", autoscaleToJson(policy)));
    spec.disruption().ifPresent(budget -> specMap.put("disruption", disruptionToJson(budget)));
    spec.tenantId().ifPresent(tenantId -> specMap.put("tenantId", tenantId));
    spec.vessel().ifPresent(v -> specMap.put("vessel", vesselToJson(v)));

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
   * Serializes an {@link AutoscalePolicy} onto the wire -- the console's own {@code
   * DeploymentSpec}/{@code DeploymentSpecInput} TypeScript types don't model this today precisely
   * because it was never in this response to begin with. Mirrors {@link #auditEventToJson}'s style:
   * required fields always written, every {@code Optional*} field written only via {@code
   * ifPresent} so an unconfigured signal/weight is omitted from the JSON entirely rather than
   * serialized as {@code null} -- the same "absent means not evaluated" convention {@link
   * AutoscalePolicy}'s own javadoc documents for the record itself.
   */
  private static Map<String, Object> autoscaleToJson(AutoscalePolicy policy) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("minReplicas", policy.minReplicas());
    map.put("maxReplicas", policy.maxReplicas());
    map.put("targetCpuUtilizationPercent", policy.targetCpuUtilizationPercent());
    policy.targetRequestRatePerSecond().ifPresent(v -> map.put("targetRequestRatePerSecond", v));
    policy.targetErrorRatePercent().ifPresent(v -> map.put("targetErrorRatePercent", v));
    policy.targetQueueDepth().ifPresent(v -> map.put("targetQueueDepth", v));
    map.put("combinationMode", policy.combinationMode().name());
    policy.cpuWeight().ifPresent(v -> map.put("cpuWeight", v));
    policy.requestRateWeight().ifPresent(v -> map.put("requestRateWeight", v));
    policy.errorRateWeight().ifPresent(v -> map.put("errorRateWeight", v));
    policy.queueDepthWeight().ifPresent(v -> map.put("queueDepthWeight", v));
    return map;
  }

  private static Map<String, Object> disruptionToJson(DisruptionBudget budget) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("maxUnavailable", budget.maxUnavailable());
    map.put("maxSurge", budget.maxSurge());
    return map;
  }

  /** Serializes a {@link VesselSpec} onto the wire for the console/CLI to render. */
  private static Map<String, Object> vesselToJson(VesselSpec vessel) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("args", vessel.args());
    map.put("jvmFlags", vessel.jvmFlags());
    Map<String, Object> env = new LinkedHashMap<>();
    for (var entry : vessel.env().entrySet()) {
      env.put(entry.getKey(), vesselEnvValueToJson(entry.getValue()));
    }
    map.put("env", env);
    List<Map<String, Object>> files = new ArrayList<>();
    for (VesselFileMount file : vessel.files()) {
      files.add(Map.of("path", file.path(), "config", file.configKey()));
    }
    map.put("files", files);
    Map<String, Object> probes = new LinkedHashMap<>();
    vessel.probes().liveness().ifPresent(p -> probes.put("liveness", vesselProbeToJson(p)));
    vessel.probes().readiness().ifPresent(p -> probes.put("readiness", vesselProbeToJson(p)));
    map.put("probes", probes);
    Map<String, Object> resources = new LinkedHashMap<>();
    resources.put(
        "request",
        Map.of("memory", vessel.resourceRequest().memory(), "cpu", vessel.resourceRequest().cpu()));
    resources.put(
        "limit",
        Map.of("memory", vessel.resourceLimit().memory(), "cpu", vessel.resourceLimit().cpu()));
    map.put("resources", resources);
    return map;
  }

  private static Map<String, Object> vesselEnvValueToJson(VesselEnvValue value) {
    return switch (value) {
      case VesselEnvValue.Literal literal -> Map.of("value", literal.value());
      case VesselEnvValue.SecretRef secretRef -> Map.of("secret", secretRef.key());
      case VesselEnvValue.PortAllocation portAllocation ->
          portAllocation.fixedPort().isPresent()
              ? Map.of("port", portAllocation.fixedPort().getAsInt())
              : Map.of("port", "dynamic");
    };
  }

  private static Map<String, Object> vesselProbeToJson(VesselProbeSpec probe) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("initialDelaySeconds", probe.initialDelaySeconds());
    switch (probe) {
      case VesselProbeSpec.Tcp ignored -> map.put("tcp", true);
      case VesselProbeSpec.Http http -> map.put("http", http.path());
    }
    return map;
  }

  // ---- /endpoints/{name} ----

  /**
   * Read-only view over where a workload's live instances are actually reachable: for each placed
   * instance, its node id, the host that node registered at startup, and whichever ports a vessel
   * instance declared (name -> allocated/fixed number) -- joined from the latest heartbeat the same
   * way {@link #findObservation} already joins an {@link InstanceAssignment} against one, plus the
   * node's own {@link NodeRegistration#apiAddress()} for the host half. No gateway, no proxying --
   * purely "list where things are," for an external client to dial itself.
   *
   * <p>{@code name} is looked up against each supported workload kind in turn (Deployment, Job,
   * DaemonSet, StatefulSet) since this one path carries no kind of its own -- the first kind whose
   * spec store actually has {@code name} wins. CronJob is deliberately not one of them: it has no
   * live instance of its own, only the Jobs it spawns, so there is nothing for this route to ever
   * join against for a CronJob's own name.
   */
  private void handleEndpoints(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      String name = pathSegmentAfter(exchange, "/endpoints/");
      if (name.isBlank()) {
        respond(exchange, 400, "missing workload name");
        return;
      }
      Optional<DeploymentSpec> deployment = storeClient.getDeployment(name);
      if (deployment.isPresent()) {
        if (authorizeEndpointsRead(
            exchange, ResourceKind.DEPLOYMENT, deployment.get().tenantId())) {
          respondJson(exchange, 200, deploymentEndpoints(deployment.get()));
        }
        return;
      }
      Optional<JobSpec> job = storeClient.getJobSpec(name);
      if (job.isPresent()) {
        if (authorizeEndpointsRead(exchange, ResourceKind.JOB, job.get().tenantId())) {
          respondJson(exchange, 200, jobEndpoints(job.get()));
        }
        return;
      }
      Optional<DaemonSetSpec> daemonSet = storeClient.getDaemonSetSpec(name);
      if (daemonSet.isPresent()) {
        if (authorizeEndpointsRead(exchange, ResourceKind.DAEMONSET, daemonSet.get().tenantId())) {
          respondJson(exchange, 200, daemonSetEndpoints(daemonSet.get()));
        }
        return;
      }
      Optional<StatefulSetSpec> statefulSet = storeClient.getStatefulSetSpec(name);
      if (statefulSet.isPresent()) {
        if (authorizeEndpointsRead(
            exchange, ResourceKind.STATEFULSET, statefulSet.get().tenantId())) {
          respondJson(exchange, 200, statefulSetEndpoints(statefulSet.get()));
        }
        return;
      }
      respond(exchange, 404, "no such workload: " + name);
    } catch (IOException | RuntimeException e) {
      log.warn("endpoints request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * Before the ordinary {@link #requireAuthorized} RBAC walk: a {@code gimle:nodes} caller takes
   * the node-tenant-scoping path instead -- permitted only if {@link
   * Authorizer#isTenantAssignedToNode} says this node currently has an active assignment for {@code
   * tenantId} -- mirroring {@code FafnirServer#decideAllowed}'s identical shape for {@code
   * /secrets/*}. Anyone else (an operator, a role-bound user, an unauthenticated plaintext caller)
   * still goes through the ordinary path unchanged. {@code /endpoints/*} is GET-only, so there is
   * no verb to branch on the way {@code decideAllowed} does for {@code /secrets/*}'s write/delete.
   */
  private boolean authorizeEndpointsRead(
      HttpExchange exchange, ResourceKind resourceKind, Optional<String> tenantId) {
    if (exchange instanceof HttpsExchange) {
      Optional<Principal> principal = resolvePrincipal(exchange);
      if (principal.isPresent() && principal.get().groups().contains(BuiltinRoles.GROUP_NODES)) {
        boolean allowed =
            tenantId.isPresent()
                && authorizer.isTenantAssignedToNode(principal.get().name(), tenantId.get());
        if (!allowed) {
          respondQuietly(exchange, 403, "forbidden");
        }
        return allowed;
      }
    }
    return requireAuthorized(exchange, resourceKind, Verb.READ, tenantId);
  }

  private List<Map<String, Object>> deploymentEndpoints(DeploymentSpec spec) {
    List<Map<String, Object>> endpoints = new ArrayList<>();
    for (InstanceAssignment assignment : storeClient.listAssignmentsFor(spec.name())) {
      endpoints.add(
          endpointEntry(
              assignment.nodeId(), assignment.instanceIndex(), findObservation(assignment)));
    }
    return endpoints;
  }

  /** {@code attempt} plays {@code instanceIndex}'s own role -- see {@link JobRun}'s own javadoc. */
  private List<Map<String, Object>> jobEndpoints(JobSpec spec) {
    List<Map<String, Object>> endpoints = new ArrayList<>();
    for (JobRun run : storeClient.listJobRunsFor(spec.name())) {
      endpoints.add(endpointEntry(run.nodeId(), run.attempt(), findObservationForJobRun(run)));
    }
    return endpoints;
  }

  /** A DaemonSet has no {@code instanceIndex} of its own -- the node itself is the index. */
  private List<Map<String, Object>> daemonSetEndpoints(DaemonSetSpec spec) {
    List<Map<String, Object>> endpoints = new ArrayList<>();
    for (DaemonSetAssignment assignment : storeClient.listDaemonSetAssignmentsFor(spec.name())) {
      endpoints.add(
          endpointEntry(assignment.nodeId(), 0, findObservationForDaemonSetAssignment(assignment)));
    }
    return endpoints;
  }

  private List<Map<String, Object>> statefulSetEndpoints(StatefulSetSpec spec) {
    List<Map<String, Object>> endpoints = new ArrayList<>();
    for (StatefulSetAssignment assignment :
        storeClient.listStatefulSetAssignmentsFor(spec.name())) {
      endpoints.add(
          endpointEntry(
              assignment.nodeId(),
              assignment.instanceIndex(),
              findObservationForStatefulSetAssignment(assignment)));
    }
    return endpoints;
  }

  /** The one entry shape every workload kind's own endpoints list is built out of. */
  private Map<String, Object> endpointEntry(
      String nodeId, int instanceIndex, Optional<InstanceObservation> observation) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("instanceIndex", instanceIndex);
    entry.put("nodeId", nodeId);
    storeClient
        .getNodeRegistration(nodeId)
        .ifPresent(
            reg -> reg.apiAddress().ifPresent(address -> entry.put("host", hostOnly(address))));
    observation.ifPresent(obs -> entry.put("ports", obs.ports()));
    return entry;
  }

  /** Strips a trailing {@code :port} off a registered {@code host:port} node address. */
  private static String hostOnly(String hostPort) {
    int at = hostPort.lastIndexOf(':');
    return at < 0 ? hostPort : hostPort.substring(0, at);
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
      List<InstanceObservation> observations, ToDoubleFunction<InstanceObservation> extractor) {
    if (observations.isEmpty()) {
      return 0.0;
    }
    return observations.stream().mapToDouble(extractor).average().orElse(0.0);
  }

  private Optional<InstanceObservation> findObservation(InstanceAssignment assignment) {
    return findObservation(
        assignment.nodeId(),
        obs ->
            obs.deploymentName().equals(assignment.deploymentName())
                && obs.instanceIndex() == assignment.instanceIndex());
  }

  /**
   * The shared scan behind {@link #findObservation(InstanceAssignment)} and its {@code
   * DaemonSetAssignment}/{@code StatefulSetAssignment}/{@code JobRun} siblings above: the given
   * node's own heartbeat, if any, filtered down to the one observation {@code matches} identifies
   * -- by deployment/job name and whichever index convention that workload kind uses (an ordinary
   * instance index, a job run's attempt number, or a fixed {@code 0} for a DaemonSet, which has no
   * index of its own). Absent either the heartbeat or a matching observation within it is not an
   * error, just "nothing to report yet."
   */
  private Optional<InstanceObservation> findObservation(
      String nodeId, Predicate<InstanceObservation> matches) {
    return storeClient
        .getNodeHeartbeat(nodeId)
        .map(ObservedHeartbeat::heartbeat)
        .flatMap(heartbeat -> heartbeat.instances().stream().filter(matches).findFirst());
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
   * {@link StoreClient#putHeartbeat} follows the leader internally the same way {@code
   * storeClient.propose} does, throwing {@link GimleRaftException} on the same store-unavailable
   * response every other write uses if no leader could be reached, even though this path never
   * touches the Raft log.
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
              spec.get().tenantId(),
              assignment.renamedFromInstanceIndex(),
              spec.get().vessel());
      assigned.add(assignedInstanceToJson(instance));
    }
    // Job runs reuse this exact same AssignedInstance wire shape --
    // from the agent's point of view a JobRun is indistinguishable from an ordinary deployment
    // replica assignment, jobName/attempt playing deploymentName/instanceIndex's own role. No
    // agent-side or worker-side code needs to know or care which kind actually placed it; the
    // only kind-specific behavior (running JobHooks to completion) lives entirely in the worker's
    // own ModuleDescriptor.jobHooksClass() check, orthogonal to how the assignment arrived here.
    for (JobRun run : storeClient.listJobRuns()) {
      if (!run.nodeId().equals(nodeId)) {
        continue;
      }
      Optional<JobSpec> jobSpec = storeClient.getJobSpec(run.jobName());
      if (jobSpec.isEmpty()) {
        continue; // stale run; JobReconciler will remove it shortly
      }
      AssignedInstance instance =
          new AssignedInstance(
              run.jobName(),
              run.attempt(),
              run.moduleId(),
              run.artifactPath(),
              jobSpec.get().tenantId(),
              OptionalInt.empty(),
              jobSpec.get().vessel());
      assigned.add(assignedInstanceToJson(instance));
    }
    // DaemonSet assignments reuse the same AssignedInstance wire shape
    // once more, the identical reasoning JobRun's own block above documents: daemonSetName/0 play
    // deploymentName/instanceIndex's own role -- instanceIndex is always 0 since a DaemonSet places
    // at most one instance per node, so no second index is ever needed to disambiguate.
    for (DaemonSetAssignment assignment : storeClient.listDaemonSetAssignments()) {
      if (!assignment.nodeId().equals(nodeId)) {
        continue;
      }
      Optional<DaemonSetSpec> daemonSetSpec =
          storeClient.getDaemonSetSpec(assignment.daemonSetName());
      if (daemonSetSpec.isEmpty()) {
        continue; // stale assignment; DaemonSetReconciler will remove it shortly
      }
      AssignedInstance instance =
          new AssignedInstance(
              assignment.daemonSetName(),
              0,
              assignment.moduleId(),
              assignment.artifactPath(),
              daemonSetSpec.get().tenantId(),
              OptionalInt.empty(),
              daemonSetSpec.get().vessel());
      assigned.add(assignedInstanceToJson(instance));
    }
    // StatefulSet assignments reuse the same AssignedInstance wire
    // shape one more time -- statefulSetName/instanceIndex map directly onto deploymentName/
    // instanceIndex, the exact same fit InstanceAssignment itself already has, since a
    // StatefulSet index is a real, stable identity like an ordinary deployment replica's own
    // index, not DaemonSet's always-0. No agent-side or worker-side kind-awareness needed here
    // either: whether this instance's descriptor declares volume: (and so needs a data directory
    // resolved) is discovered generically from the artifact itself, in AgentMain, not from which
    // reconciler placed it.
    for (StatefulSetAssignment assignment : storeClient.listStatefulSetAssignments()) {
      if (!assignment.nodeId().equals(nodeId)) {
        continue;
      }
      Optional<StatefulSetSpec> statefulSetSpec =
          storeClient.getStatefulSetSpec(assignment.statefulSetName());
      if (statefulSetSpec.isEmpty()) {
        continue; // stale assignment; StatefulSetReconciler will remove it shortly
      }
      AssignedInstance instance =
          new AssignedInstance(
              assignment.statefulSetName(),
              assignment.instanceIndex(),
              assignment.moduleId(),
              assignment.artifactPath(),
              statefulSetSpec.get().tenantId(),
              OptionalInt.empty(),
              statefulSetSpec.get().vessel());
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

  /**
   * {@code GET /audit[?principal=&resource=&tenant=&since=&limit=]} -- the read side of the
   * cross-resource audit trail {@link #requireAuthorized} writes into (see {@code
   * OBSERVABILITY_AUDIT_DESIGN.md}'s Part A). Gated on {@link ResourceKind#AUDIT}, the same
   * "reading the trail is itself an access-controlled action" framing already applied to {@code
   * ROLE}/{@code ROLE_BINDING}/{@code ACCOUNT} -- every filter is optional and independently
   * combinable, matching {@link com.gimle.mimir.store.StoreReader#listAuditEvents}'s own shape.
   */
  private void handleAudit(HttpExchange exchange) {
    try {
      if (!requireAuthorized(exchange, ResourceKind.AUDIT, Verb.READ, Optional.empty())) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Map<String, String> query = parseQuery(exchange);
      Optional<String> principal = Optional.ofNullable(query.get("principal"));
      Optional<String> resource = Optional.ofNullable(query.get("resource"));
      Optional<String> tenant = Optional.ofNullable(query.get("tenant"));
      Optional<Long> since = Optional.ofNullable(query.get("since")).map(Long::parseLong);
      int limit =
          Optional.ofNullable(query.get("limit")).map(Integer::parseInt).orElse(Integer.MAX_VALUE);

      List<Map<String, Object>> events = new ArrayList<>();
      for (AuditEvent event : storeClient.listAuditEvents(principal, resource, tenant, since)) {
        if (events.size() >= limit) {
          break;
        }
        events.add(auditEventToJson(event));
      }
      respondJson(exchange, 200, events);
    } catch (NumberFormatException e) {
      respondQuietly(exchange, 400, "since/limit must be numeric");
    } catch (IOException | RuntimeException e) {
      log.warn("audit request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private static Map<String, Object> auditEventToJson(AuditEvent event) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", event.id());
    map.put("principal", event.principal());
    map.put("groups", List.copyOf(event.groups()));
    map.put("resourceKind", event.resourceKind());
    map.put("verb", event.verb());
    event.tenantId().ifPresent(tenantId -> map.put("tenantId", tenantId));
    event.targetId().ifPresent(targetId -> map.put("targetId", targetId));
    map.put("allowed", event.allowed());
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
        numberField(map, "errorRatePerSecond", 0.0).doubleValue(),
        portsFromJson(map.get("ports")));
  }

  /** {@code ports}, when present, is a vessel instance's own declared-port-name -> number map. */
  private static Map<String, Integer> portsFromJson(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) {
      return Map.of();
    }
    Map<String, Integer> ports = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      ports.put((String) entry.getKey(), ((Number) entry.getValue()).intValue());
    }
    return ports;
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
    if (!obs.ports().isEmpty()) {
      map.put("ports", obs.ports());
    }
    return map;
  }

  private static Map<String, Object> assignedInstanceToJson(AssignedInstance instance) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("deploymentName", instance.deploymentName());
    map.put("instanceIndex", instance.instanceIndex());
    map.put("moduleId", moduleIdToJson(instance.moduleId()));
    map.put("artifactPath", instance.artifactPath());
    instance.tenantId().ifPresent(tenantId -> map.put("tenantId", tenantId));
    if (instance.renamedFromInstanceIndex().isPresent()) {
      map.put("renamedFromInstanceIndex", instance.renamedFromInstanceIndex().getAsInt());
    }
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
          if (requireAuthorized(exchange, ResourceKind.TENANT, Verb.WRITE, Optional.of(id))
              && !rejectIfReservedSystemTenant(exchange, Optional.of(id))) {
            handlePutTenant(exchange, id);
          }
        }
        case "GET" -> {
          if (requireAuthorized(exchange, ResourceKind.TENANT, Verb.READ, Optional.of(id))) {
            handleGetTenant(exchange, id);
          }
        }
        case "DELETE" -> {
          if (requireAuthorized(exchange, ResourceKind.TENANT, Verb.DELETE, Optional.of(id))
              && !rejectIfReservedSystemTenant(exchange, Optional.of(id))) {
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

  /**
   * {@code true} for a {@code ConfigEntry} key Fafnir's own versioned {@code /secrets/*} surface
   * owns -- {@code <key>@meta} or {@code <key>@N} -- rather than one written through this process's
   * own {@code /config/*} endpoints. Both keyspaces share the same underlying {@code ConfigEntry}
   * rows in {@code gimle-mimir}, with no separate store schema for secrets, so without this filter
   * every secret written through {@code /secrets/*} would leak into a plain {@code GET
   * /config/{tenantId}} listing as a handful of oddly-named, ciphertext-bearing "config entries" --
   * exactly the resource-kind blurring the {@code CONFIG}/{@code SECRET} RBAC split exists to
   * avoid.
   */
  private static boolean isFafnirManagedSecretKey(String key) {
    int at = key.lastIndexOf('@');
    if (at < 0 || at == key.length() - 1) {
      return false;
    }
    String suffix = key.substring(at + 1);
    return suffix.equals("meta") || suffix.chars().allMatch(Character::isDigit);
  }

  private void handlePutConfig(
      HttpExchange exchange, String tenantId, String key, String value, boolean encrypted)
      throws IOException {
    byte[] stored =
        encrypted
            ? fafnirClient.encrypt(value.getBytes(StandardCharsets.UTF_8))
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
    List<ConfigEntry> visible = new ArrayList<>();
    for (ConfigEntry entry : storeClient.listConfigEntriesFor(tenantId)) {
      if (isFafnirManagedSecretKey(entry.key())) {
        continue;
      }
      if (entry.encrypted() ? canReadSecrets : canReadConfig) {
        visible.add(entry);
      }
    }
    // One batched round trip to Fafnir for every encrypted entry, not one per entry -- keeps this
    // list call to a single network hop regardless of how many secrets a tenant holds.
    List<byte[]> ciphertexts =
        visible.stream().filter(ConfigEntry::encrypted).map(ConfigEntry::value).toList();
    Iterator<byte[]> decrypted =
        (ciphertexts.isEmpty() ? List.<byte[]>of() : fafnirClient.decryptBatch(ciphertexts))
            .iterator();
    List<Map<String, Object>> list = new ArrayList<>();
    for (ConfigEntry entry : visible) {
      byte[] plaintext = entry.encrypted() ? decrypted.next() : entry.value();
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

  // ---- /secrets/rotate-key ----

  /**
   * Proxies to Fafnir's own {@code /secrets/rotate-key} (moved there verbatim from this method's
   * pre-extraction body -- see {@code FafnirCrypto.rotate}'s javadoc for the re-encryption walk's
   * exact semantics, unchanged by the move). Gated on the same {@code SECRET:WRITE} permission a
   * config write itself requires, unscoped since rotation is cluster-wide, not per-tenant -- this
   * process's own authorization check; Fafnir does not yet re-run its own independent authorization
   * check here the way it does for the versioned {@code /secrets/*} proxy below.
   */
  private void handleRotateSecretsKey(HttpExchange exchange) {
    try {
      if (!requireAuthorized(exchange, ResourceKind.SECRET, Verb.WRITE, Optional.empty())) {
        return;
      }
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      byte newKeyId = fafnirClient.rotateKey();
      respondJson(exchange, 200, Map.of("activeKeyId", Byte.toUnsignedInt(newKeyId)));
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (IOException | RuntimeException e) {
      log.warn("secrets key rotation failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  // ---- /secrets/{tenantId}/... -- a byte-for-byte proxy to Fafnir ----

  /**
   * This gate doesn't move -- {@code ApiServer} still performs its own {@code requireAuthorized}
   * check exactly as it does for every other resource kind, before ever forwarding anything. Unlike
   * the fixed internal operations above, this endpoint's body/response shape is Fafnir's own
   * evolving API, so this handler never parses either -- it relays the request verbatim ({@code
   * method}, path tail, query string, body) and attaches the calling principal's identity as an
   * internal claim header for Fafnir's own independent re-check: skipping *re-authentication* here
   * is fine, skipping *re-authorization* on Fafnir's side is not, so this process's own {@link
   * #requireAuthorized} call is not a substitute for Fafnir's.
   */
  private void handleSecretsProxy(HttpExchange exchange) {
    try {
      String tail = pathSegmentAfter(exchange, "/secrets/");
      int slash = tail.indexOf('/');
      String tenantId = slash < 0 ? tail : tail.substring(0, slash);
      if (tenantId.isBlank()) {
        respond(exchange, 400, "missing tenantId");
        return;
      }
      Verb verb =
          switch (exchange.getRequestMethod()) {
            case "GET" -> Verb.READ;
            case "PUT" -> Verb.WRITE;
            case "DELETE" -> Verb.DELETE;
            default -> null;
          };
      if (verb == null) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      if (!requireAuthorized(exchange, ResourceKind.SECRET, verb, Optional.of(tenantId))) {
        return;
      }
      Map<String, String> forwardHeaders = new LinkedHashMap<>();
      resolvePrincipal(exchange)
          .ifPresent(
              principal -> {
                forwardHeaders.put("X-Gimle-Forwarded-Principal", principal.name());
                forwardHeaders.put(
                    "X-Gimle-Forwarded-Groups", String.join(",", principal.groups()));
              });
      byte[] body =
          "PUT".equals(exchange.getRequestMethod())
              ? readBody(exchange).getBytes(StandardCharsets.UTF_8)
              : null;
      String query = exchange.getRequestURI().getRawQuery();
      String path = "/secrets/" + tail + (query != null ? "?" + query : "");
      FafnirClient.RawResponse response =
          fafnirClient.forward(exchange.getRequestMethod(), path, body, forwardHeaders);
      exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(response.statusCode(), response.body().length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(response.body());
      }
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (IOException | RuntimeException e) {
      log.warn("secrets proxy request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  // ---- /artifacts/** -- a streaming proxy to the Andvari artifact registry ----

  /**
   * The push/pull/list surface for the artifact registry, proxied the same way {@code /secrets/*}
   * proxies to Fafnir: this process's own {@code requireAuthorized} gate runs first, the calling
   * principal travels as an internal claim, and Andvari independently re-authorizes regardless.
   * Unlike the secrets proxy, bodies here are jars, so both directions stream -- a push flows from
   * the caller's socket through to Andvari and a pull flows back, never a whole jar buffered in
   * this process.
   */
  private void handleArtifactsProxy(HttpExchange exchange) {
    try {
      String path = exchange.getRequestURI().getPath();
      if (!path.equals("/artifacts") && !path.startsWith("/artifacts/")) {
        respond(exchange, 404, "not found");
        return;
      }
      Optional<AndvariClient> registry = artifactResolver.registryClient();
      if (registry.isEmpty()) {
        respond(
            exchange,
            503,
            "no artifact registry configured -- start the control plane with --andvari-endpoint");
        return;
      }
      String method = exchange.getRequestMethod();
      Verb verb =
          switch (method) {
            case "GET", "HEAD" -> Verb.READ;
            case "PUT" -> Verb.WRITE;
            case "DELETE" -> Verb.DELETE;
            default -> null;
          };
      if (verb == null) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      if (!requireAuthorized(exchange, ResourceKind.ARTIFACT, verb, Optional.empty())) {
        return;
      }
      Map<String, String> forwardHeaders = new LinkedHashMap<>();
      resolvePrincipal(exchange)
          .ifPresent(
              principal -> {
                forwardHeaders.put("X-Gimle-Forwarded-Principal", principal.name());
                forwardHeaders.put(
                    "X-Gimle-Forwarded-Groups", String.join(",", principal.groups()));
              });
      InputStream requestBody = "PUT".equals(method) ? exchange.getRequestBody() : null;
      AndvariClient.StreamingResponse response =
          registry.get().forward(method, path, requestBody, forwardHeaders);
      response
          .sha256()
          .ifPresent(sha -> exchange.getResponseHeaders().add("X-Gimle-Artifact-Sha256", sha));
      response
          .contentType()
          .ifPresent(type -> exchange.getResponseHeaders().add("Content-Type", type));
      if ("HEAD".equals(method)) {
        try (InputStream ignored = response.body()) {
          exchange.sendResponseHeaders(response.statusCode(), -1);
        }
        return;
      }
      // Chunked relay (length 0): Andvari's response length isn't re-measured here, and jars
      // stream through without ever being whole in memory.
      exchange.sendResponseHeaders(response.statusCode(), 0);
      try (InputStream in = response.body();
          OutputStream out = exchange.getResponseBody()) {
        in.transferTo(out);
      }
    } catch (IOException | RuntimeException e) {
      log.warn("artifacts proxy request failed: {}", e.getMessage());
      respondQuietly(exchange, 502, "artifact registry unreachable");
    } finally {
      exchange.close();
    }
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
   * login endpoint, and how the console tells "logged out" apart from "logged in"), and {@code
   * /auth/logout} only ever clears whatever cookie is presented, authenticated or not.
   */
  private void handleAuthLogin(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      String addressKey = "addr:" + remoteAddressKey(exchange);
      Optional<Instant> addressThrottled = loginThrottle.throttledUntil(addressKey);
      if (addressThrottled.isPresent()) {
        respondThrottled(exchange, addressThrottled.get());
        return;
      }

      Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
      String username = (String) body.get("username");
      String password = (String) body.get("password");
      // Deliberately keyed even for a blank/absent username, rather than skipped -- an attacker
      // probing with no username at all still consumes this key's own backoff, same as a real one.
      String usernameKey = "user:" + (username == null ? "" : username);
      Optional<Instant> usernameThrottled = loginThrottle.throttledUntil(usernameKey);
      if (usernameThrottled.isPresent()) {
        respondThrottled(exchange, usernameThrottled.get());
        return;
      }

      Optional<Account> account =
          username == null ? Optional.empty() : storeClient.getAccount(username);
      if (account.isEmpty()
          || password == null
          || !PasswordHashes.verify(password.toCharArray(), account.get().passwordHash())) {
        loginThrottle.recordFailure(usernameKey);
        loginThrottle.recordFailure(addressKey);
        // Deliberately the same message either way -- distinguishing "unknown username" from
        // "wrong password" would let this endpoint enumerate valid usernames.
        respondQuietly(exchange, 401, "invalid username or password");
        return;
      }
      loginThrottle.recordSuccess(usernameKey);
      loginThrottle.recordSuccess(addressKey);
      String token = SessionTokens.issue(username, sessionSigningKey, SESSION_TTL);
      exchange
          .getResponseHeaders()
          .add("Set-Cookie", sessionCookieHeader(token, SESSION_TTL.toSeconds()));
      respondJson(exchange, 200, principalToJson(new Principal(username, Set.of()), false));
    } catch (IOException | RuntimeException e) {
      log.warn("login request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * The connection's own remote address, never a client-supplied header like {@code
   * X-Forwarded-For} -- this class has no configured notion of a trusted reverse proxy, so honoring
   * a client-set header here would let an attacker defeat the address-keyed throttle entirely by
   * sending a different forged value on every request. Falls back to a fixed placeholder if
   * unavailable (never null, so it's always safe to use as a map key), which under-differentiates
   * callers in that rare case rather than throwing.
   */
  private static String remoteAddressKey(HttpExchange exchange) {
    InetSocketAddress remote = exchange.getRemoteAddress();
    return remote == null ? "unknown" : remote.getAddress().getHostAddress();
  }

  /**
   * {@code 429}, same generic body as an ordinary failed login -- must not let a caller distinguish
   * "you're throttled" from "wrong credentials" by body content, only by status code and the
   * standard {@code Retry-After} header (seconds), which reveals no more than "come back later"
   * either way.
   */
  private static void respondThrottled(HttpExchange exchange, Instant nextAllowedAttempt)
      throws IOException {
    long retryAfterSeconds =
        Math.max(1, Duration.between(Instant.now(), nextAllowedAttempt).toSeconds());
    exchange.getResponseHeaders().add("Retry-After", String.valueOf(retryAfterSeconds));
    respondQuietly(exchange, 429, "too many attempts; try again later");
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

  /**
   * Polled by the console on load to tell "already logged in" apart from "show the login page".
   *
   * <p>Needs the same plaintext carve-out {@link #requireAuthorized} already has, but as a fallback
   * behind {@link #resolvePrincipal} rather than ahead of it: a genuine session cookie must still
   * resolve to its real principal regardless of transport (plaintext-mode login is a supported,
   * tested path, not just a TLS thing), so only the true "nobody's logged in" case gets the
   * plaintext carve-out. Without it, a bare 401 there -- indistinguishable from "on TLS and not
   * logged in" -- would send the console straight to a login form no credential could ever satisfy
   * (plaintext bootstrap never seeds a bootstrap account, see {@code BootstrapAccountFile}),
   * locking the operator out entirely rather than leaving the cluster open the way every other
   * endpoint already is in this mode.
   *
   * <p>The synthetic fallback principal is marked {@code anonymous: true} in its JSON, deliberately
   * distinct from a real login's response: the console's login page uses that flag to tell "there's
   * a free pass, nothing to redirect for" apart from "an operator is actually signed in" -- without
   * it, the console treated the free pass itself as a completed login and would redirect away from
   * {@code /login} the instant the page loaded, before a real login ever had a chance to happen.
   */
  private void handleAuthSession(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Optional<Principal> principal = resolvePrincipal(exchange);
      if (principal.isPresent()) {
        respondJson(exchange, 200, principalToJson(principal.get(), false));
        return;
      }
      if (!(exchange instanceof HttpsExchange)) {
        // Plaintext mode: no real session, but nothing is actually gated behind one either (see
        // requireAuthorized's own carve-out) -- report an anonymous session rather than 401, so
        // the console doesn't force a login screen that, with no bootstrap account seeded in
        // plaintext mode, may not be satisfiable by any credential at all. A genuine login (see
        // the branch above) still takes priority whenever a valid cookie is actually presented.
        respondJson(exchange, 200, principalToJson(new Principal("anonymous", Set.of()), true));
        return;
      }
      respondQuietly(exchange, 401, "not authenticated");
    } catch (IOException e) {
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private static Map<String, Object> principalToJson(Principal principal, boolean anonymous) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("username", principal.name());
    map.put("groups", List.copyOf(principal.groups()));
    map.put("anonymous", anonymous);
    return map;
  }

  // ---- /logs/controlplane, /logs/nodes/{nodeId}, /logs/instances/{name}/{idx} ----

  /**
   * Log reads are GETs against whichever control-plane replica receives them, which then makes its
   * own direct call to the target agent -- no write/consensus involved, so the leader-redirect
   * handling every write path needs doesn't apply here.
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

  /**
   * {@code GET /metrics-history/{processKind}/{processId}} -- a thin, read-only proxy to Muninn's
   * own {@code GET /metrics/{processKind}/{processId}}, via {@link #handleHistoryProxy}.
   */
  private void handleMetricsHistory(HttpExchange exchange) {
    handleHistoryProxy(exchange, "/metrics-history/", "/metrics/", "metrics history");
  }

  /**
   * {@code GET /traces-history/{processKind}/{processId}} -- structurally identical to {@link
   * #handleMetricsHistory} above, proxying to Muninn's own {@code GET
   * /traces/{processKind}/{processId}} instead of {@code /metrics/...}, via {@link
   * #handleHistoryProxy}.
   */
  private void handleTracesHistory(HttpExchange exchange) {
    handleHistoryProxy(exchange, "/traces-history/", "/traces/", "traces history");
  }

  /**
   * Shared by {@link #handleMetricsHistory} and {@link #handleTracesHistory}: the same {@code
   * ResourceKind.LOGS}/{@code Verb.READ} gate the rest of this class's own {@code /logs/*} surface
   * uses (metrics and traces are treated as the same shape of thing as logs -- no dedicated {@code
   * METRICS}/{@code TRACES} resource kind), {@code since}-only, no backward paging -- a deliberate
   * scope-narrowing, not an oversight. Unlike {@code /logs/*}, there is no live-agent path to fall
   * back from here: Muninn's shipped history *is* the only place a process's own metrics or traces
   * ever live, so a missing {@code muninnClient} is a plain 404 rather than a proxy failure.
   *
   * @param pathPrefix this route's own path prefix, e.g. {@code "/metrics-history/"}
   * @param muninnPathPrefix the corresponding prefix on Muninn's own read API, e.g. {@code
   *     "/metrics/"}
   * @param requestNoun what to call this request kind in a warning log line, e.g. {@code "metrics
   *     history"}
   */
  private void handleHistoryProxy(
      HttpExchange exchange, String pathPrefix, String muninnPathPrefix, String requestNoun) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      if (!requireAuthorized(exchange, ResourceKind.LOGS, Verb.READ, Optional.empty())) {
        return;
      }
      String tail = pathSegmentAfter(exchange, pathPrefix);
      String[] parts = tail.split("/", 2);
      if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
        respond(exchange, 400, "expected " + pathPrefix + "{processKind}/{processId}");
        return;
      }
      if (muninnClient == null) {
        respond(exchange, 404, "no muninn endpoint configured");
        return;
      }
      Map<String, String> query = parseQuery(exchange);
      String since = query.get("since");
      String muninnPath =
          muninnPathPrefix + parts[0] + "/" + parts[1] + (since != null ? "?since=" + since : "");
      proxyToMuninn(exchange, muninnPath);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("{} request failed: {}", requestNoun, e.getMessage());
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
    // Only bother computing/attempting a Muninn fallback when one is actually configured -- with
    // no muninnClient, every one of proxyToAgent's three failure branches keeps its original
    // 404/502 behavior unchanged rather than routing through proxyToMuninn's own "no muninn
    // endpoint configured" 404, which would otherwise clobber the pre-existing status codes and
    // messages callers already depend on.
    String muninnFallbackPath = muninnClient == null ? null : muninnNodeLogsPath(nodeId, exchange);
    proxyToAgent(exchange, nodeId, "/logs/nodes/" + nodeId, muninnFallbackPath);
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
    // Muninn only ever ingested the plain PLATFORM/APPLICATION shape -- a
    // crashdumps sub-path (a whole-file directory listing/fetch, never routed through
    // LogFileReader in the first place) has no Muninn-side equivalent to fall back to.
    // Same "only when actually configured" gating as handleNodeLogsProxy above, on top of the
    // existing crashdumps-subPath exclusion.
    String subPath = parts.length == 3 ? parts[2] : null;
    String muninnFallbackPath =
        subPath != null || muninnClient == null
            ? null
            : muninnInstanceLogsPath(deploymentName, instanceIndex, exchange);
    String nodeId =
        storeClient.listAssignmentsFor(deploymentName).stream()
            .filter(a -> a.instanceIndex() == instanceIndex)
            .map(InstanceAssignment::nodeId)
            .findFirst()
            .orElse(null);
    if (nodeId == null) {
      if (muninnFallbackPath != null) {
        proxyToMuninn(exchange, muninnFallbackPath);
        return;
      }
      respond(exchange, 404, "no placement found for " + deploymentName + "#" + instanceIndex);
      return;
    }
    // Forward the original tail verbatim (not reconstructed from just name/index) so any sub-path
    // -- crashdumps, crashdumps/<name> -- survives the proxy hop unchanged.
    proxyToAgent(exchange, nodeId, "/logs/instances/" + tail, muninnFallbackPath);
  }

  /**
   * {@code /logs/nodes/{nodeId}/{category}} -- Muninn's own path-segment convention for category,
   * translated from this surface's own query-parameter convention (matching {@code
   * AgentLogServer.handleNodeLogs}'s {@code category} default of {@code PLATFORM}). {@code
   * follow}/{@code category} are stripped from the forwarded query; everything else ({@code
   * cursor}/{@code since}/{@code limit}) passes through unchanged -- {@code follow=true} reaching
   * this fallback is silently dropped rather than erroring (Muninn only ever serves shipped
   * history, never a live tail), so a client that was following a now-gone node still gets a
   * non-follow page back instead of a hard failure.
   */
  private static String muninnNodeLogsPath(String nodeId, HttpExchange exchange) {
    Map<String, String> query = parseQuery(exchange);
    String category = query.getOrDefault("category", "PLATFORM");
    return "/logs/nodes/" + nodeId + "/" + category + forwardedQuery(query);
  }

  /** Same translation as {@link #muninnNodeLogsPath}, for the instance-scoped shape. */
  private static String muninnInstanceLogsPath(
      String deploymentName, int instanceIndex, HttpExchange exchange) {
    Map<String, String> query = parseQuery(exchange);
    String category = query.getOrDefault("category", "APPLICATION");
    return "/logs/instances/"
        + deploymentName
        + "/"
        + instanceIndex
        + "/"
        + category
        + forwardedQuery(query);
  }

  private static String forwardedQuery(Map<String, String> query) {
    StringBuilder qs = new StringBuilder();
    for (Map.Entry<String, String> entry : query.entrySet()) {
      if (entry.getKey().equals("category") || entry.getKey().equals("follow")) {
        continue;
      }
      qs.append(qs.isEmpty() ? '?' : '&')
          .append(entry.getKey())
          .append('=')
          .append(entry.getValue());
    }
    return qs.toString();
  }

  /** Relays Muninn's response verbatim; no configured {@code muninnClient} is just a plain 404. */
  private void proxyToMuninn(HttpExchange exchange, String muninnPath) throws IOException {
    if (muninnClient == null) {
      respond(exchange, 404, "not found (no live agent, and no muninn endpoint configured)");
      return;
    }
    MuninnClient.RawResponse response;
    try {
      response = muninnClient.get(muninnPath);
    } catch (IOException e) {
      respond(
          exchange,
          404,
          "not found (no live agent, and muninn unreachable: " + e.getMessage() + ")");
      return;
    }
    exchange.getResponseHeaders().add("Content-Type", response.contentType());
    exchange.sendResponseHeaders(response.statusCode(), response.body().length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(response.body());
    }
  }

  /**
   * Looks up the owning node's self-reported log-server address and forwards the request as-is --
   * falling back to Muninn whenever a live agent genuinely isn't reachable: an unregistered node, a
   * registered node with no advertised log-server address yet, or a registered-and-advertised node
   * whose agent the actual request still couldn't reach. {@code muninnFallbackPath} is {@code null}
   * for a request shape Muninn has no equivalent for (see {@link #handleInstanceLogsProxy}'s
   * crashdumps case), in which case these three conditions keep their original 404/502 behavior
   * unchanged.
   */
  private void proxyToAgent(
      HttpExchange exchange, String nodeId, String path, String muninnFallbackPath)
      throws IOException {
    Optional<NodeRegistration> registration = storeClient.getNodeRegistration(nodeId);
    if (registration.isEmpty()) {
      if (muninnFallbackPath != null) {
        proxyToMuninn(exchange, muninnFallbackPath);
        return;
      }
      respond(exchange, 404, "unknown node: " + nodeId);
      return;
    }
    Optional<String> apiAddress = registration.flatMap(NodeRegistration::apiAddress);
    if (apiAddress.isEmpty()) {
      // A known node whose agent hasn't self-advertised a log-server address yet -- still a
      // legitimate "upstream not ready" gateway condition, unlike the truly-unknown-node case
      // above, which isn't a gateway problem at all.
      if (muninnFallbackPath != null) {
        proxyToMuninn(exchange, muninnFallbackPath);
        return;
      }
      respond(exchange, 502, "node " + nodeId + " has no known log-server address");
      return;
    }
    String query = exchange.getRequestURI().getRawQuery();
    URI target =
        URI.create("http://" + apiAddress.get() + path + (query != null ? "?" + query : ""));

    if (query != null && query.contains("follow=true")) {
      HttpRequest request = HttpRequest.newBuilder(target).GET().build();
      proxyFollowToAgent(exchange, apiAddress.get(), request);
      return;
    }

    HttpRequest request =
        HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10)).GET().build();
    HttpResponse<InputStream> response;
    try {
      response = agentHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (muninnFallbackPath != null) {
        proxyToMuninn(exchange, muninnFallbackPath);
        return;
      }
      respond(exchange, 502, "interrupted while proxying to agent " + apiAddress.get());
      return;
    } catch (IOException e) {
      if (muninnFallbackPath != null) {
        proxyToMuninn(exchange, muninnFallbackPath);
        return;
      }
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
      String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
      String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      result.put(key, value);
    }
    return result;
  }

  // ---- /bootstrap/csr, /bootstrap/csr/{id}[/approve], /bootstrap/tokens ----

  private static final Duration LEAF_VALIDITY = Duration.ofDays(397);

  /**
   * No {@link #requireAuthorized} call here, deliberately: this is the one endpoint that by design
   * must be reachable without a client certificate -- it exists specifically to issue the cert that
   * makes mTLS possible everywhere else. Three distinct auth contexts, distinguished entirely by
   * what the request carries: a verified peer certificate present at all means rotation (subject
   * must match); none present and {@code purpose == NODE_CLIENT} means a node join, authenticated
   * by a one-time bootstrap token; none present and {@code purpose == OPERATOR_CLIENT} means a
   * human operator request, never auto-approved.
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
          Arrays.equals(
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
    // Server-stamped O=, never the CSR's own: a
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
   * {@link #handleBootstrapCsrSubResource}'s {@code /approve} branch already requires {@code
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
   * (unchanged behavior, no enforcement, matching today's baseline), else resolves a {@link
   * Principal} from either a verified peer certificate or a verified session cookie and checks it
   * against {@link #authorizer}. Two distinct status codes where the pre-RBAC {@code
   * requireClientCertificate} this replaces only ever wrote one: {@code 401} when there is no
   * usable identity at all, {@code 403} when the identity is known but lacks the permission. Writes
   * the response itself on failure so every call site can just {@code return} without duplicating
   * it.
   *
   * <p>Also the single choke point every mutating decision passes through with its principal,
   * resource, and verb already in hand -- so for {@link Verb#WRITE}/{@link Verb#DELETE} (never
   * {@link Verb#READ}, matching Kubernetes' own default audit policy: a console page-load's worth
   * of {@code GET}s would dwarf the mutating-action volume actually worth capturing) this is where
   * the decision is recorded into the durable, queryable audit trail (see {@link AuditEvent}), both
   * allowed and denied alike -- a denial is exactly as auditable as a grant. A bare {@code 401} (no
   * principal resolved at all) is deliberately not audited, since there's no principal to attribute
   * the attempt to; only a resolved-but-denied principal produces an event.
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
    boolean authorized = authorizer.authorize(principal.get(), resource, verb, tenant, targetId);
    if (verb == Verb.WRITE
        || verb == Verb.DELETE
        || (verb == Verb.READ && auditReadResourceKinds.contains(resource))) {
      recordAuditEvent(principal.get(), resource, verb, tenant, targetId, authorized);
    }
    if (!authorized) {
      respondQuietly(exchange, 403, "forbidden");
      return false;
    }
    return true;
  }

  /**
   * Parses {@code gimle.controlplane.audit.readResourceKinds} into the set {@link
   * #requireAuthorized} checks a {@link Verb#READ} decision's resource kind against -- blank (the
   * property's own default) yields an empty set, reproducing the pre-existing "reads are never
   * audited" behavior exactly. An unknown {@link ResourceKind} name fails fast at startup rather
   * than silently auditing nothing for it.
   */
  private static Set<ResourceKind> parseAuditReadResourceKinds(String csv) {
    if (csv.isBlank()) {
      return Set.of();
    }
    Set<ResourceKind> kinds = new LinkedHashSet<>();
    for (String name : csv.split(",")) {
      kinds.add(ResourceKind.valueOf(name.trim()));
    }
    return Set.copyOf(kinds);
  }

  private void recordAuditEvent(
      Principal principal,
      ResourceKind resource,
      Verb verb,
      Optional<String> tenant,
      Optional<String> targetId,
      boolean allowed) {
    storeClient.propose(
        new StateMutation.AppendAuditEvent(
            new AuditEvent(
                UUID.randomUUID().toString(),
                principal.name(),
                principal.groups(),
                resource.name(),
                verb.name(),
                tenant,
                targetId,
                allowed,
                System.currentTimeMillis())));
  }

  /** {@code targetId}-less convenience overload for the majority of call sites that need none. */
  private boolean requireAuthorized(
      HttpExchange exchange, ResourceKind resource, Verb verb, Optional<String> tenant) {
    return requireAuthorized(exchange, resource, verb, tenant, Optional.empty());
  }

  /**
   * The one veto in this codebase that an ordinary {@link RoleBinding} can never grant its way
   * around: {@link #requireAuthorized} already ran and passed by the time either call site below
   * reaches this, so a caller with no {@link ResourceKind#TENANT}/{@link ResourceKind#DEPLOYMENT}/
   * etc. permission whatsoever still gets that check's own plain 403, never something that reveals
   * gimle-system's reserved status to someone not even in the ballpark. Only when the ordinary
   * check already said yes does this ask the one further question that matters here: is this
   * specifically the bootstrap-level operator credential, not merely some grant broad enough to
   * otherwise qualify. Sends the 403 itself and returns {@code true} so both call sites read as a
   * single guard clause.
   */
  private boolean rejectIfReservedSystemTenant(HttpExchange exchange, Optional<String> tenantId) {
    if (!isReservedSystemTenant(tenantId) || isOperatorCaller(exchange)) {
      return false;
    }
    respondQuietly(
        exchange, 403, "gimle-system is reserved for gimle:operators-group callers only");
    return true;
  }

  private static boolean isReservedSystemTenant(Optional<String> tenantId) {
    return tenantId.filter(Tenant.RESERVED_SYSTEM_TENANT_ID::equals).isPresent();
  }

  /**
   * True for a caller carrying the bootstrap-level {@code gimle:operators} group -- reusing the
   * exact signal {@link Authorizer#authorize} already special-cases as its implicit cluster-admin
   * bypass, rather than inventing a second notion of "trusted enough." Plaintext mode resolves no
   * principal at all (see {@link #requireAuthorized}'s identical carve-out just above) and is
   * treated as trusted here too, matching every other authorization decision this class makes for a
   * plaintext deployment.
   */
  private boolean isOperatorCaller(HttpExchange exchange) {
    if (!(exchange instanceof HttpsExchange)) {
      return true;
    }
    return resolvePrincipal(exchange)
        .map(principal -> principal.groups().contains(BuiltinRoles.GROUP_OPERATORS))
        .orElse(false);
  }

  /**
   * A verified client certificate wins over a session cookie when both are somehow present (mTLS is
   * the stronger proof) -- in practice only one is ever offered by a given caller (the CLI/node
   * agents never send a session cookie, the console never presents a client certificate).
   */
  private Optional<Principal> resolvePrincipal(HttpExchange exchange) {
    Optional<X509Certificate> certificate = peerCertificate(exchange);
    if (certificate.isPresent()) {
      return Optional.of(Subjects.principalFrom(certificate.get()));
    }
    return sessionCookie(exchange)
        .flatMap(token -> SessionTokens.verify(token, sessionSigningKey))
        .map(username -> new Principal(username, Set.of()));
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
   * endpoint was tried, including one leader-follow retry against a {@code NotLeader} hint, before
   * {@link GimleRaftException} was thrown. {@code 503}, not the pre-split {@code 307} redirect:
   * leader routing is now entirely internal to {@code StoreClient}, invisible to the HTTP caller,
   * so this process has no leader address left to redirect anyone to -- a simplification of the
   * client contract, not a lesser response.
   */
  private void respondStoreUnavailable(HttpExchange exchange) {
    respondQuietly(exchange, 503, "store temporarily unavailable; retry shortly");
  }
}
