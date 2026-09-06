package com.gimle.controlplane.api;

import com.gimle.controlplane.ConsoleAddons;
import com.gimle.controlplane.admission.AdmissionChain;
import com.gimle.controlplane.admission.AdmissionDecision;
import com.gimle.controlplane.admission.ConfigMapRefsPlugin;
import com.gimle.controlplane.admission.LimitRangePlugin;
import com.gimle.controlplane.admission.PolicyConfigPlugin;
import com.gimle.controlplane.admission.SecretMapRefsPlugin;
import com.gimle.controlplane.admission.TenantQuotaPlugin;
import com.gimle.controlplane.admission.WorkloadResourceProfile;
import com.gimle.controlplane.alert.AlertRuleRegistry;
import com.gimle.controlplane.andvari.AndvariClient;
import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.controlplane.authz.BootstrapAccountFile;
import com.gimle.controlplane.config.ConfigDeleteOutcome;
import com.gimle.controlplane.config.ConfigRollbackOutcome;
import com.gimle.controlplane.config.ConfigVersion;
import com.gimle.controlplane.config.ConfigVersionStore;
import com.gimle.controlplane.config.ConfigWriteOutcome;
import com.gimle.controlplane.configmap.ConfigMap;
import com.gimle.controlplane.configmap.ConfigMapCodec;
import com.gimle.controlplane.configmap.ConfigMapDeleteOutcome;
import com.gimle.controlplane.configmap.ConfigMapRollbackOutcome;
import com.gimle.controlplane.configmap.ConfigMapStore;
import com.gimle.controlplane.configmap.ConfigMapVersion;
import com.gimle.controlplane.configmap.ConfigMapWriteResult;
import com.gimle.controlplane.fafnir.FafnirClient;
import com.gimle.controlplane.galdr.CustomResourceManifestParser;
import com.gimle.controlplane.galdr.GaldrJson;
import com.gimle.controlplane.galdr.GaldrKinds;
import com.gimle.controlplane.galdr.KindDefinitionParser;
import com.gimle.controlplane.ingress.IngressRegistry;
import com.gimle.controlplane.ingress.IngressWriteResult;
import com.gimle.controlplane.muninn.MuninnClient;
import com.gimle.controlplane.networkpolicy.NetworkPolicyPatch;
import com.gimle.controlplane.networkpolicy.NetworkPolicyRegistry;
import com.gimle.controlplane.networkpolicy.NetworkPolicyWriteResult;
import com.gimle.controlplane.node.NodeFreshness;
import com.gimle.controlplane.pki.BootstrapTokenRegistry;
import com.gimle.controlplane.pki.CaKeyMaterial;
import com.gimle.controlplane.pki.PendingCsrStore;
import com.gimle.controlplane.preview.DryRunVerdict;
import com.gimle.controlplane.preview.PlacementForecast;
import com.gimle.controlplane.preview.PreviewCheck;
import com.gimle.controlplane.preview.PreviewOutcome;
import com.gimle.controlplane.preview.WorkloadPlacementPreview;
import com.gimle.controlplane.reconcile.CronJobReconciler;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.controlplane.service.ServiceAdvisories;
import com.gimle.controlplane.service.ServiceEndpoint;
import com.gimle.controlplane.service.ServiceEndpointResolver;
import com.gimle.controlplane.service.ServiceRegistry;
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
import com.gimle.core.exception.GimleCodecException;
import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.exception.GimleRaftException;
import com.gimle.core.ingress.IngressRule;
import com.gimle.core.io.SizeLimitedInputStream;
import com.gimle.core.logging.LogFileReader;
import com.gimle.core.logging.LogFilter;
import com.gimle.core.module.ArtifactKind;
import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.AuditOutcome;
import com.gimle.core.protocol.AuditTrailStatus;
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
import com.gimle.core.tenant.TenantIsolationPosture;
import com.gimle.core.throttle.ConcurrencyLimiter;
import com.gimle.core.throttle.LoginThrottle;
import com.gimle.core.throttle.RequestRateLimiter;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.core.vessel.VesselArtifacts;
import com.gimle.core.vessel.VesselEnvValue;
import com.gimle.core.vessel.VesselFileMount;
import com.gimle.core.vessel.VesselProbeSpec;
import com.gimle.core.vessel.VesselSpec;
import com.gimle.core.web.RootRedirectHandler;
import com.gimle.core.web.SpaStaticHandler;
import com.gimle.mimir.authz.Authorizer;
import com.gimle.mimir.authz.CertificateRotationAuditor;
import com.gimle.mimir.galdr.CustomResource;
import com.gimle.mimir.galdr.KindDefinitionSpec;
import com.gimle.mimir.galdr.KindScope;
import com.gimle.mimir.galdr.SchemaValidator;
import com.gimle.mimir.manifest.AlertRuleSpec;
import com.gimle.mimir.manifest.AutoscalePolicy;
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.DisruptionBudget;
import com.gimle.mimir.manifest.IngressSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.LimitRangeSpec;
import com.gimle.mimir.manifest.ManifestParser;
import com.gimle.mimir.manifest.NetworkPolicySpec;
import com.gimle.mimir.manifest.ParsedManifest;
import com.gimle.mimir.manifest.ServiceProtocol;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.manifest.WorkloadSpec;
import com.gimle.mimir.raft.MutationOutcome;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.store.ControllerRevision;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.JobRun;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.mimir.store.WorkloadTokenRecord;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.observability.ApiServerMetrics;
import com.gimle.observability.CertificateRotationMetrics;
import com.gimle.observability.GimleTracing;
import com.gimle.observability.ObservedProcessKind;
import com.gimle.observability.ServerSpan;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateRotationMonitor;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
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

  /**
   * The identity of a caller that presented no credential at all -- the only identity available
   * under plaintext transport, where nothing authenticates anybody. Deliberately a real principal
   * belonging to no group whatsoever rather than the absence of one: a membership check ({@link
   * BuiltinRoles#GROUP_OPERATORS}, say) needs a subject it can answer "no" for, and every audit row
   * written in this mode needs a name to attribute the write to.
   */
  private static final Principal ANONYMOUS_PRINCIPAL = new Principal("anonymous", Set.of());

  private static final Duration SESSION_TTL = Duration.ofHours(12);
  // Generous on purpose: gimle-system hosts the platform's own self-hosted extensions, not a
  // workload a human sizes deployment-by-deployment, so these ceilings just need enough headroom
  // that an operator never has to tune them for ordinary platform-extension traffic.
  private static final ResourceQuota RESERVED_SYSTEM_TENANT_QUOTA =
      new ResourceQuota(64L * 1024 * 1024 * 1024, 32_000, 1000);
  // Generous on purpose, for the same reason: an untenanted deployment previously skipped quota
  // checking entirely (see Tenant#DEFAULT_TENANT_ID's own javadoc), so this default must not turn
  // into a surprise rejection for a workload that never had to fit inside any quota before.
  private static final ResourceQuota DEFAULT_TENANT_QUOTA =
      new ResourceQuota(32L * 1024 * 1024 * 1024, 16_000, 500);

  private final StoreClient storeClient;
  private final NodeFreshness nodeFreshness = NodeFreshness.standard();
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

  /** Instrumentation scope every span this server starts is attributed to. */
  private static final String TRACING_SCOPE = "com.gimle.controlplane.api";

  /**
   * Mirrors the cadence {@code ControlPlaneMain}'s own ticker calls {@link
   * #checkAndRotateOwnCertificateIfDue} at. Used only to report when the next attempt is due in a
   * rotation-failure log line and status -- nothing here schedules anything.
   */
  private static final Duration CERT_ROTATION_CHECK_INTERVAL = Duration.ofSeconds(2);

  // Own-certificate rotation health, published into the same registry #metrics already ships and
  // appended to the durable audit trail: a rotation that quietly stops working stays harmless only
  // until the certificate it failed to renew expires, so the check needs a signal an operator can
  // alert on long before that.
  private final CertificateRotationMetrics certificateRotationMetrics =
      new CertificateRotationMetrics(metrics.registry());
  private final OwnCertificateRotator certificateRotator;
  // The ordered admission chain shared by every placeable workload kind's own PUT (Deployment,
  // Job, DaemonSet, StatefulSet) -- quota and limit-range enforcement, generalized over
  // WorkloadSpec rather than Deployment alone (see WorkloadResourceProfile's own javadoc for why),
  // run before deploymentAdmissionChain's own Deployment-only checks below. Not exposed through
  // any public constructor parameter, same reasoning as {@code metrics} above: no test/caller has
  // ever needed to inject a custom plugin list. Built in the constructor body (not a field
  // initializer) because TenantQuotaPlugin needs this.artifactResolver, which instance field
  // initializers run before the constructor body assigns.
  private final AdmissionChain<WorkloadSpec> workloadAdmissionChain;
  // Deployment-only admission checks that don't generalize to every workload kind: {@code
  // PolicyConfigPlugin}'s opt-in organization-specific rules, and ConfigMapRefs/SecretMapRefs
  // narrowing, both fields that exist only on DeploymentSpec. Runs after workloadAdmissionChain
  // above (see handlePutDeployment) -- quota/limit-range still rejects first, matching this
  // chain's own pre-generalization plugin order exactly.
  private final AdmissionChain<DeploymentSpec> deploymentAdmissionChain;

  /**
   * The dry-run path's placement forecaster. Reads the store and runs the real {@link Scheduler};
   * proposes nothing, which is what lets a PUT answer "where would this land?" without landing it.
   */
  private final WorkloadPlacementPreview placementPreview;

  // Signs/verifies console session cookies -- deliberately a separate key from anything Fafnir
  // manages, for key separation between two unrelated crypto purposes (see SessionTokens' own
  // javadoc). Never rotated -- a session token's own short TTL already bounds its exposure window,
  // unlike a secret's config-entry value which persists indefinitely. Stays local to ApiServer
  // precisely because it's not secret-value material Fafnir's own security boundary is about.
  private final SecretKey sessionSigningKey;
  private final SecureRandom secureRandom = new SecureRandom();
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
  // Specs persist through storeClient into the Raft-replicated store, the same as ServiceRegistry
  // above and every other resource kind here -- see NetworkPolicyRegistry's own javadoc. Read by
  // gimle-agent's own poller (GET /networkpolicies below), never by a reconciler -- nothing in this
  // process itself needs to act on a NetworkPolicySpec, only relay it downstream unchanged.
  private final NetworkPolicyRegistry networkPolicyRegistry;
  private final IngressRegistry ingressRegistry;
  // Same delegate-to-the-store shape as networkPolicyRegistry above -- see AlertRuleRegistry's own
  // javadoc. Read by ControlPlaneMain's own AlertReconciler, evaluated on the same reconcile tick
  // every other resource kind here converges on.
  private final AlertRuleRegistry alertRuleRegistry;
  // Backed by the same ConfigEntry store, under its own "configmap:" synthetic-key convention --
  // see ConfigMapCodec's own javadoc. Unlike networkPolicyRegistry above, constructed with the
  // full StoreClient rather than a StoreReader/MutationSink pair: its write path needs the lease
  // and linearizable-read primitives only StoreClient exposes.
  private final ConfigMapStore configMapStore;
  // Version history for plain, unencrypted /config/* entries -- see the class's own javadoc for why
  // it's a separate ledger rather than a change to the live ConfigEntry row's own shape.
  private final ConfigVersionStore configVersionStore;
  // Throttles /auth/login by username and by remote address independently -- see the
  // class's own javadoc for why in-memory/per-replica is the right call here, not a StateMutation.
  private final LoginThrottle loginThrottle = new LoginThrottle();
  // Rate-limits POST /bootstrap/csr, the one route here that must answer a caller holding no
  // credential at all, and the most expensive one per request (a PKCS#10 parse and signature
  // verify, then an RSA signing for an auto-approved join) -- so it is the cheapest endpoint to
  // turn into a CPU-exhaustion lever and the only one where an unauthenticated flood is possible.
  // Two independent buckets: one keyed by remote address, so one source can't spend the whole
  // cluster's budget, and one shared across every caller, so a distributed source can't either.
  // Both are sized for a real fleet bring-up -- every node of a cluster joining at once, each
  // submitting exactly one CSR, all of them arriving from a single address when the fleet sits
  // behind one NAT or on one development machine -- because a joining agent treats a rejected
  // submission as fatal and does not retry. Raise gimle.controlplane.csr.burstPerAddress for a
  // fleet larger than one burst behind a single address.
  private final RequestRateLimiter csrAddressRateLimiter =
      new RequestRateLimiter(
          Integer.getInteger(CSR_BURST_PER_ADDRESS_PROPERTY, 200),
          Duration.ofMillis(Long.getLong(CSR_REFILL_MILLIS_PER_ADDRESS_PROPERTY, 1_000L)));
  private final RequestRateLimiter csrClusterRateLimiter =
      new RequestRateLimiter(
          Integer.getInteger(CSR_CLUSTER_BURST_PROPERTY, 1_000),
          Duration.ofMillis(Long.getLong(CSR_CLUSTER_REFILL_MILLIS_PROPERTY, 50L)));
  // Bounds how fast any single source may be served *at all*, whatever route it calls and whether
  // or not it authenticates -- the backstop the CSR buckets above only ever provided for one route.
  // Hooked into #instrument, so every registered context is covered and a new route cannot forget
  // to opt in.
  //
  // Keyed by remote address only, with deliberately no cluster-wide companion bucket, which is the
  // one place this differs from the CSR pair above. A shared bucket would let a single flooding
  // source spend the budget every other caller draws from -- including the node agents' own
  // heartbeats, whose starvation the control plane reads as nodes going dark and answers with mass
  // rescheduling. That converts a flood from one source into a cluster-wide outage, which is worse
  // than the unbounded surface it set out to fix. CSR can afford a shared bucket because
  // submissions are rare and bounded by fleet size; ordinary API traffic is neither.
  //
  // Sized as a flood backstop rather than a quota: a node agent's heartbeat/assignment polling, an
  // operator's bulk apply, and a console page load's asset burst all sit far below it, so the
  // limit is only ever reached by traffic no legitimate caller generates.
  private final Optional<RequestRateLimiter> requestRateLimiter = buildRequestRateLimiter();
  // Bounds how many requests may be executing at once across every ordinary route -- a second,
  // orthogonal defense from the rate limiter just above. requestRateLimiter (and
  // csrAddressRateLimiter/csrClusterRateLimiter/loginThrottle) govern *acceptance*: how often a key
  // may be charged a fresh attempt. None of them bounds how many already-accepted requests may be
  // running concurrently, so a flood spread across enough distinct addresses to stay under the
  // per-address rate limit still piles up as raw thread/connection volume until the JVM itself
  // falls over -- accepted and left to time out rather than ever reaching a 429. tryAcquire is
  // non-blocking, so a caller past the budget is turned away immediately instead of queued behind
  // whoever is already in flight.
  static final String ADMISSION_GENERAL_LIMIT_PROPERTY =
      "gimle.controlplane.admission.maxConcurrentRequests";
  private final ConcurrencyLimiter generalAdmission =
      new ConcurrencyLimiter(Integer.getInteger(ADMISSION_GENERAL_LIMIT_PROPERTY, 200));
  // A second, structurally independent budget for node-agent traffic (register/heartbeat/
  // assignments/cordon/taint/events, everything under /nodes/{nodeId}/...) -- its own Semaphore,
  // never a carve-out of generalAdmission's own count, so a flood that exhausts every ordinary
  // route's budget can never leave a node agent's own heartbeat with nothing to acquire. This is
  // the specific cascade a flood on an unrelated read endpoint used to trigger: a node's heartbeat
  // timing out, the control plane concluding the node had gone dark, and reconcilers rescheduling
  // every instance it was told to run.
  static final String ADMISSION_NODE_LIMIT_PROPERTY =
      "gimle.controlplane.admission.maxConcurrentNodeRequests";
  private final ConcurrencyLimiter nodeAdmission =
      new ConcurrencyLimiter(Integer.getInteger(ADMISSION_NODE_LIMIT_PROPERTY, 64));
  private final PendingCsrStore pendingCsrStore = new PendingCsrStore();
  private final Instant startedAt = Instant.now();
  static final String STORE_PROBE_INTERVAL_PROPERTY =
      "gimle.controlplane.health.storeProbeIntervalMillis";

  /**
   * How stale the last completed store probe may be before {@code /health} calls the store down.
   * Generous enough by default to ride out a couple of missed cycles, short enough that a probe
   * wedged behind {@code StoreClient}'s own leader search is reported rather than waited on.
   */
  static final String STORE_PROBE_MAX_AGE_PROPERTY =
      "gimle.controlplane.health.storeProbeMaxAgeMillis";

  /** How long {@link #start()} waits on the first store probe before carrying on without it. */
  private static final Duration FIRST_STORE_PROBE_GRACE = Duration.ofSeconds(2);

  private final Duration storeProbeInterval =
      Duration.ofMillis(Long.getLong(STORE_PROBE_INTERVAL_PROPERTY, 2_000));
  private final Duration storeProbeMaxAge =
      Duration.ofMillis(Long.getLong(STORE_PROBE_MAX_AGE_PROPERTY, 15_000));

  private final ScheduledExecutorService storeProbe =
      Executors.newSingleThreadScheduledExecutor(
          r -> Thread.ofPlatform().name("controlplane-store-probe").daemon(true).unstarted(r));

  /**
   * The last completed store probe. {@code /health} reads this and answers immediately instead of
   * making the caller wait on a live round trip: a store with no reachable leader blocks that round
   * trip for as long as {@code StoreClient}'s own leader search runs, and a probe that answers
   * nothing at all is strictly worse than one that answers "down" -- a caller cannot act on
   * silence, and enough silent handlers pile up to take the endpoint out entirely.
   */
  private volatile StoreProbeResult lastStoreProbe = StoreProbeResult.notYetProbed();

  private record StoreProbeResult(boolean up, int tenantCount, String reason, Instant completedAt) {

    static StoreProbeResult notYetProbed() {
      return new StoreProbeResult(false, 0, "store has not been probed yet", Instant.EPOCH);
    }

    static StoreProbeResult up(int tenantCount) {
      return new StoreProbeResult(true, tenantCount, "", Instant.now());
    }

    static StoreProbeResult down(String reason) {
      return new StoreProbeResult(false, 0, reason, Instant.now());
    }
  }

  // Absent in plaintext mode, or in TLS mode when gimle.tls.caKeyFile isn't configured on this
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
  private volatile Optional<ConsoleAddons> consoleAddons = Optional.empty();

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
    this.artifactResolver =
        artifactResolver == null ? ArtifactResolver.localOnly() : artifactResolver;
    // Must share this same resolver (not ArtifactResolver.localOnly()) -- otherwise a CronJob
    // whose jobTemplate names a registry coordinate could never resolve it, and every one of its
    // firings would fail the tenant-quota admission check below with "artifact unreadable" even
    // though the coordinate is perfectly resolvable through the configured registry.
    this.cronJobReconciler =
        new CronJobReconciler(storeClient, storeClient, Clock.systemUTC(), this.artifactResolver);
    this.serviceRegistry = new ServiceRegistry(storeClient, storeClient);
    this.networkPolicyRegistry = new NetworkPolicyRegistry(storeClient);
    this.ingressRegistry = new IngressRegistry(storeClient);
    this.alertRuleRegistry = new AlertRuleRegistry(storeClient, storeClient);
    this.configMapStore = new ConfigMapStore(storeClient);
    this.configVersionStore = new ConfigVersionStore(storeClient);
    this.fafnirClient = fafnirClient;
    this.muninnClient = muninnClient;
    this.workloadAdmissionChain =
        new AdmissionChain<>(
            List.of(new LimitRangePlugin(), new TenantQuotaPlugin(this.artifactResolver)));
    this.deploymentAdmissionChain =
        new AdmissionChain<>(
            List.of(
                new PolicyConfigPlugin(), new ConfigMapRefsPlugin(), new SecretMapRefsPlugin()));
    this.placementPreview =
        new WorkloadPlacementPreview(storeClient, new Scheduler(), Clock.systemUTC());
    this.sessionSigningKey = sessionSigningKey;
    this.authorizer = new Authorizer(storeClient);
    this.certificateRotator =
        new OwnCertificateRotator(
            new CertificateRotationMonitor(
                "control plane",
                CERT_ROTATION_CHECK_INTERVAL,
                new CertificateRotationAuditor(storeClient, "control-plane")
                    .andThen(
                        status ->
                            certificateRotationMetrics.recordCheck(
                                status.outcome().name(),
                                status.consecutiveFailures(),
                                status.remainingValidity(Instant.now())))));
    seedReservedSystemTenantIfAbsent();
    seedDefaultTenantIfAbsent();
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

  /**
   * Ensures {@link Tenant#DEFAULT_TENANT_ID} exists as a real, persisted row before any request is
   * ever served, the same check-then-propose shape {@link #seedReservedSystemTenantIfAbsent} uses
   * just above and for the identical reason: every manifest parser now resolves an omitted {@code
   * tenantId} to this id (see that field's own javadoc), so {@code TenantQuotaPlugin}'s existence
   * check must never see it as unknown. Unlike the reserved system tenant, this one is an ordinary,
   * unreserved tenant -- no write/delete guard singles it out, and an operator may freely rename
   * its quota through the normal {@code /tenants/*} API without any special-casing.
   */
  private void seedDefaultTenantIfAbsent() {
    if (storeClient.getTenant(Tenant.DEFAULT_TENANT_ID).isPresent()) {
      return;
    }
    storeClient.propose(
        new StateMutation.PutTenant(new Tenant(Tenant.DEFAULT_TENANT_ID, DEFAULT_TENANT_QUOTA)));
  }

  private void registerContexts(HttpServer target) throws IOException {
    target.createContext("/health", instrument("health", this::handleHealth));
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
    target.createContext(
        "/instances/", instrument("instances", this::handleInstanceFabricEndpoint));
    target.createContext("/services/", instrument("services", this::handleService));
    target.createContext("/services", instrument("services", this::handleServicesCollection));
    target.createContext(
        "/networkpolicies/", instrument("networkpolicies", this::handleNetworkPolicy));
    target.createContext(
        "/networkpolicies", instrument("networkpolicies", this::handleNetworkPoliciesCollection));
    target.createContext("/ingresses/", instrument("ingresses", this::handleIngress));
    target.createContext("/ingresses", instrument("ingresses", this::handleIngressesCollection));
    target.createContext(
        "/networkpostures", instrument("networkpostures", this::handleNetworkPosturesList));
    target.createContext("/alertrules/", instrument("alertrules", this::handleAlertRule));
    target.createContext("/alertrules", instrument("alertrules", this::handleAlertRulesCollection));
    target.createContext("/configmaps/", instrument("configmaps", this::handleConfigMap));
    target.createContext("/metrics", instrument("metrics", this::handleMetrics));
    target.createContext("/events", instrument("events", this::handleEvents));
    target.createContext("/audit", instrument("audit", this::handleAudit));
    target.createContext("/nodes/", instrument("nodes", this::handleNode));
    target.createContext("/nodes", instrument("nodes", this::handleNodesList));
    target.createContext("/tenants/", instrument("tenants", this::handleTenant));
    target.createContext("/tenants", instrument("tenants", this::handleTenantsList));
    target.createContext("/limitranges/", instrument("limitranges", this::handleLimitRange));
    target.createContext("/limitranges", instrument("limitranges", this::handleLimitRangesList));
    target.createContext("/config/", instrument("config", this::handleConfig));
    target.createContext("/logs/", instrument("logs", this::handleLogs));
    target.createContext(
        "/metrics-history/", instrument("metrics-history", this::handleMetricsHistory));
    target.createContext(
        "/metrics-history", instrument("metrics-history", this::handleMetricsHistoryKinds));
    target.createContext(
        "/traces-history/", instrument("traces-history", this::handleTracesHistory));
    target.createContext(
        "/traces-history", instrument("traces-history", this::handleTracesHistoryKinds));
    target.createContext("/roles/", instrument("roles", this::handleRole));
    target.createContext("/roles", instrument("roles", this::handleRolesList));
    target.createContext("/rolebindings/", instrument("rolebindings", this::handleRoleBinding));
    target.createContext("/rolebindings", instrument("rolebindings", this::handleRoleBindingsList));
    target.createContext("/accounts/", instrument("accounts", this::handleAccount));
    target.createContext("/accounts", instrument("accounts", this::handleAccountsList));
    target.createContext(
        "/secrets/rotate-key", instrument("secrets-rotate-key", this::handleRotateSecretsKey));
    target.createContext(
        "/secrets/retire-key", instrument("secrets-retire-key", this::handleRetireSecretsKeyProxy));
    target.createContext(
        "/secrets/rewrap", instrument("secrets-rewrap", this::handleRewrapSecretsProxy));
    target.createContext("/secrets/", instrument("secrets", this::handleSecretsProxy));
    target.createContext("/secretmaps/", instrument("secretmaps", this::handleSecretMapsProxy));
    target.createContext(
        "/seal/public-key", instrument("seal-public-key", this::handleSealPublicKeyProxy));
    target.createContext(
        "/seal/rotate-key", instrument("seal-rotate-key", this::handleSealRotateKeyProxy));
    target.createContext(
        "/seal/retire-key", instrument("seal-retire-key", this::handleSealRetireKeyProxy));
    // One bare-prefix context with an in-handler path check rather than "/artifacts/" +
    // "/artifacts" pair: the catalog listing lives at the bare path, and the JDK server's
    // prefix matching would otherwise let "/artifactsX" through -- same defense AndvariServer's
    // own routing documents.
    target.createContext("/artifacts", instrument("artifacts", this::handleArtifactsProxy));
    target.createContext("/backup", instrument("backup", this::handleBackup));
    target.createContext("/restore", instrument("restore", this::handleRestore));
    target.createContext("/auth/login", instrument("auth-login", this::handleAuthLogin));
    target.createContext("/auth/logout", instrument("auth-logout", this::handleAuthLogout));
    target.createContext("/auth/session", instrument("auth-session", this::handleAuthSession));
    target.createContext("/authz/can-i", instrument("authz-can-i", this::handleCanI));
    target.createContext(
        "/authz/vocabulary", instrument("authz-vocabulary", this::handleAuthzVocabulary));
    target.createContext(
        "/kinddefinitions/", instrument("kinddefinitions", this::handleKindDefinition));
    target.createContext(
        "/kinddefinitions", instrument("kinddefinitions", this::handleKindDefinitionsList));
    target.createContext("/resources/", instrument("resources", this::handleCustomResources));
    target.createContext("/volumes/", instrument("volumes", this::handleVolumeDestroy));
    target.createContext("/volumes", instrument("volumes", this::handleVolumesList));
    target.createContext(
        "/certificates/revoked/",
        instrument("cert-revocations", this::handleCertificateRevocation));
    target.createContext(
        "/certificates/revoked",
        instrument("cert-revocations", this::handleCertificateRevocations));
    target.createContext(
        "/workload-tokens", instrument("workload-tokens", this::handleWorkloadTokenMint));
    if (certificateAuthority.isPresent()) {
      target.createContext(
          "/bootstrap/csr", instrument("bootstrap-csr", this::handleBootstrapCsrSubmit));
      target.createContext(
          "/bootstrap/csr/", instrument("bootstrap-csr", this::handleBootstrapCsrSubResource));
      target.createContext(
          "/bootstrap/tokens", instrument("bootstrap-tokens", this::handleBootstrapTokens));
    }
    if (consoleStaticRoot.isPresent()) {
      registerConsole(target, consoleStaticRoot.get(), consoleAddons.orElseThrow());
    }
  }

  /**
   * Wraps a handler with admission control and request-count/latency/error Micrometer recording, at
   * context-registration time rather than inside each handler body -- the identical
   * metrics-recording pattern {@code FafnirServer.instrument} already established, copied rather
   * than shared since the two classes have no common base to hang it on. {@code error} is read from
   * the exchange's own response code after the delegate finishes, not from an escaping exception --
   * every handler here already sends a real status and closes the exchange itself.
   *
   * <p>Admission control runs first, before the delegate does any work at all: the {@code "nodes"}
   * endpoint (every {@code /nodes/{nodeId}/...} action, including register/heartbeat/assignments)
   * draws from {@link #nodeAdmission}, its own reserved budget; every other endpoint draws from the
   * shared {@link #generalAdmission}. A caller finding no permit free is refused with a fast {@code
   * 429} rather than being handed to the delegate and left to contend for whatever resource is
   * actually saturated.
   */
  private HttpHandler instrument(String endpoint, HttpHandler delegate) {
    ConcurrencyLimiter admission = "nodes".equals(endpoint) ? nodeAdmission : generalAdmission;
    return exchange -> {
      String verb = exchange.getRequestMethod();
      if (!admission.tryAcquire()) {
        metrics.recordRequest(endpoint, verb, Duration.ZERO, true);
        exchange.getResponseHeaders().add("Retry-After", "1");
        respondQuietly(exchange, 429, "control plane at capacity; retry shortly");
        exchange.close();
        return;
      }
      long startNanos = System.nanoTime();
      // The control plane's own spans: nothing else in this process ever begins a trace, so
      // without this its shipped trace history stays permanently empty however correctly the
      // exporter behind it is wired. A no-op until a tracer provider is installed. Ended in the
      // finally block below rather than by try-with-resources, so the span carries the status the
      // handler actually produced -- a resource is closed before that status can be read.
      ServerSpan span =
          GimleTracing.startServerSpan(TRACING_SCOPE, verb + " /" + endpoint, endpoint, verb);
      try {
        Optional<Instant> retryAt = rateLimited(exchange);
        if (retryAt.isPresent()) {
          respondThrottled(exchange, retryAt.get());
          exchange.close();
          return;
        }
        delegate.handle(exchange);
      } catch (GimleRaftException e) {
        // A store that cannot currently serve a linearizable read or land a write is a retryable
        // condition, not this process's own internal error -- and it reaches here from any route
        // whose own handler doesn't already draw that distinction for itself. Guarded on nothing
        // having been sent yet: a handler that already answered and then failed on a later store
        // call must not have a second response appended to its own, which would leave the
        // connection's framing wrong for whatever the caller sends next.
        if (exchange.getResponseCode() <= 0) {
          respondStoreUnavailable(exchange);
        }
        exchange.close();
      } finally {
        admission.release();
        Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
        int status = exchange.getResponseCode();
        boolean error = status <= 0 || status >= 400;
        metrics.recordRequest(endpoint, verb, latency, error);
        span.recordStatus(status);
        span.close();
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

  public AlertRuleRegistry alertRuleRegistry() {
    return alertRuleRegistry;
  }

  /**
   * Registers a static-file context at {@code /console} serving the built SPA under {@code
   * staticRoot}, with client-side-route fallback to whichever shell file the SPA's tooling produced
   * -- {@code _shell.html} if present (TanStack Start's SPA mode), else the conventional {@code
   * index.html}. Opt-in: no constructor calls this, so every existing caller/test is unaffected
   * until something explicitly wires a console directory in. Remembered on {@link
   * #consoleStaticRoot} so a later {@link #reloadTlsMaterial} rebuild re-registers it too. Also
   * registers a {@code /} redirect to {@code /console} -- before this, the bare root address had no
   * context at all and fell through to the JDK server's own {@code 404}, which is what a person
   * actually gets when they type the console's host with no path.
   */
  public void serveConsole(Path staticRoot, ConsoleAddons addons) throws IOException {
    consoleStaticRoot = Optional.of(staticRoot);
    consoleAddons = Optional.of(addons);
    registerConsole(server, staticRoot, addons);
  }

  private static void registerConsole(HttpServer target, Path staticRoot, ConsoleAddons addons)
      throws IOException {
    String shellFileName =
        Files.isRegularFile(staticRoot.resolve("_shell.html")) ? "_shell.html" : "index.html";
    // Registered before the static prefix only for readability -- the JDK's own HttpServer matches
    // the longest registered path, so this wins over "/console" whatever the order.
    target.createContext("/console/addons", exchange -> handleConsoleAddons(exchange, addons));
    target.createContext("/console", new SpaStaticHandler(staticRoot, shellFileName));
    target.createContext("/", new RootRedirectHandler("/console"));
  }

  /**
   * {@code GET /console/addons} -- which bundled console addons this process advertises.
   *
   * <p>Deliberately behind no RBAC gate and reachable with no session: it says which screens exist,
   * not what they contain, and the console reads it before anyone has signed in. Every screen it
   * names still enforces its own reads through the ordinary authorized routes.
   */
  private static void handleConsoleAddons(HttpExchange exchange, ConsoleAddons addons) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(exchange, 200, addons.toJson());
    } catch (IOException | RuntimeException e) {
      log.warn("console addons request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /** A fresh temp path per JVM run -- the ephemeral constructor never intends key reuse anyway. */
  private static Path ephemeralKeyPath() throws IOException {
    Path dir = Files.createTempDirectory("gimle-apiserver-ephemeral-key-");
    return dir.resolve("secret.key");
  }

  /**
   * Logged either way at startup: whether {@code /bootstrap/*} is registered is otherwise invisible
   * from the outside (the routes just 404), which once cost a real operator a long debugging
   * session against a misspelled property name.
   */
  private static Optional<CertificateAuthority> loadCertificateAuthorityIfConfigured() {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return Optional.empty();
    }
    Optional<CertificateAuthority> authority =
        CaKeyMaterial.loadIfConfigured(TlsSettings.fromConfig().caFile());
    if (authority.isPresent()) {
      log.info(
          "CSR signing enabled on this node ({} configured): /bootstrap/csr and /bootstrap/tokens"
              + " will be registered",
          CaKeyMaterial.CA_KEY_FILE_PROPERTY);
    } else {
      log.info(
          "CSR signing disabled on this node: {} is not set, so /bootstrap/csr and"
              + " /bootstrap/tokens will not be registered",
          CaKeyMaterial.CA_KEY_FILE_PROPERTY);
    }
    return authority;
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
    // The first probe runs on the probe thread and start() waits only briefly for it: long enough
    // that a reachable store makes the very first /health answer about reality, bounded so an
    // unreachable one delays startup by that bound rather than by StoreClient's own leader search.
    // Whatever the outcome, the scheduled refresh below takes over.
    Future<?> first = storeProbe.submit(this::refreshStoreProbe);
    try {
      first.get(FIRST_STORE_PROBE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException e) {
      log.debug("first store probe did not complete before startup continued: {}", e.getMessage());
    }
    storeProbe.scheduleWithFixedDelay(
        this::refreshStoreProbe,
        storeProbeInterval.toMillis(),
        storeProbeInterval.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  /**
   * Runs on its own thread so a slow or hanging store round trip delays only the next probe, never
   * a caller of {@code /health}. Fixed *delay* rather than fixed rate: a probe that overran its
   * interval must not have a queue of catch-up runs waiting behind it.
   */
  private void refreshStoreProbe() {
    try {
      lastStoreProbe = StoreProbeResult.up(storeClient.listTenants().size());
    } catch (RuntimeException e) {
      lastStoreProbe = StoreProbeResult.down(String.valueOf(e.getMessage()));
    }
  }

  public int port() {
    return boundPort;
  }

  @Override
  public void close() {
    storeProbe.shutdownNow();
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
    boolean rotated = certificateRotator.checkAndRotateIfDue(settings, ownCsrEndpoint).rotated();
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
   * Tenant-scoped for every verb here: GET/DELETE authorize against the caller-declared {@code
   * ?tenant=} query parameter (see {@link #dispatchResourceRequest}'s own javadoc for why), and PUT
   * authorizes against the submitted manifest's own {@code tenantId} -- so a permission scoped to
   * one tenant now actually authorizes that tenant's workloads, not just cluster-wide grants. Every
   * {@code handle{Deployment,Job,CronJob,DaemonSet,StatefulSet}} singleton-resource handler shares
   * this same posture.
   */
  private void handleDeployment(HttpExchange exchange) {
    dispatchResourceRequest(
        exchange,
        ResourceKind.DEPLOYMENT,
        "missing deployment name",
        "deployment",
        DeploymentSpec.class,
        this::resolveDeploymentNameOrHandleSubRoute,
        name -> findTenantByName(storeClient.listDeployments(), name),
        this::handlePutDeployment,
        this::handleGetDeployment,
        this::handleDeleteDeployment);
  }

  /**
   * {@code /deployments/{name}} name resolution is one segment, except a path may carry a second
   * segment -- {@code /deployments/{name}/revisions} (GET, revision history) or {@code
   * /deployments/{name}/rollback} (POST, restore an earlier revision) -- neither of which is a
   * plain PUT/GET/DELETE-by-name. Mirrors {@link #resolveCronJobNameOrHandleSubRoute}'s own shape
   * exactly, generalized to two reserved actions instead of one. The sub-route's own tenant is the
   * same caller-declared {@code ?tenant=} hint {@link #dispatchResourceRequest} itself now uses,
   * not a store lookup -- see its own javadoc for why a bare name can no longer resolve its tenant
   * on the server's behalf.
   */
  private Optional<String> resolveDeploymentNameOrHandleSubRoute(HttpExchange exchange)
      throws IOException {
    String tail = pathSegmentAfter(exchange, "/deployments/");
    int slash = tail.indexOf('/');
    String name = slash < 0 ? tail : tail.substring(0, slash);
    if (name.isBlank() || slash < 0) {
      return Optional.of(name);
    }
    String action = tail.substring(slash + 1);
    Optional<String> tenant = workloadTenantHint(exchange);
    switch (action) {
      case "revisions" -> {
        if (requireAuthorized(
            exchange, ResourceKind.DEPLOYMENT, Verb.READ, tenant, Optional.of(name))) {
          handleListControllerRevisions(exchange, "Deployment", tenant, name);
        }
      }
      case "rollback" -> {
        if (requireAuthorized(
            exchange, ResourceKind.DEPLOYMENT, Verb.WRITE, tenant, Optional.of(name))) {
          handleRollbackDeployment(exchange, tenant, name);
        }
      }
      default -> respond(exchange, 404, "unknown deployment endpoint: " + action);
    }
    return Optional.empty();
  }

  /**
   * A {@code (HttpExchange, tenantHint, name)} action that may itself throw {@link IOException}.
   * {@code tenantHint} is the caller-declared {@code ?tenant=} query parameter (see {@link
   * #dispatchResourceRequest}'s own javadoc for why GET/DELETE must declare it rather than have the
   * server infer it): the namespace this action addresses {@code name} within.
   */
  @FunctionalInterface
  private interface ResourceAction {
    void run(HttpExchange exchange, Optional<String> tenantHint, String name) throws IOException;
  }

  /**
   * A PUT action that receives the manifest {@link #dispatchResourceRequest} already parsed --
   * once, to resolve the tenant to authorize the write against -- rather than re-reading {@code
   * exchange}'s own request body itself, which can only be consumed once. {@code warnings} rides
   * alongside so the handler can attach them only at its own genuine success point (see {@link
   * #attachWarnings}) -- never eagerly before the handler has decided whether this PUT actually
   * succeeds, which would leak a deprecation warning onto an unrelated 400/409 rejection.
   *
   * <p>Returns the real {@link AuditOutcome} rather than {@code void}: authorization (already
   * checked by the time this runs) says only whether the caller is *allowed* to write, not whether
   * this particular manifest actually gets applied -- a kind/name mismatch or an admission-chain
   * rejection (tenant quota, LimitRange) still happens inside this method, strictly after
   * authorization already passed. {@link #dispatchResourceRequest} uses the returned outcome to
   * record the one audit event this write gets, so it reflects what genuinely happened rather than
   * always claiming success.
   */
  @FunctionalInterface
  private interface PutResourceAction {
    AuditOutcome run(
        HttpExchange exchange, String name, WorkloadSpec submitted, List<String> warnings)
        throws IOException;
  }

  /**
   * Attaches each deprecation warning as its own {@code X-Gimle-Warning} response header and logs
   * it -- called by each {@code handlePut*} handler immediately before its own {@code respond(200,
   * "ok")}, never before that handler has decided the PUT actually succeeds. A rejected PUT (name
   * mismatch, admission conflict, wrong kind) must never carry a warning about a field that, since
   * nothing was applied, had no effect at all.
   */
  private void attachWarnings(
      HttpExchange exchange, List<String> warnings, String requestNoun, String name) {
    for (String warning : warnings) {
      log.warn("{} {} manifest: {}", requestNoun, name, warning);
      exchange.getResponseHeaders().add("X-Gimle-Warning", warning);
    }
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
   * Resolves the tenant a bare {@code name} is actually stored under, for GET/DELETE by name when
   * the caller declares no {@code ?tenant=} of its own (and for a PUT's own re-tenanting guard) --
   * {@code Optional.empty()} if no such resource exists under any tenant. This is a convenience
   * default only, standing in for a hint the caller never gave -- it never overrides one the caller
   * did give (see {@code dispatchResourceRequest}'s own javadoc for why an explicit {@code
   * ?tenant=} always wins): the same {@code resolveTenantForWorkloadName} shape {@code
   * /endpoints/{name}} uses for the same reason. Each {@code handle{Deployment,Job,
   * CronJob,DaemonSet,StatefulSet}} call site supplies its own kind-specific lookup.
   */
  @FunctionalInterface
  private interface TenantLookup {
    Optional<String> lookup(String name);
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
   * encrypted}-flag-dependent resource kind. GET/DELETE resolve the tenant to authorize against
   * from a caller-declared {@code ?tenant=} when one is given -- exactly {@code kubectl}'s own
   * {@code --namespace} convention: naming the tenant is never itself a bypass, since {@link
   * #requireAuthorized} still independently checks the caller's real grant against whichever tenant
   * is resolved, so declaring one the caller isn't authorized for still denies cleanly. Only when
   * the caller declares none does this fall back to {@code existingTenant} -- the resource's own
   * currently stored tenant, found by bare-name search across every tenant -- purely so a manifest
   * that itself omitted {@code tenantId} (which always resolves to {@code default} at parse time)
   * can still be read/deleted back by the same bare name with no flag at all. Silently preferring
   * that search over an explicit {@code ?tenant=} would be a real correctness bug, not a
   * convenience: two tenants can legitimately share a name (that's the entire point of per-tenant
   * store keys), and a caller who took the trouble to disambiguate must never have that
   * disambiguation quietly overridden by a guess -- an operator asking to delete tenant A's
   * same-named resource must never end up deleting tenant B's instead.
   */
  private void dispatchResourceRequest(
      HttpExchange exchange,
      ResourceKind kind,
      String missingNameMessage,
      String requestNoun,
      Class<? extends WorkloadSpec> manifestType,
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
          ParsedManifest parsed;
          try {
            parsed = ManifestParser.parse(exchange.getRequestBody());
          } catch (GimleManifestException | IllegalArgumentException e) {
            // A preview answers in the verdict shape whatever the outcome -- that is the whole
            // point of a gate a pipeline can read one way. A manifest the parser refuses used to
            // be the single rejection it reported as a bare error instead, so the one class of
            // problem an operator most wants a preview for was the one it could not preview.
            if (!isDryRun(exchange)) {
              throw e;
            }
            respondRejectedPreview(
                exchange,
                manifestKindLabel(manifestType),
                name,
                Optional.empty(),
                400,
                List.of(PreviewCheck.failed("manifest", String.valueOf(e.getMessage()))));
            return;
          }
          WorkloadSpec submitted = parsed.spec();
          Optional<String> submittedTenant = submitted.tenantId();
          // Deferred audit: requireAuthorizedForWrite (unlike requireAuthorized) records nothing
          // for an authorized caller and instead hands back the principal to audit with once the
          // real outcome -- known only after put.run below actually runs admission -- is in hand.
          // A denial is still recorded immediately inside it, same as requireAuthorized, since a
          // denial is always final.
          Optional<Principal> auditPrincipal =
              requireAuthorizedForWrite(exchange, kind, submittedTenant);
          if (auditPrincipal.isPresent()
              && !requireDoubleAuthorizedForRetenanting(
                  exchange, kind, name, submittedTenant, existingTenant, auditPrincipal.get())) {
            // The helper has already written the 403 (and its own audit event) by this point --
            // nothing left to do for this request.
            return;
          }
          if (auditPrincipal.isPresent()) {
            if (isDryRun(exchange)) {
              // No audit event: a dry-run proposes nothing, so there is no write for the audit
              // trail to record. A *denied* dry-run is still audited, by requireAuthorizedForWrite
              // above -- that denial is a real authorization decision about a real caller,
              // indistinguishable from and just as worth recording as any other.
              previewWorkload(exchange, kind, requestNoun, manifestType, name, submitted);
            } else if (rejectIfReservedSystemTenant(exchange, submittedTenant)) {
              // rejectIfReservedSystemTenant has already written the 403 itself by this point --
              // see recordAuditEventBestEffort's own javadoc for why a failure recording this
              // event must never be allowed to disturb a response already on the wire.
              recordAuditEventBestEffort(
                  auditPrincipal.get(),
                  kind,
                  Verb.WRITE,
                  submittedTenant,
                  Optional.empty(),
                  true,
                  AuditOutcome.REJECTED);
            } else {
              // Deprecation warnings ride response headers back to the submitting operator (the
              // CLI prints them on stderr) -- but only attached by the per-kind handler at its own
              // genuine success point (see attachWarnings), never here: a manifest that fails
              // admission or name/kind validation inside put.run must not carry a warning about a
              // field that, since nothing was applied, had no effect at all.
              AuditOutcome outcome = put.run(exchange, name, submitted, parsed.warnings());
              // put.run has already written its own response (200/400/409) by this point -- see
              // recordAuditEventBestEffort's own javadoc for why this call must never be allowed
              // to turn an already-decided response into a corrupted one.
              recordAuditEventBestEffort(
                  auditPrincipal.get(),
                  kind,
                  Verb.WRITE,
                  submittedTenant,
                  Optional.empty(),
                  true,
                  outcome);
            }
          }
        }
        case "GET" -> {
          Optional<String> tenant = declaredOrExistingTenant(exchange, existingTenant, name);
          if (requireAuthorized(exchange, kind, Verb.READ, tenant)) {
            get.run(exchange, tenant, name);
          }
        }
        case "DELETE" -> {
          Optional<String> tenant = declaredOrExistingTenant(exchange, existingTenant, name);
          if (requireAuthorized(exchange, kind, Verb.DELETE, tenant)) {
            delete.run(exchange, tenant, name);
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (AmbiguousTenantException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (GimleManifestException | IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("{} request failed: {}", requestNoun, e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * The tenant a GET/DELETE-by-name should resolve to: an explicit {@code ?tenant=} the caller
   * declared, taken verbatim (never second-guessed against what actually exists -- that's exactly
   * what lets {@link #requireAuthorized} cleanly deny a caller naming a tenant it has no grant for,
   * rather than this method silently substituting a different, resolvable one); only when the
   * caller declares none does {@code existingTenant} run at all, as a pure convenience default for
   * "no flag given" -- see {@link #dispatchResourceRequest}'s own javadoc for the full reasoning.
   */
  private Optional<String> declaredOrExistingTenant(
      HttpExchange exchange, TenantLookup existingTenant, String name) {
    Optional<String> declared = Optional.ofNullable(parseQuery(exchange).get("tenant"));
    return declared.isPresent() ? declared : existingTenant.lookup(name);
  }

  /**
   * GIMLE-249's double-authorization: {@code name} moving to {@code submittedTenant} -- whether
   * it's a brand-new name, or already lives under {@code submittedTenant} itself -- needs nothing
   * beyond the ordinary write grant {@link #requireAuthorizedForWrite} already checked. But when
   * {@code existingTenant} says the name currently lives under one or more <em>different</em>
   * tenants, this is a re-tenanting write: the two tenants' own stored copies never collide (each
   * is keyed independently), so nothing here stops the write from *corrupting* anything -- what it
   * stops is a caller who can write only {@code submittedTenant} conjuring a name that already
   * means something under a tenant it has no access to at all, silently new to anyone who reads or
   * deletes that name unqualified expecting the tenant they know about. Requires a real WRITE grant
   * on every one of those other tenants too, not just the one being written into; a caller lacking
   * even one is refused with a {@code 403} naming which tenant it still needs, and that denial is
   * audited against the tenant it was actually denied on, the same way {@link
   * #requireAuthorizedForWrite}'s own denial path audits its tenant.
   *
   * <p>{@code existingTenant.lookup} throwing {@link AmbiguousTenantException} (the name already
   * collides across more than one tenant before this write even lands) is not an error here -- it's
   * simply more than one "other" tenant to check, so this unwraps the exception's own tenant list
   * instead of letting it propagate.
   *
   * <p>Plaintext mode has no identity to check at all (matching {@link
   * #requireAuthorizedForWrite}'s own carve-out) -- {@code exchange} not being an {@link
   * HttpsExchange} skips this gate entirely rather than ever calling {@link Authorizer#authorize}
   * with the synthetic {@code anonymous} principal {@code requireAuthorizedForWrite} hands back
   * there, which holds no grant at all and would otherwise turn "no auth in this mode" into a
   * spurious, unconditional 403 on every re-tenanting write.
   */
  private boolean requireDoubleAuthorizedForRetenanting(
      HttpExchange exchange,
      ResourceKind kind,
      String name,
      Optional<String> submittedTenant,
      TenantLookup existingTenant,
      Principal principal)
      throws IOException {
    if (!(exchange instanceof HttpsExchange)) {
      return true;
    }
    List<String> existingTenants;
    try {
      existingTenants = existingTenant.lookup(name).map(List::of).orElse(List.of());
    } catch (AmbiguousTenantException e) {
      existingTenants = e.tenantIds();
    }
    for (String other : existingTenants) {
      if (submittedTenant.isPresent() && submittedTenant.get().equals(other)) {
        continue;
      }
      if (!authorizer.authorize(
          principal, kind, Verb.WRITE, Optional.of(other), Optional.empty())) {
        recordAuditEvent(principal, kind, Verb.WRITE, Optional.of(other), Optional.empty(), false);
        respondQuietly(
            exchange,
            403,
            "forbidden: '"
                + name
                + "' already exists under tenant '"
                + other
                + "'; write access to that tenant is required to re-tenant it");
        return false;
      }
    }
    return true;
  }

  private AuditOutcome handlePutDeployment(
      HttpExchange exchange, String name, WorkloadSpec parsed, List<String> warnings)
      throws IOException {
    // Parsed and kind/apiVersion-checked at the real operator-facing admission surface by
    // ManifestParser, already done once by dispatchResourceRequest to resolve the tenant to
    // authorize against; DeploymentManifestParser itself stays kind-agnostic (see its own updated
    // javadoc), used directly only by its own parser-shape unit tests.
    if (!(parsed instanceof DeploymentSpec parsedSpec)) {
      respond(
          exchange,
          400,
          "manifest kind does not match /deployments route (expected kind:" + " Deployment)");
      return AuditOutcome.REJECTED;
    }
    if (!parsedSpec.name().equals(name)) {
      respond(
          exchange,
          400,
          "manifest name '" + parsedSpec.name() + "' does not match URL path '" + name + "'");
      return AuditOutcome.REJECTED;
    }
    // Read as the very first store interaction this handler makes, before admissionArtifact/
    // deploymentAdmissionChain below -- both of those make their own store round-trips, and a
    // concurrent delete's own handler has nothing comparable in front of its own generation read,
    // so it normally finishes its whole read-then-propose cycle first. Reading late (after that
    // admission work) would silently observe the post-delete state as this request's own
    // precondition instead of racing against the same starting point delete does; reading here
    // instead means any change that lands during admission is correctly caught as a conflict by
    // proposePutDeploymentOrConflict below, not absorbed.
    Optional<DeploymentSpec> previous = storeClient.getDeployment(parsedSpec.tenantId(), name);
    long expectedGeneration = storeClient.getDeploymentGeneration(parsedSpec.tenantId(), name);
    // Computed here, once, regardless of tenancy -- never trusted from the submitted
    // manifest itself (DeploymentManifestParser only parses artifactSha256 back out of StateStore's
    // own previously-written YAML on reload, never treats a caller-supplied value as
    // authoritative).
    // Optional.empty() if the artifact is unreadable at admission time, the same tolerant posture
    // untenanted deployments already had before this field existed -- DeploymentReconciler still
    // catches an unreadable artifact every tick regardless.
    AdmissionArtifact admitted =
        admissionArtifact(
            parsedSpec.artifactPath(),
            parsedSpec.moduleId(),
            parsedSpec.vessel(),
            parsedSpec.tenantId());
    if (admitted.rejection().isPresent()) {
      respond(exchange, 400, admitted.rejection().get());
      return AuditOutcome.REJECTED;
    }
    DeploymentSpec spec = withArtifactSha256(parsedSpec, admitted.sha256());
    DeploymentSpec afterQuota;
    {
      Optional<DeploymentSpec> allowed =
          admitWorkload(exchange, ResourceKind.DEPLOYMENT, spec, admitted.artifact());
      if (allowed.isEmpty()) {
        return AuditOutcome.REJECTED;
      }
      afterQuota = allowed.get();
    }
    AdmissionDecision<DeploymentSpec> decision =
        deploymentAdmissionChain.admit(
            ResourceKind.DEPLOYMENT, Verb.WRITE, afterQuota, storeClient, admitted.artifact());
    return switch (decision) {
      case AdmissionDecision.Reject<DeploymentSpec> reject -> {
        respond(exchange, 409, reject.reason());
        yield AuditOutcome.REJECTED;
      }
      case AdmissionDecision.Allow<DeploymentSpec> allow -> {
        if (previous.isEmpty() || deploymentContentChanged(previous.get(), allow.spec())) {
          storeClient.propose(
              new StateMutation.AppendControllerRevision(
                  nextRevisionFor("Deployment", allow.spec(), OptionalInt.empty())));
        }
        if (!proposePutDeploymentOrConflict(exchange, allow.spec(), expectedGeneration)) {
          yield AuditOutcome.REJECTED;
        }
        attachWarnings(exchange, warnings, "deployment", name);
        respond(exchange, 200, "ok");
        yield AuditOutcome.APPLIED;
      }
    };
  }

  /**
   * Shared by {@link #handlePutDeployment} and {@link #handleRollbackDeployment}: proposes {@code
   * spec} via a generation-guarded {@code StateMutation.PutDeployment}, returning {@code true} if
   * it landed. On rejection -- the deployment was concurrently created, deleted, or changed since
   * {@code expectedGeneration} was read -- writes a {@code 409} explaining that and returns {@code
   * false}, so the caller's own success response never runs for a write that didn't durably take
   * effect exactly as sent.
   */
  private boolean proposePutDeploymentOrConflict(
      HttpExchange exchange, DeploymentSpec spec, long expectedGeneration) throws IOException {
    MutationOutcome outcome =
        storeClient.propose(new StateMutation.PutDeployment(spec, expectedGeneration));
    if (outcome instanceof MutationOutcome.Rejected rejected) {
      respond(
          exchange,
          409,
          "deployment '"
              + spec.name()
              + "' was concurrently modified since it was last read ("
              + rejected.reason()
              + "); re-fetch and retry");
      return false;
    }
    return true;
  }

  /**
   * "Meaningfully changed" for revision-history purposes is narrower than "any field differs": only
   * {@code moduleId}/{@code artifactPath}/{@code artifactSha256} -- the same three fields {@link
   * com.gimle.controlplane.reconcile.DeploymentReconciler#validateArtifact} and {@code
   * mismatchedAssignments} already treat as "this instance is running the wrong thing." A
   * replica-count/placement/autoscale-only PUT mints no new revision, matching Kubernetes' own
   * template-hash-triggered {@code ControllerRevision} creation.
   */
  private static boolean deploymentContentChanged(DeploymentSpec previous, DeploymentSpec next) {
    return !previous.moduleId().equals(next.moduleId())
        || !previous.artifactPath().equals(next.artifactPath())
        || !previous.artifactSha256().equals(next.artifactSha256());
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
   *
   * <p>{@code vessel}, when present, routes the local-path/registry-coordinate read through {@link
   * VesselArtifacts} instead of {@link ModuleArtifactReader} -- see {@link
   * ArtifactResolver#resolve(String, ModuleId, Optional)}'s own javadoc for why this is the one
   * place every admission path needs to know a spec is vessel-hosted, and nowhere else does.
   *
   * <p>{@code deployingTenantId} is the submitted workload's own {@code tenantId} -- checked
   * against a registry-resolved coordinate's recorded tenant (see {@code
   * AndvariClient.HeadOutcome.Found#tenantId}), rejecting outright on a mismatch the same way a
   * missing coordinate already is: an artifact tagged for one tenant should never silently end up
   * backing another tenant's workload. Either side being untenanted skips the check entirely --
   * this is purely additive over every existing untenanted deployment/artifact combination, which
   * never had a tenant to compare in the first place. {@code deployingTenantId} is checked via
   * {@link Tenant#isEnforceable}, not a plain {@code isPresent()}: a workload that omitted {@code
   * tenantId} in its manifest resolves to {@link Tenant#DEFAULT_TENANT_ID} at parse time, and this
   * check's whole point -- catching an artifact tagged for a real tenant silently backing a
   * different real tenant's workload -- doesn't apply to a workload that never named one.
   */
  private AdmissionArtifact admissionArtifact(
      String artifactPath,
      ModuleId moduleId,
      Optional<VesselSpec> vessel,
      Optional<String> deployingTenantId) {
    if (ArtifactReference.isLocalPath(artifactPath)) {
      Optional<ModuleArtifact> artifact = readArtifactIfPossible(artifactPath, moduleId, vessel);
      Optional<String> mismatch = moduleVersionMismatchRejection(moduleId, artifact);
      if (mismatch.isPresent()) {
        return AdmissionArtifact.rejection(mismatch.get());
      }
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
      case AndvariClient.HeadOutcome.Found found -> {
        if (found.tenantId().isPresent()
            && Tenant.isEnforceable(deployingTenantId)
            && !found.tenantId().get().equals(deployingTenantId.get())) {
          yield AdmissionArtifact.rejection(
              "artifact "
                  + moduleId.name()
                  + ":"
                  + moduleId.version()
                  + " is recorded in the registry under tenant '"
                  + found.tenantId().get()
                  + "', not '"
                  + deployingTenantId.get()
                  + "'");
        }
        if (found.kind() == ArtifactKind.BUNDLE && vessel.isEmpty()) {
          yield AdmissionArtifact.rejection(
              "artifact "
                  + moduleId.name()
                  + ":"
                  + moduleId.version()
                  + " is a bundle; bundle artifacts are vessel-only -- add a vessel: block to run"
                  + " it as its own process");
        }
        if (found.kind() == ArtifactKind.BUNDLE
            && vessel.isPresent()
            && !vessel.get().jvmFlags().isEmpty()) {
          yield AdmissionArtifact.rejection(
              "vessel jvmFlags cannot apply to bundle artifact "
                  + moduleId.name()
                  + ":"
                  + moduleId.version()
                  + " -- its own entrypoint command decides how (and whether) a JVM is launched");
        }
        Optional<ModuleArtifact> resolved =
            artifactResolver.resolveIfPossible(artifactPath, moduleId, vessel);
        Optional<String> mismatch = moduleVersionMismatchRejection(moduleId, resolved);
        if (mismatch.isPresent()) {
          yield AdmissionArtifact.rejection(mismatch.get());
        }
        yield new AdmissionArtifact(Optional.empty(), resolved, Optional.of(found.sha256()));
      }
    };
  }

  /**
   * Rejects outright once the artifact is actually readable and its own identity disagrees with
   * what the manifest declares -- most commonly a manifest's {@code module.version} bumped without
   * rebuilding the jar, so the artifact still bundles the old one. A vessel-hosted spec can never
   * trigger this: {@link VesselArtifacts#syntheticDescriptor} builds its descriptor directly from
   * the declared {@code moduleId}, so {@code artifact.id()} is that same value by construction, not
   * read back from anything a jar could disagree with. Unreadable stays {@code Optional.empty()}
   * (no verdict either way) so the existing tolerant "admit with no recorded digest, the reconciler
   * catches it every tick" posture for a transiently-unreadable artifact is untouched -- this only
   * fires once the artifact resolved cleanly and is definitively wrong, the same "fail loudly at
   * submit time, don't sit unplaceable forever" reasoning the registry's own {@code NotFound}
   * rejection above already applies. Without this, the mismatch used to surface only once a worker
   * actually tried to install the module and nacked it -- correctly landing the instance in {@code
   * FAILED}, but only after a real placement attempt, not at submission.
   */
  private static Optional<String> moduleVersionMismatchRejection(
      ModuleId declared, Optional<ModuleArtifact> resolved) {
    return resolved
        .filter(artifact -> !artifact.id().equals(declared))
        .map(
            artifact ->
                "manifest declares module "
                    + declared.name()
                    + ":"
                    + declared.version()
                    + ", but the resolved artifact actually bundles "
                    + artifact.id().name()
                    + ":"
                    + artifact.id().version());
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

  /**
   * Runs {@link #workloadAdmissionChain} (quota/limit-range) against any placeable workload kind's
   * PUT, shared by every {@code handlePut{Deployment,Job,DaemonSet,StatefulSet,CronJob}} handler --
   * writing the {@code 409} rejection response itself and returning {@link Optional#empty()} on
   * reject, so each caller's own switch only ever has to handle the success path. The unchecked
   * cast back to {@code T} is safe in practice (no plugin in this chain ever returns a spec of a
   * different concrete type than it received -- see {@link AdmissionDecision.Allow}'s own javadoc
   * for why that's even possible in principle), and is exactly the "single well-named, documented
   * helper" case for absorbing it in one place rather than at every call site.
   */
  @SuppressWarnings("unchecked")
  private <T extends WorkloadSpec> Optional<T> admitWorkload(
      HttpExchange exchange, ResourceKind kind, T spec, Optional<ModuleArtifact> artifact)
      throws IOException {
    AdmissionDecision<WorkloadSpec> decision =
        workloadAdmissionChain.admit(kind, Verb.WRITE, spec, storeClient, artifact);
    return switch (decision) {
      case AdmissionDecision.Reject<WorkloadSpec> reject -> {
        respond(exchange, 409, reject.reason());
        yield Optional.empty();
      }
      case AdmissionDecision.Allow<WorkloadSpec> allow -> Optional.of((T) allow.spec());
    };
  }

  /**
   * {@code ?dryRun=true} on a workload PUT: run everything a real submission runs -- authorization
   * (already done by the time this is reached), manifest kind/name validation, artifact resolution,
   * and the admission chain -- then report what would have happened instead of doing it. Nothing
   * here proposes a mutation.
   *
   * <p>Every stage runs the identical code the real PUT does, not a validation-only copy of it:
   * {@link AdmissionChain#admit} takes a {@link com.gimle.mimir.store.StoreReader} and is already
   * side-effect-free, which is what makes an honest preview possible at all. The one thing that
   * would otherwise differ is placement, which no PUT performs -- a reconciler does, later -- so
   * {@link WorkloadPlacementPreview} runs the real {@link Scheduler} against the real candidate
   * list to forecast it here.
   *
   * <p>The response is always {@code 200}: the preview itself succeeded. Whether the previewed
   * submission would have succeeded is {@code admitted} in the body, alongside {@code
   * wouldRespondStatus}, the status the real request would have answered with.
   */
  private void previewWorkload(
      HttpExchange exchange,
      ResourceKind kind,
      String requestNoun,
      Class<? extends WorkloadSpec> manifestType,
      String name,
      WorkloadSpec submitted)
      throws IOException {
    String manifestKind = manifestKindLabel(manifestType);
    List<PreviewCheck> checks = new ArrayList<>();
    if (isReservedSystemTenant(submitted.tenantId()) && !isOperatorCaller(exchange)) {
      checks.add(
          PreviewCheck.failed(
              "rbac", "gimle-system is reserved for gimle:operators-group callers only"));
      respondRejectedPreview(exchange, manifestKind, name, submitted.tenantId(), 403, checks);
      return;
    }
    checks.add(
        PreviewCheck.passed(
            "rbac",
            "the calling identity may "
                + Verb.WRITE
                + " "
                + kind
                + " in tenant "
                + submitted.tenantId().orElse("(none)")));

    if (!manifestType.isInstance(submitted)) {
      checks.add(
          PreviewCheck.failed(
              "manifest",
              "manifest kind does not match /"
                  + requestNoun
                  + "s route (expected kind: "
                  + manifestKind
                  + ")"));
      respondRejectedPreview(exchange, manifestKind, name, submitted.tenantId(), 400, checks);
      return;
    }
    if (!submitted.name().equals(name)) {
      checks.add(
          PreviewCheck.failed(
              "manifest",
              "manifest name '" + submitted.name() + "' does not match URL path '" + name + "'"));
      respondRejectedPreview(exchange, manifestKind, name, submitted.tenantId(), 400, checks);
      return;
    }
    checks.add(PreviewCheck.passed("manifest", "kind and name match the addressed route"));

    // A CronJob has no artifact of its own to resolve -- each firing materializes an ordinary Job,
    // which resolves its own -- so the real PUT skips this stage for it too.
    Optional<WorkloadResourceProfile.Profile> profile =
        WorkloadResourceProfile.of(submitted, storeClient);
    AdmissionArtifact artifact;
    if (profile.isEmpty()) {
      artifact = new AdmissionArtifact(Optional.empty(), Optional.empty(), Optional.empty());
      checks.add(
          PreviewCheck.skipped(
              "artifact", "a " + manifestKind + " resolves no artifact of its own"));
    } else {
      artifact =
          admissionArtifact(
              profile.get().artifactPath(),
              profile.get().moduleId(),
              profile.get().vessel(),
              submitted.tenantId());
      if (artifact.rejection().isPresent()) {
        checks.add(PreviewCheck.failed("artifact", artifact.rejection().get()));
        respondRejectedPreview(exchange, manifestKind, name, submitted.tenantId(), 400, checks);
        return;
      }
      checks.add(
          PreviewCheck.passed(
              "artifact",
              artifact
                  .sha256()
                  .map(sha -> "resolved, sha256 " + sha)
                  .orElse("admitted with no recorded digest (artifact not readable right now)")));
    }

    WorkloadSpec spec = withArtifactSha256(submitted, artifact.sha256());
    WorkloadSpec admittedSpec;
    switch (workloadAdmissionChain.admit(
        kind, Verb.WRITE, spec, storeClient, artifact.artifact())) {
      case AdmissionDecision.Reject<WorkloadSpec> reject -> {
        checks.add(PreviewCheck.failed("admission", reject.reason()));
        respondRejectedPreview(exchange, manifestKind, name, submitted.tenantId(), 409, checks);
        return;
      }
      case AdmissionDecision.Allow<WorkloadSpec> allow -> admittedSpec = allow.spec();
    }
    if (admittedSpec instanceof DeploymentSpec deploymentSpec) {
      switch (deploymentAdmissionChain.admit(
          kind, Verb.WRITE, deploymentSpec, storeClient, artifact.artifact())) {
        case AdmissionDecision.Reject<DeploymentSpec> reject -> {
          checks.add(PreviewCheck.failed("admission", reject.reason()));
          respondRejectedPreview(exchange, manifestKind, name, submitted.tenantId(), 409, checks);
          return;
        }
        case AdmissionDecision.Allow<DeploymentSpec> allow -> admittedSpec = allow.spec();
      }
    }
    checks.add(PreviewCheck.passed("admission", "quota, limit range and references all satisfied"));

    Optional<PlacementForecast> forecast = Optional.empty();
    if (artifact.artifact().isEmpty()) {
      checks.add(
          PreviewCheck.skipped(
              "placement",
              profile.isEmpty()
                  ? "a " + manifestKind + " is never placed itself; each firing's Job is"
                  : "the artifact could not be read, so its isolation tier and resource request"
                      + " are unknown"));
    } else {
      forecast = placementPreview.forecast(admittedSpec, artifact.artifact().get().descriptor());
      checks.add(describePlacement(forecast));
    }
    respondPreview(
        exchange,
        DryRunVerdict.admitted(manifestKind, name, submitted.tenantId(), checks, forecast));
  }

  /**
   * An unplaceable replica is reported as a {@code FAILED} placement check but never changes the
   * verdict's own {@code admitted}: no submission is ever refused for being unschedulable right
   * now, so saying otherwise would make the preview disagree with the request it predicts.
   */
  private static PreviewCheck describePlacement(Optional<PlacementForecast> forecast) {
    if (forecast.isEmpty()) {
      return PreviewCheck.skipped("placement", "this workload kind places nothing of its own");
    }
    PlacementForecast placement = forecast.get();
    if (placement.replicasEvaluated() == 0) {
      return PreviewCheck.passed("placement", "no replica needs a new placement");
    }
    if (placement.fullyPlaceable()) {
      return PreviewCheck.passed(
          "placement",
          "all " + placement.replicasEvaluated() + " replica(s) needing placement would be placed");
    }
    List<String> reasons = new ArrayList<>();
    for (PlacementForecast.Failure failure : placement.failures()) {
      reasons.add("instance " + failure.instanceIndex() + ": " + failure.reason());
    }
    return PreviewCheck.failed(
        "placement",
        placement.failures().size()
            + " of "
            + placement.replicasEvaluated()
            + " replica(s) would remain unplaced -- "
            + String.join("; ", reasons));
  }

  private void respondPreview(HttpExchange exchange, DryRunVerdict verdict) throws IOException {
    respondJson(exchange, 200, verdict.toJson());
  }

  /**
   * Every stage a preview knows about, in the order a real submission runs them. A rejected verdict
   * lists all of them regardless of how early it stopped, the stages after the failure marked
   * {@link com.gimle.controlplane.preview.PreviewOutcome#SKIPPED} -- so a caller reading the
   * verdict gets the same shape whatever went wrong, rather than a list that silently ends at the
   * failure.
   */
  private static final List<String> PREVIEW_STAGES =
      List.of("rbac", "manifest", "artifact", "admission", "placement");

  private void respondRejectedPreview(
      HttpExchange exchange,
      String manifestKind,
      String name,
      Optional<String> tenantId,
      int wouldRespondStatus,
      List<PreviewCheck> checks)
      throws IOException {
    String failedStage =
        checks.stream()
            .filter(check -> check.outcome() == PreviewOutcome.FAILED)
            .map(PreviewCheck::name)
            .findFirst()
            .orElse("an earlier");
    List<PreviewCheck> complete = new ArrayList<>(checks);
    for (String stage : PREVIEW_STAGES) {
      if (complete.stream().noneMatch(check -> check.name().equals(stage))) {
        complete.add(
            PreviewCheck.skipped(
                stage,
                "not evaluated: the submission would be rejected at the '"
                    + failedStage
                    + "' stage"));
      }
    }
    respondPreview(
        exchange,
        DryRunVerdict.rejected(manifestKind, name, tenantId, wouldRespondStatus, complete));
  }

  private static boolean isDryRun(HttpExchange exchange) {
    return "true".equalsIgnoreCase(parseQuery(exchange).get("dryRun"));
  }

  /** {@code DeploymentSpec.class} to {@code "Deployment"} -- the manifest's own {@code kind:}. */
  private static String manifestKindLabel(Class<? extends WorkloadSpec> manifestType) {
    String simpleName = manifestType.getSimpleName();
    return simpleName.substring(0, simpleName.length() - "Spec".length());
  }

  /** Kind-dispatching sibling of the four concrete {@code withArtifactSha256} overloads. */
  private static WorkloadSpec withArtifactSha256(WorkloadSpec spec, Optional<String> sha256) {
    return switch (spec) {
      case DeploymentSpec s -> withArtifactSha256(s, sha256);
      case JobSpec s -> withArtifactSha256(s, sha256);
      case DaemonSetSpec s -> withArtifactSha256(s, sha256);
      case StatefulSetSpec s -> withArtifactSha256(s, sha256);
      // A CronJob's own spec has no artifact field; its jobTemplate's is resolved per firing.
      case CronJobSpec s -> s;
    };
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
        spec.vessel(),
        spec.configMapRefs(),
        spec.secretMapRefs());
  }

  private void handleGetDeployment(HttpExchange exchange, Optional<String> tenantHint, String name)
      throws IOException {
    Optional<DeploymentSpec> spec = storeClient.getDeployment(tenantHint, name);
    if (spec.isEmpty()) {
      respond(exchange, 404, "no such deployment: " + name);
      return;
    }
    respondJson(exchange, 200, deploymentStatus(spec.get()));
  }

  /**
   * Generation-guarded: proposes {@code RemoveDeployment} with the generation this handler last
   * read as its precondition, so a racing {@code apply} that committed a change to this name after
   * that read cannot be silently discarded by this delete -- see {@code
   * StateMutation.RemoveDeployment}'s own javadoc for the full compare-and-set protocol this closes
   * the concurrent apply/delete race with. A rejection here means someone else's write landed
   * first; re-reading is only to decide which honest response fits: {@code 200} if the name is gone
   * regardless (their write was itself a delete, or ours applied before theirs did -- either way,
   * this caller's actual goal was met), {@code 409} if it is still present with content this caller
   * never asked to keep.
   */
  private void handleDeleteDeployment(
      HttpExchange exchange, Optional<String> tenantHint, String name) throws IOException {
    long expectedGeneration = storeClient.getDeploymentGeneration(tenantHint, name);
    MutationOutcome outcome =
        storeClient.propose(
            new StateMutation.RemoveDeployment(tenantHint, name, expectedGeneration));
    if (outcome instanceof MutationOutcome.Rejected
        && storeClient.getDeployment(tenantHint, name).isPresent()) {
      respond(
          exchange,
          409,
          "deployment "
              + name
              + " was concurrently modified since it was last read -- retry if you still want it"
              + " deleted");
      return;
    }
    respond(exchange, 200, "ok");
  }

  // ---- controller revision history / rollback (Deployment/StatefulSet/DaemonSet) ----

  /**
   * {@code GET /{deployments,statefulsets,daemonsets}/{name}/revisions} -- newest-first, matching
   * {@link com.gimle.mimir.store.StateStore#listControllerRevisions}'s own read order. Job/CronJob
   * have no revision history at all: run-to-completion workloads have no "roll back to an earlier
   * desired state" concept, so this route only ever reaches Deployment/StatefulSet/DaemonSet.
   */
  private void handleListControllerRevisions(
      HttpExchange exchange, String workloadKind, Optional<String> tenantHint, String name)
      throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    List<ControllerRevision> revisions =
        storeClient.listControllerRevisions(workloadKind, tenantHint, name);
    respondJson(
        exchange,
        200,
        Map.of("revisions", revisions.stream().map(this::controllerRevisionToJson).toList()));
  }

  /**
   * {@code POST /deployments/{name}/rollback}: restores an earlier revision's content as a
   * brand-new revision -- forward-only, the same "restore = new revision, never rewrite history"
   * semantics {@code SecretMapStore#rollback} and {@code gimle-hilmir}'s own release rollback
   * already establish. Re-validates the restored content through the identical admission chain a
   * fresh PUT runs (artifact resolution, tenant quota): a rollback is not a bypass of checks that
   * may have tightened since this content last ran successfully.
   */
  private void handleRollbackDeployment(
      HttpExchange exchange, Optional<String> tenantHint, String name) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    // Read before any other store interaction below (revision listing, admissionArtifact,
    // deploymentAdmissionChain) for the same reason handlePutDeployment reads it first thing: a
    // concurrent delete's own handler has nothing comparable in front of its own generation read,
    // so it normally finishes first, and reading this late would silently observe the post-delete
    // state as this request's own precondition instead of racing against the same starting point.
    long expectedGeneration = storeClient.getDeploymentGeneration(tenantHint, name);
    List<ControllerRevision> revisions =
        storeClient.listControllerRevisions("Deployment", tenantHint, name);
    if (revisions.isEmpty()) {
      respond(exchange, 404, "no revision history for deployment: " + name);
      return;
    }
    OptionalInt targetRevision =
        resolveRollbackTarget(revisions, parseToRevision(readBody(exchange)));
    Optional<ControllerRevision> target =
        targetRevision.isEmpty()
            ? Optional.empty()
            : revisions.stream().filter(r -> r.revision() == targetRevision.getAsInt()).findFirst();
    if (target.isEmpty()) {
      respond(
          exchange,
          404,
          targetRevision.isEmpty()
              ? "deployment " + name + " has no earlier revision to roll back to"
              : "no such revision of deployment " + name + ": " + targetRevision.getAsInt());
      return;
    }
    DeploymentSpec restored = (DeploymentSpec) target.get().spec();
    AdmissionArtifact admitted =
        admissionArtifact(
            restored.artifactPath(), restored.moduleId(), restored.vessel(), restored.tenantId());
    if (admitted.rejection().isPresent()) {
      respond(exchange, 409, admitted.rejection().get());
      return;
    }
    DeploymentSpec resolved = withArtifactSha256(restored, admitted.sha256());
    AdmissionDecision<DeploymentSpec> decision =
        deploymentAdmissionChain.admit(
            ResourceKind.DEPLOYMENT, Verb.WRITE, resolved, storeClient, admitted.artifact());
    switch (decision) {
      case AdmissionDecision.Reject<DeploymentSpec> reject ->
          respond(exchange, 409, reject.reason());
      case AdmissionDecision.Allow<DeploymentSpec> allow -> {
        ControllerRevision newRevision =
            nextRevisionFor("Deployment", allow.spec(), targetRevision);
        storeClient.propose(new StateMutation.AppendControllerRevision(newRevision));
        if (proposePutDeploymentOrConflict(exchange, allow.spec(), expectedGeneration)) {
          respondJson(exchange, 200, controllerRevisionToJson(newRevision));
        }
      }
    }
  }

  /**
   * Builds the next revision to record for {@code workloadKind}/{@code spec.name()} -- {@code
   * rollbackOfRevision} is {@link OptionalInt#empty()} for an ordinary content-changed apply,
   * present only when this revision restores an earlier one via rollback. Shared by every kind's
   * PUT handler and rollback handler alike: purely a function of the current revision count plus
   * the caller's own rollback intent, nothing kind-specific.
   */
  private ControllerRevision nextRevisionFor(
      String workloadKind, WorkloadSpec spec, OptionalInt rollbackOfRevision) {
    List<ControllerRevision> existing =
        storeClient.listControllerRevisions(workloadKind, spec.tenantId(), spec.name());
    int nextRevision = existing.isEmpty() ? 1 : existing.get(0).revision() + 1;
    return new ControllerRevision(
        workloadKind,
        spec.name(),
        nextRevision,
        spec,
        System.currentTimeMillis(),
        rollbackOfRevision);
  }

  /**
   * Resolves which revision a rollback targets: the caller's explicit {@code toRevision}, or --
   * when omitted, matching {@code gimle-hilmir}'s own {@code RollbackCommand.resolveTargetRevision}
   * default -- the revision immediately before the current (newest) one. Empty means there is no
   * earlier revision to roll back to. Pure and kind-agnostic, the one piece of rollback logic
   * genuinely worth sharing across Deployment/StatefulSet/DaemonSet rather than tripling.
   */
  private static OptionalInt resolveRollbackTarget(
      List<ControllerRevision> newestFirst, OptionalInt explicitTarget) {
    if (explicitTarget.isPresent()) {
      return explicitTarget;
    }
    return newestFirst.size() > 1
        ? OptionalInt.of(newestFirst.get(1).revision())
        : OptionalInt.empty();
  }

  private static OptionalInt parseToRevision(String body) {
    if (body.isBlank()) {
      return OptionalInt.empty();
    }
    Object raw = Json.asObject(Json.parse(body)).get("toRevision");
    if (raw == null) {
      return OptionalInt.empty();
    }
    if (!(raw instanceof Number number)) {
      throw new IllegalArgumentException("'toRevision' must be an integer");
    }
    return OptionalInt.of(number.intValue());
  }

  /**
   * Shared response shape for every {@code .../revisions} and {@code .../rollback} route -- {@code
   * moduleId}/{@code artifactPath}/{@code artifactSha256} exist identically-named on {@link
   * DeploymentSpec}/{@link StatefulSetSpec}/{@link DaemonSetSpec} but aren't on the shared {@link
   * WorkloadSpec} interface itself (deliberately minimal -- see its own javadoc), so this switches
   * on the concrete type the same way {@link ControllerRevision}'s own compact constructor already
   * does.
   */
  private Map<String, Object> controllerRevisionToJson(ControllerRevision revision) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("revision", revision.revision());
    json.put("createdAtEpochMilli", revision.createdAtEpochMilli());
    revision.rollbackOfRevision().ifPresent(r -> json.put("rollbackOfRevision", r));
    switch (revision.spec()) {
      case DeploymentSpec s -> {
        json.put("moduleId", moduleIdToJson(s.moduleId()));
        json.put("artifactPath", s.artifactPath());
        s.artifactSha256().ifPresent(sha -> json.put("artifactSha256", sha));
      }
      case StatefulSetSpec s -> {
        json.put("moduleId", moduleIdToJson(s.moduleId()));
        json.put("artifactPath", s.artifactPath());
        s.artifactSha256().ifPresent(sha -> json.put("artifactSha256", sha));
      }
      case DaemonSetSpec s -> {
        json.put("moduleId", moduleIdToJson(s.moduleId()));
        json.put("artifactPath", s.artifactPath());
        s.artifactSha256().ifPresent(sha -> json.put("artifactSha256", sha));
      }
      default ->
          throw new IllegalStateException(
              "ControllerRevision cannot embed a " + revision.spec().getClass());
    }
    return json;
  }

  /** Every deployment, in the same shape {@link #handleGetDeployment} returns for one. */
  private void handleDeploymentsList(HttpExchange exchange) {
    try {
      Optional<Predicate<Optional<String>>> readableTenant =
          requireListAuthorized(exchange, ResourceKind.DEPLOYMENT);
      if (readableTenant.isEmpty()) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange,
          200,
          storeClient.listDeployments().stream()
              .filter(spec -> readableTenant.get().test(spec.tenantId()))
              .map(this::deploymentStatus)
              .toList());
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
    // Defaulted to the default tenant, not left untenanted: a workload manifest's own omitted
    // tenantId already resolves to it, so no Deployment/StatefulSet/DaemonSet can ever land in the
    // untenanted namespace. A Service that stayed untenanted would join against that namespace and
    // find nothing, for every deployment it names, whatever ports either side declares -- silently,
    // since there is no instance to report an exclusion about.
    Optional<String> tenantId =
        body.get("tenantId") instanceof String s && !s.isBlank()
            ? Optional.of(s)
            : Optional.of(Tenant.DEFAULT_TENANT_ID);
    Set<String> deploymentNames = new LinkedHashSet<>();
    if (body.get("deploymentNames") instanceof List<?> rawNames) {
      for (Object rawName : rawNames) {
        deploymentNames.add(String.valueOf(rawName));
      }
    }
    int port = ((Number) body.get("port")).intValue();
    OptionalInt targetPort =
        body.get("targetPort") instanceof Number n
            ? OptionalInt.of(n.intValue())
            : OptionalInt.empty();
    boolean sessionAffinity = Boolean.TRUE.equals(body.get("sessionAffinity"));
    Optional<String> externalName =
        body.get("externalName") instanceof String s ? Optional.of(s) : Optional.empty();
    ServiceProtocol protocol;
    try {
      protocol =
          body.get("protocol") instanceof String p
              ? ServiceProtocol.valueOf(p.toUpperCase(Locale.ROOT))
              : ServiceProtocol.TCP;
    } catch (IllegalArgumentException e) {
      respond(exchange, 400, "protocol must be TCP or UDP");
      return;
    }

    // No re-tenanting guard needed here (unlike this method's own history before Service names
    // were tenant-scoped): a PUT always targets the submitted tenant's own (tenantId, name) key,
    // so it can never overwrite a different tenant's same-named Service the way a flat namespace
    // once allowed -- see StateStore's own tenant-scoping javadoc.
    boolean authorized = requireAuthorized(exchange, ResourceKind.SERVICE, Verb.WRITE, tenantId);
    if (authorized && !rejectIfReservedSystemTenant(exchange, tenantId)) {
      ServiceSpec spec =
          new ServiceSpec(
              name,
              tenantId,
              deploymentNames,
              port,
              targetPort,
              sessionAffinity,
              externalName,
              protocol);
      // Computed against the Service set as it stands *before* this one lands, so a re-submit
      // never reads as overlapping itself, and attached before respond() writes the headers out.
      List<String> advisories =
          ServiceAdvisories.forSubmission(spec, serviceRegistry.list(), storeClient);
      serviceRegistry.put(spec);
      for (String advisory : advisories) {
        log.warn("service {}: {}", name, advisory);
        exchange.getResponseHeaders().add("X-Gimle-Warning", advisory);
      }
      respond(exchange, 200, "ok");
    }
  }

  /** Every Service, in the same shape {@link #handleGetService} returns for one. */
  private void handleServicesList(HttpExchange exchange) throws IOException {
    Optional<Predicate<Optional<String>>> readableTenant =
        requireListAuthorized(exchange, ResourceKind.SERVICE);
    if (readableTenant.isEmpty()) {
      return;
    }
    if (!"GET".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    respondJson(
        exchange,
        200,
        serviceRegistry.list().stream()
            .filter(spec -> readableTenant.get().test(spec.tenantId()))
            .map(ApiServer::serviceToJson)
            .toList());
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
        // An explicit ?tenant= always wins; resolveTenantForServiceName only stands in for a hint
        // nobody gave, exactly as handleEndpoints does for a workload name. Both of the gateway's
        // own endpoint caches address their target by bare name -- VesselEndpointCache through
        // /endpoints/{name}, ServiceEndpointCache through this route -- so without the fallback
        // the two behave differently for the same spelling, and every tenant-scoped Service a
        // SERVICE route names resolves to nothing.
        Optional<String> declaredServiceTenant =
            Optional.ofNullable(parseQuery(exchange).get("tenant"));
        handleServiceEndpoints(
            exchange,
            declaredServiceTenant.isPresent()
                ? declaredServiceTenant
                : resolveTenantForServiceName(name),
            name);
        return;
      }
      // Caller-declared ?tenant= hint, falling back to resolveTenantForServiceName the same way
      // the /endpoints sub-route above already does -- see declaredOrExistingTenant's own
      // javadoc for the full reasoning. Without this fallback, a DELETE (or GET) against a
      // tenant-scoped Service with no explicit ?tenant= resolved to Optional.empty(), which
      // ServiceRegistry#remove then removed under the untenanted key rather than the Service's
      // real tenant-scoped one -- a silent no-op that still answered 200: the Service reappeared
      // in the next listing with its endpoints intact, on every replica, indistinguishable from
      // success.
      Optional<String> tenant =
          declaredOrExistingTenant(exchange, this::resolveTenantForServiceName, name);
      switch (exchange.getRequestMethod()) {
        case "GET" -> {
          if (requireAuthorized(exchange, ResourceKind.SERVICE, Verb.READ, tenant)) {
            handleGetService(exchange, tenant, name);
          }
        }
        case "DELETE" -> {
          if (requireAuthorized(exchange, ResourceKind.SERVICE, Verb.DELETE, tenant)) {
            serviceRegistry.remove(tenant, name);
            respond(exchange, 200, "ok");
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (AmbiguousTenantException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("service request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleGetService(HttpExchange exchange, Optional<String> tenantHint, String name)
      throws IOException {
    Optional<ServiceSpec> spec = serviceRegistry.get(tenantHint, name);
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
  private void handleServiceEndpoints(
      HttpExchange exchange, Optional<String> tenantHint, String name) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    Optional<ServiceSpec> spec = serviceRegistry.get(tenantHint, name);
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
    spec.targetPort().ifPresent(targetPort -> map.put("targetPort", targetPort));
    map.put("sessionAffinity", spec.sessionAffinity());
    map.put("protocol", spec.protocol().name());
    List<Map<String, Object>> endpoints = new ArrayList<>();
    for (ServiceEndpoint endpoint :
        ServiceEndpointResolver.resolve(storeClient, spec).endpoints()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("host", endpoint.host());
      entry.put("port", endpoint.port());
      endpoint.nodeId().ifPresent(nodeId -> entry.put("nodeId", nodeId));
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
    spec.targetPort().ifPresent(targetPort -> map.put("targetPort", targetPort));
    map.put("sessionAffinity", spec.sessionAffinity());
    spec.externalName().ifPresent(externalName -> map.put("externalName", externalName));
    map.put("protocol", spec.protocol().name());
    return map;
  }

  // ---- /alertrules, /alertrules/{name} ----

  /**
   * {@code POST /alertrules} (create/replace by the name the submitted body carries) and {@code GET
   * /alertrules} (list every one) -- the identical collection/per-resource split {@link
   * #handleServicesCollection} already established, since an {@link AlertRuleSpec} isn't a {@link
   * WorkloadSpec} itself either and so travels as plain JSON rather than a {@code kind:}-dispatched
   * manifest.
   */
  private void handleAlertRulesCollection(HttpExchange exchange) {
    try {
      switch (exchange.getRequestMethod()) {
        case "POST" -> handlePostAlertRule(exchange);
        case "GET" -> handleAlertRulesList(exchange);
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("alertrules request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * A PUT always targets the submitted tenant's own {@code (tenantId, name)} key, so it can never
   * overwrite a different tenant's same-named rule -- same posture {@link #handlePostService}
   * takes, no re-tenanting guard needed. Field validation is entirely {@link AlertRuleSpec}'s own
   * constructor's job; an {@link IllegalArgumentException} it throws is caught by {@link
   * #handleAlertRulesCollection} and mapped to 400.
   */
  private void handlePostAlertRule(HttpExchange exchange) throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    String name = (String) body.get("name");
    if (name == null || name.isBlank()) {
      respond(exchange, 400, "missing alert rule name");
      return;
    }
    // An omitted tenantId resolves to the default tenant, never Optional.empty(): a rule is only
    // ever evaluated against the assignments of the deployment it names, and a manifest's own
    // omitted tenantId already resolves to that same default tenant, so an untenanted rule would
    // be keyed under a namespace no deployment can ever land in -- it would match no instance,
    // average zero forever, and so never cross its threshold nor ever report a verdict at all.
    Optional<String> tenantId =
        Optional.of(
            body.get("tenantId") instanceof String s && !s.isBlank()
                ? s
                : Tenant.DEFAULT_TENANT_ID);
    String deploymentName = (String) body.get("deploymentName");
    AlertRuleSpec.Metric metric = AlertRuleSpec.Metric.valueOf((String) body.get("metric"));
    AlertRuleSpec.Comparator comparator =
        AlertRuleSpec.Comparator.valueOf((String) body.get("comparator"));
    double threshold = ((Number) body.get("threshold")).doubleValue();
    String webhookUrl = (String) body.get("webhookUrl");
    boolean enabled = !Boolean.FALSE.equals(body.get("enabled"));

    boolean authorized = requireAuthorized(exchange, ResourceKind.ALERT_RULE, Verb.WRITE, tenantId);
    if (authorized && !rejectIfReservedSystemTenant(exchange, tenantId)) {
      AlertRuleSpec spec =
          new AlertRuleSpec(
              name, tenantId, deploymentName, metric, comparator, threshold, webhookUrl, enabled);
      alertRuleRegistry.put(spec);
      respond(exchange, 200, "ok");
    }
  }

  /** Every AlertRule, in the same shape {@link #handleGetAlertRule} returns for one. */
  private void handleAlertRulesList(HttpExchange exchange) throws IOException {
    Optional<Predicate<Optional<String>>> readableTenant =
        requireListAuthorized(exchange, ResourceKind.ALERT_RULE);
    if (readableTenant.isEmpty()) {
      return;
    }
    if (!"GET".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    respondJson(
        exchange,
        200,
        alertRuleRegistry.list().stream()
            .filter(spec -> readableTenant.get().test(spec.tenantId()))
            .map(ApiServer::alertRuleToJson)
            .toList());
  }

  /**
   * {@code GET}/{@code DELETE /alertrules/{name}}, plus the {@code /alertrules/{name}/firing}
   * sub-route -- resolved the same way {@code /services/{name}/endpoints} carries a second path
   * segment under one context.
   */
  private void handleAlertRule(HttpExchange exchange) {
    try {
      String tail = pathSegmentAfter(exchange, "/alertrules/");
      int slash = tail.indexOf('/');
      String name = slash < 0 ? tail : tail.substring(0, slash);
      if (name.isBlank()) {
        respond(exchange, 400, "missing alert rule name");
        return;
      }
      // Caller-declared ?tenant= hint: a per-tenant AlertRule name can't resolve its own tenant
      // from the bare name alone. Defaulted the workload way rather than to Optional.empty(),
      // because that is the key POST writes an omitted tenantId under -- reading it back with a
      // bare empty Optional would address a rule this API can no longer create.
      Optional<String> tenant = workloadTenantHint(exchange);
      if (slash >= 0) {
        String subResource = tail.substring(slash + 1);
        if (!"firing".equals(subResource)) {
          respond(exchange, 404, "unknown alert rule endpoint: " + subResource);
          return;
        }
        if (requireAuthorized(exchange, ResourceKind.ALERT_RULE, Verb.READ, tenant)) {
          handleAlertRuleFiring(exchange, tenant, name);
        }
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "GET" -> {
          if (requireAuthorized(exchange, ResourceKind.ALERT_RULE, Verb.READ, tenant)) {
            handleGetAlertRule(exchange, tenant, name);
          }
        }
        case "DELETE" -> {
          if (requireAuthorized(exchange, ResourceKind.ALERT_RULE, Verb.DELETE, tenant)) {
            alertRuleRegistry.remove(tenant, name);
            respond(exchange, 200, "ok");
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("alert rule request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleGetAlertRule(HttpExchange exchange, Optional<String> tenantHint, String name)
      throws IOException {
    Optional<AlertRuleSpec> spec = alertRuleRegistry.get(tenantHint, name);
    if (spec.isEmpty()) {
      respond(exchange, 404, "no such alert rule: " + name);
      return;
    }
    respondJson(exchange, 200, alertRuleToJson(spec.get()));
  }

  /**
   * The durable verdict {@code AlertReconciler} maintains for {@code name} -- see {@code
   * StateStore#putAlertFiringState}'s own javadoc for why this moved out of the reconciler's own
   * process. {@code known} is {@code false} (with no {@code firing} field at all) when the rule has
   * never crossed or resolved yet, a genuinely different answer from "known, not firing" -- an
   * empty verdict is a valid 200, not a 404, exactly like {@code handleServiceEndpoints}'s own
   * empty endpoints list: the alert rule itself exists, only its verdict is not yet known.
   */
  private void handleAlertRuleFiring(
      HttpExchange exchange, Optional<String> tenantHint, String name) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    if (alertRuleRegistry.get(tenantHint, name).isEmpty()) {
      respond(exchange, 404, "no such alert rule: " + name);
      return;
    }
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", name);
    Optional<Boolean> firing = alertRuleRegistry.getFiringState(tenantHint, name);
    map.put("known", firing.isPresent());
    firing.ifPresent(value -> map.put("firing", value));
    respondJson(exchange, 200, map);
  }

  private static Map<String, Object> alertRuleToJson(AlertRuleSpec spec) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", spec.name());
    spec.tenantId().ifPresent(tenantId -> map.put("tenantId", tenantId));
    map.put("deploymentName", spec.deploymentName());
    map.put("metric", spec.metric().name());
    map.put("comparator", spec.comparator().name());
    map.put("threshold", spec.threshold());
    map.put("webhookUrl", spec.webhookUrl());
    map.put("enabled", spec.enabled());
    return map;
  }

  // ---- /networkpolicies, /networkpolicies/{name} ----

  /**
   * {@code POST /networkpolicies} (create/replace by the name the submitted body carries) and
   * {@code GET /networkpolicies} (list every one) -- the identical collection/per-resource split
   * {@link #handleServicesCollection} already established for the sibling network-model resource,
   * which isn't a {@link WorkloadSpec} itself either and so travels as plain JSON rather than a
   * {@code kind:}-dispatched manifest.
   */
  // ---- /ingresses, /ingresses/{name} ----

  private void handleIngressesCollection(HttpExchange exchange) {
    try {
      switch (exchange.getRequestMethod()) {
        case "POST" -> handlePostIngress(exchange);
        case "GET" -> {
          if (requireAuthorized(exchange, ResourceKind.INGRESS, Verb.READ, Optional.empty())) {
            List<Map<String, Object>> body = new ArrayList<>();
            for (IngressSpec spec : ingressRegistry.list()) {
              body.add(ingressToJson(spec));
            }
            respondJson(exchange, 200, body);
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("ingresses request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleIngress(HttpExchange exchange) {
    try {
      String name = pathSegmentAfter(exchange, "/ingresses/");
      if (name.isBlank()) {
        respond(exchange, 400, "missing ingress name");
        return;
      }
      Optional<String> tenantId = workloadTenantHint(exchange);
      switch (exchange.getRequestMethod()) {
        case "GET" -> {
          if (requireAuthorized(exchange, ResourceKind.INGRESS, Verb.READ, tenantId)) {
            Optional<IngressSpec> spec = ingressRegistry.get(tenantId.orElseThrow(), name);
            if (spec.isEmpty()) {
              respond(exchange, 404, "no such ingress: " + name);
            } else {
              respondJson(exchange, 200, ingressToJson(spec.get()));
            }
          }
        }
        case "DELETE" -> {
          if (requireAuthorized(exchange, ResourceKind.INGRESS, Verb.WRITE, tenantId)) {
            ingressRegistry.remove(tenantId.orElseThrow(), name);
            respondJson(exchange, 200, Map.of("deleted", true));
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("ingress request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * A route's own validation lives in {@link IngressRule}'s compact constructor, so a malformed
   * declaration is an {@link IllegalArgumentException} the caller sees as a 400 naming the field --
   * rather than a route that parses and then silently never matches, which is the failure mode a
   * flat config string has.
   */
  private void handlePostIngress(HttpExchange exchange) throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    String name = (String) body.get("name");
    if (name == null || name.isBlank()) {
      respond(exchange, 400, "missing ingress name");
      return;
    }
    String tenantId = (String) body.get("tenantId");
    if (tenantId == null || tenantId.isBlank()) {
      respond(exchange, 400, "missing tenantId");
      return;
    }
    if (!requireAuthorized(exchange, ResourceKind.INGRESS, Verb.WRITE, Optional.of(tenantId))) {
      return;
    }
    List<IngressRule> routes = new ArrayList<>();
    for (Map<String, Object> route : Json.asObjectList(body.get("routes"))) {
      routes.add(ingressRuleFromJson(route));
    }
    OptionalInt expectedVersion =
        body.get("expectedVersion") instanceof Number n
            ? OptionalInt.of(n.intValue())
            : OptionalInt.empty();
    IngressWriteResult result =
        ingressRegistry.put(new IngressSpec(name, tenantId, routes), expectedVersion);
    switch (result) {
      case IngressWriteResult.Written written ->
          respondJson(exchange, 200, ingressToJson(written.spec()));
      case IngressWriteResult.VersionConflict conflict ->
          respondJson(
              exchange,
              409,
              Map.of("error", "version conflict", "currentVersion", conflict.currentVersion()));
      case IngressWriteResult.Contended unused ->
          respond(exchange, 503, "ingress " + name + " is being written by another caller");
    }
  }

  /**
   * {@code Enum.valueOf}'s own failure names the fully-qualified enum class, which means nothing to
   * whoever wrote the manifest -- this names the kinds a route may actually declare instead.
   */
  private static IngressRule.Kind ingressRouteKind(String declared) {
    try {
      return IngressRule.Kind.valueOf(declared.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "unknown route kind '"
              + declared
              + "'; expected one of "
              + Arrays.stream(IngressRule.Kind.values())
                  .map(Enum::name)
                  .collect(Collectors.joining(", ")));
    }
  }

  private static IngressRule ingressRuleFromJson(Map<String, Object> route) {
    IngressRule.Kind kind = ingressRouteKind(String.valueOf(route.get("kind")));
    return new IngressRule(
        Optional.ofNullable((String) route.get("host")),
        (String) route.get("path"),
        Boolean.TRUE.equals(route.get("prefix")),
        kind,
        Optional.ofNullable((String) route.get("serviceName")),
        Optional.ofNullable((String) route.get("deploymentName")),
        Optional.ofNullable((String) route.get("portName")),
        Optional.ofNullable((String) route.get("interfaceName")),
        route.get("majorVersion") instanceof Number n ? n.intValue() : 0,
        Optional.ofNullable((String) route.get("methodName")),
        Optional.ofNullable((String) route.get("paramType")));
  }

  private static Map<String, Object> ingressToJson(IngressSpec spec) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", spec.name());
    map.put("tenantId", spec.tenantId());
    map.put("version", spec.version());
    List<Map<String, Object>> routes = new ArrayList<>();
    for (IngressRule route : spec.routes()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      route.host().ifPresent(host -> entry.put("host", host));
      entry.put("path", route.path());
      entry.put("prefix", route.prefix());
      entry.put("kind", route.kind().name());
      route.serviceName().ifPresent(v -> entry.put("serviceName", v));
      route.deploymentName().ifPresent(v -> entry.put("deploymentName", v));
      route.portName().ifPresent(v -> entry.put("portName", v));
      route.interfaceName().ifPresent(v -> entry.put("interfaceName", v));
      if (route.kind() == IngressRule.Kind.FABRIC) {
        entry.put("majorVersion", route.majorVersion());
      }
      route.methodName().ifPresent(v -> entry.put("methodName", v));
      route.paramType().ifPresent(v -> entry.put("paramType", v));
      routes.add(entry);
    }
    map.put("routes", routes);
    return map;
  }

  private void handleNetworkPoliciesCollection(HttpExchange exchange) {
    try {
      switch (exchange.getRequestMethod()) {
        case "POST" -> handlePostNetworkPolicy(exchange);
        case "GET" -> handleNetworkPoliciesList(exchange);
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("network policies request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * Same two-check re-tenanting guard {@link #handlePostService} applies to its own resource: a
   * caller needs write access under the submitted tenant, and, only if a same-named NetworkPolicy
   * already exists under a different tenant, write access under that existing tenant too --
   * otherwise a grant scoped to one tenant could silently steal a NetworkPolicy out of another it
   * has no access to. Unlike {@link ServiceSpec#tenantId()}, {@link NetworkPolicySpec#tenantId()}
   * is never optional -- a NetworkPolicy restricts access to exactly one tenant's own Services, so
   * a missing/blank {@code tenantId} in the request body is rejected outright as a 400, the same
   * way a missing {@code name} already is.
   */
  private void handlePostNetworkPolicy(HttpExchange exchange) throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    String name = (String) body.get("name");
    if (name == null || name.isBlank()) {
      respond(exchange, 400, "missing network policy name");
      return;
    }
    String tenantId = (String) body.get("tenantId");
    if (tenantId == null || tenantId.isBlank()) {
      respond(exchange, 400, "missing tenantId");
      return;
    }
    Optional<Set<String>> deploymentNames =
        scopingSetFromJson(body.get("deploymentNames"), "deploymentNames");
    Optional<Set<String>> serviceInterfaceNames =
        scopingSetFromJson(body.get("serviceInterfaceNames"), "serviceInterfaceNames");
    Optional<Set<String>> allowedCallerTenantIds =
        directionSetFromJson(body.get("allowedCallerTenantIds"), "allowedCallerTenantIds");
    Optional<Set<String>> allowedCalleeTenantIds =
        directionSetFromJson(body.get("allowedCalleeTenantIds"), "allowedCalleeTenantIds");
    if (allowedCallerTenantIds.isEmpty() && allowedCalleeTenantIds.isEmpty()) {
      respond(
          exchange,
          400,
          "a network policy must restrict at least one direction (ingress or egress)");
      return;
    }

    OptionalInt expectedVersion = optionalIntField(body, "expectedVersion");

    // No re-tenanting guard needed here, for the same reason handlePostService's own no longer
    // needs one: a PUT always targets the submitted tenant's own (tenantId, name) key, so it can
    // never overwrite a different tenant's same-named NetworkPolicy.
    boolean authorized =
        requireAuthorized(exchange, ResourceKind.NETWORK_POLICY, Verb.WRITE, Optional.of(tenantId));
    if (authorized && !rejectIfReservedSystemTenant(exchange, Optional.of(tenantId))) {
      NetworkPolicySpec spec =
          new NetworkPolicySpec(
              name,
              tenantId,
              deploymentNames,
              serviceInterfaceNames,
              allowedCallerTenantIds,
              allowedCalleeTenantIds);
      if (rejectUnknownOwningTenant(exchange, tenantId)
          || rejectUnknownAllowListTenants(exchange, spec.referencedTenantIds())) {
        return;
      }
      respondNetworkPolicyWrite(exchange, networkPolicyRegistry.put(spec, expectedVersion));
    }
  }

  /**
   * {@code PATCH /networkpolicies/{name}?tenant=<id>}: adds/removes individual allow-list tenants
   * and replaces the scoping sets, leaving everything else exactly as stored. Body {@code
   * {expectedVersion, addAllowedCallerTenantIds?, removeAllowedCallerTenantIds?,
   * addAllowedCalleeTenantIds?, removeAllowedCalleeTenantIds?, deploymentNames?,
   * serviceInterfaceNames?}}. {@code expectedVersion} is required and has no "unconditional"
   * spelling, unlike {@code POST}'s -- a merge onto a base the caller never read is precisely the
   * lost update this route exists to make impossible.
   */
  private void handlePatchNetworkPolicy(HttpExchange exchange, String tenant, String name)
      throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    OptionalInt expectedVersion = optionalIntField(body, "expectedVersion");
    if (expectedVersion.isEmpty()) {
      respond(exchange, 400, "PATCH requires an 'expectedVersion' field");
      return;
    }
    NetworkPolicyPatch patch =
        new NetworkPolicyPatch(
            tenantSetFromJson(body.get("addAllowedCallerTenantIds"), "addAllowedCallerTenantIds"),
            tenantSetFromJson(
                body.get("removeAllowedCallerTenantIds"), "removeAllowedCallerTenantIds"),
            tenantSetFromJson(body.get("addAllowedCalleeTenantIds"), "addAllowedCalleeTenantIds"),
            tenantSetFromJson(
                body.get("removeAllowedCalleeTenantIds"), "removeAllowedCalleeTenantIds"),
            presentMeansReplace(body.get("deploymentNames"), "deploymentNames"),
            presentMeansReplace(body.get("serviceInterfaceNames"), "serviceInterfaceNames"));
    if (patch.isEmpty()) {
      respond(exchange, 400, "PATCH must change at least one field");
      return;
    }
    if (rejectUnknownAllowListTenants(exchange, patch.addedTenantIds())) {
      return;
    }
    respondNetworkPolicyWrite(
        exchange, networkPolicyRegistry.patch(tenant, name, expectedVersion.getAsInt(), patch));
  }

  private void respondNetworkPolicyWrite(HttpExchange exchange, NetworkPolicyWriteResult result)
      throws IOException {
    switch (result) {
      case NetworkPolicyWriteResult.Written written ->
          respondJson(exchange, 200, networkPolicyToJson(written.spec(), knownTenantIds()));
      case NetworkPolicyWriteResult.VersionConflict conflict -> {
        Set<String> known = knownTenantIds();
        Map<String, Object> conflictBody = new LinkedHashMap<>();
        conflictBody.put("currentVersion", conflict.currentVersion());
        conflict
            .current()
            .ifPresent(current -> conflictBody.put("current", networkPolicyToJson(current, known)));
        respondJson(exchange, 409, conflictBody);
      }
      case NetworkPolicyWriteResult.NotFound ignored ->
          respond(exchange, 404, "no such network policy to patch");
      case NetworkPolicyWriteResult.WriteContention contention ->
          respond(
              exchange,
              409,
              "too many concurrent writers to this network policy ("
                  + contention.attempts()
                  + " attempts)");
    }
  }

  /**
   * Rejects a write naming a tenant that does not exist in either direction's allow list. Checked
   * here rather than in {@link NetworkPolicySpec}'s own constructor, which validates the shape of
   * what it is handed and has no registry to ask. A typo'd or long-gone tenant id would otherwise
   * be stored as a rule that can never match anything, with nothing anywhere telling the operator
   * so.
   *
   * <p>Only a write is rejected. A tenant deleted <em>after</em> a valid policy was written leaves
   * a dangling reference, surfaced as an advisory on every read of that policy (see {@link
   * #networkPolicyToJson}) and logged when the tenant is deleted -- never by retroactively voiding
   * a stored policy, which would silently widen it at the exact moment a tenant disappeared.
   */
  /**
   * The tenant a policy declares itself to belong to must exist, exactly as the tenants it names in
   * its allow list already must. {@link NetworkPolicySpec#referencedTenantIds} deliberately
   * excludes the owning tenant, so without this a policy could be stored against a tenant that was
   * never created -- deny-by-default, enforced for nobody, and invisible on every per-tenant view.
   */
  private boolean rejectUnknownOwningTenant(HttpExchange exchange, String tenantId)
      throws IOException {
    if (knownTenantIds().contains(tenantId)) {
      return false;
    }
    respond(exchange, 400, "no such tenant: " + tenantId);
    return true;
  }

  private boolean rejectUnknownAllowListTenants(HttpExchange exchange, Set<String> referenced)
      throws IOException {
    if (referenced.isEmpty()) {
      return false;
    }
    Set<String> known = knownTenantIds();
    List<String> unknown = referenced.stream().filter(id -> !known.contains(id)).sorted().toList();
    if (unknown.isEmpty()) {
      return false;
    }
    respond(exchange, 400, "no such tenant(s) in this policy's allow list: " + unknown);
    return true;
  }

  private Set<String> knownTenantIds() {
    Set<String> ids = new LinkedHashSet<>();
    for (Tenant tenant : storeClient.listTenants()) {
      ids.add(tenant.id());
    }
    return ids;
  }

  /** A patch's allow-list edit: a missing field edits nothing in that direction. */
  private static Set<String> tenantSetFromJson(Object rawValue, String field) {
    if (rawValue == null) {
      return Set.of();
    }
    if (!(rawValue instanceof List<?> rawTenants)) {
      throw new IllegalArgumentException(field + " must be a JSON array");
    }
    Set<String> tenants = new LinkedHashSet<>();
    for (Object rawTenant : rawTenants) {
      tenants.add(String.valueOf(rawTenant));
    }
    return tenants;
  }

  /**
   * A patch's scoping replacement: a missing field leaves the stored scoping alone, a present
   * (possibly empty) array replaces it -- an empty one widening the policy back to the whole
   * tenant.
   */
  private static Optional<Set<String>> presentMeansReplace(Object rawValue, String field) {
    if (rawValue == null) {
      return Optional.empty();
    }
    if (!(rawValue instanceof List<?> rawNames)) {
      throw new IllegalArgumentException(field + " must be a JSON array");
    }
    Set<String> names = new LinkedHashSet<>();
    for (Object rawName : rawNames) {
      names.add(String.valueOf(rawName));
    }
    return Optional.of(names);
  }

  /** A scoping set (deployments, service interfaces): empty or missing both mean unscoped. */
  private static Optional<Set<String>> scopingSetFromJson(Object rawValue, String field) {
    if (rawValue == null) {
      return Optional.empty();
    }
    if (!(rawValue instanceof List<?> rawNames)) {
      throw new IllegalArgumentException(field + " must be a JSON array");
    }
    if (rawNames.isEmpty()) {
      return Optional.empty();
    }
    Set<String> names = new LinkedHashSet<>();
    for (Object rawName : rawNames) {
      names.add(String.valueOf(rawName));
    }
    return Optional.of(names);
  }

  /**
   * A direction set (allowed caller/callee tenants): a missing field means the policy does not
   * restrict that direction, while a present-but-empty array means "deny every cross-tenant peer"
   * -- the two states are distinct here, unlike a scoping set's.
   */
  private static Optional<Set<String>> directionSetFromJson(Object rawValue, String field) {
    if (rawValue == null) {
      return Optional.empty();
    }
    if (!(rawValue instanceof List<?> rawTenants)) {
      throw new IllegalArgumentException(field + " must be a JSON array");
    }
    Set<String> tenants = new LinkedHashSet<>();
    for (Object rawTenant : rawTenants) {
      tenants.add(String.valueOf(rawTenant));
    }
    return Optional.of(tenants);
  }

  /** Every NetworkPolicy, in the same shape {@link #handleGetNetworkPolicy} returns for one. */
  private void handleNetworkPoliciesList(HttpExchange exchange) throws IOException {
    Optional<Predicate<Optional<String>>> readableTenant =
        requireListAuthorized(exchange, ResourceKind.NETWORK_POLICY);
    if (readableTenant.isEmpty()) {
      return;
    }
    if (!"GET".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    respondJson(exchange, 200, networkPolicyList(readableTenant.get()));
  }

  /**
   * {@code GET}/{@code DELETE /networkpolicies/{name}?tenant=<id>} -- {@code tenant} is required
   * (not merely a hint the way it is for every optionally-tenanted workload kind), since {@link
   * NetworkPolicySpec#tenantId()} itself is never optional: a policy has no untenanted namespace to
   * default into.
   */
  private void handleNetworkPolicy(HttpExchange exchange) {
    try {
      String name = pathSegmentAfter(exchange, "/networkpolicies/");
      if (name.isBlank()) {
        respond(exchange, 400, "missing network policy name");
        return;
      }
      String tenant = parseQuery(exchange).get("tenant");
      if (tenant == null || tenant.isBlank()) {
        respond(exchange, 400, "missing ?tenant=");
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "GET" -> {
          if (requireAuthorized(
              exchange, ResourceKind.NETWORK_POLICY, Verb.READ, Optional.of(tenant))) {
            handleGetNetworkPolicy(exchange, tenant, name);
          }
        }
        case "PATCH" -> {
          if (requireAuthorized(
                  exchange, ResourceKind.NETWORK_POLICY, Verb.WRITE, Optional.of(tenant))
              && !rejectIfReservedSystemTenant(exchange, Optional.of(tenant))) {
            handlePatchNetworkPolicy(exchange, tenant, name);
          }
        }
        case "DELETE" -> {
          if (requireAuthorized(
              exchange, ResourceKind.NETWORK_POLICY, Verb.DELETE, Optional.of(tenant))) {
            networkPolicyRegistry.remove(tenant, name);
            respond(exchange, 200, "ok");
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("network policy request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleGetNetworkPolicy(HttpExchange exchange, String tenant, String name)
      throws IOException {
    Optional<NetworkPolicySpec> spec = networkPolicyRegistry.get(tenant, name);
    if (spec.isEmpty()) {
      respond(exchange, 404, "no such network policy: " + name);
      return;
    }
    respondJson(exchange, 200, networkPolicyToJson(spec.get(), knownTenantIds()));
  }

  /**
   * One store read of the tenant set for the whole listing, rather than one per policy: the
   * dangling-reference advisory below needs the same answer for every row.
   */
  private List<Map<String, Object>> networkPolicyList(Predicate<Optional<String>> readableTenant) {
    Set<String> known = knownTenantIds();
    return networkPolicyRegistry.list().stream()
        .filter(spec -> readableTenant.test(Optional.of(spec.tenantId())))
        .map(spec -> networkPolicyToJson(spec, known))
        .toList();
  }

  /**
   * {@code GET /networkpostures}: every readable tenant's declared cross-tenant isolation posture.
   * A separate route from {@code /tenants} because a node agent polls this on every tick to relay
   * it to its workers alongside the policy set, and a node has an unscoped read grant on network
   * policies but deliberately none on tenants -- the posture is network-policy data derived from
   * the tenant record, not the tenant record itself, and is gated as such.
   */
  private void handleNetworkPosturesList(HttpExchange exchange) {
    try {
      Optional<Predicate<Optional<String>>> readableTenant =
          requireListAuthorized(exchange, ResourceKind.NETWORK_POLICY);
      if (readableTenant.isEmpty()) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange,
          200,
          storeClient.listTenants().stream()
              .filter(tenant -> readableTenant.get().test(Optional.of(tenant.id())))
              .map(
                  tenant ->
                      Map.<String, Object>of(
                          "tenantId",
                          tenant.id(),
                          "isolationPosture",
                          tenant.isolationPosture().name()))
              .toList());
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (IOException | RuntimeException e) {
      log.warn("network postures list request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * The scoping sets ({@code deploymentNames}, {@code serviceInterfaceNames}) always serialize as a
   * present (possibly empty) array, never an absent field -- {@code gimle-agent}'s own poller (see
   * {@code HttpNetworkPolicySource}) treats an empty array as "unscoped" the same way {@link
   * NetworkPolicySpec}'s own {@code Optional.empty()} does. The two direction sets serialize only
   * when present, because for them "absent" (no restriction) and "empty" (deny every cross-tenant
   * peer) are distinct states.
   *
   * <p>{@code danglingTenantIds} is an advisory, present only when non-empty: allow-list tenants
   * that were real when the policy was written and have since been deleted. The policy itself is
   * left exactly as stored -- dropping the reference would silently change what the policy allows
   * at the moment a tenant disappeared, and a tenant id can be recreated -- so this reports the
   * condition to an operator instead of acting on it.
   */
  private static Map<String, Object> networkPolicyToJson(
      NetworkPolicySpec spec, Set<String> knownTenantIds) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", spec.name());
    map.put("tenantId", spec.tenantId());
    map.put("version", spec.version());
    map.put("deploymentNames", spec.deploymentNames().map(List::copyOf).orElse(List.of()));
    map.put(
        "serviceInterfaceNames", spec.serviceInterfaceNames().map(List::copyOf).orElse(List.of()));
    spec.allowedCallerTenantIds()
        .ifPresent(tenants -> map.put("allowedCallerTenantIds", List.copyOf(tenants)));
    spec.allowedCalleeTenantIds()
        .ifPresent(tenants -> map.put("allowedCalleeTenantIds", List.copyOf(tenants)));
    List<String> dangling =
        spec.referencedTenantIds().stream()
            .filter(id -> !knownTenantIds.contains(id))
            .sorted()
            .toList();
    if (!dangling.isEmpty()) {
      map.put("danglingTenantIds", dangling);
    }
    return map;
  }

  // ---- /jobs/{name}, /jobs ----

  private void handleJob(HttpExchange exchange) {
    dispatchResourceRequest(
        exchange,
        ResourceKind.JOB,
        "missing job name",
        "job",
        JobSpec.class,
        ex -> Optional.of(pathSegmentAfter(ex, "/jobs/")),
        name -> findTenantByName(storeClient.listJobSpecs(), name),
        this::handlePutJob,
        this::handleGetJob,
        this::handleDeleteJob);
  }

  private AuditOutcome handlePutJob(
      HttpExchange exchange, String name, WorkloadSpec parsed, List<String> warnings)
      throws IOException {
    if (!(parsed instanceof JobSpec parsedSpec)) {
      respond(exchange, 400, "manifest kind does not match /jobs route (expected kind: Job)");
      return AuditOutcome.REJECTED;
    }
    if (!parsedSpec.name().equals(name)) {
      respond(
          exchange,
          400,
          "manifest name '" + parsedSpec.name() + "' does not match URL path '" + name + "'");
      return AuditOutcome.REJECTED;
    }
    // Same reasoning as handlePutDeployment's own identical step: never trusted
    // from the submitted manifest, always recomputed server-side at admission.
    AdmissionArtifact admitted =
        admissionArtifact(
            parsedSpec.artifactPath(),
            parsedSpec.moduleId(),
            parsedSpec.vessel(),
            parsedSpec.tenantId());
    if (admitted.rejection().isPresent()) {
      respond(exchange, 400, admitted.rejection().get());
      return AuditOutcome.REJECTED;
    }
    JobSpec spec = withArtifactSha256(parsedSpec, admitted.sha256());
    Optional<JobSpec> allowed =
        admitWorkload(exchange, ResourceKind.JOB, spec, admitted.artifact());
    if (allowed.isEmpty()) {
      return AuditOutcome.REJECTED;
    }
    spec = allowed.get();
    storeClient.propose(new StateMutation.PutJobSpec(spec));
    attachWarnings(exchange, warnings, "job", name);
    respond(exchange, 200, "ok");
    return AuditOutcome.APPLIED;
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

  private void handleGetJob(HttpExchange exchange, Optional<String> tenantHint, String name)
      throws IOException {
    Optional<JobSpec> spec = storeClient.getJobSpec(tenantHint, name);
    if (spec.isEmpty()) {
      respond(exchange, 404, "no such job: " + name);
      return;
    }
    respondJson(exchange, 200, jobStatus(spec.get()));
  }

  private void handleDeleteJob(HttpExchange exchange, Optional<String> tenantHint, String name)
      throws IOException {
    storeClient.propose(new StateMutation.RemoveJobSpec(tenantHint, name));
    respond(exchange, 200, "ok");
  }

  /** Every job, in the same shape {@link #handleGetJob} returns for one. */
  private void handleJobsList(HttpExchange exchange) {
    try {
      Optional<Predicate<Optional<String>>> readableTenant =
          requireListAuthorized(exchange, ResourceKind.JOB);
      if (readableTenant.isEmpty()) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange,
          200,
          storeClient.listJobSpecs().stream()
              .filter(spec -> readableTenant.get().test(spec.tenantId()))
              .map(this::jobStatus)
              .toList());
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
    status.put(
        "phase",
        storeClient.getJobPhase(spec.tenantId(), spec.name()).map(Enum::name).orElse("RUNNING"));

    Optional<JobRun> currentRun =
        storeClient.listJobRunsFor(spec.tenantId(), spec.name()).stream()
            .max(Comparator.comparingInt(JobRun::attempt));
    if (currentRun.isPresent()) {
      JobRun run = currentRun.get();
      Map<String, Object> runMap = new LinkedHashMap<>();
      runMap.put("attempt", run.attempt());
      runMap.put("nodeId", run.nodeId());
      Optional<InstanceObservation> observation = findObservationForJobRun(run);
      observation.ifPresent(obs -> runMap.put("observation", observationToJson(obs)));
      if (observation.isEmpty()) {
        // A run whose node never got it started has no observation to report a state from, and the
        // phase above still reads RUNNING because nothing has moved this job to a terminal one.
        // Reported in the same "reason" a finished run carries, so the run that never started and
        // the run that ended both say what happened rather than showing an empty state.
        notRunningReason(spec.tenantId(), spec.name(), run.attempt())
            .ifPresent(reason -> runMap.put("reason", reason));
      }
      status.put("currentRun", runMap);
    } else {
      // A terminal job's own JobRun is removed at the same transition that makes it terminal (see
      // JobReconciler's own terminal-transition mutations) -- JobRunSummary is what's left to
      // report back here instead, so currentRun doesn't just disappear the moment a job finishes.
      storeClient
          .getJobRunSummary(spec.tenantId(), spec.name())
          .ifPresent(
              summary -> {
                Map<String, Object> runMap = new LinkedHashMap<>();
                runMap.put("attempt", summary.attempt());
                runMap.put("nodeId", summary.nodeId());
                runMap.put("reason", summary.reason());
                status.put("currentRun", runMap);
              });
    }
    return status;
  }

  private Optional<InstanceObservation> findObservationForJobRun(JobRun run) {
    return findObservation(
        run.nodeId(),
        obs ->
            obs.deploymentName().equals(run.jobName())
                && obs.instanceIndex() == run.attempt()
                && obs.tenantId().equals(run.tenantId()));
  }

  // ---- /cronjobs/{name}, /cronjobs, /cronjobs/{name}/trigger ----

  private void handleCronJob(HttpExchange exchange) {
    dispatchResourceRequest(
        exchange,
        ResourceKind.JOB,
        "missing cronjob name",
        "cronjob",
        CronJobSpec.class,
        this::resolveCronJobNameOrHandleSubRoute,
        name -> findTenantByName(storeClient.listCronJobSpecs(), name),
        this::handlePutCronJob,
        this::handleGetCronJob,
        this::handleDeleteCronJob);
  }

  /**
   * {@code /cronjobs/{name}} name resolution is one segment, exactly like {@link
   * #handleDeployment}'s own {@code pathSegmentAfter} call -- except a CronJob path may carry a
   * second segment, {@code /cronjobs/{name}/trigger}, which isn't a plain PUT/GET/DELETE-by-name at
   * all. A blank name is returned rather than handled here (regardless of whether a second segment
   * follows it) so {@link #dispatchResourceRequest}'s own blank-name check reports it the same way
   * every other resource kind's does; a present, non-blank second segment is handled entirely here,
   * returning {@code Optional.empty()} to tell the caller "already handled, skip the ordinary
   * dispatch." The sub-route's tenant follows the exact same convention {@link
   * #dispatchResourceRequest}'s own GET/DELETE branches use for every other bare-name lookup: an
   * explicit {@code ?tenant=} wins outright, and only when the caller declares none does this fall
   * back to a real cross-tenant search for whichever tenant's CronJob is actually named this --
   * never a blind default to the untenanted namespace regardless of where the CronJob actually
   * lives (that silently fired a same-named CronJob under a different tenant than the caller
   * intended, with the wrong one picked whenever the untenanted namespace also happened to hold
   * nothing by this name).
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
    Optional<String> tenant =
        declaredOrExistingTenant(
            exchange, n -> findTenantByName(storeClient.listCronJobSpecs(), n), name);
    if (requireAuthorized(exchange, ResourceKind.JOB, Verb.WRITE, tenant)) {
      handleCronJobTrigger(exchange, tenant, name);
    }
    return Optional.empty();
  }

  private AuditOutcome handlePutCronJob(
      HttpExchange exchange, String name, WorkloadSpec parsed, List<String> warnings)
      throws IOException {
    if (!(parsed instanceof CronJobSpec spec)) {
      respond(
          exchange, 400, "manifest kind does not match /cronjobs route (expected kind: CronJob)");
      return AuditOutcome.REJECTED;
    }
    if (!spec.name().equals(name)) {
      respond(
          exchange,
          400,
          "manifest name '" + spec.name() + "' does not match URL path '" + name + "'");
      return AuditOutcome.REJECTED;
    }
    // A CronJobSpec itself has nothing to charge against quota/limit-range (see
    // WorkloadResourceProfile's own javadoc -- each firing's generated JobSpec is what's actually
    // chargeable, and CronJobReconciler runs this identical chain against it), so this call's only
    // real effect here is TenantQuotaPlugin's own "unknown tenantId" check -- but every other
    // handlePut{Deployment,Job,DaemonSet,StatefulSet} runs it too, and CronJob shouldn't be the one
    // exception that lets a nonexistent tenant through.
    Optional<CronJobSpec> allowed =
        admitWorkload(exchange, ResourceKind.JOB, spec, Optional.empty());
    if (allowed.isEmpty()) {
      return AuditOutcome.REJECTED;
    }
    storeClient.propose(new StateMutation.PutCronJobSpec(allowed.get()));
    attachWarnings(exchange, warnings, "cronjob", name);
    respond(exchange, 200, "ok");
    return AuditOutcome.APPLIED;
  }

  private void handleGetCronJob(HttpExchange exchange, Optional<String> tenantHint, String name)
      throws IOException {
    Optional<CronJobSpec> spec = storeClient.getCronJobSpec(tenantHint, name);
    if (spec.isEmpty()) {
      respond(exchange, 404, "no such cronjob: " + name);
      return;
    }
    respondJson(exchange, 200, cronJobStatus(spec.get()));
  }

  private void handleDeleteCronJob(HttpExchange exchange, Optional<String> tenantHint, String name)
      throws IOException {
    storeClient.propose(new StateMutation.RemoveCronJobSpec(tenantHint, name));
    respond(exchange, 200, "ok");
  }

  /**
   * The {@code gimle cronjob trigger <name>} verb's server-side implementation -- fires
   * immediately, bypassing the schedule entirely (still subject to {@code concurrencyPolicy}), via
   * the same {@link CronJobReconciler#triggerNow} the scheduled tick path shares. 404 if the
   * CronJob doesn't exist; 409 if the firing was refused -- either {@code concurrencyPolicy:
   * FORBID} against a still-running previous firing, or an admission check (tenant quota, limit
   * range) that the generated Job failed. Distinguishable from "doesn't exist" so a caller isn't
   * left guessing which happened; which of the two refusals it was is in the control plane's own
   * log, since the firing decision is a single yes/no here.
   */
  private void handleCronJobTrigger(HttpExchange exchange, Optional<String> tenantHint, String name)
      throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    if (storeClient.getCronJobSpec(tenantHint, name).isEmpty()) {
      respond(exchange, 404, "no such cronjob: " + name);
      return;
    }
    Optional<String> generatedJobName = cronJobReconciler.triggerNow(tenantHint, name);
    if (generatedJobName.isEmpty()) {
      respond(
          exchange,
          409,
          "cronjob "
              + name
              + " not triggered: its concurrencyPolicy or an admission check refused the firing");
      return;
    }
    respondJson(exchange, 200, Map.of("jobName", generatedJobName.get()));
  }

  /** Every cronjob, in the same shape {@link #handleGetCronJob} returns for one. */
  private void handleCronJobsList(HttpExchange exchange) {
    try {
      Optional<Predicate<Optional<String>>> readableTenant =
          requireListAuthorized(exchange, ResourceKind.JOB);
      if (readableTenant.isEmpty()) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange,
          200,
          storeClient.listCronJobSpecs().stream()
              .filter(spec -> readableTenant.get().test(spec.tenantId()))
              .map(this::cronJobStatus)
              .toList());
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
    specMap.put("successfulJobsHistoryLimit", spec.successfulJobsHistoryLimit());
    specMap.put("failedJobsHistoryLimit", spec.failedJobsHistoryLimit());
    specMap.put("suspend", spec.suspend());

    Map<String, Object> status = new LinkedHashMap<>();
    status.put("spec", specMap);
    storeClient
        .getCronJobLastSchedule(spec.tenantId(), spec.name())
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
        DaemonSetSpec.class,
        this::resolveDaemonSetNameOrHandleSubRoute,
        name -> findTenantByName(storeClient.listDaemonSetSpecs(), name),
        this::handlePutDaemonSet,
        this::handleGetDaemonSet,
        this::handleDeleteDaemonSet);
  }

  /** Mirrors {@link #resolveDeploymentNameOrHandleSubRoute}'s shape exactly. */
  private Optional<String> resolveDaemonSetNameOrHandleSubRoute(HttpExchange exchange)
      throws IOException {
    String tail = pathSegmentAfter(exchange, "/daemonsets/");
    int slash = tail.indexOf('/');
    String name = slash < 0 ? tail : tail.substring(0, slash);
    if (name.isBlank() || slash < 0) {
      return Optional.of(name);
    }
    String action = tail.substring(slash + 1);
    Optional<String> tenant = workloadTenantHint(exchange);
    switch (action) {
      case "revisions" -> {
        if (requireAuthorized(
            exchange, ResourceKind.DAEMONSET, Verb.READ, tenant, Optional.of(name))) {
          handleListControllerRevisions(exchange, "DaemonSet", tenant, name);
        }
      }
      case "rollback" -> {
        if (requireAuthorized(
            exchange, ResourceKind.DAEMONSET, Verb.WRITE, tenant, Optional.of(name))) {
          handleRollbackDaemonSet(exchange, tenant, name);
        }
      }
      default -> respond(exchange, 404, "unknown daemonset endpoint: " + action);
    }
    return Optional.empty();
  }

  private AuditOutcome handlePutDaemonSet(
      HttpExchange exchange, String name, WorkloadSpec parsed, List<String> warnings)
      throws IOException {
    if (!(parsed instanceof DaemonSetSpec parsedSpec)) {
      respond(
          exchange,
          400,
          "manifest kind does not match /daemonsets route (expected kind: DaemonSet)");
      return AuditOutcome.REJECTED;
    }
    if (!parsedSpec.name().equals(name)) {
      respond(
          exchange,
          400,
          "manifest name '" + parsedSpec.name() + "' does not match URL path '" + name + "'");
      return AuditOutcome.REJECTED;
    }
    AdmissionArtifact admitted =
        admissionArtifact(
            parsedSpec.artifactPath(),
            parsedSpec.moduleId(),
            parsedSpec.vessel(),
            parsedSpec.tenantId());
    if (admitted.rejection().isPresent()) {
      respond(exchange, 400, admitted.rejection().get());
      return AuditOutcome.REJECTED;
    }
    DaemonSetSpec spec = withArtifactSha256(parsedSpec, admitted.sha256());
    Optional<DaemonSetSpec> allowed =
        admitWorkload(exchange, ResourceKind.DAEMONSET, spec, admitted.artifact());
    if (allowed.isEmpty()) {
      return AuditOutcome.REJECTED;
    }
    spec = allowed.get();
    Optional<DaemonSetSpec> previous = storeClient.getDaemonSetSpec(parsedSpec.tenantId(), name);
    if (previous.isEmpty() || daemonSetContentChanged(previous.get(), spec)) {
      storeClient.propose(
          new StateMutation.AppendControllerRevision(
              nextRevisionFor("DaemonSet", spec, OptionalInt.empty())));
    }
    storeClient.propose(new StateMutation.PutDaemonSetSpec(spec));
    attachWarnings(exchange, warnings, "daemonset", name);
    respond(exchange, 200, "ok");
    return AuditOutcome.APPLIED;
  }

  /** Same three-field trigger {@link #deploymentContentChanged} uses -- see its own javadoc. */
  private static boolean daemonSetContentChanged(DaemonSetSpec previous, DaemonSetSpec next) {
    return !previous.moduleId().equals(next.moduleId())
        || !previous.artifactPath().equals(next.artifactPath())
        || !previous.artifactSha256().equals(next.artifactSha256());
  }

  /**
   * {@code POST /daemonsets/{name}/rollback} -- mirrors {@link #handleRollbackDeployment} exactly,
   * except there is no {@code AdmissionChain} for DaemonSet to re-run (see {@link
   * #handlePutDaemonSet}'s own "No tenant-quota check here" comment): re-validation is artifact
   * resolution only.
   */
  private void handleRollbackDaemonSet(
      HttpExchange exchange, Optional<String> tenantHint, String name) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    List<ControllerRevision> revisions =
        storeClient.listControllerRevisions("DaemonSet", tenantHint, name);
    if (revisions.isEmpty()) {
      respond(exchange, 404, "no revision history for daemonset: " + name);
      return;
    }
    OptionalInt targetRevision =
        resolveRollbackTarget(revisions, parseToRevision(readBody(exchange)));
    Optional<ControllerRevision> target =
        targetRevision.isEmpty()
            ? Optional.empty()
            : revisions.stream().filter(r -> r.revision() == targetRevision.getAsInt()).findFirst();
    if (target.isEmpty()) {
      respond(
          exchange,
          404,
          targetRevision.isEmpty()
              ? "daemonset " + name + " has no earlier revision to roll back to"
              : "no such revision of daemonset " + name + ": " + targetRevision.getAsInt());
      return;
    }
    DaemonSetSpec restored = (DaemonSetSpec) target.get().spec();
    AdmissionArtifact admitted =
        admissionArtifact(
            restored.artifactPath(), restored.moduleId(), restored.vessel(), restored.tenantId());
    if (admitted.rejection().isPresent()) {
      respond(exchange, 409, admitted.rejection().get());
      return;
    }
    DaemonSetSpec resolved = withArtifactSha256(restored, admitted.sha256());
    ControllerRevision newRevision = nextRevisionFor("DaemonSet", resolved, targetRevision);
    storeClient.propose(new StateMutation.AppendControllerRevision(newRevision));
    storeClient.propose(new StateMutation.PutDaemonSetSpec(resolved));
    respondJson(exchange, 200, controllerRevisionToJson(newRevision));
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
        spec.vessel(),
        spec.tolerateAllTaints());
  }

  private void handleGetDaemonSet(HttpExchange exchange, Optional<String> tenantHint, String name)
      throws IOException {
    Optional<DaemonSetSpec> spec = storeClient.getDaemonSetSpec(tenantHint, name);
    if (spec.isEmpty()) {
      respond(exchange, 404, "no such daemonset: " + name);
      return;
    }
    respondJson(exchange, 200, daemonSetStatus(spec.get()));
  }

  private void handleDeleteDaemonSet(
      HttpExchange exchange, Optional<String> tenantHint, String name) throws IOException {
    storeClient.propose(new StateMutation.RemoveDaemonSetSpec(tenantHint, name));
    respond(exchange, 200, "ok");
  }

  /** Every daemonset, in the same shape {@link #handleGetDaemonSet} returns for one. */
  private void handleDaemonSetsList(HttpExchange exchange) {
    try {
      Optional<Predicate<Optional<String>>> readableTenant =
          requireListAuthorized(exchange, ResourceKind.DAEMONSET);
      if (readableTenant.isEmpty()) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange,
          200,
          storeClient.listDaemonSetSpecs().stream()
              .filter(spec -> readableTenant.get().test(spec.tenantId()))
              .map(this::daemonSetStatus)
              .toList());
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
    specMap.put("tolerateAllTaints", spec.tolerateAllTaints());
    spec.tenantId().ifPresent(tenantId -> specMap.put("tenantId", tenantId));
    spec.vessel().ifPresent(v -> specMap.put("vessel", vesselToJson(v)));

    List<Map<String, Object>> instances = new ArrayList<>();
    List<String> notRunningReasons = new ArrayList<>();
    for (DaemonSetAssignment assignment :
        storeClient.listDaemonSetAssignmentsFor(spec.tenantId(), spec.name())) {
      Map<String, Object> instance = new LinkedHashMap<>();
      instance.put("nodeId", assignment.nodeId());
      Optional<InstanceObservation> observation = findObservationForDaemonSetAssignment(assignment);
      observation.ifPresent(obs -> instance.put("observation", observationToJson(obs)));
      if (observation.isEmpty()) {
        // A DaemonSet places at most one instance per node, so every one of its own timeline
        // events is recorded against index 0 -- the same index its assignments are handed to an
        // agent under.
        notRunningReason(spec.tenantId(), spec.name(), 0)
            .ifPresent(
                reason -> {
                  instance.put("notRunningReason", reason);
                  notRunningReasons.add(reason);
                });
      }
      instances.add(instance);
    }

    Map<String, Object> status = new LinkedHashMap<>();
    status.put("spec", specMap);
    status.put("instances", instances);
    putNotRunningRollup(status, notRunningReasons);
    // Absent until DaemonSetReconciler's first tick for this daemonset -- see
    // StoreReader#getDaemonSetDesiredCount's own javadoc. Present alongside instances.size() (the
    // placed count) is what lets a shortfall be read back at all, the same "desired" vs "placed"
    // comparison a Deployment/StatefulSet's own unplacedCount already exposes.
    storeClient
        .getDaemonSetDesiredCount(spec.tenantId(), spec.name())
        .ifPresent(
            desired -> {
              status.put("desired", desired);
              // Clamped: a count of "how many are still missing" is never negative. A scale-down
              // releases one index per tick by design, so instances legitimately outnumber the
              // target for a few ticks, and a raw subtraction published that as a negative.
              status.put("unplacedCount", Math.max(0, desired - instances.size()));
            });
    return status;
  }

  private Optional<InstanceObservation> findObservationForDaemonSetAssignment(
      DaemonSetAssignment assignment) {
    return findObservation(
        assignment.nodeId(),
        obs ->
            obs.deploymentName().equals(assignment.daemonSetName())
                && obs.instanceIndex() == 0
                && obs.tenantId().equals(assignment.tenantId()));
  }

  // ---- /statefulsets/{name}, /statefulsets ----

  private void handleStatefulSet(HttpExchange exchange) {
    dispatchResourceRequest(
        exchange,
        ResourceKind.STATEFULSET,
        "missing statefulset name",
        "statefulset",
        StatefulSetSpec.class,
        this::resolveStatefulSetNameOrHandleSubRoute,
        name -> findTenantByName(storeClient.listStatefulSetSpecs(), name),
        this::handlePutStatefulSet,
        this::handleGetStatefulSet,
        this::handleDeleteStatefulSet);
  }

  /** Mirrors {@link #resolveDeploymentNameOrHandleSubRoute}'s shape exactly. */
  private Optional<String> resolveStatefulSetNameOrHandleSubRoute(HttpExchange exchange)
      throws IOException {
    String tail = pathSegmentAfter(exchange, "/statefulsets/");
    int slash = tail.indexOf('/');
    String name = slash < 0 ? tail : tail.substring(0, slash);
    if (name.isBlank() || slash < 0) {
      return Optional.of(name);
    }
    String action = tail.substring(slash + 1);
    Optional<String> tenant = workloadTenantHint(exchange);
    switch (action) {
      case "revisions" -> {
        if (requireAuthorized(
            exchange, ResourceKind.STATEFULSET, Verb.READ, tenant, Optional.of(name))) {
          handleListControllerRevisions(exchange, "StatefulSet", tenant, name);
        }
      }
      case "rollback" -> {
        if (requireAuthorized(
            exchange, ResourceKind.STATEFULSET, Verb.WRITE, tenant, Optional.of(name))) {
          handleRollbackStatefulSet(exchange, tenant, name);
        }
      }
      default -> respond(exchange, 404, "unknown statefulset endpoint: " + action);
    }
    return Optional.empty();
  }

  private AuditOutcome handlePutStatefulSet(
      HttpExchange exchange, String name, WorkloadSpec parsed, List<String> warnings)
      throws IOException {
    if (!(parsed instanceof StatefulSetSpec parsedSpec)) {
      respond(
          exchange,
          400,
          "manifest kind does not match /statefulsets route (expected kind: StatefulSet)");
      return AuditOutcome.REJECTED;
    }
    if (!parsedSpec.name().equals(name)) {
      respond(
          exchange,
          400,
          "manifest name '" + parsedSpec.name() + "' does not match URL path '" + name + "'");
      return AuditOutcome.REJECTED;
    }
    AdmissionArtifact admitted =
        admissionArtifact(
            parsedSpec.artifactPath(),
            parsedSpec.moduleId(),
            parsedSpec.vessel(),
            parsedSpec.tenantId());
    if (admitted.rejection().isPresent()) {
      respond(exchange, 400, admitted.rejection().get());
      return AuditOutcome.REJECTED;
    }
    StatefulSetSpec spec = withArtifactSha256(parsedSpec, admitted.sha256());
    Optional<StatefulSetSpec> allowed =
        admitWorkload(exchange, ResourceKind.STATEFULSET, spec, admitted.artifact());
    if (allowed.isEmpty()) {
      return AuditOutcome.REJECTED;
    }
    spec = allowed.get();
    Optional<StatefulSetSpec> previous =
        storeClient.getStatefulSetSpec(parsedSpec.tenantId(), name);
    if (previous.isEmpty() || statefulSetContentChanged(previous.get(), spec)) {
      storeClient.propose(
          new StateMutation.AppendControllerRevision(
              nextRevisionFor("StatefulSet", spec, OptionalInt.empty())));
    }
    storeClient.propose(new StateMutation.PutStatefulSetSpec(spec));
    attachWarnings(exchange, warnings, "statefulset", name);
    respond(exchange, 200, "ok");
    return AuditOutcome.APPLIED;
  }

  /** Same three-field trigger {@link #deploymentContentChanged} uses -- see its own javadoc. */
  private static boolean statefulSetContentChanged(StatefulSetSpec previous, StatefulSetSpec next) {
    return !previous.moduleId().equals(next.moduleId())
        || !previous.artifactPath().equals(next.artifactPath())
        || !previous.artifactSha256().equals(next.artifactSha256());
  }

  /**
   * {@code POST /statefulsets/{name}/rollback} -- mirrors {@link #handleRollbackDeployment}
   * exactly, except there is no {@code AdmissionChain} for StatefulSet to re-run (see {@link
   * #handlePutStatefulSet}'s own "No tenant-quota check here" comment): re-validation is artifact
   * resolution only.
   */
  private void handleRollbackStatefulSet(
      HttpExchange exchange, Optional<String> tenantHint, String name) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    List<ControllerRevision> revisions =
        storeClient.listControllerRevisions("StatefulSet", tenantHint, name);
    if (revisions.isEmpty()) {
      respond(exchange, 404, "no revision history for statefulset: " + name);
      return;
    }
    OptionalInt targetRevision =
        resolveRollbackTarget(revisions, parseToRevision(readBody(exchange)));
    Optional<ControllerRevision> target =
        targetRevision.isEmpty()
            ? Optional.empty()
            : revisions.stream().filter(r -> r.revision() == targetRevision.getAsInt()).findFirst();
    if (target.isEmpty()) {
      respond(
          exchange,
          404,
          targetRevision.isEmpty()
              ? "statefulset " + name + " has no earlier revision to roll back to"
              : "no such revision of statefulset " + name + ": " + targetRevision.getAsInt());
      return;
    }
    StatefulSetSpec restored = (StatefulSetSpec) target.get().spec();
    AdmissionArtifact admitted =
        admissionArtifact(
            restored.artifactPath(), restored.moduleId(), restored.vessel(), restored.tenantId());
    if (admitted.rejection().isPresent()) {
      respond(exchange, 409, admitted.rejection().get());
      return;
    }
    StatefulSetSpec resolved = withArtifactSha256(restored, admitted.sha256());
    ControllerRevision newRevision = nextRevisionFor("StatefulSet", resolved, targetRevision);
    storeClient.propose(new StateMutation.AppendControllerRevision(newRevision));
    storeClient.propose(new StateMutation.PutStatefulSetSpec(resolved));
    respondJson(exchange, 200, controllerRevisionToJson(newRevision));
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

  private void handleGetStatefulSet(HttpExchange exchange, Optional<String> tenantHint, String name)
      throws IOException {
    Optional<StatefulSetSpec> spec = storeClient.getStatefulSetSpec(tenantHint, name);
    if (spec.isEmpty()) {
      respond(exchange, 404, "no such statefulset: " + name);
      return;
    }
    respondJson(exchange, 200, statefulSetStatus(spec.get()));
  }

  private void handleDeleteStatefulSet(
      HttpExchange exchange, Optional<String> tenantHint, String name) throws IOException {
    storeClient.propose(new StateMutation.RemoveStatefulSetSpec(tenantHint, name));
    respond(exchange, 200, "ok");
  }

  /** Every statefulset, in the same shape {@link #handleGetStatefulSet} returns for one. */
  private void handleStatefulSetsList(HttpExchange exchange) {
    try {
      Optional<Predicate<Optional<String>>> readableTenant =
          requireListAuthorized(exchange, ResourceKind.STATEFULSET);
      if (readableTenant.isEmpty()) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange,
          200,
          storeClient.listStatefulSetSpecs().stream()
              .filter(spec -> readableTenant.get().test(spec.tenantId()))
              .map(this::statefulSetStatus)
              .toList());
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
    List<String> notRunningReasons = new ArrayList<>();
    for (StatefulSetAssignment assignment :
        storeClient.listStatefulSetAssignmentsFor(spec.tenantId(), spec.name())) {
      Map<String, Object> instance = new LinkedHashMap<>();
      instance.put("instanceIndex", assignment.instanceIndex());
      instance.put("nodeId", assignment.nodeId());
      Optional<InstanceObservation> observation =
          findObservationForStatefulSetAssignment(assignment);
      observation.ifPresent(obs -> instance.put("observation", observationToJson(obs)));
      if (observation.isEmpty()) {
        notRunningReason(spec.tenantId(), spec.name(), assignment.instanceIndex())
            .ifPresent(
                reason -> {
                  instance.put("notRunningReason", reason);
                  notRunningReasons.add(reason);
                });
      }
      instances.add(instance);
    }

    Map<String, Object> status = new LinkedHashMap<>();
    status.put("spec", specMap);
    status.put("instances", instances);
    // Clamped for the same reason the DaemonSet count is: a scale-down leaves more instances than
    // replicas for a few ticks, and "how many are still missing" cannot be negative.
    status.put("unplacedCount", Math.max(0, spec.replicas() - instances.size()));
    unplacedReason(spec.tenantId(), spec.name(), spec.replicas(), instances)
        .ifPresent(reason -> status.put("unplacedReason", reason));
    putNotRunningRollup(status, notRunningReasons);
    return status;
  }

  private Optional<InstanceObservation> findObservationForStatefulSetAssignment(
      StatefulSetAssignment assignment) {
    return findObservation(
        assignment.nodeId(),
        obs ->
            obs.deploymentName().equals(assignment.statefulSetName())
                && obs.instanceIndex() == assignment.instanceIndex()
                && obs.tenantId().equals(assignment.tenantId()));
  }

  /**
   * Why one of this workload's replicas is currently sitting unplaced, in the scheduler's own
   * words. The reconciler already records its refusal as a durable {@code TRANSITION_FAILED} event
   * against the index it could not place; without surfacing it here, reading that reason meant
   * either querying the index's own event timeline (which an operator has no reason to suspect
   * exists for a replica that never started) or reading the control plane's own server log, where
   * it is re-logged every tick for as long as the deployment stays stuck.
   *
   * <p>Indices are walked in order and the first refusal wins, so a partially-placed workload
   * reports the lowest-numbered replica's reason rather than an arbitrary one. Absent when nothing
   * is unplaced, and also when an index is unplaced for a reason no reconciler tick has recorded
   * yet -- a deployment admitted moments ago has genuinely not been refused anything.
   */
  private Optional<String> unplacedReason(
      Optional<String> tenantId, String name, int replicas, List<Map<String, Object>> instances) {
    if (instances.size() >= replicas) {
      return Optional.empty();
    }
    Set<Object> placed =
        instances.stream().map(i -> i.get("instanceIndex")).collect(Collectors.toSet());
    for (int index = 0; index < replicas; index++) {
      if (placed.contains(index)) {
        continue;
      }
      List<InstanceEvent> events = storeClient.listInstanceEvents(tenantId, name, index);
      if (!events.isEmpty() && events.get(0).kind() == InstanceEventKind.TRANSITION_FAILED) {
        return Optional.of(events.get(0).message());
      }
    }
    return Optional.empty();
  }

  /**
   * Why an instance that <em>is</em> placed is nevertheless not running: its owning node's own
   * agent recorded a durable {@code TRANSITION_FAILED} against this index and no node currently
   * reports an observation for it. A workload whose replicas were all placed but none of which ever
   * started otherwise reads back as fully healthy -- every index accounted for, nothing unplaced,
   * and simply no observation where a live one would be -- which is precisely the state an operator
   * most needs told.
   *
   * <p>Conditioned on a recorded failure rather than on the missing observation alone: an instance
   * placed moments ago has legitimately not reported anything yet, and calling that "not running"
   * would flag every fresh deployment for its first few seconds.
   */
  private Optional<String> notRunningReason(
      Optional<String> tenantId, String name, int instanceIndex) {
    List<InstanceEvent> events = storeClient.listInstanceEvents(tenantId, name, instanceIndex);
    if (events.isEmpty() || events.get(0).kind() != InstanceEventKind.TRANSITION_FAILED) {
      return Optional.empty();
    }
    InstanceEvent event = events.get(0);
    return Optional.of(
        event.causeSummary().map(cause -> event.message() + ": " + cause).orElse(event.message()));
  }

  /**
   * Folds the per-instance {@code notRunning} reasons collected while building a workload's {@code
   * instances[]} into the workload-level rollup an operator's own tooling reads: a count that is
   * always present (so "how many of my replicas are actually running" is a subtraction, not an
   * inference from an absent key) and the first of the reasons as the one line to show, with each
   * instance keeping its own alongside it.
   */
  private static void putNotRunningRollup(Map<String, Object> status, List<String> reasons) {
    status.put("notRunningCount", reasons.size());
    if (!reasons.isEmpty()) {
      status.put("notRunningReason", reasons.get(0));
    }
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
    List<String> notRunningReasons = new ArrayList<>();
    for (InstanceAssignment assignment :
        storeClient.listAssignmentsFor(spec.tenantId(), spec.name())) {
      Map<String, Object> instance = new LinkedHashMap<>();
      instance.put("instanceIndex", assignment.instanceIndex());
      instance.put("nodeId", assignment.nodeId());
      Optional<InstanceObservation> observation = findObservation(assignment);
      observation.ifPresent(obs -> instance.put("observation", observationToJson(obs)));
      if (observation.isEmpty()) {
        notRunningReason(spec.tenantId(), spec.name(), assignment.instanceIndex())
            .ifPresent(
                reason -> {
                  instance.put("notRunningReason", reason);
                  notRunningReasons.add(reason);
                });
      }
      instances.add(instance);
    }

    Map<String, Object> status = new LinkedHashMap<>();
    status.put("spec", specMap);
    status.put("instances", instances);
    // Clamped for the same reason the DaemonSet count is: a scale-down leaves more instances than
    // replicas for a few ticks, and "how many are still missing" cannot be negative.
    status.put("unplacedCount", Math.max(0, spec.replicas() - instances.size()));
    unplacedReason(spec.tenantId(), spec.name(), spec.replicas(), instances)
        .ifPresent(reason -> status.put("unplacedReason", reason));
    putNotRunningRollup(status, notRunningReasons);
    status.put("quotaViolating", storeClient.isQuotaViolating(spec.tenantId(), spec.name()));
    // Present only once the autoscaler has actually moved this deployment -- what its own
    // stabilization windows are measured against, so an operator can see why a scale decision is
    // currently being held back.
    storeClient
        .getDeploymentLastScale(spec.tenantId(), spec.name())
        .ifPresent(t -> status.put("lastScaleTime", t.toString()));
    Optional<String> limitRangeViolationReason =
        storeClient.limitRangeViolationReason(spec.tenantId(), spec.name());
    status.put("limitRangeViolating", limitRangeViolationReason.isPresent());
    limitRangeViolationReason.ifPresent(reason -> status.put("limitRangeViolationReason", reason));
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
    map.put("scaleUpCooldownSeconds", policy.scaleUpCooldown().toSeconds());
    map.put("scaleDownCooldownSeconds", policy.scaleDownCooldown().toSeconds());
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
      Map<String, Object> fileMap = new LinkedHashMap<>();
      fileMap.put("path", file.path());
      file.configKey().ifPresent(key -> fileMap.put("config", key));
      file.secretKey().ifPresent(key -> fileMap.put("secret", key));
      files.add(fileMap);
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
      case VesselEnvValue.VolumeMount volumeMount ->
          Map.of(
              "volume",
              Map.of(
                  "sizeBytes",
                  volumeMount.sizeBytes(),
                  "reclaimPolicy",
                  volumeMount.reclaimPolicy().name()));
    };
  }

  private static Map<String, Object> vesselProbeToJson(VesselProbeSpec probe) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("initialDelaySeconds", probe.initialDelaySeconds());
    switch (probe) {
      case VesselProbeSpec.Tcp ignored -> map.put("tcp", true);
      case VesselProbeSpec.Http http -> map.put("http", http.path());
    }
    // Emitted under the same key the manifest parser reads, so a spec read back out of the API
    // and re-applied keeps naming the port it dials -- without it, a multi-port vessel's probe
    // silently becomes ambiguous on the round trip.
    probe.portName().ifPresent(name -> map.put("port", name));
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
      // An explicit ?tenant= (an operator disambiguating a same-named collision, or a node that
      // happens to know its own tenant) always wins, exactly like dispatchResourceRequest's own
      // GET/DELETE -- resolveTenantForWorkloadName only stands in for a hint nobody gave.
      Optional<String> declaredTenant = Optional.ofNullable(parseQuery(exchange).get("tenant"));
      Optional<String> tenantHint =
          declaredTenant.isPresent() ? declaredTenant : resolveTenantForWorkloadName(name);
      Optional<DeploymentSpec> deployment = storeClient.getDeployment(tenantHint, name);
      if (deployment.isPresent()) {
        if (authorizeEndpointsRead(
            exchange, ResourceKind.DEPLOYMENT, deployment.get().tenantId())) {
          respondJson(exchange, 200, deploymentEndpoints(deployment.get()));
        }
        return;
      }
      Optional<JobSpec> job = storeClient.getJobSpec(tenantHint, name);
      if (job.isPresent()) {
        if (authorizeEndpointsRead(exchange, ResourceKind.JOB, job.get().tenantId())) {
          respondJson(exchange, 200, jobEndpoints(job.get()));
        }
        return;
      }
      Optional<DaemonSetSpec> daemonSet = storeClient.getDaemonSetSpec(tenantHint, name);
      if (daemonSet.isPresent()) {
        if (authorizeEndpointsRead(exchange, ResourceKind.DAEMONSET, daemonSet.get().tenantId())) {
          respondJson(exchange, 200, daemonSetEndpoints(daemonSet.get()));
        }
        return;
      }
      Optional<StatefulSetSpec> statefulSet = storeClient.getStatefulSetSpec(tenantHint, name);
      if (statefulSet.isPresent()) {
        if (authorizeEndpointsRead(
            exchange, ResourceKind.STATEFULSET, statefulSet.get().tenantId())) {
          respondJson(exchange, 200, statefulSetEndpoints(statefulSet.get()));
        }
        return;
      }
      respond(exchange, 404, "no such workload: " + name);
    } catch (AmbiguousTenantException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("endpoints request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * The tenant of whichever Deployment/Job/DaemonSet/StatefulSet spec is named {@code name} -- the
   * {@code /endpoints/{name}} fallback for a caller that declares no {@code ?tenant=} of its own
   * (an operator with a broad grant, or a {@code gimle:nodes} caller that only ever knows the name
   * of what it's running, never which tenant owns it), resolving across every tenant instead. That
   * guess is never trusted as an access decision by itself -- {@link #authorizeEndpointsRead}
   * downstream independently re-checks the resolved tenant against the caller's real RBAC grant
   * (or, for a node, its own real assignment), so guessing the wrong tenant under a same-named
   * collision only ever costs an honest 403, never a cross-tenant read.
   */
  private Optional<String> resolveTenantForWorkloadName(String name) {
    Optional<String> deployment = findTenantByName(storeClient.listDeployments(), name);
    if (deployment.isPresent()) {
      return deployment;
    }
    Optional<String> job = findTenantByName(storeClient.listJobSpecs(), name);
    if (job.isPresent()) {
      return job;
    }
    Optional<String> daemonSet = findTenantByName(storeClient.listDaemonSetSpecs(), name);
    if (daemonSet.isPresent()) {
      return daemonSet;
    }
    return findTenantByName(storeClient.listStatefulSetSpecs(), name);
  }

  /**
   * The tenant of whichever Service is named {@code name}, for a caller that gave no {@code
   * ?tenant=} hint of its own. {@link ServiceSpec} is not a {@link WorkloadSpec}, so this cannot
   * reuse {@link #findTenantByName}; the resolution rule is otherwise identical, including the
   * {@link AmbiguousTenantException} thrown when more than one tenant owns the name -- see that
   * method's own javadoc.
   */
  private Optional<String> resolveTenantForServiceName(String name) {
    List<String> tenantIds =
        serviceRegistry.list().stream()
            .filter(spec -> spec.name().equals(name))
            .flatMap(spec -> spec.tenantId().stream())
            .distinct()
            .toList();
    if (tenantIds.size() > 1) {
      throw new AmbiguousTenantException("service", name, tenantIds);
    }
    return tenantIds.stream().findFirst();
  }

  /**
   * The tenant of whichever spec in {@code specs} is named {@code name} -- {@link Optional#empty()}
   * if none is, collapsing "no such resource" and "found, but genuinely untenanted" into the one
   * answer every {@link TenantLookup}/{@link #resolveTenantForWorkloadName} caller already treats
   * identically (a caller must declare a real tenant grant to read/delete either). When {@code
   * name} exists under more than one tenant -- a real cross-tenant name collision, not a hash-order
   * artifact of iterating {@code specs} (previously this picked whichever tenant's copy happened to
   * come first in an unordered backing collection, silently and inconsistently across workload
   * kinds, with no sign to the caller a second copy even existed) -- this refuses to guess and
   * throws {@link AmbiguousTenantException} instead, forcing the caller to disambiguate with an
   * explicit {@code ?tenant=}, exactly as it already must for two tenants sharing nothing else.
   */
  private static Optional<String> findTenantByName(
      List<? extends WorkloadSpec> specs, String name) {
    List<String> tenantIds =
        specs.stream()
            .filter(s -> s.name().equals(name))
            .flatMap(s -> s.tenantId().stream())
            .distinct()
            .toList();
    if (tenantIds.size() > 1) {
      throw new AmbiguousTenantException("workload", name, tenantIds);
    }
    return tenantIds.stream().findFirst();
  }

  /**
   * Thrown by {@link #findTenantByName}/{@link #resolveTenantForServiceName} when a bare,
   * untenanted name resolves to more than one tenant's own copy -- a real collision, not a hint to
   * guess from. Every caller of those two methods sits inside a try block that maps this to a
   * {@code 400} telling the caller to add {@code ?tenant=}, the same way an explicit tenant is
   * already required to disambiguate a {@code GET}/{@code DELETE} once a caller knows the collision
   * exists; this is what makes that disambiguation discoverable in the first place, instead of one
   * tenant's copy being silently substituted for another's with no sign the substitution ever
   * happened.
   */
  private static final class AmbiguousTenantException extends RuntimeException {
    private final List<String> tenantIds;

    AmbiguousTenantException(String resourceNoun, String name, List<String> tenantIds) {
      super(
          "ambiguous "
              + resourceNoun
              + " name '"
              + name
              + "' exists in multiple tenants ("
              + String.join(", ", tenantIds)
              + "); specify ?tenant= to disambiguate");
      this.tenantIds = tenantIds;
    }

    /** Every tenant currently holding a same-named copy -- what triggered the ambiguity. */
    List<String> tenantIds() {
      return tenantIds;
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
  // ---- /instances/{deploymentName}/{instanceIndex}/fabric-endpoint ----

  /**
   * Where one instance is reachable on the service fabric -- the address a {@code FabricClient}
   * dials to invoke a service on it directly, rather than through {@code FabricServiceRegistry}'s
   * own locality-preferring selection.
   *
   * <p>Only the agent supervising the instance holds this: it arrives on the worker's own {@code
   * Hello} handshake and lives in that agent's memory. This route resolves which node that is and
   * proxies there, the same API-server-to-kubelet shape {@code /logs/instances/...} already uses,
   * so a caller needs only the workload name and index rather than having to know the placement.
   *
   * <p>Read-only and diagnostic. It exposes no capability a caller does not already have -- an
   * instance's fabric listener authenticates and authorizes every inbound call itself, and dialing
   * it directly is precisely what {@code FabricServer}'s own listener-side tenant re-check exists
   * to defend against, which is why that defence could not be exercised end to end while the
   * address stayed undiscoverable.
   */
  private void handleInstanceFabricEndpoint(HttpExchange exchange) {
    try {
      handleInstanceFabricEndpointRequest(exchange);
    } catch (AmbiguousTenantException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("fabric-endpoint request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleInstanceFabricEndpointRequest(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    String tail = pathSegmentAfter(exchange, "/instances/");
    String[] parts = tail.split("/");
    if (parts.length != 3 || !"fabric-endpoint".equals(parts[2])) {
      respond(
          exchange, 404, "expected /instances/{deploymentName}/{instanceIndex}/fabric-endpoint");
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

    Optional<String> tenantId = instanceTenantHint(exchange, deploymentName);
    Optional<InstancePlacement> placement =
        resolveInstancePlacement(tenantId, deploymentName, instanceIndex);
    if (placement.isEmpty()) {
      // Authorize before answering: without this, an unauthorized caller could probe which
      // (name, index) pairs exist by telling 404 apart from 403. DEPLOYMENT is the right kind to
      // check against for a workload whose kind we could not determine, since it is the least
      // privileged of the four an instance can belong to.
      if (requireAuthorized(exchange, ResourceKind.DEPLOYMENT, Verb.READ, tenantId)) {
        respond(exchange, 404, "no placement found for " + deploymentName + "#" + instanceIndex);
      }
      return;
    }
    if (!requireAuthorized(exchange, placement.get().kind(), Verb.READ, tenantId)) {
      return;
    }
    proxyToAgent(
        exchange,
        placement.get().nodeId(),
        "/fabric-endpoints/" + deploymentName + "/" + instanceIndex,
        null);
  }

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
    for (InstanceAssignment assignment :
        storeClient.listAssignmentsFor(spec.tenantId(), spec.name())) {
      endpoints.add(
          endpointEntry(
              assignment.nodeId(), assignment.instanceIndex(), findObservation(assignment)));
    }
    return endpoints;
  }

  /** {@code attempt} plays {@code instanceIndex}'s own role -- see {@link JobRun}'s own javadoc. */
  private List<Map<String, Object>> jobEndpoints(JobSpec spec) {
    List<Map<String, Object>> endpoints = new ArrayList<>();
    for (JobRun run : storeClient.listJobRunsFor(spec.tenantId(), spec.name())) {
      endpoints.add(endpointEntry(run.nodeId(), run.attempt(), findObservationForJobRun(run)));
    }
    return endpoints;
  }

  /** A DaemonSet has no {@code instanceIndex} of its own -- the node itself is the index. */
  private List<Map<String, Object>> daemonSetEndpoints(DaemonSetSpec spec) {
    List<Map<String, Object>> endpoints = new ArrayList<>();
    for (DaemonSetAssignment assignment :
        storeClient.listDaemonSetAssignmentsFor(spec.tenantId(), spec.name())) {
      endpoints.add(
          endpointEntry(assignment.nodeId(), 0, findObservationForDaemonSetAssignment(assignment)));
    }
    return endpoints;
  }

  private List<Map<String, Object>> statefulSetEndpoints(StatefulSetSpec spec) {
    List<Map<String, Object>> endpoints = new ArrayList<>();
    for (StatefulSetAssignment assignment :
        storeClient.listStatefulSetAssignmentsFor(spec.tenantId(), spec.name())) {
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
   * instances contributed a reading, one row per deployment, keyed by the {@code (tenantId,
   * deploymentName)} pair that actually identifies one. Instances with no observation yet (never
   * heartbeated, or heartbeated but not yet reporting metrics) simply don't contribute to the
   * average rather than dragging it toward zero, the same "degrade, don't fail" posture {@link
   * #findObservation} already has for a missing reading.
   */
  private void handleMetrics(HttpExchange exchange) {
    try {
      Optional<Predicate<Optional<String>>> readableTenant =
          requireListAuthorized(exchange, ResourceKind.DEPLOYMENT);
      if (readableTenant.isEmpty()) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      for (DeploymentSpec spec : storeClient.listDeployments()) {
        if (!readableTenant.get().test(spec.tenantId())) {
          continue;
        }
        List<InstanceObservation> observations = new ArrayList<>();
        for (InstanceAssignment assignment :
            storeClient.listAssignmentsFor(spec.tenantId(), spec.name())) {
          findObservation(assignment).ifPresent(observations::add);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        // Both halves of the identity, not just the name: listDeployments spans tenants, so two
        // tenants running an identically-named deployment produce two rows a client reading only
        // deploymentName cannot tell apart -- or join back to either tenant's deployment.
        row.put("tenantId", spec.tenantId().orElse(null));
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
                && obs.instanceIndex() == assignment.instanceIndex()
                && obs.tenantId().equals(assignment.tenantId()));
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
      String nodeId = slash < 0 ? tail : tail.substring(0, slash);
      if (nodeId.isBlank()) {
        respond(exchange, 400, "missing nodeId");
        return;
      }
      if (slash < 0) {
        if (!"GET".equals(exchange.getRequestMethod())) {
          respond(exchange, 405, "method not allowed");
          return;
        }
        if (requireAuthorized(
            exchange, ResourceKind.NODE, Verb.READ, Optional.empty(), Optional.of(nodeId))) {
          handleNodeRead(exchange, nodeId);
        }
        return;
      }
      String action = tail.substring(slash + 1);
      // targetId=nodeId is what lets a gimle:nodes principal reach exactly its own subresources
      // (Authorizer's node self-service short-circuit) with no RoleBinding needing to exist for
      // it -- and nothing else. Labelling is deliberately excluded from that: a node that could
      // label itself could grant itself the very labels placement uses to keep workloads off it,
      // so this one action is withheld from the self-service path and needs a real grant.
      Verb verb = "assignments".equals(action) ? Verb.READ : Verb.WRITE;
      Optional<String> selfServiceTarget =
          "labels".equals(action) ? Optional.empty() : Optional.of(nodeId);
      if (!requireAuthorized(
          exchange, ResourceKind.NODE, verb, Optional.empty(), selfServiceTarget)) {
        return;
      }
      switch (action) {
        case "register" -> handleRegister(exchange, nodeId);
        case "heartbeat" -> handleHeartbeat(exchange, nodeId);
        case "assignments" -> handleAssignments(exchange, nodeId);
        case "cordon" -> handleCordon(exchange, nodeId, true);
        case "uncordon" -> handleCordon(exchange, nodeId, false);
        case "taint" -> handleTaint(exchange, nodeId, true);
        case "untaint" -> handleTaint(exchange, nodeId, false);
        case "labels" -> handleNodeLabels(exchange, nodeId);
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
    // Carried over rather than reset: a node restarting re-reports only what it knows about
    // itself, and dropping the operator's labels here would silently un-place every workload
    // that was scheduled because of them.
    Set<String> operatorLabels =
        storeClient
            .getNodeRegistration(nodeId)
            .map(NodeRegistration::operatorLabels)
            .orElse(Set.of());
    storeClient.propose(
        new StateMutation.PutNodeRegistration(
            new NodeRegistration(
                nodeId,
                capabilities,
                apiAddress == null ? Optional.empty() : Optional.of((String) apiAddress),
                operatorLabels)));
    respond(exchange, 200, "ok");
  }

  /**
   * {@code PUT /nodes/{nodeId}/labels} -- replaces this node's operator-applied label set with the
   * one in the body ({@code {"labels": ["edge"]}}), the same declarative shape every other write
   * here takes: the request states the labels the node should have, not an edit to apply.
   *
   * <p>Only the operator half is touched. Labels the node reported for itself at startup stay
   * exactly as they are and cannot be removed through this endpoint, since they are a property of
   * how the node was launched, not of cluster state.
   */
  private void handleNodeLabels(HttpExchange exchange, String nodeId) throws IOException {
    if (!"PUT".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    Optional<NodeRegistration> existing = storeClient.getNodeRegistration(nodeId);
    if (existing.isEmpty()) {
      respond(exchange, 404, "no such node: " + nodeId);
      return;
    }
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    Object rawLabels = body.get("labels");
    if (!(rawLabels instanceof List<?> list)) {
      respond(exchange, 400, "expected {\"labels\": [...]}");
      return;
    }
    Set<String> labels = new LinkedHashSet<>();
    for (Object label : list) {
      if (!(label instanceof String text) || text.isBlank()) {
        respond(exchange, 400, "every label must be a non-blank string");
        return;
      }
      labels.add(text.trim());
    }
    storeClient.propose(
        new StateMutation.PutNodeRegistration(existing.get().withOperatorLabels(labels)));
    respondJson(exchange, 200, Map.of("nodeId", nodeId, "labels", List.copyOf(labels)));
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
      Optional<DeploymentSpec> spec =
          storeClient.getDeployment(assignment.tenantId(), assignment.deploymentName());
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
              spec.get().vessel(),
              spec.get().configMapRefs(),
              spec.get().secretMapRefs());
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
      Optional<JobSpec> jobSpec = storeClient.getJobSpec(run.tenantId(), run.jobName());
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
          storeClient.getDaemonSetSpec(assignment.tenantId(), assignment.daemonSetName());
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
          storeClient.getStatefulSetSpec(assignment.tenantId(), assignment.statefulSetName());
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
   * Reserves (or releases) {@code nodeId} for one tenant -- the Kubernetes node-taint/toleration
   * analogue described in {@code Scheduler}'s own javadoc: a tainted node excludes every tenant
   * that isn't a member of its taint set from future placement, unconditionally across every
   * isolation tier, until untainted. Never evicts an instance already running there, only keeps a
   * non-tolerating tenant's new placements off it. Idempotent: tainting an already-tainted {@code
   * (nodeId, tenantId)} pair, or untainting a pair that isn't tainted, is a no-op success.
   */
  private void handleTaint(HttpExchange exchange, String nodeId, boolean tainted)
      throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    Object tenantId = body.get("tenantId");
    if (!(tenantId instanceof String tenantIdString) || tenantIdString.isBlank()) {
      respond(exchange, 400, "missing tenantId");
      return;
    }
    storeClient.propose(new StateMutation.PutNodeTaint(nodeId, tenantIdString, tainted));
    respond(exchange, 200, "ok");
  }

  /**
   * Relays one worker-reported {@link InstanceEvent}, forwarded by its agent, into the durable
   * per-instance event log -- the {@code nodeId} in the URL is only used for the {@code NODE:WRITE}
   * self-service authorization {@link #handleNode} already applied; the event itself carries its
   * own deployment/instance identity, unrelated to which node happened to relay it. {@code
   * InstanceEvent} carries no {@code tenantId} of its own (it predates per-tenant store scoping and
   * crosses the agent/worker wire, neither of which otherwise needs to know about tenancy), so the
   * tenant to key this event's timeline under is joined from whichever live assignment currently
   * matches this (deploymentName, instanceIndex) pair, tried across all four kinds via {@link
   * #resolveInstanceEventTenant} -- the same cross-kind join {@link #resolveInstancePlacement}
   * already uses for {@code /instances/.../fabric-endpoint}. Checking only {@link
   * InstanceAssignment} (Deployment-kind bookkeeping alone) here left every StatefulSet/DaemonSet
   * instance's own relayed events permanently misfiled under the untenanted namespace regardless of
   * their real tenant, indistinguishable from "genuinely untenanted" to any later {@code ?tenant=}
   * read. Untenanted (rather than rejected) if no matching assignment is found in any of the four,
   * e.g. a final lifecycle event arriving just after the assignment itself was already torn down.
   */
  private void handleAppendInstanceEvent(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    Object causeSummary = body.get("causeSummary");
    String deploymentName = (String) body.get("deploymentName");
    int instanceIndex = ((Number) body.get("instanceIndex")).intValue();
    InstanceEvent event =
        new InstanceEvent(
            (String) body.get("id"),
            deploymentName,
            instanceIndex,
            InstanceEventKind.valueOf((String) body.get("kind")),
            (String) body.get("message"),
            causeSummary == null ? Optional.empty() : Optional.of((String) causeSummary),
            ((Number) body.get("occurredAtEpochMilli")).longValue());
    Optional<String> tenant = resolveInstanceEventTenant(deploymentName, instanceIndex);
    storeClient.propose(new StateMutation.AppendInstanceEvent(tenant, event));
    respond(exchange, 200, "ok");
  }

  /**
   * The tenant owning {@code (deploymentName, instanceIndex)}, tried across all four assignment
   * kinds in turn -- Deployment ({@link InstanceAssignment}), StatefulSet, DaemonSet (index always
   * {@code 0}, keyed by node instead), then Job (matched by attempt number) -- exactly the same
   * kind-priority order {@link #resolveInstancePlacement} already uses, except unscoped by tenant
   * since the whole point here is discovering which tenant owns the name in the first place. {@link
   * Optional#empty()} (the untenanted namespace) if none of the four currently has a matching
   * assignment.
   */
  private Optional<String> resolveInstanceEventTenant(String deploymentName, int instanceIndex) {
    Optional<Optional<String>> deployment =
        storeClient.listAssignments().stream()
            .filter(
                a ->
                    a.deploymentName().equals(deploymentName) && a.instanceIndex() == instanceIndex)
            .map(InstanceAssignment::tenantId)
            .findFirst();
    if (deployment.isPresent()) {
      return deployment.get();
    }
    Optional<Optional<String>> statefulSet =
        storeClient.listStatefulSetAssignments().stream()
            .filter(
                a ->
                    a.statefulSetName().equals(deploymentName)
                        && a.instanceIndex() == instanceIndex)
            .map(StatefulSetAssignment::tenantId)
            .findFirst();
    if (statefulSet.isPresent()) {
      return statefulSet.get();
    }
    if (instanceIndex == 0) {
      Optional<Optional<String>> daemonSet =
          storeClient.listDaemonSetAssignments().stream()
              .filter(a -> a.daemonSetName().equals(deploymentName))
              .map(DaemonSetAssignment::tenantId)
              .findFirst();
      if (daemonSet.isPresent()) {
        return daemonSet.get();
      }
    }
    return storeClient.listJobRuns().stream()
        .filter(run -> run.jobName().equals(deploymentName) && run.attempt() == instanceIndex)
        .map(JobRun::tenantId)
        .findFirst()
        .orElse(Optional.empty());
  }

  /**
   * {@code GET /events?deployment=<name>&instance=<index>[&tenant=<id>]} -- an instance's own
   * timeline, newest-first, capped at {@code StateStore}'s own per-instance retention window.
   * {@code GET /events[?tenant=<id>&since=&limit=&cursor=]} -- neither {@code deployment} nor
   * {@code instance} given -- is the cluster-wide sibling instead, delegated to {@link
   * #handleClusterInstanceEvents}: "what has this cluster been doing" rather than one instance's
   * own history, paginated the same {@code since}/{@code limit}/{@code cursor} way {@code GET
   * /audit} already established (see {@link #handleAudit}) rather than a second idiom. Supplying
   * only one of {@code deployment}/{@code instance} is rejected rather than silently falling back
   * to the cluster-wide mode, since that combination can only ever be a caller's mistake.
   *
   * <p>Both modes authorize as {@code DEPLOYMENT:READ} scoped to {@code tenant}. For the
   * single-instance mode, an explicit {@code ?tenant=} always wins; an omitted one now resolves via
   * {@link #resolveInstanceEventTenant} -- the exact same live-assignment join {@link
   * #handleAppendInstanceEvent} itself used to decide which tenant to file the event under in the
   * first place, so a read can never disagree with where a write actually landed the way it did
   * defaulting straight to the untenanted namespace: a bare {@code gimle events <name> <idx>} for
   * an ordinary, currently-assigned instance used to come back empty every time regardless of its
   * real tenant, requiring an exact {@code --tenant} match to see anything at all, unlike every
   * other verb this class exposes. {@link #handleClusterInstanceEvents} instead matches every
   * tenant when {@code tenant} is omitted, since a cluster-wide read has no one instance's key to
   * address in the first place.
   */
  private void handleEvents(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Map<String, String> query = parseQuery(exchange);
      String deploymentName = query.get("deployment");
      String instanceParam = query.get("instance");
      if (deploymentName == null && instanceParam == null) {
        handleClusterInstanceEvents(exchange, query);
        return;
      }
      if (deploymentName == null || deploymentName.isBlank() || instanceParam == null) {
        respond(exchange, 400, "expected ?deployment=<name>&instance=<index>");
        return;
      }
      int instanceIndex = Integer.parseInt(instanceParam);
      Optional<String> declaredTenant = Optional.ofNullable(query.get("tenant"));
      Optional<String> tenant =
          declaredTenant.isPresent()
              ? declaredTenant
              : resolveInstanceEventTenant(deploymentName, instanceIndex);
      if (!requireAuthorized(exchange, ResourceKind.DEPLOYMENT, Verb.READ, tenant)) {
        return;
      }
      List<Map<String, Object>> events = new ArrayList<>();
      for (InstanceEvent event :
          storeClient.listInstanceEvents(tenant, deploymentName, instanceIndex)) {
        events.add(instanceEventToJson(event));
      }
      respondJson(exchange, 200, events);
    } catch (NumberFormatException e) {
      respondQuietly(exchange, 400, "instance/since/limit must be numeric");
    } catch (AmbiguousTenantException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("events request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * The cluster-wide branch of {@link #handleEvents}: every instance's own lifecycle timeline
   * merged newest-first, {@code since}/{@code limit}/{@code cursor}-paginated the same way {@link
   * #handleAudit} paginates the audit trail (see {@link InstanceEventPage}, {@link
   * InstanceEventCursor} -- mirrored rather than shared with {@link AuditPage}/{@link AuditCursor}
   * so the two endpoints' pagination can evolve independently). Unlike the single-instance mode, an
   * omitted {@code tenant} matches every tenant's own timelines rather than addressing the
   * untenanted namespace specifically -- there is no single instance key here for an absent tenant
   * to resolve to, and a caller with no tenant filter is, by construction, one {@link
   * #requireAuthorized} already required to hold an unscoped {@code DEPLOYMENT:READ} grant to reach
   * at all.
   */
  private void handleClusterInstanceEvents(HttpExchange exchange, Map<String, String> query)
      throws IOException {
    Optional<String> tenant = Optional.ofNullable(query.get("tenant"));
    if (!requireAuthorized(exchange, ResourceKind.DEPLOYMENT, Verb.READ, tenant)) {
      return;
    }
    Optional<Long> since = Optional.ofNullable(query.get("since")).map(Long::parseLong);
    int limit =
        Optional.ofNullable(query.get("limit")).map(Integer::parseInt).orElse(Integer.MAX_VALUE);
    Optional<String> cursor = Optional.ofNullable(query.get("cursor")).filter(c -> !c.isBlank());

    // One list call, one snapshot: the cursor is resolved against exactly the events this page is
    // cut from, so a concurrent append or per-instance eviction can never land between the two.
    List<InstanceEvent> matching = storeClient.listInstanceEvents(tenant, since);
    InstanceEventPage page;
    try {
      page =
          InstanceEventPage.of(
              matching, cursor, limit, InstanceEventCursor.fingerprintOf(tenant, since));
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, e.getMessage());
      return;
    }
    List<Map<String, Object>> events = new ArrayList<>();
    for (InstanceEvent event : page.events()) {
      events.add(instanceEventToJson(event));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("events", events);
    body.put("matchedCount", page.matchedCount());
    page.nextCursor().ifPresent(next -> body.put("nextCursor", next));
    body.put("cursorExpired", page.cursorExpired());
    respondJson(exchange, 200, body);
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
   * {@code GET /audit[?principal=&resource=&tenant=&since=&limit=&cursor=]} -- the read side of the
   * cross-resource audit trail {@link #requireAuthorized} writes into. Gated on {@link
   * ResourceKind#AUDIT}, the same "reading the trail is itself an access-controlled action" framing
   * already applied to {@code ROLE}/{@code ROLE_BINDING}/{@code ACCOUNT} -- every filter is
   * optional and independently combinable, matching {@link
   * com.gimle.mimir.store.StoreReader#listAuditEvents}'s own shape.
   *
   * <p>The response is an envelope, not a bare array, and it describes two independent things the
   * caller must be able to tell apart. {@code retainedCount}/{@code evictedTotal}/{@code
   * oldestRetainedAtEpochMilli}/{@code truncated} describe the trail's own retention state (see
   * {@link AuditTrailStatus}) -- "this is the complete record" versus "this cluster crossed the
   * retention cap". {@code matchedCount}/{@code nextCursor}/{@code cursorExpired} describe this
   * query instead: how many retained events matched the filters at all (so a caller can say
   * "showing 100 of 412" rather than only "100 rows"), how to ask for the next page, and whether
   * the page asked for had already been evicted. Without the first pair an operator cannot tell a
   * complete trail from a capped one; without the second they cannot tell a complete answer from a
   * silently truncated one.
   *
   * <p>{@code limit} defaults to no limit at all, so an unpaged caller still gets every matching
   * event in one response; paging is opt-in by setting {@code limit} and following {@code
   * nextCursor}. See {@link AuditCursor} for why the cursor names an event rather than an offset.
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
      Optional<String> cursor = Optional.ofNullable(query.get("cursor")).filter(c -> !c.isBlank());

      // One list call, one snapshot: the cursor is resolved against exactly the events this page is
      // cut from, so a concurrent append or eviction can never land between the two.
      List<AuditEvent> matching = storeClient.listAuditEvents(principal, resource, tenant, since);
      AuditPage page;
      try {
        page =
            AuditPage.of(
                matching,
                cursor,
                limit,
                AuditCursor.fingerprintOf(principal, resource, tenant, since));
      } catch (IllegalArgumentException e) {
        respondQuietly(exchange, 400, e.getMessage());
        return;
      }
      List<Map<String, Object>> events = new ArrayList<>();
      for (AuditEvent event : page.events()) {
        events.add(auditEventToJson(event));
      }
      AuditTrailStatus status = storeClient.auditTrailStatus();
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("events", events);
      body.put("matchedCount", page.matchedCount());
      page.nextCursor().ifPresent(next -> body.put("nextCursor", next));
      body.put("cursorExpired", page.cursorExpired());
      body.put("retainedCount", status.retainedCount());
      body.put("evictedTotal", status.evictedTotal());
      status.oldestRetainedAtEpochMilli().ifPresent(t -> body.put("oldestRetainedAtEpochMilli", t));
      body.put("truncated", status.truncated());
      respondJson(exchange, 200, body);
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
    map.put("outcome", event.outcome().name());
    map.put("occurredAtEpochMilli", event.occurredAtEpochMilli());
    return map;
  }

  /**
   * Every registered node, with its capabilities, its last-heartbeat time if it's ever sent one,
   * and the platform's own verdict on that node's freshness.
   *
   * <p>The verdict is computed here rather than left to each client to derive from {@code
   * lastHeartbeatAt}: deriving it needs the store's observation window too, which no client has,
   * and every client that tried ended up carrying its own copy of the staleness threshold and
   * reporting a node as stale whenever a store election had merely cleared the heartbeat it was
   * reading.
   */
  private void handleNodesList(HttpExchange exchange) {
    try {
      if (!requireAuthorized(exchange, ResourceKind.NODE, Verb.READ, Optional.empty())) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Instant now = Instant.now();
      Instant observingSince = storeClient.nodeObservationWindowStart();
      respondJson(
          exchange,
          200,
          storeClient.listNodeRegistrations().stream()
              .map(registration -> nodeJson(registration, observingSince, now))
              .toList());
    } catch (IOException | RuntimeException e) {
      log.warn("nodes list request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * One registered node's full read shape -- shared by the whole-cluster listing and the
   * single-node read, so the two can never drift into describing the same node differently.
   */
  private Map<String, Object> nodeJson(
      NodeRegistration registration, Instant observingSince, Instant now) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("nodeId", registration.nodeId());
    Map<String, Object> capabilities = new LinkedHashMap<>();
    capabilities.put(
        "supportedTiers",
        registration.capabilities().supportedTiers().stream().map(Enum::name).toList());
    capabilities.put("labels", List.copyOf(registration.effectiveLabels()));
    capabilities.put("reportedLabels", List.copyOf(registration.capabilities().labels()));
    capabilities.put("operatorLabels", List.copyOf(registration.operatorLabels()));
    node.put("capabilities", capabilities);
    node.put("cordoned", storeClient.isNodeCordoned(registration.nodeId()));
    node.put("taints", storeClient.getNodeTaints(registration.nodeId()).stream().sorted().toList());
    Optional<ObservedHeartbeat> observed = storeClient.getNodeHeartbeat(registration.nodeId());
    node.put("status", nodeFreshness.statusOf(true, observed, observingSince, now).name());
    observed.ifPresent(
        heartbeat -> {
          node.put("lastHeartbeatAt", heartbeat.receivedAt().toString());
          node.put("capacity", capacityToJson(heartbeat.heartbeat().capacity()));
        });
    return node;
  }

  /**
   * {@code GET /nodes/{nodeId}} -- the single-node read, in the identical shape {@code GET /nodes}
   * lists. Without it, addressing one node by name reached the sub-resource dispatcher below and
   * came back as a usage error, so a caller wanting one node's current labels or taints had no way
   * to ask for them but to list the whole cluster and filter client-side.
   */
  private void handleNodeRead(HttpExchange exchange, String nodeId) throws IOException {
    Optional<NodeRegistration> registration = storeClient.getNodeRegistration(nodeId);
    if (registration.isEmpty()) {
      respond(exchange, 404, "unknown node: " + nodeId);
      return;
    }
    respondJson(
        exchange,
        200,
        nodeJson(registration.get(), storeClient.nodeObservationWindowStart(), Instant.now()));
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
    return InstanceObservation.builder(
            (String) map.get("deploymentName"),
            ((Number) map.get("instanceIndex")).intValue(),
            moduleIdFromJson((Map<?, ?>) map.get("moduleId")),
            (String) map.get("lifecycleState"),
            (Boolean) map.get("alive"),
            (Boolean) map.get("ready"))
        .load(
            numberField(map, "requestRatePerSecond", 0.0).doubleValue(),
            numberField(map, "errorRatePerSecond", 0.0).doubleValue(),
            numberField(map, "queueDepth", 0).intValue(),
            numberField(map, "cpuMillicoresUsed", 0L).longValue(),
            numberField(map, "memoryBytesUsed", 0L).longValue())
        .ports(portsFromJson(map.get("ports")))
        .volumeUsageBytes(numberField(map, "volumeUsageBytes", 0L).longValue())
        .workerId(Optional.ofNullable((String) map.get("workerId")))
        .tenantId(Optional.ofNullable((String) map.get("tenantId")))
        .isolationTier(isolationTierFromJson(map.get("isolationTier")))
        .resourceLimit(
            map.get("resourceLimit") instanceof Map<?, ?> limit
                ? resourceSpecFromJson(limit)
                : Optional.empty())
        .build();
  }

  /**
   * Absent whenever the reporting agent held no module descriptor for the instance -- a vessel is
   * an OS process and has none. An unrecognized tier name is treated as absent rather than failing
   * the whole heartbeat: this field is advisory, and one unreadable value must not cost the control
   * plane every other observation in the payload.
   */
  private static Optional<IsolationTier> isolationTierFromJson(Object raw) {
    if (!(raw instanceof String name)) {
      return Optional.empty();
    }
    try {
      return Optional.of(IsolationTier.valueOf(name));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
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
    if (obs.volumeUsageBytes() > 0) {
      map.put("volumeUsageBytes", obs.volumeUsageBytes());
    }
    obs.workerId().ifPresent(id -> map.put("workerId", id));
    obs.tenantId().ifPresent(id -> map.put("tenantId", id));
    obs.isolationTier().ifPresent(tier -> map.put("isolationTier", tier.name()));
    obs.resourceLimit().ifPresent(limit -> map.put("resourceLimit", resourceSpecToJson(limit)));
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
    // Without this, every vessel-flagged assignment reaches the agent looking exactly like a
    // module assignment: the agent's own AssignedInstance#vessel() is always empty over the wire,
    // so it always tries ModuleArtifactReader on the vessel's own non-modular jar and fails
    // forever, no matter what the manifest actually declared.
    instance.vessel().ifPresent(v -> map.put("vessel", vesselToJson(v)));
    if (!instance.configMapRefs().isEmpty()) {
      map.put("configMapRefs", instance.configMapRefs());
    }
    if (!instance.secretMapRefs().isEmpty()) {
      map.put("secretMapRefs", instance.secretMapRefs());
    }
    return map;
  }

  // ---- /tenants and /tenants/{id} ----

  private void handleTenantsList(HttpExchange exchange) {
    try {
      Optional<Predicate<Optional<String>>> readableTenant =
          requireListAuthorized(exchange, ResourceKind.TENANT);
      if (readableTenant.isEmpty()) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange,
          200,
          storeClient.listTenants().stream()
              .filter(tenant -> readableTenant.get().test(Optional.of(tenant.id())))
              .map(this::tenantToJson)
              .toList());
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
          // Deferred past authorization itself (see requireAuthorizedForWrite's javadoc): an
          // authorized caller isn't audited by that call alone, because
          // rejectIfReservedSystemTenant/rejectSecondTenantUnderPlaintext -- admission checks
          // that only run *after* authorization -- can still refuse the write. Recording
          // "allowed" before those checks run was exactly the bug this defers past: a tenant
          // creation plaintext mode went on to refuse still landed in the audit trail as
          // allowed:true/APPLIED, indistinguishable from a genuine success.
          //
          // Unlike dispatchResourceRequest's own workload-PUT branch, though, the real outcome
          // here is already fully known the moment both guards below resolve -- handlePutTenant
          // itself has no rejection path of its own, so there is no further admission stage left
          // to run inside it the way tenant-quota/LimitRange admission runs inside a workload
          // put.run. The audit event is therefore recorded before handlePutTenant runs, not
          // after: this route's own pre-existing audit-before-response ordering, which a durable
          // read immediately following the HTTP response (no intervening round trip to give a
          // deferred-after write time to land) already depends on.
          Optional<Principal> auditPrincipal =
              requireAuthorizedForWrite(exchange, ResourceKind.TENANT, Optional.of(id));
          if (auditPrincipal.isPresent()) {
            if (rejectIfReservedSystemTenant(exchange, Optional.of(id))
                || rejectSecondTenantUnderPlaintext(exchange, id)) {
              // Both guards have already written their own 403 by this point -- see
              // recordAuditEventBestEffort's own javadoc for why a failure recording this event
              // must never be allowed to disturb a response already on the wire.
              recordAuditEventBestEffort(
                  auditPrincipal.get(),
                  ResourceKind.TENANT,
                  Verb.WRITE,
                  Optional.of(id),
                  Optional.empty(),
                  true,
                  AuditOutcome.REJECTED);
            } else {
              recordAuditEventBestEffort(
                  auditPrincipal.get(),
                  ResourceKind.TENANT,
                  Verb.WRITE,
                  Optional.of(id),
                  Optional.empty(),
                  true,
                  AuditOutcome.APPLIED);
              handlePutTenant(exchange, id);
            }
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

  /**
   * Omitting {@code isolationPosture} keeps whatever posture the tenant already has, and defaults a
   * brand-new tenant to {@link TenantIsolationPosture#OPEN}. Deliberately not a full replace for
   * this one field: every other field here is a quota an operator edits routinely, and a quota bump
   * that silently reopened a tenant an operator had closed would be a security regression caused by
   * an unrelated edit.
   */
  private void handlePutTenant(HttpExchange exchange, String id) throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    Map<?, ?> quotaMap = (Map<?, ?>) body.get("quota");
    ResourceQuota quota =
        new ResourceQuota(
            ((Number) quotaMap.get("maxMemoryBytes")).longValue(),
            ((Number) quotaMap.get("maxCpuMillicores")).longValue(),
            ((Number) quotaMap.get("maxInstances")).intValue());
    Optional<TenantIsolationPosture> submitted =
        parseIsolationPosture(body.get("isolationPosture"));
    TenantIsolationPosture posture =
        submitted.orElseGet(
            () ->
                storeClient
                    .getTenant(id)
                    .map(Tenant::isolationPosture)
                    .orElse(TenantIsolationPosture.OPEN));
    storeClient.propose(new StateMutation.PutTenant(new Tenant(id, quota, posture)));
    respond(exchange, 200, "ok");
  }

  private static Optional<TenantIsolationPosture> parseIsolationPosture(Object rawValue) {
    if (rawValue == null) {
      return Optional.empty();
    }
    String text = String.valueOf(rawValue);
    for (TenantIsolationPosture candidate : TenantIsolationPosture.values()) {
      if (candidate.name().equalsIgnoreCase(text)) {
        return Optional.of(candidate);
      }
    }
    throw new IllegalArgumentException(
        "isolationPosture must be one of "
            + Arrays.toString(TenantIsolationPosture.values())
            + ", got: "
            + text);
  }

  private void handleGetTenant(HttpExchange exchange, String id) throws IOException {
    Optional<Tenant> tenant = storeClient.getTenant(id);
    if (tenant.isEmpty()) {
      respond(exchange, 404, "no such tenant: " + id);
      return;
    }
    respondJson(exchange, 200, tenantToJson(tenant.get()));
  }

  /**
   * Deleting a tenant other policies name in their allow lists is permitted, but never silent: the
   * references it leaves behind are logged here and reported as {@code danglingTenantIds} on every
   * later read of the affected policies. Rewriting those policies instead would change what they
   * allow without anyone asking for that, and a tenant id can legitimately be recreated later.
   */
  private void handleDeleteTenant(HttpExchange exchange, String id) throws IOException {
    storeClient.propose(new StateMutation.RemoveTenant(id));
    List<String> dangling =
        networkPolicyRegistry.list().stream()
            .filter(spec -> spec.referencedTenantIds().contains(id))
            .map(spec -> spec.tenantId() + "/" + spec.name())
            .sorted()
            .toList();
    if (!dangling.isEmpty()) {
      log.warn(
          "deleted tenant {} is still named in the allow list of network policies {};"
              + " those rules can no longer match anything",
          id,
          dangling);
    }
    respond(exchange, 200, "ok");
  }

  // ---- /kinddefinitions and /resources/* (Galdr custom kinds) ----

  /**
   * {@code GET /kinddefinitions} -- readable by any authenticated principal, no RBAC walk:
   * definitions are schemas, not data; manifest authors and the console's kind picker both need the
   * catalog to do anything at all, the Kubernetes {@code system:discovery} posture.
   */
  private void handleKindDefinitionsList(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      if (!requireAuthenticated(exchange)) {
        return;
      }
      respondJson(
          exchange,
          200,
          storeClient.listKindDefinitions().stream()
              .sorted(Comparator.comparing(KindDefinitionSpec::kindName))
              .map(GaldrJson::definitionToJson)
              .toList());
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (IOException | RuntimeException e) {
      log.warn("kind definitions list request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /** {@code PUT}/{@code GET}/{@code DELETE /kinddefinitions/{kind}}. */
  private void handleKindDefinition(HttpExchange exchange) {
    try {
      String kindSegment = pathSegmentAfter(exchange, "/kinddefinitions/");
      if (kindSegment.isBlank()) {
        respond(exchange, 400, "missing kind name");
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "PUT" -> handlePutKindDefinition(exchange, kindSegment);
        case "GET" -> {
          if (requireAuthenticated(exchange)) {
            handleGetKindDefinition(exchange, kindSegment);
          }
        }
        case "DELETE" -> {
          if (requireAuthorized(
              exchange,
              ResourceKind.KIND_DEFINITION,
              Verb.DELETE,
              Optional.empty(),
              Optional.of(storedKindName(kindSegment)))) {
            handleDeleteKindDefinition(exchange, storedKindName(kindSegment));
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (GimleManifestException | IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("kind definition request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * The stored, prefixed form of a URL kind segment -- a caller addressing a definition by the
   * unprefixed name it submitted ({@code /kinddefinitions/Greeting}) reaches the normalized {@code
   * custom.Greeting} record, the same forgiveness the PUT's own normalization implies.
   */
  private static String storedKindName(String kindSegment) {
    return kindSegment.contains(".")
        ? kindSegment
        : KindDefinitionParser.DEFAULT_PREFIX + kindSegment;
  }

  private void handlePutKindDefinition(HttpExchange exchange, String kindSegment)
      throws IOException {
    // Parse before authorizing, the same body-before-authorize order dispatchResourceRequest
    // establishes -- though a KindDefinition is always cluster-scoped, so no tenant resolution
    // hangs on the body here, only the fail-fast 400 for a malformed manifest.
    KindDefinitionParser.ParsedKindDefinition parsed =
        KindDefinitionParser.parse(exchange.getRequestBody());
    KindDefinitionSpec submitted = parsed.spec();
    if (!submitted.kindName().equals(kindSegment)
        && !submitted.kindName().equals(storedKindName(kindSegment))) {
      respond(
          exchange,
          400,
          "manifest kind name '"
              + submitted.kindName()
              + "' does not match URL path '"
              + kindSegment
              + "'");
      return;
    }
    Optional<Principal> auditPrincipal =
        requireAuthorizedForWrite(exchange, ResourceKind.KIND_DEFINITION, Optional.empty());
    if (auditPrincipal.isEmpty()) {
      return;
    }
    AuditOutcome outcome = applyKindDefinition(exchange, submitted, parsed.warnings());
    recordAuditEventBestEffort(
        auditPrincipal.get(),
        ResourceKind.KIND_DEFINITION,
        Verb.WRITE,
        Optional.empty(),
        Optional.of(submitted.kindName()),
        true,
        outcome);
  }

  /**
   * Admits one KindDefinition put: declared-name collisions, then -- on a re-PUT of a live kind --
   * re-validation of <em>every stored instance</em> against the new schema (refusing with the
   * violator list rather than any compatibility calculus over nested schemas: the instances
   * themselves are the check), then a backfill of newly-defaulted fields into the stored instances,
   * batched atomically with the definition put itself so no replica ever holds the new schema
   * beside un-backfilled instances.
   */
  private AuditOutcome applyKindDefinition(
      HttpExchange exchange, KindDefinitionSpec submitted, List<String> warnings)
      throws IOException {
    Optional<String> collision =
        GaldrKinds.declaredNameCollision(submitted, storeClient.listKindDefinitions());
    if (collision.isPresent()) {
      respond(exchange, 409, "declared name collision: " + collision.get());
      return AuditOutcome.REJECTED;
    }
    Optional<KindDefinitionSpec> existing = storeClient.getKindDefinition(submitted.kindName());
    long expectedGeneration = existing.map(KindDefinitionSpec::generation).orElse(0L);
    if (existing.isPresent() && existing.get().withGeneration(0L).equals(submitted)) {
      // Identical re-definition: no mutation proposed, no generation churn -- the Andvari
      // identical-re-push rule, so declarative re-applies never look like updates.
      attachWarnings(exchange, warnings, "kinddefinition", submitted.kindName());
      respond(exchange, 200, "ok");
      return AuditOutcome.APPLIED;
    }

    List<StateMutation> backfills = new ArrayList<>();
    if (existing.isPresent()) {
      List<String> violators = new ArrayList<>();
      for (CustomResource resource : storeClient.listCustomResources(submitted.kindName())) {
        try {
          Map<String, Object> defaulted =
              SchemaValidator.validateAndDefault(
                  submitted.schema(),
                  Json.asObject(
                      Json.parse(new String(resource.specJson(), StandardCharsets.UTF_8))));
          byte[] canonical = GaldrJson.canonicalJson(defaulted);
          if (!Arrays.equals(canonical, resource.specJson())) {
            backfills.add(
                new StateMutation.PutCustomResource(
                    new CustomResource(
                        resource.kindName(),
                        resource.name(),
                        resource.tenantId(),
                        canonical,
                        new byte[0],
                        0L),
                    resource.generation()));
          }
        } catch (GimleManifestException e) {
          violators.add(
              resource.tenantId().map(t -> t + "/").orElse("")
                  + resource.name()
                  + " ("
                  + e.getMessage()
                  + ")");
        }
      }
      if (!violators.isEmpty()) {
        respond(
            exchange,
            409,
            "cannot update kind '"
                + submitted.kindName()
                + "': "
                + violators.size()
                + " stored instance(s) violate the new schema: "
                + String.join("; ", violators));
        return AuditOutcome.REJECTED;
      }
    }

    StateMutation.PutKindDefinition putDefinition =
        new StateMutation.PutKindDefinition(submitted, expectedGeneration);
    List<StateMutation> mutations = new ArrayList<>();
    mutations.add(putDefinition);
    mutations.addAll(backfills);
    MutationOutcome outcome =
        storeClient.propose(
            mutations.size() == 1 ? putDefinition : new StateMutation.Batch(mutations));
    if (outcome instanceof MutationOutcome.Rejected rejected) {
      respond(
          exchange,
          409,
          "kind definition '"
              + submitted.kindName()
              + "' was concurrently modified since it was last read ("
              + rejected.reason()
              + "); re-fetch and retry");
      return AuditOutcome.REJECTED;
    }
    attachWarnings(exchange, warnings, "kinddefinition", submitted.kindName());
    respond(exchange, 200, "ok");
    return AuditOutcome.APPLIED;
  }

  private void handleGetKindDefinition(HttpExchange exchange, String kindSegment)
      throws IOException {
    Optional<KindDefinitionSpec> definition =
        storeClient.getKindDefinition(storedKindName(kindSegment));
    if (definition.isEmpty()) {
      // A read miss is a 404 like every other kind's, but still carries the catalog -- the same
      // choices-in-hand contract the /resources unknown-kind 400 gives an apply.
      respond(
          exchange,
          404,
          GaldrKinds.unknownKindMessage(
              storedKindName(kindSegment), storeClient.listKindDefinitions()));
      return;
    }
    respondJson(exchange, 200, GaldrJson.definitionToJson(definition.get()));
  }

  private void handleDeleteKindDefinition(HttpExchange exchange, String kindName)
      throws IOException {
    if (storeClient.getKindDefinition(kindName).isEmpty()) {
      // Idempotent delete-on-missing, the convention every other resource kind here follows.
      respond(exchange, 200, "ok");
      return;
    }
    MutationOutcome outcome = storeClient.propose(new StateMutation.RemoveKindDefinition(kindName));
    if (outcome instanceof MutationOutcome.Rejected rejected) {
      respond(exchange, 409, rejected.reason());
      return;
    }
    respond(exchange, 200, "ok");
  }

  /**
   * {@code /resources/{Kind}}, {@code /resources/{Kind}/{name}}, and {@code
   * /resources/{Kind}/{name}/status} -- the generalized twin of {@link #dispatchResourceRequest},
   * over a generic schema-validated body instead of {@code ManifestParser}'s typed workload specs.
   * Every route resolves the kind against the live definition catalog first, so an unknown kind is
   * a 400 carrying the catalog on every surface, not just apply.
   */
  private void handleCustomResources(HttpExchange exchange) {
    try {
      String tail = pathSegmentAfter(exchange, "/resources/");
      if (tail.isBlank()) {
        respond(exchange, 400, "missing kind name");
        return;
      }
      String[] segments = tail.split("/", -1);
      List<KindDefinitionSpec> definitions = storeClient.listKindDefinitions();
      KindDefinitionSpec definition = GaldrKinds.requireDefinition(segments[0], definitions);
      if (segments.length == 1) {
        if (!"GET".equals(exchange.getRequestMethod())) {
          respond(exchange, 405, "method not allowed");
          return;
        }
        handleListCustomResources(exchange, definition);
        return;
      }
      String name = segments[1];
      if (name.isBlank()) {
        respond(exchange, 400, "missing resource name");
        return;
      }
      if (segments.length == 3 && "status".equals(segments[2])) {
        if (!"PUT".equals(exchange.getRequestMethod())) {
          respond(exchange, 405, "method not allowed");
          return;
        }
        handlePutCustomResourceStatus(exchange, definition, name);
        return;
      }
      if (segments.length > 2) {
        respond(exchange, 404, "unknown resource endpoint: " + segments[2]);
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "PUT" -> handlePutCustomResource(exchange, definition, name);
        case "GET" -> handleGetCustomResource(exchange, definition, name);
        case "DELETE" -> handleDeleteCustomResource(exchange, definition, name);
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (GimleManifestException | IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("custom resource request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleListCustomResources(HttpExchange exchange, KindDefinitionSpec definition)
      throws IOException {
    Optional<Predicate<Optional<String>>> readableTenant =
        requireCustomResourceListAuthorized(exchange, definition.kindName());
    if (readableTenant.isEmpty()) {
      return;
    }
    Optional<String> tenantFilter = Optional.ofNullable(parseQuery(exchange).get("tenant"));
    respondJson(
        exchange,
        200,
        storeClient.listCustomResources(definition.kindName()).stream()
            .filter(resource -> tenantFilter.isEmpty() || resource.tenantId().equals(tenantFilter))
            .filter(resource -> readableTenant.get().test(resource.tenantId()))
            .sorted(
                Comparator.comparing((CustomResource resource) -> resource.tenantId().orElse(""))
                    .thenComparing(CustomResource::name))
            .map(GaldrJson::resourceToJson)
            .toList());
  }

  private void handlePutCustomResource(
      HttpExchange exchange, KindDefinitionSpec definition, String name) throws IOException {
    CustomResourceManifestParser.ParsedCustomResource parsed =
        CustomResourceManifestParser.parse(exchange.getRequestBody());
    if (!parsed.kindName().equals(definition.kindName())) {
      respond(
          exchange,
          400,
          "manifest kind '"
              + parsed.kindName()
              + "' does not match /resources/"
              + definition.kindName()
              + " route -- instances always use the stored, prefixed kind name");
      return;
    }
    if (!parsed.name().equals(name)) {
      respond(
          exchange,
          400,
          "manifest name '" + parsed.name() + "' does not match URL path '" + name + "'");
      return;
    }
    if (definition.scope() == KindScope.TENANT && parsed.tenantId().isEmpty()) {
      respond(
          exchange,
          400,
          "kind '" + definition.kindName() + "' is Tenant-scoped -- tenantId is required");
      return;
    }
    if (definition.scope() == KindScope.CLUSTER && parsed.tenantId().isPresent()) {
      respond(
          exchange,
          400,
          "kind '" + definition.kindName() + "' is Cluster-scoped -- tenantId must be omitted");
      return;
    }
    // No second re-tenanting check needed (unlike the flat-namespace era dispatchResourceRequest
    // documents): a custom resource's store key is (kind, tenant, name), so a PUT can only ever
    // target the submitted tenant's own record, never overwrite a different tenant's same-named
    // one.
    Optional<Principal> auditPrincipal =
        requireCustomResourceWrite(exchange, definition.kindName(), parsed.tenantId(), false);
    if (auditPrincipal.isEmpty()) {
      return;
    }
    if (rejectIfReservedSystemTenant(exchange, parsed.tenantId())) {
      recordCustomResourceAuditBestEffort(
          auditPrincipal.get(),
          definition.kindName(),
          Verb.WRITE,
          parsed.tenantId(),
          Optional.of(name),
          true,
          AuditOutcome.REJECTED);
      return;
    }
    AuditOutcome outcome = applyCustomResourcePut(exchange, definition, parsed);
    recordCustomResourceAuditBestEffort(
        auditPrincipal.get(),
        definition.kindName(),
        Verb.WRITE,
        parsed.tenantId(),
        Optional.of(name),
        true,
        outcome);
  }

  private AuditOutcome applyCustomResourcePut(
      HttpExchange exchange,
      KindDefinitionSpec definition,
      CustomResourceManifestParser.ParsedCustomResource parsed)
      throws IOException {
    byte[] canonical;
    try {
      Map<String, Object> defaulted =
          SchemaValidator.validateAndDefault(definition.schema(), parsed.spec());
      canonical = GaldrJson.canonicalJson(defaulted);
      SchemaValidator.checkPayloadSize("spec", canonical.length);
    } catch (GimleManifestException e) {
      respond(exchange, 400, String.valueOf(e.getMessage()));
      return AuditOutcome.REJECTED;
    }
    Optional<CustomResource> existing =
        storeClient.getCustomResource(definition.kindName(), parsed.tenantId(), parsed.name());
    if (existing.isPresent() && Arrays.equals(existing.get().specJson(), canonical)) {
      // Identical canonical spec: no mutation proposed, no generation bump -- declarative
      // re-applies never cause phantom generation/observedGeneration churn.
      respond(exchange, 200, "ok");
      return AuditOutcome.APPLIED;
    }
    long expectedGeneration = existing.map(CustomResource::generation).orElse(0L);
    MutationOutcome outcome =
        storeClient.propose(
            new StateMutation.PutCustomResource(
                new CustomResource(
                    definition.kindName(),
                    parsed.name(),
                    parsed.tenantId(),
                    canonical,
                    new byte[0],
                    0L),
                expectedGeneration));
    if (outcome instanceof MutationOutcome.Rejected rejected) {
      respond(
          exchange,
          409,
          "resource '"
              + definition.kindName()
              + "/"
              + parsed.name()
              + "' was concurrently modified since it was last read ("
              + rejected.reason()
              + "); re-fetch and retry");
      return AuditOutcome.REJECTED;
    }
    respond(exchange, 200, "ok");
    return AuditOutcome.APPLIED;
  }

  private void handleGetCustomResource(
      HttpExchange exchange, KindDefinitionSpec definition, String name) throws IOException {
    Optional<String> tenant = customResourceTenant(exchange, definition, name);
    if (!requireCustomResourceAuthorized(
        exchange, definition.kindName(), Verb.READ, tenant, Optional.of(name))) {
      return;
    }
    Optional<CustomResource> resource =
        storeClient.getCustomResource(definition.kindName(), tenant, name);
    if (resource.isEmpty()) {
      respond(exchange, 404, "no such resource: " + definition.kindName() + "/" + name);
      return;
    }
    respondJson(exchange, 200, GaldrJson.resourceToJson(resource.get()));
  }

  private void handleDeleteCustomResource(
      HttpExchange exchange, KindDefinitionSpec definition, String name) throws IOException {
    Optional<String> tenant = customResourceTenant(exchange, definition, name);
    if (!requireCustomResourceAuthorized(
        exchange, definition.kindName(), Verb.DELETE, tenant, Optional.of(name))) {
      return;
    }
    // Idempotent delete-on-missing, matching the majority convention across resource kinds.
    storeClient.propose(
        new StateMutation.RemoveCustomResource(definition.kindName(), tenant, name));
    respond(exchange, 200, "ok");
  }

  private void handlePutCustomResourceStatus(
      HttpExchange exchange, KindDefinitionSpec definition, String name) throws IOException {
    Optional<String> tenant = customResourceTenant(exchange, definition, name);
    Optional<Principal> auditPrincipal =
        requireCustomResourceWrite(exchange, definition.kindName(), tenant, true);
    if (auditPrincipal.isEmpty()) {
      return;
    }
    AuditOutcome outcome = applyCustomResourceStatusPut(exchange, definition, tenant, name);
    recordCustomResourceAuditBestEffort(
        auditPrincipal.get(),
        definition.kindName(),
        Verb.WRITE,
        tenant,
        Optional.of(name + "/status"),
        true,
        outcome);
  }

  private AuditOutcome applyCustomResourceStatusPut(
      HttpExchange exchange, KindDefinitionSpec definition, Optional<String> tenant, String name)
      throws IOException {
    byte[] canonical;
    try {
      Object parsed = Json.parse(readBody(exchange));
      if (!(parsed instanceof Map)) {
        respond(exchange, 400, "status must be a JSON object");
        return AuditOutcome.REJECTED;
      }
      canonical = Json.write(parsed).getBytes(StandardCharsets.UTF_8);
      SchemaValidator.checkPayloadSize("status", canonical.length);
    } catch (GimleManifestException | IllegalArgumentException e) {
      respond(exchange, 400, String.valueOf(e.getMessage()));
      return AuditOutcome.REJECTED;
    }
    if (storeClient.getCustomResource(definition.kindName(), tenant, name).isEmpty()) {
      respond(exchange, 404, "no such resource: " + definition.kindName() + "/" + name);
      return AuditOutcome.REJECTED;
    }
    storeClient.propose(
        new StateMutation.PutCustomResourceStatus(definition.kindName(), tenant, name, canonical));
    respond(exchange, 200, "ok");
    return AuditOutcome.APPLIED;
  }

  /**
   * The tenant a custom-resource GET/DELETE/status-PUT resolves to: always empty for a
   * Cluster-scoped kind; otherwise the caller's own {@code ?tenant=} taken verbatim, falling back
   * to a bare-name search across the kind's stored instances only when no hint was declared --
   * exactly {@link #declaredOrExistingTenant}'s convention, scoped to one kind.
   */
  private Optional<String> customResourceTenant(
      HttpExchange exchange, KindDefinitionSpec definition, String name) {
    if (definition.scope() == KindScope.CLUSTER) {
      return Optional.empty();
    }
    Optional<String> declared = Optional.ofNullable(parseQuery(exchange).get("tenant"));
    if (declared.isPresent()) {
      return declared;
    }
    return storeClient.listCustomResources(definition.kindName()).stream()
        .filter(resource -> resource.name().equals(name))
        .map(CustomResource::tenantId)
        .flatMap(Optional::stream)
        .findFirst();
  }

  // ---- custom-resource authorization/audit plumbing ----
  // Mirrors requireAuthorized/requireAuthorizedForWrite/requireListAuthorized, with two
  // deliberate differences the design calls for: the Authorizer walk carries the request's
  // qualifier ({kind}, or {kind}/status for a status write), and audit rows record the qualified
  // "CustomResource:{kind}" string instead of the bare enum name -- the enum-name path used
  // everywhere else stays untouched.

  private static String customResourceQualifier(String kindName, boolean statusSubresource) {
    return statusSubresource ? kindName + Permission.STATUS_QUALIFIER_SUFFIX : kindName;
  }

  private boolean requireCustomResourceAuthorized(
      HttpExchange exchange,
      String kindName,
      Verb verb,
      Optional<String> tenant,
      Optional<String> targetId) {
    if (!(exchange instanceof HttpsExchange)) {
      if (verb == Verb.WRITE || verb == Verb.DELETE) {
        recordCustomResourceAudit(
            ANONYMOUS_PRINCIPAL, kindName, verb, tenant, targetId, true, AuditOutcome.APPLIED);
      }
      return true;
    }
    Optional<Principal> principal = resolvePrincipal(exchange);
    if (principal.isEmpty()) {
      respondQuietly(exchange, 401, "authentication required");
      return false;
    }
    boolean authorized =
        authorizer.authorize(
            principal.get(),
            ResourceKind.CUSTOM_RESOURCE,
            verb,
            tenant,
            targetId,
            Optional.of(customResourceQualifier(kindName, false)));
    if (verb == Verb.WRITE || verb == Verb.DELETE) {
      recordCustomResourceAudit(
          principal.get(),
          kindName,
          verb,
          tenant,
          targetId,
          authorized,
          authorized ? AuditOutcome.APPLIED : AuditOutcome.REJECTED);
    }
    if (!authorized) {
      respondQuietly(exchange, 403, "forbidden");
      return false;
    }
    return true;
  }

  /**
   * Deferred-audit write authorization for custom-resource spec and status puts, the {@link
   * #requireAuthorizedForWrite} shape: a denial is recorded and answered immediately; an authorized
   * caller is handed back for the caller to audit once the real {@link AuditOutcome} is known.
   */
  private Optional<Principal> requireCustomResourceWrite(
      HttpExchange exchange, String kindName, Optional<String> tenant, boolean statusSubresource) {
    if (!(exchange instanceof HttpsExchange)) {
      return Optional.of(ANONYMOUS_PRINCIPAL);
    }
    Optional<Principal> principal = resolvePrincipal(exchange);
    if (principal.isEmpty()) {
      respondQuietly(exchange, 401, "authentication required");
      return Optional.empty();
    }
    boolean authorized =
        authorizer.authorize(
            principal.get(),
            ResourceKind.CUSTOM_RESOURCE,
            Verb.WRITE,
            tenant,
            Optional.empty(),
            Optional.of(customResourceQualifier(kindName, statusSubresource)));
    if (!authorized) {
      recordCustomResourceAudit(
          principal.get(),
          kindName,
          Verb.WRITE,
          tenant,
          Optional.empty(),
          false,
          AuditOutcome.REJECTED);
      respondQuietly(exchange, 403, "forbidden");
      return Optional.empty();
    }
    return principal;
  }

  /** {@link #requireListAuthorized}'s shape, carrying the kind's own qualifier per item. */
  private Optional<Predicate<Optional<String>>> requireCustomResourceListAuthorized(
      HttpExchange exchange, String kindName) {
    if (!(exchange instanceof HttpsExchange)) {
      return Optional.of(itemTenant -> true);
    }
    Optional<Principal> resolved = resolvePrincipal(exchange);
    if (resolved.isEmpty()) {
      respondQuietly(exchange, 401, "authentication required");
      return Optional.empty();
    }
    Principal principal = resolved.get();
    Optional<String> qualifier = Optional.of(customResourceQualifier(kindName, false));
    boolean unscopedRead =
        authorizer.authorize(
            principal,
            ResourceKind.CUSTOM_RESOURCE,
            Verb.READ,
            Optional.empty(),
            Optional.empty(),
            qualifier);
    boolean allowed =
        unscopedRead || authorizer.hasAnyReadGrant(principal, ResourceKind.CUSTOM_RESOURCE);
    if (!allowed) {
      respondQuietly(exchange, 403, "forbidden");
      return Optional.empty();
    }
    if (unscopedRead) {
      return Optional.of(itemTenant -> true);
    }
    return Optional.of(
        itemTenant ->
            itemTenant.isPresent()
                && authorizer.authorize(
                    principal,
                    ResourceKind.CUSTOM_RESOURCE,
                    Verb.READ,
                    itemTenant,
                    Optional.empty(),
                    qualifier));
  }

  private void recordCustomResourceAudit(
      Principal principal,
      String kindName,
      Verb verb,
      Optional<String> tenant,
      Optional<String> targetId,
      boolean allowed,
      AuditOutcome outcome) {
    storeClient.propose(
        new StateMutation.AppendAuditEvent(
            new AuditEvent(
                UUID.randomUUID().toString(),
                principal.name(),
                principal.groups(),
                "CustomResource:" + kindName,
                verb.name(),
                tenant,
                targetId,
                allowed,
                outcome,
                System.currentTimeMillis())));
  }

  /** {@link #recordAuditEventBestEffort}'s posture for the qualified custom-resource rows. */
  private void recordCustomResourceAuditBestEffort(
      Principal principal,
      String kindName,
      Verb verb,
      Optional<String> tenant,
      Optional<String> targetId,
      boolean allowed,
      AuditOutcome outcome) {
    try {
      recordCustomResourceAudit(principal, kindName, verb, tenant, targetId, allowed, outcome);
    } catch (RuntimeException e) {
      log.warn(
          "failed to record audit event for {} CustomResource:{} (response already sent): {}",
          verb,
          kindName,
          e.getMessage());
    }
  }

  /**
   * Any authenticated principal, no RBAC walk -- the {@code /kinddefinitions} read posture.
   * Plaintext mode has no identity to check, matching {@link #requireAuthorized}'s own carve-out.
   */
  private boolean requireAuthenticated(HttpExchange exchange) {
    if (!(exchange instanceof HttpsExchange)) {
      return true;
    }
    if (resolvePrincipal(exchange).isEmpty()) {
      respondQuietly(exchange, 401, "authentication required");
      return false;
    }
    return true;
  }

  // ---- /limitranges and /limitranges/{tenantId} ----

  /** The four bound keys a {@code PUT /limitranges/{tenantId}} body may carry, in schema order. */
  private static final List<String> LIMIT_RANGE_BOUND_KEYS =
      List.of("minRequest", "maxRequest", "minLimit", "maxLimit");

  private void handleLimitRangesList(HttpExchange exchange) {
    try {
      Optional<Predicate<Optional<String>>> readableTenant =
          requireListAuthorized(exchange, ResourceKind.LIMIT_RANGE);
      if (readableTenant.isEmpty()) {
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange,
          200,
          storeClient.listLimitRanges().stream()
              .filter(spec -> readableTenant.get().test(Optional.of(spec.tenantId())))
              .map(ApiServer::limitRangeSpecToJson)
              .toList());
    } catch (IOException | RuntimeException e) {
      log.warn("limit ranges list request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleLimitRange(HttpExchange exchange) {
    try {
      String tenantId = pathSegmentAfter(exchange, "/limitranges/");
      if (tenantId.isBlank()) {
        respond(exchange, 400, "missing tenant id");
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "PUT" -> {
          if (requireAuthorized(
              exchange, ResourceKind.LIMIT_RANGE, Verb.WRITE, Optional.of(tenantId))) {
            handlePutLimitRange(exchange, tenantId);
          }
        }
        case "GET" -> {
          if (requireAuthorized(
              exchange, ResourceKind.LIMIT_RANGE, Verb.READ, Optional.of(tenantId))) {
            handleGetLimitRange(exchange, tenantId);
          }
        }
        case "DELETE" -> {
          if (requireAuthorized(
              exchange, ResourceKind.LIMIT_RANGE, Verb.DELETE, Optional.of(tenantId))) {
            handleDeleteLimitRange(exchange, tenantId);
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("limit range request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * Strict about the body's shape, deliberately: a field this doesn't recognize is refused, never
   * dropped. A silently-dropped bound is the worst of the three possible outcomes -- the operator
   * is told the floor they wrote was applied, a boundless LimitRange is stored under that tenant,
   * and the mistake only surfaces later as a workload that should have been refused running
   * happily. The nested {@code {memory, cpu}} block is the only accepted spelling of a bound, so a
   * body mirroring {@code gimle set limitrange}'s flat flag names ({@code minRequestMemory}) is
   * named back to the caller rather than quietly ignored.
   *
   * <p>A body declaring no bound at all is refused for the same reason: a LimitRange bounding
   * nothing is indistinguishable from having no LimitRange, so it can only ever be a mistake or a
   * roundabout way of saying {@code DELETE /limitranges/{tenantId}}, which says it unambiguously.
   */
  private void handlePutLimitRange(HttpExchange exchange, String tenantId) throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    List<String> unrecognized =
        body.keySet().stream()
            .map(String::valueOf)
            .filter(key -> !LIMIT_RANGE_BOUND_KEYS.contains(key) && !"tenantId".equals(key))
            .sorted()
            .toList();
    if (!unrecognized.isEmpty()) {
      respond(
          exchange,
          400,
          "unrecognized limit range field(s): "
              + String.join(", ", unrecognized)
              + "; each bound is a nested block, e.g. minRequest: {memory: 24Mi, cpu: 15m} (bounds:"
              + " "
              + String.join(", ", LIMIT_RANGE_BOUND_KEYS)
              + ")");
      return;
    }
    // The resource's identity is the URL path; a body repeating it is tolerated (a GET response
    // handed straight back as a PUT body round-trips) but never allowed to disagree with it.
    Object bodyTenantId = body.get("tenantId");
    if (bodyTenantId != null && !tenantId.equals(String.valueOf(bodyTenantId))) {
      respond(
          exchange,
          400,
          "body tenantId '" + bodyTenantId + "' does not match path tenant '" + tenantId + "'");
      return;
    }
    if (LIMIT_RANGE_BOUND_KEYS.stream().noneMatch(body::containsKey)) {
      respond(
          exchange,
          400,
          "a limit range must declare at least one of "
              + String.join(", ", LIMIT_RANGE_BOUND_KEYS)
              + "; to remove a tenant's bounds use DELETE /limitranges/"
              + tenantId);
      return;
    }
    LimitRangeSpec spec =
        new LimitRangeSpec(
            tenantId,
            boundFromJson(body, "minRequest"),
            boundFromJson(body, "maxRequest"),
            boundFromJson(body, "minLimit"),
            boundFromJson(body, "maxLimit"));
    storeClient.propose(new StateMutation.PutLimitRange(spec));
    respond(exchange, 200, "ok");
  }

  /**
   * Both halves of a bound are required together: a floor on memory alone is a coherent wish, but
   * {@link ResourceSpec} has no representation for it, so accepting one half would mean inventing a
   * value for the other.
   */
  private static Optional<ResourceSpec> boundFromJson(Map<?, ?> body, String key) {
    Object bound = body.get(key);
    if (bound == null) {
      return Optional.empty();
    }
    if (!(bound instanceof Map<?, ?> pair)) {
      throw new IllegalArgumentException(key + " must be a block with 'memory' and 'cpu'");
    }
    Object memory = pair.get("memory");
    Object cpu = pair.get("cpu");
    if (memory == null || cpu == null) {
      throw new IllegalArgumentException(key + " requires both 'memory' and 'cpu'");
    }
    return Optional.of(new ResourceSpec(String.valueOf(memory), String.valueOf(cpu)));
  }

  private void handleGetLimitRange(HttpExchange exchange, String tenantId) throws IOException {
    Optional<LimitRangeSpec> limitRange = storeClient.getLimitRange(tenantId);
    if (limitRange.isEmpty()) {
      respond(exchange, 404, "no such limit range: " + tenantId);
      return;
    }
    respondJson(exchange, 200, limitRangeSpecToJson(limitRange.get()));
  }

  private void handleDeleteLimitRange(HttpExchange exchange, String tenantId) throws IOException {
    storeClient.propose(new StateMutation.RemoveLimitRange(tenantId));
    respond(exchange, 200, "ok");
  }

  private static Map<String, Object> limitRangeSpecToJson(LimitRangeSpec spec) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("tenantId", spec.tenantId());
    spec.minRequest().ifPresent(r -> map.put("minRequest", resourceSpecToJson(r)));
    spec.maxRequest().ifPresent(r -> map.put("maxRequest", resourceSpecToJson(r)));
    spec.minLimit().ifPresent(r -> map.put("minLimit", resourceSpecToJson(r)));
    spec.maxLimit().ifPresent(r -> map.put("maxLimit", resourceSpecToJson(r)));
    return map;
  }

  private static Map<String, Object> resourceSpecToJson(ResourceSpec spec) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("memory", spec.memory());
    map.put("cpu", spec.cpu());
    return map;
  }

  private static Optional<ResourceSpec> resourceSpecFromJson(Map<?, ?> map) {
    if (map == null) {
      return Optional.empty();
    }
    return Optional.of(new ResourceSpec((String) map.get("memory"), (String) map.get("cpu")));
  }

  /**
   * Instance method (not static) because it consults {@link #storeClient}/{@link #artifactResolver}
   * to compute {@code usage} -- the same {@link TenantUsage} calculation admission and {@code
   * QuotaReconciler} already use, so the console/CLI see exactly the numbers enforcement actually
   * acts on rather than a separately-derived approximation.
   */
  private Map<String, Object> tenantToJson(Tenant tenant) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", tenant.id());
    Map<String, Object> quota = new LinkedHashMap<>();
    quota.put("maxMemoryBytes", tenant.quota().maxMemoryBytes());
    quota.put("maxCpuMillicores", tenant.quota().maxCpuMillicores());
    quota.put("maxInstances", tenant.quota().maxInstances());
    map.put("quota", quota);
    map.put("isolationPosture", tenant.isolationPosture().name());
    TenantUsage.Usage usage =
        TenantUsage.currentlyAssigned(storeClient, artifactResolver, tenant.id(), Optional.empty());
    Map<String, Object> usageJson = new LinkedHashMap<>();
    usageJson.put("memoryBytes", usage.memoryBytes());
    usageJson.put("cpuMillicores", usage.cpuMillicores());
    usageJson.put("instances", usage.instances());
    map.put("usage", usageJson);
    map.put("quotaViolating", usage.exceeds(tenant.quota()));
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
      String[] parts = tail.split("/", 3);
      String tenantId = parts[0];
      if (tenantId.isBlank()) {
        respond(exchange, 400, "missing tenantId");
        return;
      }
      if (parts.length == 1) {
        if (!"GET".equals(exchange.getRequestMethod())) {
          respond(exchange, 405, "method not allowed");
          return;
        }
        handleListConfig(exchange, tenantId);
        return;
      }
      String key = parts[1];
      if (key.isBlank()) {
        respond(exchange, 400, "missing config key");
        return;
      }
      if (ConfigMapCodec.isConfigMapKey(key)
          && ("PUT".equals(exchange.getRequestMethod())
              || "DELETE".equals(exchange.getRequestMethod()))) {
        // Reserved: a plain /config/* write must never collide with the "configmap:" + name rows
        // ConfigMapStore owns, the same reasoning Fafnir's own key@meta/key@N convention already
        // needed a filter for (see isFafnirManagedSecretKey below).
        respond(
            exchange,
            400,
            "'"
                + ConfigMapCodec.KEY_PREFIX
                + "' is a reserved config key prefix; use /configmaps/* instead");
        return;
      }
      // GET .../versions and POST .../rollback are reserved action segments, checked before the
      // general 2-part PUT/DELETE handling below -- both only ever cover the plaintext ledger (see
      // ConfigVersionStore's own javadoc), so they authorize as CONFIG unconditionally, unlike PUT/
      // DELETE below which branch on an encrypted flag.
      if (parts.length == 3) {
        if ("versions".equals(parts[2]) && "GET".equals(exchange.getRequestMethod())) {
          if (requireAuthorized(exchange, ResourceKind.CONFIG, Verb.READ, Optional.of(tenantId))) {
            handleListConfigVersions(exchange, tenantId, key);
          }
          return;
        }
        if ("rollback".equals(parts[2]) && "POST".equals(exchange.getRequestMethod())) {
          if (requireAuthorized(exchange, ResourceKind.CONFIG, Verb.WRITE, Optional.of(tenantId))) {
            handleRollbackConfig(exchange, tenantId, key);
          }
          return;
        }
        respond(exchange, 404, "unknown config sub-resource: " + parts[2]);
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
          // A nonexistent key is deleted idempotently (200, matching every other resource kind's
          // own DELETE-of-a-never-existed-name convention -- deployment/job/tenant/role/account/
          // etc. all no-op successfully rather than 404) rather than erroring, so there's no
          // stored entry left here to read an `encrypted` flag from; authorize as CONFIG, the
          // plain, unencrypted default this endpoint otherwise assumes.
          Optional<ConfigEntry> existing = findConfigEntry(tenantId, key);
          boolean encrypted = existing.map(ConfigEntry::encrypted).orElse(false);
          ResourceKind resource = encrypted ? ResourceKind.SECRET : ResourceKind.CONFIG;
          if (requireAuthorized(exchange, resource, Verb.DELETE, Optional.of(tenantId))) {
            handleDeleteConfig(exchange, tenantId, key, encrypted);
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (BodyTooLargeException e) {
      respondQuietly(exchange, 413, String.valueOf(e.getMessage()));
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

  /**
   * An encrypted write bypasses {@link ConfigVersionStore} entirely, exactly as before that class
   * existed -- versioning only ever covers the plaintext ledger (see that class's own javadoc).
   */
  private void handlePutConfig(
      HttpExchange exchange, String tenantId, String key, String value, boolean encrypted)
      throws IOException {
    byte[] plaintext = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    if (plaintext.length > MAX_CONFIG_VALUE_BYTES) {
      respond(
          exchange,
          413,
          "config value for "
              + key
              + " is "
              + plaintext.length
              + " bytes, exceeding the maximum of "
              + MAX_CONFIG_VALUE_BYTES);
      return;
    }
    if (encrypted) {
      byte[] stored = fafnirClient.encrypt(plaintext);
      storeClient.propose(
          new StateMutation.PutConfigEntry(new ConfigEntry(tenantId, key, stored, true)));
      respond(exchange, 200, "ok");
      return;
    }
    switch (configVersionStore.put(tenantId, key, value)) {
      case ConfigWriteOutcome.Written written ->
          respondJson(exchange, 200, Map.of("version", written.version()));
      case ConfigWriteOutcome.WriteContention contention ->
          respond(
              exchange,
              409,
              "too many concurrent writers to config key "
                  + key
                  + " ("
                  + contention.attempts()
                  + " attempts)");
    }
  }

  /**
   * An encrypted key's delete bypasses {@link ConfigVersionStore} entirely, the same as {@link
   * #handlePutConfig}'s own encrypted branch -- there is no plaintext ledger entry to tombstone for
   * a value that was never written through it.
   */
  private void handleDeleteConfig(
      HttpExchange exchange, String tenantId, String key, boolean encrypted) throws IOException {
    if (encrypted) {
      storeClient.propose(new StateMutation.RemoveConfigEntry(tenantId, key));
      respond(exchange, 200, "ok");
      return;
    }
    switch (configVersionStore.delete(tenantId, key)) {
      case ConfigDeleteOutcome.Deleted ignored -> respond(exchange, 200, "ok");
      // Idempotent, matching every other resource kind's own delete-of-a-never-existed-name
      // convention -- the key simply never had a plaintext ledger entry to tombstone.
      case ConfigDeleteOutcome.NotFound ignored -> respond(exchange, 200, "ok");
      case ConfigDeleteOutcome.WriteContention contention ->
          respond(
              exchange,
              409,
              "too many concurrent writers to config key "
                  + key
                  + " ("
                  + contention.attempts()
                  + " attempts)");
    }
  }

  private void handleListConfigVersions(HttpExchange exchange, String tenantId, String key)
      throws IOException {
    List<Map<String, Object>> versions =
        configVersionStore.listVersions(tenantId, key).stream()
            .map(ApiServer::configVersionToJson)
            .toList();
    respondJson(exchange, 200, Map.of("versions", versions));
  }

  private static Map<String, Object> configVersionToJson(ConfigVersion version) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("version", version.version());
    map.put("value", version.value());
    map.put("deleted", version.deleted());
    return map;
  }

  private void handleRollbackConfig(HttpExchange exchange, String tenantId, String key)
      throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    Object raw = body.get("version");
    if (!(raw instanceof Number number)) {
      respond(exchange, 400, "'version' must be an integer");
      return;
    }
    switch (configVersionStore.rollback(tenantId, key, number.intValue())) {
      case ConfigRollbackOutcome.TargetNotFound ignored ->
          respond(exchange, 404, "no such version of config key " + key + ": " + number.intValue());
      case ConfigRollbackOutcome.WriteContention contention ->
          respond(
              exchange,
              409,
              "too many concurrent writers to config key "
                  + key
                  + " ("
                  + contention.attempts()
                  + " attempts)");
      case ConfigRollbackOutcome.Applied applied -> {
        Map<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("version", applied.version());
        responseBody.put("value", applied.value().orElse(null));
        responseBody.put("deleted", applied.deleted());
        respondJson(exchange, 200, responseBody);
      }
    }
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
      if (isFafnirManagedSecretKey(entry.key()) || ConfigMapCodec.isConfigMapKey(entry.key())) {
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

  // ---- /configmaps/{tenant}[?names=a,b,c] and /configmaps/{tenant}/{name} ----

  private void handleConfigMap(HttpExchange exchange) {
    try {
      String tail = pathSegmentAfter(exchange, "/configmaps/");
      if (tail.isBlank()) {
        respond(exchange, 400, "expected /configmaps/{tenantId} or /configmaps/{tenantId}/{name}");
        return;
      }
      String[] parts = tail.split("/", 3);
      String tenantId = parts[0];
      if (tenantId.isBlank()) {
        respond(exchange, 400, "missing tenantId");
        return;
      }
      if (parts.length == 1) {
        if (!"GET".equals(exchange.getRequestMethod())) {
          respond(exchange, 405, "method not allowed");
          return;
        }
        if (!requireAuthorized(
            exchange, ResourceKind.CONFIGMAP, Verb.READ, Optional.of(tenantId))) {
          return;
        }
        handleListOrBatchGetConfigMaps(exchange, tenantId);
        return;
      }
      String name = parts[1];
      if (name.isBlank()) {
        respond(exchange, 400, "missing configmap name");
        return;
      }
      // GET .../versions and POST .../rollback are reserved action segments, checked before the
      // general 2-part GET/PUT/PATCH/DELETE handling below -- a real name literally named
      // "versions"/"rollback" only ever reaches that branch via GET/PUT/PATCH/DELETE, which these
      // two never intercept.
      if (parts.length == 3) {
        if ("versions".equals(parts[2]) && "GET".equals(exchange.getRequestMethod())) {
          if (requireAuthorized(
              exchange, ResourceKind.CONFIGMAP, Verb.READ, Optional.of(tenantId))) {
            handleListConfigMapVersions(exchange, tenantId, name);
          }
          return;
        }
        if ("rollback".equals(parts[2]) && "POST".equals(exchange.getRequestMethod())) {
          if (requireAuthorized(
              exchange, ResourceKind.CONFIGMAP, Verb.WRITE, Optional.of(tenantId))) {
            handleRollbackConfigMap(exchange, tenantId, name);
          }
          return;
        }
        respond(exchange, 404, "unknown configmap sub-resource: " + parts[2]);
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "GET" -> {
          if (requireAuthorized(
              exchange, ResourceKind.CONFIGMAP, Verb.READ, Optional.of(tenantId))) {
            handleGetConfigMap(exchange, tenantId, name);
          }
        }
        case "PUT" -> {
          if (requireAuthorized(
              exchange, ResourceKind.CONFIGMAP, Verb.WRITE, Optional.of(tenantId))) {
            handlePutConfigMap(exchange, tenantId, name);
          }
        }
        case "PATCH" -> {
          if (requireAuthorized(
              exchange, ResourceKind.CONFIGMAP, Verb.WRITE, Optional.of(tenantId))) {
            handlePatchConfigMap(exchange, tenantId, name);
          }
        }
        case "DELETE" -> {
          if (requireAuthorized(
              exchange, ResourceKind.CONFIGMAP, Verb.DELETE, Optional.of(tenantId))) {
            handleDeleteConfigMap(exchange, tenantId, name);
          }
        }
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("configmap request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * {@code GET /configmaps/{tenantId}}: every ConfigMap name in the tenant. {@code ?names=a,b,c}
   * additionally batches full bodies for the named ConfigMaps into one response -- the shape {@code
   * gimle-agent} uses to fetch every {@code configMapRefs} entry for an instance assignment in a
   * single round trip, rather than one HTTP call per referenced ConfigMap.
   */
  private void handleListOrBatchGetConfigMaps(HttpExchange exchange, String tenantId)
      throws IOException {
    String namesParam = parseQuery(exchange).get("names");
    if (namesParam == null) {
      respondJson(exchange, 200, configMapStore.list(tenantId));
      return;
    }
    List<String> names = Arrays.stream(namesParam.split(",")).filter(s -> !s.isBlank()).toList();
    respondJson(
        exchange,
        200,
        configMapStore.getMany(tenantId, names).stream().map(ApiServer::configMapToJson).toList());
  }

  private void handleGetConfigMap(HttpExchange exchange, String tenantId, String name)
      throws IOException {
    Optional<ConfigMap> configMap = configMapStore.get(tenantId, name);
    if (configMap.isEmpty()) {
      respond(exchange, 404, "no such configmap: " + name);
      return;
    }
    respondJson(exchange, 200, configMapToJson(configMap.get()));
  }

  /**
   * {@code PUT}: full replace. Body {@code {data, expectedVersion?}} -- omitting {@code
   * expectedVersion} means an unconditional overwrite.
   */
  private void handlePutConfigMap(HttpExchange exchange, String tenantId, String name)
      throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    Map<String, String> data = stringMap(body.get("data"));
    OptionalInt expectedVersion = optionalIntField(body, "expectedVersion");
    respondConfigMapWrite(exchange, configMapStore.put(tenantId, name, data, expectedVersion));
  }

  /**
   * {@code PATCH}: merges only the sent key(s). Unlike {@code PUT}, {@code expectedVersion} is
   * required in the body -- {@code 0} is the create-a-new-ConfigMap case, not "unconditional."
   */
  private void handlePatchConfigMap(HttpExchange exchange, String tenantId, String name)
      throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    Map<String, String> data = stringMap(body.get("data"));
    OptionalInt expectedVersion = optionalIntField(body, "expectedVersion");
    if (expectedVersion.isEmpty()) {
      respond(exchange, 400, "PATCH requires an 'expectedVersion' field (0 for a new configmap)");
      return;
    }
    respondConfigMapWrite(
        exchange, configMapStore.patch(tenantId, name, data, expectedVersion.getAsInt()));
  }

  private void respondConfigMapWrite(HttpExchange exchange, ConfigMapWriteResult result)
      throws IOException {
    switch (result) {
      case ConfigMapWriteResult.Written written ->
          respondJson(exchange, 200, Map.of("version", written.version()));
      case ConfigMapWriteResult.VersionConflict conflict ->
          respondJson(
              exchange,
              409,
              Map.of(
                  "currentVersion", conflict.currentVersion(),
                  "currentData", conflict.currentData()));
      case ConfigMapWriteResult.WriteContention contention ->
          respond(
              exchange,
              409,
              "too many concurrent writers to this configmap ("
                  + contention.attempts()
                  + " attempts)");
    }
  }

  private void handleDeleteConfigMap(HttpExchange exchange, String tenantId, String name)
      throws IOException {
    switch (configMapStore.delete(tenantId, name)) {
      case ConfigMapDeleteOutcome.Deleted ignored -> respond(exchange, 200, "ok");
      // Idempotent, matching every other resource kind's own delete-of-a-never-existed-name
      // convention -- see handleDeleteConfig's identical reasoning.
      case ConfigMapDeleteOutcome.NotFound ignored -> respond(exchange, 200, "ok");
      case ConfigMapDeleteOutcome.WriteContention contention ->
          respond(
              exchange,
              409,
              "too many concurrent writers to this configmap ("
                  + contention.attempts()
                  + " attempts)");
    }
  }

  private void handleListConfigMapVersions(HttpExchange exchange, String tenantId, String name)
      throws IOException {
    List<Map<String, Object>> versions =
        configMapStore.listVersions(tenantId, name).stream()
            .map(ApiServer::configMapVersionToJson)
            .toList();
    respondJson(exchange, 200, Map.of("versions", versions));
  }

  private static Map<String, Object> configMapVersionToJson(ConfigMapVersion version) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("version", version.version());
    map.put("data", version.data());
    map.put("deleted", version.deleted());
    return map;
  }

  private void handleRollbackConfigMap(HttpExchange exchange, String tenantId, String name)
      throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    Object raw = body.get("version");
    if (!(raw instanceof Number number)) {
      respond(exchange, 400, "'version' must be an integer");
      return;
    }
    switch (configMapStore.rollback(tenantId, name, number.intValue())) {
      case ConfigMapRollbackOutcome.TargetNotFound ignored ->
          respond(exchange, 404, "no such version of configmap " + name + ": " + number.intValue());
      case ConfigMapRollbackOutcome.WriteContention contention ->
          respond(
              exchange,
              409,
              "too many concurrent writers to this configmap ("
                  + contention.attempts()
                  + " attempts)");
      case ConfigMapRollbackOutcome.Applied applied -> {
        Map<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("version", applied.version());
        responseBody.put("data", applied.data());
        responseBody.put("deleted", applied.deleted());
        respondJson(exchange, 200, responseBody);
      }
    }
  }

  private static Map<String, String> stringMap(Object rawData) {
    if (!(rawData instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("'data' must be a mapping of string to string");
    }
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
    }
    return result;
  }

  private static OptionalInt optionalIntField(Map<?, ?> body, String key) {
    Object value = body.get(key);
    if (value == null) {
      return OptionalInt.empty();
    }
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException("'" + key + "' must be a number if present");
    }
    return OptionalInt.of(number.intValue());
  }

  private static Map<String, Object> configMapToJson(ConfigMap configMap) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", configMap.name());
    map.put("version", configMap.version());
    map.put("data", configMap.data());
    return map;
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
          if (requireAuthorized(
              exchange, ResourceKind.ROLE, Verb.WRITE, Optional.empty(), Optional.of(name))) {
            handlePutRole(exchange, name);
          }
        }
        case "GET" -> {
          if (requireAuthorized(
              exchange, ResourceKind.ROLE, Verb.READ, Optional.empty(), Optional.of(name))) {
            handleGetRole(exchange, name);
          }
        }
        case "DELETE" -> {
          if (requireAuthorized(
              exchange, ResourceKind.ROLE, Verb.DELETE, Optional.empty(), Optional.of(name))) {
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

  /**
   * {@code StateMutation.RemoveRole} itself cascades the actual removal of every {@code
   * RoleBinding} naming this Role atomically -- the security fix, see its own javadoc -- so
   * correctness here does not depend on the list taken below. That list is purely for the
   * operator-facing response and this call's own audit trail: it is read moments before the delete
   * is proposed, not inside the same mutation, so a binding created in the narrow window between
   * the two (still cascaded by the mutation itself, just silently) would not appear in {@code
   * removedRoleBindings} or get its own audit event here. An acceptable gap for a best-effort
   * report, not for the guarantee the fix actually depends on.
   */
  private void handleDeleteRole(HttpExchange exchange, String name) throws IOException {
    List<RoleBinding> cascaded =
        storeClient.listRoleBindings().stream()
            .filter(binding -> binding.roleName().equals(name))
            .toList();
    storeClient.propose(new StateMutation.RemoveRole(name));
    resolvePrincipal(exchange)
        .ifPresent(
            principal -> {
              for (RoleBinding binding : cascaded) {
                recordAuditEventBestEffort(
                    principal,
                    ResourceKind.ROLE_BINDING,
                    Verb.DELETE,
                    Optional.empty(),
                    Optional.of(binding.id()),
                    true,
                    AuditOutcome.APPLIED);
              }
            });
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "ok");
    body.put("removedRoleBindings", cascaded.stream().map(RoleBinding::id).toList());
    respondJson(exchange, 200, body);
  }

  private static Map<String, Object> roleToJson(Role role) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", role.name());
    List<Map<String, Object>> permissions = new ArrayList<>();
    for (Permission p : role.permissions()) {
      Map<String, Object> pm = new LinkedHashMap<>();
      pm.put("resource", p.resourceToken());
      pm.put("verb", p.verbToken());
      p.tenantScope().ifPresent(t -> pm.put("tenantScope", t));
      p.qualifier().ifPresent(q -> pm.put("qualifier", q));
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
        permissions.add(
            new Permission(
                Permission.parseResource((String) pm.get("resource")),
                Permission.parseVerb((String) pm.get("verb")),
                Permission.parseTenantScope((String) pm.get("tenantScope")),
                Permission.parseQualifier((String) pm.get("qualifier"))));
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
              exchange, ResourceKind.ROLE_BINDING, Verb.WRITE, Optional.empty(), Optional.of(id))) {
            handlePutRoleBinding(exchange, id);
          }
        }
        case "GET" -> {
          if (requireAuthorized(
              exchange, ResourceKind.ROLE_BINDING, Verb.READ, Optional.empty(), Optional.of(id))) {
            handleGetRoleBinding(exchange, id);
          }
        }
        case "DELETE" -> {
          if (requireAuthorized(
              exchange,
              ResourceKind.ROLE_BINDING,
              Verb.DELETE,
              Optional.empty(),
              Optional.of(id))) {
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
   * Proxies to Fafnir's own {@code /secrets/rotate-key} via {@link #forwardGlobalAdminRoute}, the
   * same generic relay style {@link #handleRetireSecretsKeyProxy} already used -- no dedicated
   * typed request/response handling needed here, since Fafnir's own JSON response body ({@code
   * {"activeKeyId": N}}) is exactly what this route already returned verbatim. Gated on the same
   * {@code SECRET:WRITE} permission a config write itself requires, unscoped since rotation is
   * cluster-wide, not per-tenant -- this process's own authorization check, forwarded onward to
   * Fafnir's own independent re-check the way every other {@code /secrets/*} route already is.
   */
  private void handleRotateSecretsKey(HttpExchange exchange) {
    forwardGlobalAdminRoute(exchange, "/secrets/rotate-key", ResourceKind.SECRET);
  }

  /**
   * Sibling of {@link #handleRotateSecretsKey}, same {@code SECRET}/{@code WRITE} global gate -- a
   * byte-for-byte relay to Fafnir's own {@code /secrets/retire-key} via {@link
   * FafnirClient#forward}, the same generic relay style {@link #handleSecretMapsProxy} already
   * established, rather than a second dedicated typed method.
   */
  private void handleRetireSecretsKeyProxy(HttpExchange exchange) {
    forwardGlobalAdminRoute(exchange, "/secrets/retire-key", ResourceKind.SECRET);
  }

  /**
   * Sibling of the two above, same global gate: re-encrypting every tenant's secrets under the
   * active key is what makes retiring an older one safe, so it is the same class of operation and
   * relays the same way.
   */
  private void handleRewrapSecretsProxy(HttpExchange exchange) {
    forwardGlobalAdminRoute(exchange, "/secrets/rewrap", ResourceKind.SECRET);
  }

  // ---- /seal/public-key, /seal/rotate-key, /seal/retire-key ----

  /**
   * The one proxied route with no authorization check at all: fetching the sealing public key is
   * meant to be reachable by a caller with zero Gimlé credentials -- the whole point of asymmetric
   * sealing is that a caller who can only seal, not read, needs this key before it can seal
   * anything. A check here would protect nothing, so none is forwarded, and no principal is
   * resolved either (nothing to attribute for an anonymous, harmless read).
   */
  private void handleSealPublicKeyProxy(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      FafnirClient.RawResponse response =
          fafnirClient.forward("GET", "/seal/public-key", null, Map.of());
      relay(exchange, response);
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (IOException | RuntimeException e) {
      log.warn("seal public key proxy request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleSealRotateKeyProxy(HttpExchange exchange) {
    forwardGlobalAdminRoute(exchange, "/seal/rotate-key", ResourceKind.SECRET);
  }

  private void handleSealRetireKeyProxy(HttpExchange exchange) {
    forwardGlobalAdminRoute(exchange, "/seal/retire-key", ResourceKind.SECRET);
  }

  /**
   * Shared body for the four cluster-wide (non-tenant-scoped) admin routes above that require
   * authorization: checks {@code kind}/{@code Verb.WRITE} unscoped (the same shape {@link
   * #handleRotateSecretsKey} already uses), then relays the request body verbatim to Fafnir's own
   * identically-named route -- carrying the resolved caller's identity as the same {@code
   * X-Gimle-Forwarded-Principal}/{@code X-Gimle-Forwarded-Groups} pair {@link #handleSecretsProxy}
   * already forwards, so Fafnir's own independent {@code SECRET}/{@code WRITE} re-check (see {@code
   * FafnirServer#authorizeGlobalSecretsAdmin}) has an actual principal to evaluate rather than
   * falling back to this process's own peer certificate, which holds no such grant. The two {@code
   * /seal/*} routes ignore the forwarded headers entirely (Fafnir never gates them -- the sealing
   * key is meant to be public), so forwarding them there is harmless, not merely unnecessary.
   */
  private void forwardGlobalAdminRoute(HttpExchange exchange, String path, ResourceKind kind) {
    try {
      if (!requireAuthorized(exchange, kind, Verb.WRITE, Optional.empty())) {
        return;
      }
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
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
      byte[] body = readBody(exchange).getBytes(StandardCharsets.UTF_8);
      FafnirClient.RawResponse response = fafnirClient.forward("POST", path, body, forwardHeaders);
      relay(exchange, response);
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (IOException | RuntimeException e) {
      log.warn("{} proxy request failed: {}", path, e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void relay(HttpExchange exchange, FafnirClient.RawResponse response) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(response.statusCode(), response.body().length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(response.body());
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
            // POST is always the /undelete action sub-route (see FafnirServer#handleSecrets) --
            // a write, the same way PUT is.
            case "PUT", "POST" -> Verb.WRITE;
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
          "PUT".equals(exchange.getRequestMethod()) || "POST".equals(exchange.getRequestMethod())
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
    } catch (BodyTooLargeException e) {
      respondQuietly(exchange, 413, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("secrets proxy request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  // ---- /secretmaps/{tenantId}/... -- a byte-for-byte proxy to Fafnir ----

  /**
   * Mirrors {@link #handleSecretsProxy} exactly, but gated on {@link ResourceKind#SECRETMAP} rather
   * than {@link ResourceKind#SECRET} -- the same split {@code CONFIGMAP}/{@code CONFIG} already
   * establishes, so a role can be granted "read flat secrets" without also getting "read named
   * SecretMaps." This process's own gate is not a substitute for Fafnir's own independent re-check
   * on the forwarded request, for the identical reason {@link #handleSecretsProxy}'s own javadoc
   * gives.
   */
  private void handleSecretMapsProxy(HttpExchange exchange) {
    try {
      String tail = pathSegmentAfter(exchange, "/secretmaps/");
      int slash = tail.indexOf('/');
      String tenantId = slash < 0 ? tail : tail.substring(0, slash);
      if (tenantId.isBlank()) {
        respond(exchange, 400, "missing tenantId");
        return;
      }
      Verb verb =
          switch (exchange.getRequestMethod()) {
            case "GET" -> Verb.READ;
            case "PUT", "POST" -> Verb.WRITE;
            case "DELETE" -> Verb.DELETE;
            default -> null;
          };
      if (verb == null) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      if (!requireAuthorized(exchange, ResourceKind.SECRETMAP, verb, Optional.of(tenantId))) {
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
      // POST is always one of the /rollback, /seal, or /replace action sub-routes (see
      // FafnirServer#handleSecretMaps), whose JSON body must be forwarded on exactly like PUT's
      // already is.
      byte[] body =
          "PUT".equals(exchange.getRequestMethod()) || "POST".equals(exchange.getRequestMethod())
              ? readBody(exchange).getBytes(StandardCharsets.UTF_8)
              : null;
      String query = exchange.getRequestURI().getRawQuery();
      String path = "/secretmaps/" + tail + (query != null ? "?" + query : "");
      FafnirClient.RawResponse response =
          fafnirClient.forward(exchange.getRequestMethod(), path, body, forwardHeaders);
      exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(response.statusCode(), response.body().length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(response.body());
      }
    } catch (GimleRaftException e) {
      respondStoreUnavailable(exchange);
    } catch (BodyTooLargeException e) {
      respondQuietly(exchange, 413, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("secretmaps proxy request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  // ---- /health ----

  /**
   * Unauthenticated, matching {@code FafnirServer}/{@code MuninnServer}/{@code AndvariServer}'s own
   * {@code /status} posture -- process-level liveness a load balancer or orchestrator probe needs
   * to reach with nothing but raw TCP, before any identity has been established. Fails closed on a
   * downstream outage rather than reporting healthy regardless: {@code storeClient.listTenants()}
   * is a real round trip to the {@code gimle-mimir} cluster this process depends on for every other
   * request it serves, so a store that's unreachable or has no leader turns into a {@code 503} here
   * exactly as it would turn into failures everywhere else -- the one signal this process had no
   * operator-pollable way to surface at all before this endpoint existed (see {@code gimle-mimir}'s
   * own lack of any HTTP surface, which is why this checks the dependency rather than only this
   * process's own liveness).
   */
  private void handleHealth(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Map<String, Object> status = new LinkedHashMap<>();
      status.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds());
      status.put("transportProtocol", TransportProtocol.fromConfig().name());
      StoreProbeResult probe = lastStoreProbe;
      Duration age = Duration.between(probe.completedAt(), Instant.now());
      // A probe too old to trust is reported down rather than waited on: the prober being stuck is
      // itself evidence the store cannot be reached.
      if (age.compareTo(storeProbeMaxAge) > 0) {
        status.put("status", "DOWN");
        status.put(
            "reason",
            probe.completedAt().equals(Instant.EPOCH)
                ? probe.reason()
                : "last store probe completed " + age.toSeconds() + "s ago");
        respondJson(exchange, 503, status);
        return;
      }
      if (!probe.up()) {
        status.put("status", "DOWN");
        status.put("reason", probe.reason());
        respondJson(exchange, 503, status);
        return;
      }
      status.put("storeTenantCount", probe.tenantCount());
      status.put("status", "UP");
      respondJson(exchange, 200, status);
    } catch (IOException | RuntimeException e) {
      log.warn("health request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  // ---- /artifacts/** -- a streaming proxy to the Andvari artifact registry ----

  /**
   * {@code GET /backup} -- a full-cluster-state snapshot, straight from {@link
   * StoreClient#getSnapshot()} (leader-routed, so never a not-yet-caught-up follower's stale view).
   * The response body is {@code RaftCodec.encodeSnapshot}'s own already-versioned bytes, opaque to
   * every caller here -- {@code gimle backup create} writes it straight to a file, and {@code gimle
   * backup restore} reads that same file straight back for {@link #handleRestore} below, never
   * inspecting or re-encoding it in between.
   */
  private void handleBackup(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      if (!requireAuthorized(exchange, ResourceKind.BACKUP, Verb.READ, Optional.empty())) {
        return;
      }
      byte[] snapshot = storeClient.getSnapshot();
      exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
      exchange.sendResponseHeaders(200, snapshot.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(snapshot);
      }
    } catch (IOException e) {
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * {@code PUT /restore} -- restores full cluster state from a prior {@code GET /backup}'s own
   * bytes, proposed through {@link StoreClient#restore(byte[])} so every replica applies it through
   * the ordinary replicated log rather than only this request's leader-of-the-moment silently
   * diverging from the rest of the cluster. {@code PUT}, matching {@code /artifacts/*}'s own
   * binary-upload convention (idempotent replace-with-this-exact-content, the same shape a restore
   * actually has), so {@code gimle backup restore} can reuse {@code ControlPlaneClient#putFile}
   * unchanged rather than a bespoke streaming-POST method. The body is decoded here, before ever
   * reaching {@code StoreClient}, so a corrupt or foreign file is rejected with a {@code 400}
   * rather than proposed -- the same "reject before proposing" posture every manifest-accepting
   * endpoint here already follows.
   */
  private void handleRestore(HttpExchange exchange) {
    try {
      if (!"PUT".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      if (!requireAuthorized(exchange, ResourceKind.BACKUP, Verb.WRITE, Optional.empty())) {
        return;
      }
      byte[] snapshotBytes;
      try (InputStream body = exchange.getRequestBody()) {
        snapshotBytes = body.readAllBytes();
      }
      MutationOutcome outcome;
      try {
        outcome = storeClient.restore(snapshotBytes);
      } catch (GimleCodecException e) {
        respondQuietly(exchange, 400, "not a valid backup: " + e.getMessage());
        return;
      }
      if (outcome instanceof MutationOutcome.Rejected rejected) {
        respond(exchange, 500, "restore was rejected: " + rejected.reason());
        return;
      }
      respond(exchange, 200, "cluster state restored");
    } catch (IOException e) {
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * The push/pull/list surface for the artifact registry, proxied the same way {@code /secrets/*}
   * proxies to Fafnir: this process's own {@code requireAuthorized} gate runs first, the calling
   * principal travels as an internal claim, and Andvari independently re-authorizes regardless.
   * Unlike the secrets proxy, bodies here are jars, so both directions stream -- a push flows from
   * the caller's socket through to Andvari and a pull flows back, never a whole jar buffered in
   * this process.
   */
  /**
   * The {@code moduleId:version} a {@code /artifacts/**} path addresses, absent for the catalog and
   * per-module listings, which name no single artifact.
   */
  private static Optional<String> artifactCoordinateOf(String path) {
    String tail = path.startsWith("/artifacts/") ? path.substring("/artifacts/".length()) : "";
    String[] segments = tail.split("/");
    if (segments.length < 2 || segments[0].isBlank() || segments[1].isBlank()) {
      return Optional.empty();
    }
    return Optional.of(segments[0] + ":" + segments[1]);
  }

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
      // Named in the audit record rather than left empty: with concurrent pushes, "someone deleted
      // an artifact" is useless without which one, and the coordinate is right there in the path.
      if (!requireAuthorized(
          exchange, ResourceKind.ARTIFACT, verb, Optional.empty(), artifactCoordinateOf(path))) {
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
      // The caller's own tenant claim and declared artifact kind on a push -- forwarded verbatim
      // so Andvari's own authorizeArtifacts/put can check and record them itself, not just the
      // forwarded-principal identity.
      if ("PUT".equals(method)) {
        Optional<String> tenantId =
            Optional.ofNullable(exchange.getRequestHeaders().getFirst("X-Gimle-Artifact-Tenant"));
        tenantId.ifPresent(tenant -> forwardHeaders.put("X-Gimle-Artifact-Tenant", tenant));
        Optional<String> declaredKind =
            Optional.ofNullable(exchange.getRequestHeaders().getFirst("X-Gimle-Artifact-Kind"));
        declaredKind.ifPresent(kind -> forwardHeaders.put("X-Gimle-Artifact-Kind", kind));
      }
      InputStream requestBody = "PUT".equals(method) ? exchange.getRequestBody() : null;
      AndvariClient.StreamingResponse response =
          registry.get().forward(method, path, requestBody, forwardHeaders);
      response
          .sha256()
          .ifPresent(sha -> exchange.getResponseHeaders().add("X-Gimle-Artifact-Sha256", sha));
      response
          .tenantId()
          .ifPresent(
              tenant -> exchange.getResponseHeaders().add("X-Gimle-Artifact-Tenant", tenant));
      response
          .kind()
          .ifPresent(kind -> exchange.getResponseHeaders().add("X-Gimle-Artifact-Kind", kind));
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
          if (requireAuthorized(
              exchange,
              ResourceKind.ACCOUNT,
              Verb.WRITE,
              Optional.empty(),
              Optional.of(username))) {
            handlePutAccount(exchange, username);
          }
        }
        case "GET" -> {
          if (requireAuthorized(
              exchange, ResourceKind.ACCOUNT, Verb.READ, Optional.empty(), Optional.of(username))) {
            handleGetAccount(exchange, username);
          }
        }
        case "DELETE" -> {
          if (requireAuthorized(
              exchange,
              ResourceKind.ACCOUNT,
              Verb.DELETE,
              Optional.empty(),
              Optional.of(username))) {
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
   * convention. {@code groups} is optional and, when omitted, preserves whatever group membership
   * the account already had -- deliberately not "PUT replaces the whole object": a console operator
   * resetting someone's password via the Accounts screen's own password-only form must never
   * silently wipe that account's {@code group:} binding eligibility as a side effect.
   */
  private void handlePutAccount(HttpExchange exchange, String username) throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    String password = (String) body.get("password");
    if (password == null || password.isBlank()) {
      respond(exchange, 400, "missing password");
      return;
    }
    byte[] passwordHash = PasswordHashes.hash(password.toCharArray());
    Set<String> groups =
        body.containsKey("groups")
            ? new LinkedHashSet<>(
                Json.asArray(body.get("groups")).stream().map(v -> (String) v).toList())
            : storeClient.getAccount(username).map(Account::groups).orElse(Set.of());
    storeClient.propose(new StateMutation.PutAccount(new Account(username, passwordHash, groups)));
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

  /**
   * Refused with {@code 409}, naming every still-referencing role binding, while at least one
   * {@link RoleBinding#subject()} still points at this account's {@code user:<username>} subject --
   * the same "can't delete something still referenced" pattern {@link #handleVolumeDestroy} already
   * uses for a still-attached volume. Deleting the account first would leave the binding pointing
   * at a subject that no longer exists, with nothing left to signal that it happened.
   */
  private void handleDeleteAccount(HttpExchange exchange, String username) throws IOException {
    String subject = RoleBinding.userSubject(username);
    List<String> referencing =
        storeClient.listRoleBindings().stream()
            .filter(binding -> binding.subject().equals(subject))
            .map(RoleBinding::id)
            .toList();
    if (!referencing.isEmpty()) {
      respond(
          exchange,
          409,
          "account "
              + username
              + " is still referenced by role binding(s) "
              + String.join(", ", referencing)
              + "; delete those first");
      return;
    }
    storeClient.propose(new StateMutation.RemoveAccount(username));
    respond(exchange, 200, "ok");
  }

  /** Never includes {@code passwordHash} -- this is the one field an API response never leaks. */
  private static Map<String, Object> accountToJson(Account account) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("username", account.username());
    map.put("groups", List.copyOf(account.groups()));
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
      respondJson(
          exchange, 200, principalToJson(new Principal(username, account.get().groups()), false));
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
   * either way. Shared with the rate-limited CSR submission route, which needs the identical "come
   * back after" answer and has the same reason to say nothing more.
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
      // Revokes server-side, not just the client-side cookie: whichever username the presented
      // cookie verifies to gets its "revoked before" watermark advanced to now, so the very token
      // being logged out (and any other still-outstanding token for that same username) is
      // rejected by resolvePrincipal's own session-cookie check from this instant on, rather than
      // staying usable for the rest of its ordinary 12-hour lifetime. A missing or already-invalid
      // cookie has no username to revoke and is left alone -- there is nothing to undo.
      sessionCookie(exchange)
          .flatMap(token -> SessionTokens.verify(token, sessionSigningKey))
          .ifPresent(
              session ->
                  storeClient.propose(
                      new StateMutation.PutSessionRevocation(
                          session.username(), System.currentTimeMillis())));
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
        respondJson(exchange, 200, principalToJson(ANONYMOUS_PRINCIPAL, true));
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

  // ---- /authz/can-i ----

  /**
   * The self-subject access review: {@code GET
   * /authz/can-i?resource=DEPLOYMENT&verb=WRITE[&tenant=acme][&target=node-1]} answers whether the
   * *calling* principal would be authorized for that action, without performing it. Deliberately
   * ungated by {@link #requireAuthorized}: asking "may I?" needs no permission of its own (any
   * authenticated caller may ask about itself, and only about itself -- there is no principal
   * parameter to review someone else), and the answer is computed by the identical {@link
   * Authorizer#authorize} walk every real request goes through, so it can never drift from what
   * enforcement would actually decide. Not audited: a hypothetical is a read-shaped question, and
   * recording it would drown the audit trail's real mutating decisions in console UI probes.
   * Plaintext mode answers {@code true} for everything, matching {@link #requireAuthorized}'s own
   * carve-out -- the honest answer, since nothing is actually gated in that mode.
   */
  private void handleCanI(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Map<String, String> query = parseQuery(exchange);
      ResourceKind resource;
      Verb verb;
      try {
        resource = ResourceKind.valueOf(require(query, "resource"));
        verb = Verb.valueOf(require(query, "verb"));
      } catch (IllegalArgumentException e) {
        respond(exchange, 400, e.getMessage());
        return;
      }
      Optional<String> tenant = Optional.ofNullable(query.get("tenant"));
      Optional<String> targetId = Optional.ofNullable(query.get("target"));
      Principal principal;
      boolean allowed;
      if (!(exchange instanceof HttpsExchange)) {
        principal = ANONYMOUS_PRINCIPAL;
        allowed = true;
      } else {
        Optional<Principal> resolved = resolvePrincipal(exchange);
        if (resolved.isEmpty()) {
          respondQuietly(exchange, 401, "authentication required");
          return;
        }
        principal = resolved.get();
        allowed = authorizer.authorize(principal, resource, verb, tenant, targetId);
      }
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("principal", principal.name());
      body.put("resource", resource.name());
      body.put("verb", verb.name());
      tenant.ifPresent(t -> body.put("tenant", t));
      targetId.ifPresent(t -> body.put("target", t));
      body.put("allowed", allowed);
      respondJson(exchange, 200, body);
    } catch (IOException e) {
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  // ---- /authz/vocabulary ----

  /**
   * The permission vocabulary itself: {@code GET /authz/vocabulary} answers with every {@link
   * ResourceKind} and {@link Verb} this build actually enforces, in declaration order. It exists so
   * a permission editor can offer exactly the kinds {@link Authorizer} will accept instead of
   * carrying its own hand-maintained copy of the enum -- a copy that has silently fallen behind
   * this enum more than once, leaving whole resource kinds grantable only from the CLI.
   *
   * <p>Read-only and gated the same way its {@code /authz/can-i} neighbour is: under mTLS a caller
   * must authenticate, but no permission is required beyond that, and nothing is audited. There is
   * nothing here to withhold -- the answer is a compile-time constant of this build, identical for
   * every principal, carrying no cluster state, no tenant's data, and no hint of who may do what.
   * Gating it behind a grant would only break the picker for exactly the operator being asked to
   * choose from it.
   */
  private void handleAuthzVocabulary(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      if (exchange instanceof HttpsExchange && resolvePrincipal(exchange).isEmpty()) {
        respondQuietly(exchange, 401, "authentication required");
        return;
      }
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("resourceKinds", Arrays.stream(ResourceKind.values()).map(Enum::name).toList());
      body.put("verbs", Arrays.stream(Verb.values()).map(Enum::name).toList());
      respondJson(exchange, 200, body);
    } catch (IOException e) {
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /** A required query parameter, or an {@link IllegalArgumentException} naming what's missing. */
  private static String require(Map<String, String> query, String name) {
    String value = query.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing required query parameter '" + name + "'");
    }
    return value;
  }

  // ---- /workload-tokens ----

  /** How long a minted workload-identity token verifies before its agent must re-mint. */
  private static final Duration WORKLOAD_TOKEN_TTL = Duration.ofHours(1);

  /**
   * Mints a workload-identity token for one workload's instances on one node -- the ServiceAccount
   * analogue's issuance path. The caller is the node's own agent: under mTLS a {@code gimle:nodes}
   * principal may mint only for its own {@code nodeId} and only for a workload the store currently
   * assigns to that node (the same assignment-scoped least-privilege check Fafnir's node secret
   * fetch already applies); an operator may mint for any node. The token itself is {@code key ":"
   * random}; only its SHA-256 is replicated (see {@link WorkloadTokenRecord}), keyed {@code
   * deploymentName#nodeId} so a re-mint replaces exactly this node's token. Untenanted workloads
   * are refused: a workload identity exists to carry tenant-scoped RBAC, and an untenanted workload
   * has no tenant to scope to.
   *
   * <p>{@code deploymentName} names any workload kind, not only a {@code Deployment} -- the field
   * is called that generically across {@link com.gimle.core.protocol.AssignedInstance} regardless
   * of what actually owns the instance (see that record's own javadoc). Resolved against each
   * kind's own spec store in turn, the same workload-kind-agnostic lookup {@link #handleEndpoints}
   * already establishes -- minting unconditionally against {@code storeClient.getDeployment} alone
   * 404'd every Job/DaemonSet/StatefulSet instance's own mint attempt, permanently blocking any
   * tenanted non-Deployment workload's {@code relayControlPlaneRead} calls.
   */
  private void handleWorkloadTokenMint(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Map<String, Object> body = Json.asObject(Json.parse(readBody(exchange)));
      String deploymentName = String.valueOf(body.get("deploymentName"));
      String nodeId = String.valueOf(body.get("nodeId"));
      if (deploymentName.isBlank()
          || "null".equals(deploymentName)
          || nodeId.isBlank()
          || "null".equals(nodeId)) {
        respond(exchange, 400, "deploymentName and nodeId are required");
        return;
      }
      Optional<Optional<String>> workloadTenantId = workloadTenantId(deploymentName, nodeId);
      if (workloadTenantId.isEmpty()) {
        respond(exchange, 404, "unknown workload: " + deploymentName);
        return;
      }
      if (workloadTenantId.get().isEmpty()) {
        respond(
            exchange,
            400,
            "workload " + deploymentName + " is untenanted; no workload identity to mint");
        return;
      }
      if (exchange instanceof HttpsExchange) {
        Optional<Principal> principal = resolvePrincipal(exchange);
        if (principal.isEmpty()) {
          respondQuietly(exchange, 401, "authentication required");
          return;
        }
        boolean operator = principal.get().groups().contains(BuiltinRoles.GROUP_OPERATORS);
        boolean owningNode =
            principal.get().groups().contains(BuiltinRoles.GROUP_NODES)
                && principal.get().name().equals(nodeId)
                && storeClient.listAssignments().stream()
                    .anyMatch(
                        assignment ->
                            assignment.deploymentName().equals(deploymentName)
                                && assignment.nodeId().equals(nodeId));
        if (!operator && !owningNode) {
          respondQuietly(exchange, 403, "forbidden");
          return;
        }
      }
      String key = deploymentName + "#" + nodeId;
      byte[] random = new byte[32];
      secureRandom.nextBytes(random);
      String token = key + ":" + HexFormat.of().formatHex(random);
      long expiresAtEpochMilli = System.currentTimeMillis() + WORKLOAD_TOKEN_TTL.toMillis();
      storeClient.propose(
          new StateMutation.PutWorkloadToken(
              new WorkloadTokenRecord(
                  key,
                  sha256Hex(token),
                  workloadTenantId.get(),
                  deploymentName,
                  expiresAtEpochMilli),
              System.currentTimeMillis()));
      respondJson(
          exchange, 200, Map.of("token", token, "expiresAtEpochMilli", expiresAtEpochMilli));
    } catch (IOException e) {
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * {@code name}'s tenant, resolved by joining against whichever live assignment currently places
   * it on {@code nodeId} -- checked across every assignment kind in turn (Instance, Job run,
   * DaemonSet, StatefulSet), since {@code deploymentName} names whichever workload kind actually
   * owns the instance, not only a {@code Deployment}. {@link Optional#empty()} at the outer level
   * means no such assignment exists on this node at all; a present-but-empty inner {@link Optional}
   * means it does and is untenanted. Joining through the assignment (each of which now carries its
   * own {@code tenantId}, mirroring the spec that placed it) rather than looking the name up in a
   * per-kind spec store directly is what lets this resolve a tenant without already knowing one to
   * scope that lookup by -- the same problem {@code deploymentName} alone can no longer answer once
   * names are tenant-scoped rather than globally unique (see {@link #dispatchResourceRequest}'s own
   * javadoc) -- and it doubles as the very authorization check this method exists for: a workload
   * not actually assigned to {@code nodeId} resolves to nothing, exactly as if it didn't exist.
   */
  private Optional<Optional<String>> workloadTenantId(String name, String nodeId) {
    Optional<Optional<String>> instance =
        storeClient.listAssignments().stream()
            .filter(a -> a.deploymentName().equals(name) && a.nodeId().equals(nodeId))
            .map(InstanceAssignment::tenantId)
            .findFirst();
    if (instance.isPresent()) {
      return instance;
    }
    Optional<Optional<String>> job =
        storeClient.listJobRuns().stream()
            .filter(r -> r.jobName().equals(name) && r.nodeId().equals(nodeId))
            .map(JobRun::tenantId)
            .findFirst();
    if (job.isPresent()) {
      return job;
    }
    Optional<Optional<String>> daemonSet =
        storeClient.listDaemonSetAssignments().stream()
            .filter(a -> a.daemonSetName().equals(name) && a.nodeId().equals(nodeId))
            .map(DaemonSetAssignment::tenantId)
            .findFirst();
    if (daemonSet.isPresent()) {
      return daemonSet;
    }
    return storeClient.listStatefulSetAssignments().stream()
        .filter(a -> a.statefulSetName().equals(name) && a.nodeId().equals(nodeId))
        .map(StatefulSetAssignment::tenantId)
        .findFirst();
  }

  /**
   * Verifies a presented bearer token against its replicated record: parse the {@code
   * deploymentName#nodeId} key out of the token itself, look the record up on the store (any
   * replica -- the whole reason these are store-backed rather than signed with a per-replica key),
   * compare hashes constant-time, and check expiry. The principal a live token resolves to is
   * {@code svc:<tenantId>:<deploymentName>} in group {@code gimle:workloads} -- bindable in RBAC
   * exactly like a user (e.g. {@code --subject user:svc:acme:orders}), with no implicit grants at
   * all: an unbound workload principal is denied everything, deny-by-default.
   */
  private Optional<Principal> verifyWorkloadToken(String token) {
    int separator = token.indexOf(':');
    if (separator <= 0) {
      return Optional.empty();
    }
    String key = token.substring(0, separator);
    Optional<WorkloadTokenRecord> record = storeClient.getWorkloadToken(key);
    if (record.isEmpty()) {
      return Optional.empty();
    }
    byte[] presented = sha256Hex(token).getBytes(StandardCharsets.UTF_8);
    byte[] stored = record.get().tokenSha256Hex().getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(presented, stored)) {
      return Optional.empty();
    }
    if (System.currentTimeMillis() > record.get().expiresAtEpochMilli()) {
      return Optional.empty();
    }
    return Optional.of(
        new Principal(
            "svc:" + record.get().tenantId().orElse("") + ":" + record.get().deploymentName(),
            Set.of("gimle:workloads")));
  }

  private static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  // ---- /certificates/revoked, /certificates/revoked/{serial} ----

  /** {@code GET /certificates/revoked}: every currently-revoked serial, sorted. */
  private void handleCertificateRevocations(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      if (!requireAuthorized(
          exchange, ResourceKind.CERTIFICATE_REQUEST, Verb.READ, Optional.empty())) {
        return;
      }
      respondJson(
          exchange,
          200,
          Map.of(
              "revokedSerials",
              storeClient.listRevokedCertificateSerials().stream().sorted().toList()));
    } catch (IOException e) {
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * {@code PUT /certificates/revoked/{serial}} revokes, {@code DELETE} un-revokes. The serial is
   * lowercase hex (the {@code openssl x509 -serial} form); it is normalized here so an operator's
   * uppercase paste still matches the lowercase form {@code resolvePrincipal} derives. Guarded by
   * {@code CERTIFICATE_REQUEST} -- revocation is the flip side of issuance, not its own kind.
   * Deliberately reversible: revocation is a store entry, so an operator who revoked the wrong
   * serial can undo it, unlike a destroyed secret.
   */
  private void handleCertificateRevocation(HttpExchange exchange) {
    try {
      String serial = pathSegmentAfter(exchange, "/certificates/revoked/").toLowerCase(Locale.ROOT);
      if (serial.isBlank() || !serial.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
        respond(exchange, 400, "expected a hex certificate serial number");
        return;
      }
      boolean revoke;
      switch (exchange.getRequestMethod()) {
        case "PUT" -> revoke = true;
        case "DELETE" -> revoke = false;
        default -> {
          respond(exchange, 405, "method not allowed");
          return;
        }
      }
      if (!requireAuthorized(
          exchange,
          ResourceKind.CERTIFICATE_REQUEST,
          revoke ? Verb.WRITE : Verb.DELETE,
          Optional.empty(),
          Optional.of(serial))) {
        return;
      }
      storeClient.propose(new StateMutation.PutCertificateRevocation(serial, revoke));
      respondJson(exchange, 200, Map.of("serial", serial, "revoked", revoke));
    } catch (IOException e) {
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  // ---- /volumes, /volumes/{nodeId}/{statefulSetName}/{instanceIndex} ----

  /**
   * The cluster-wide volume inventory: fans out to every registered node's own agent {@code
   * /volumes} surface and aggregates, annotating each entry with its node and whether the store's
   * sticky binding still attaches it -- {@code attached=false} is a retained orphan an operator can
   * inspect and, when done, destroy through the {@code DELETE} route below. A node whose agent is
   * unreachable contributes an {@code unreachableNodes} entry rather than failing the whole
   * listing, so one dark node never hides every other node's volumes. RBAC-gated on {@code
   * STATEFULSET} reads: a volume is a StatefulSet's own storage, not a resource kind of its own.
   */
  private void handleVolumesList(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      if (!requireAuthorized(
          exchange, ResourceKind.STATEFULSET, Verb.READ, Optional.empty(), Optional.empty())) {
        return;
      }
      List<Map<String, Object>> volumes = new ArrayList<>();
      List<String> unreachableNodes = new ArrayList<>();
      for (NodeRegistration registration : storeClient.listNodeRegistrations()) {
        Optional<String> apiAddress = registration.apiAddress();
        if (apiAddress.isEmpty()) {
          unreachableNodes.add(registration.nodeId());
          continue;
        }
        List<Map<String, Object>> nodeVolumes;
        try {
          nodeVolumes = fetchAgentVolumes(apiAddress.get());
        } catch (IOException | InterruptedException e) {
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
          unreachableNodes.add(registration.nodeId());
          continue;
        }
        for (Map<String, Object> volume : nodeVolumes) {
          String statefulSet = String.valueOf(volume.get("statefulSet"));
          int instanceIndex = ((Number) volume.get("instanceIndex")).intValue();
          Optional<String> tenantId = Optional.ofNullable((String) volume.get("tenantId"));
          Map<String, Object> entry = new LinkedHashMap<>(volume);
          entry.put("nodeId", registration.nodeId());
          entry.put(
              "attached",
              isVolumeAttached(tenantId, statefulSet, instanceIndex, registration.nodeId()));
          volumes.add(entry);
        }
      }
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("volumes", volumes);
      if (!unreachableNodes.isEmpty()) {
        body.put("unreachableNodes", unreachableNodes);
      }
      respondJson(exchange, 200, body);
    } catch (IOException e) {
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * Destroys one volume through its owning node's agent -- an explicit, RBAC-gated operator action
   * ({@code STATEFULSET} delete), refused with {@code 409} while the store still attaches that
   * index to that node (a live instance's data is never destroyable through this route; scale down
   * or delete the spec first). The agent independently refuses again if a supervised instance still
   * holds the volume -- defense in depth against a racing placement.
   */
  private void handleVolumeDestroy(HttpExchange exchange) {
    try {
      if (!"DELETE".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      String[] segments = pathSegmentAfter(exchange, "/volumes/").split("/");
      if (segments.length != 3 || !segments[2].chars().allMatch(Character::isDigit)) {
        respond(
            exchange, 400, "expected DELETE /volumes/{nodeId}/{statefulSetName}/{instanceIndex}");
        return;
      }
      String nodeId = segments[0];
      String statefulSetName = segments[1];
      int instanceIndex = Integer.parseInt(segments[2]);
      Optional<String> tenantId = volumeTenant(exchange);
      if (!requireAuthorized(
          exchange,
          ResourceKind.STATEFULSET,
          Verb.DELETE,
          tenantId,
          Optional.of(statefulSetName))) {
        return;
      }
      if (isVolumeAttached(tenantId, statefulSetName, instanceIndex, nodeId)) {
        respond(
            exchange,
            409,
            "volume "
                + statefulSetName
                + "["
                + instanceIndex
                + "] is still attached on node "
                + nodeId
                + "; scale down or delete the statefulset first");
        return;
      }
      Optional<NodeRegistration> registration = storeClient.getNodeRegistration(nodeId);
      Optional<String> apiAddress = registration.flatMap(NodeRegistration::apiAddress);
      if (apiAddress.isEmpty()) {
        respond(exchange, 502, "node " + nodeId + " has no reachable agent address");
        return;
      }
      String tenantQuery =
          tenantId.map(t -> "?tenant=" + URLEncoder.encode(t, StandardCharsets.UTF_8)).orElse("");
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://"
                          + apiAddress.get()
                          + "/volumes/"
                          + statefulSetName
                          + "/"
                          + instanceIndex
                          + tenantQuery))
              .timeout(Duration.ofSeconds(10))
              .DELETE()
              .build();
      HttpResponse<String> response;
      try {
        response = agentHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        respond(exchange, 502, "interrupted while reaching agent " + apiAddress.get());
        return;
      } catch (IOException e) {
        respond(
            exchange, 502, "failed to reach agent at " + apiAddress.get() + ": " + e.getMessage());
        return;
      }
      respond(exchange, response.statusCode(), response.body());
    } catch (IOException e) {
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private List<Map<String, Object>> fetchAgentVolumes(String apiAddress)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://" + apiAddress + "/volumes"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
    HttpResponse<String> response =
        agentHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("agent answered " + response.statusCode() + " for GET /volumes");
    }
    return Json.asObjectList(Json.parse(response.body()));
  }

  /**
   * Whether the store currently binds {@code (statefulSetName, instanceIndex)} to {@code nodeId}
   * with the spec still existing -- the definition of "not an orphan." A binding pointing at a
   * different node leaves the data on this node orphaned (sticky placement moved on without it,
   * which only happens through explicit operator intervention), and a deleted spec orphans every
   * index's data at once.
   */
  private boolean isVolumeAttached(
      Optional<String> tenantHint, String statefulSetName, int instanceIndex, String nodeId) {
    if (storeClient.getStatefulSetSpec(tenantHint, statefulSetName).isEmpty()) {
      return false;
    }
    return storeClient
        .getStatefulSetIndexNode(tenantHint, statefulSetName, instanceIndex)
        .filter(nodeId::equals)
        .isPresent();
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
      // An instance-log request honors an explicit ?tenant=<id> the same way every other
      // tenant-scoped route does; when the caller gives none, this resolves the owning tenant by
      // searching across workload kinds for the named deployment -- the same {@link
      // #resolveTenantForWorkloadName} search {@code /endpoints/{name}} already uses (and, since
      // GIMLE-746, ambiguity-safe: two tenants genuinely sharing this deploymentName raise a clear
      // 400 instead of silently picking one and 404ing the other). Previously this defaulted
      // straight to the untenanted namespace with no search at all, so a real, ACTIVE instance
      // whose owning tenant wasn't literally "default" 404'd on this live path forever -- workable
      // only through Muninn's own fallback store -- even though {@code resolveInstanceNodeId}
      // itself has always been able to resolve it correctly once given the right tenant.
      InstanceLogsTenant instanceTenant =
          tail.startsWith("instances/")
              ? resolveInstanceLogsTenant(exchange, tail)
              : InstanceLogsTenant.NONE;
      Optional<String> tenantId = instanceTenant.tenantId();
      if (!requireAuthorized(exchange, ResourceKind.LOGS, Verb.READ, tenantId, targetNodeId)) {
        return;
      }
      if (tail.equals("controlplane")) {
        handleControlPlaneLogs(exchange);
      } else if (tail.startsWith("nodes/")) {
        handleNodeLogsProxy(exchange, tail.substring("nodes/".length()));
      } else if (tail.startsWith("instances/")) {
        handleInstanceLogsProxy(exchange, tail.substring("instances/".length()), instanceTenant);
      } else {
        respond(exchange, 404, "unknown logs endpoint: " + tail);
      }
    } catch (AmbiguousTenantException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
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
   * The tenant an instance-log request should be authorized and resolved against: an explicit
   * {@code ?tenant=} always wins, exactly like every other tenant-scoped route in this class;
   * otherwise this searches for whichever tenant currently owns a workload named the request's own
   * {@code deploymentName} segment, the same {@link #resolveTenantForWorkloadName} search {@code
   * /endpoints/{name}} already relies on. Only when that search finds no such workload spec under
   * any tenant at all (e.g. a name only Muninn still remembers, its own spec long gone) does this
   * fall back to {@link Tenant#DEFAULT_TENANT_ID}, matching the default every manifest without its
   * own {@code tenantId} already resolves to at parse time -- not {@link Optional#empty()}, which
   * is a distinct, effectively-dead store bucket nothing ever actually writes into. {@code tail} is
   * the full post-{@code /logs/} path (still carrying its {@code instances/} prefix), matching what
   * {@link #handleLogs} already has in hand.
   */
  private InstanceLogsTenant resolveInstanceLogsTenant(HttpExchange exchange, String tail) {
    Optional<String> declared = Optional.ofNullable(parseQuery(exchange).get("tenant"));
    if (declared.isPresent()) {
      return new InstanceLogsTenant(declared, Optional.empty());
    }
    String instanceTail = tail.substring("instances/".length());
    String deploymentName = instanceTail.split("/", 2)[0];
    Optional<String> resolved = resolveTenantForWorkloadName(deploymentName);
    return new InstanceLogsTenant(
        resolved.isPresent() ? resolved : Optional.of(Tenant.DEFAULT_TENANT_ID), resolved);
  }

  /**
   * How an instance-log request's owning tenant was arrived at. {@code tenantId} is what the
   * request is authorized and placement-resolved against; {@code agentHint} is the same value only
   * when it should additionally be spelled out to the node agent on the proxy hop.
   *
   * <p>Spelling it out matters because an agent handed a bare name and index has to answer from
   * whatever it happens to supervise, and that name search does not cover every hosting mode: a
   * workload running as its own process is filed under a tenant-scoped key with no bare name to
   * match, so its logs read back empty while an identically-addressed module-hosted instance reads
   * back fine. The hint is deliberately absent in the two cases where adding it could only do harm
   * -- the caller already put a {@code tenant=} on the query itself (it is forwarded verbatim), and
   * the tenant is only {@link #resolveInstanceLogsTenant}'s own default-namespace fallback for a
   * name no workload spec claims anywhere, where an invented value would turn the agent's working
   * name search into an exact key miss.
   */
  private record InstanceLogsTenant(Optional<String> tenantId, Optional<String> agentHint) {
    static final InstanceLogsTenant NONE =
        new InstanceLogsTenant(Optional.empty(), Optional.empty());
  }

  /**
   * {@code GET /metrics-history/{processKind}/{processId}} -- a thin, read-only proxy to Muninn's
   * own {@code GET /metrics/{processKind}/{processId}}, via {@link #handleHistoryProxy}.
   */
  private void handleMetricsHistory(HttpExchange exchange) {
    handleHistoryProxy(
        exchange,
        "/metrics-history/",
        "/metrics/",
        "metrics history",
        ObservedProcessKind.Signal.METRICS);
  }

  /**
   * {@code GET /metrics-history} -- the process kinds whose own metrics are ever shipped here, so a
   * caller offering the operator a choice builds it from what the platform actually ships rather
   * than from a list of its own that can quietly fall behind. The same read gate the per-process
   * route below applies, since the answer describes that route.
   */
  private void handleMetricsHistoryKinds(HttpExchange exchange) {
    handleHistoryKinds(exchange, ObservedProcessKind.Signal.METRICS, "metrics history kinds");
  }

  /**
   * {@code GET /traces-history/{processKind}/{processId}} -- structurally identical to {@link
   * #handleMetricsHistory} above, proxying to Muninn's own {@code GET
   * /traces/{processKind}/{processId}} instead of {@code /metrics/...}, via {@link
   * #handleHistoryProxy}.
   */
  private void handleTracesHistory(HttpExchange exchange) {
    handleHistoryProxy(
        exchange,
        "/traces-history/",
        "/traces/",
        "traces history",
        ObservedProcessKind.Signal.TRACES);
  }

  /** {@code GET /traces-history} -- see {@link #handleMetricsHistoryKinds}. */
  private void handleTracesHistoryKinds(HttpExchange exchange) {
    handleHistoryKinds(exchange, ObservedProcessKind.Signal.TRACES, "traces history kinds");
  }

  private void handleHistoryKinds(
      HttpExchange exchange, ObservedProcessKind.Signal signal, String requestNoun) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      if (!requireAuthorized(exchange, ResourceKind.LOGS, Verb.READ, Optional.empty())) {
        return;
      }
      respondJson(exchange, 200, Map.of("processKinds", ObservedProcessKind.namesShipping(signal)));
    } catch (IOException | RuntimeException e) {
      log.warn("{} request failed: {}", requestNoun, e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
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
      HttpExchange exchange,
      String pathPrefix,
      String muninnPathPrefix,
      String requestNoun,
      ObservedProcessKind.Signal signal) {
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
      // A kind nothing ships this signal for could only ever read back an empty history, which is
      // indistinguishable from "the process is quiet" -- naming the kinds that do ship it turns a
      // typo, or a picker offering a kind this signal has no data for, into an answerable error.
      if (!ObservedProcessKind.shipsSignal(parts[0], signal)) {
        respond(
            exchange,
            400,
            "no "
                + signal.name().toLowerCase(Locale.ROOT)
                + " are shipped for process kind '"
                + parts[0]
                + "' (expected one of "
                + String.join(", ", ObservedProcessKind.namesShipping(signal))
                + ")");
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

  private void handleInstanceLogsProxy(
      HttpExchange exchange, String tail, InstanceLogsTenant tenant) throws IOException {
    Optional<String> tenantId = tenant.tenantId();
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
    // tenantId was already resolved by handleLogs (see resolveInstanceLogsTenant) -- computed once
    // there, before the RBAC check, rather than re-derived here a second time with a chance of
    // disagreeing with what was actually authorized.
    String nodeId = resolveInstanceNodeId(tenantId, deploymentName, instanceIndex);
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
    proxyToAgent(
        exchange, nodeId, "/logs/instances/" + tail, muninnFallbackPath, tenant.agentHint());
  }

  /**
   * Resolves {@code (tenantId, deploymentName, instanceIndex)} to the node currently hosting it,
   * regardless of which of the five workload kinds actually placed it -- {@code
   * storeClient.listAssignmentsFor} only ever holds Deployment-kind placements (it's populated
   * exclusively by {@code DeploymentReconciler}'s own bookkeeping, the same lookup {@code
   * ServiceEndpointResolver}/{@code AutoscaleReconciler} use), so a StatefulSet/DaemonSet/Job
   * instance's log request 404'd here forever even while genuinely {@code ACTIVE} elsewhere. Tries
   * each kind's own assignment list in turn, the same shape {@link #handleEndpoints} already uses
   * to resolve a workload name across kinds -- first match wins, since a name is unique across all
   * five kinds within one tenant's own namespace (not globally -- every lookup here is scoped to
   * {@code tenantId}, so two tenants' identically-named workload can never resolve into each
   * other's node/logs).
   */
  private String resolveInstanceNodeId(
      Optional<String> tenantId, String deploymentName, int instanceIndex) {
    return resolveInstancePlacement(tenantId, deploymentName, instanceIndex)
        .map(InstancePlacement::nodeId)
        .orElse(null);
  }

  /**
   * Which node hosts an instance, and which workload kind put it there -- the kind matters because
   * DaemonSet and StatefulSet are independently withholdable RBAC grants, so a route authorizing a
   * read of one instance must check the grant its owning workload actually falls under rather than
   * assume DEPLOYMENT.
   */
  private record InstancePlacement(String nodeId, ResourceKind kind) {}

  private Optional<InstancePlacement> resolveInstancePlacement(
      Optional<String> tenantId, String deploymentName, int instanceIndex) {
    Optional<InstancePlacement> deployment =
        storeClient.listAssignmentsFor(tenantId, deploymentName).stream()
            .filter(a -> a.instanceIndex() == instanceIndex)
            .map(a -> new InstancePlacement(a.nodeId(), ResourceKind.DEPLOYMENT))
            .findFirst();
    if (deployment.isPresent()) {
      return deployment;
    }
    Optional<InstancePlacement> statefulSet =
        storeClient.listStatefulSetAssignmentsFor(tenantId, deploymentName).stream()
            .filter(a -> a.instanceIndex() == instanceIndex)
            .map(a -> new InstancePlacement(a.nodeId(), ResourceKind.STATEFULSET))
            .findFirst();
    if (statefulSet.isPresent()) {
      return statefulSet;
    }
    // A DaemonSet instance's own "index" is always 0 (one instance per node, see
    // DaemonSetAssignment's own javadoc) -- instanceIndex must match that convention to resolve,
    // the same way a Job's own attempt number must match below.
    if (instanceIndex == 0) {
      Optional<InstancePlacement> daemonSet =
          storeClient.listDaemonSetAssignmentsFor(tenantId, deploymentName).stream()
              .map(a -> new InstancePlacement(a.nodeId(), ResourceKind.DAEMONSET))
              .findFirst();
      if (daemonSet.isPresent()) {
        return daemonSet;
      }
    }
    return storeClient.listJobRunsFor(tenantId, deploymentName).stream()
        .filter(run -> run.attempt() == instanceIndex)
        .map(run -> new InstancePlacement(run.nodeId(), ResourceKind.JOB))
        .findFirst();
  }

  /**
   * {@code /logs/nodes/{nodeId}/{category}} -- Muninn's own path-segment convention for category,
   * translated from this surface's own query-parameter convention (matching {@code
   * AgentLogServer.handleNodeLogs}'s {@code category} default of {@code PLATFORM}). {@code
   * follow}/{@code category} are stripped from the forwarded query; everything else ({@code
   * cursor}/{@code since}/{@code limit}, plus the {@code level}/{@code contains} content filter)
   * passes through unchanged, so a filtered read of a gone node's shipped history returns exactly
   * what the same filtered read of its live agent would have -- {@code follow=true} reaching this
   * fallback is silently dropped rather than erroring (Muninn only ever serves shipped history,
   * never a live tail), so a client that was following a now-gone node still gets a non-follow page
   * back instead of a hard failure.
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

  /**
   * {@code rawQuery} with a {@code tenant=} appended when {@code addedTenant} is present, leaving
   * every other parameter byte-for-byte as the caller sent it. The value is percent-encoded, since
   * a tenant id reaches this class as arbitrary text from a manifest or a path segment.
   */
  private static String withAddedTenant(String rawQuery, Optional<String> addedTenant) {
    if (addedTenant.isEmpty()) {
      return rawQuery;
    }
    String parameter = "tenant=" + URLEncoder.encode(addedTenant.get(), StandardCharsets.UTF_8);
    return rawQuery == null || rawQuery.isBlank() ? parameter : rawQuery + "&" + parameter;
  }

  /**
   * Values are re-encoded on the way out: {@link #parseQuery} hands back already-decoded text, and
   * a content filter's own search text is arbitrary operator input -- a space or {@code &} in it
   * would otherwise splice into, or outright invalidate, the URI built for the downstream hop.
   */
  private static String forwardedQuery(Map<String, String> query) {
    StringBuilder qs = new StringBuilder();
    for (Map.Entry<String, String> entry : query.entrySet()) {
      if (entry.getKey().equals("category") || entry.getKey().equals("follow")) {
        continue;
      }
      qs.append(qs.isEmpty() ? '?' : '&')
          .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
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
   * How long a bare TCP connect to an agent's advertised log-server address may take before {@link
   * #isAgentReachable} gives up -- matches {@code agentHttpClient}'s own {@code connectTimeout},
   * the bound the ordinary (non-follow) proxy path already accepts for the identical address.
   */
  private static final Duration AGENT_REACHABILITY_TIMEOUT = Duration.ofSeconds(5);

  /**
   * A bare TCP connect probe to {@code apiAddress} ({@code host:port}), bounded by {@link
   * #AGENT_REACHABILITY_TIMEOUT} -- used only to decide whether a {@code follow=true} session is
   * safe to commit to (see its call site's own javadoc for why that path can't simply try and
   * recover the way the bounded, non-follow path does). Never opens an HTTP request of its own: a
   * plain connect/refuse is all that's needed to distinguish "the agent process is down" from
   * "reachable, proceed."
   */
  private static boolean isAgentReachable(String apiAddress) {
    int colon = apiAddress.lastIndexOf(':');
    if (colon < 0) {
      return false;
    }
    String host = apiAddress.substring(0, colon);
    int port;
    try {
      port = Integer.parseInt(apiAddress.substring(colon + 1));
    } catch (NumberFormatException e) {
      return false;
    }
    try (Socket socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(host, port), (int) AGENT_REACHABILITY_TIMEOUT.toMillis());
      return true;
    } catch (IOException e) {
      return false;
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
    proxyToAgent(exchange, nodeId, path, muninnFallbackPath, Optional.empty());
  }

  /**
   * As above, additionally spelling out {@code addedTenant} as a {@code tenant=} on the forwarded
   * query -- for a route whose own resolution already established which tenant owns the target and
   * whose agent-side counterpart would otherwise have to re-derive it from the bare name.
   */
  private void proxyToAgent(
      HttpExchange exchange,
      String nodeId,
      String path,
      String muninnFallbackPath,
      Optional<String> addedTenant)
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
    String query = withAddedTenant(exchange.getRequestURI().getRawQuery(), addedTenant);
    URI target =
        URI.create("http://" + apiAddress.get() + path + (query != null ? "?" + query : ""));

    if (query != null && query.contains("follow=true")) {
      // A follow session commits to a chunked 200 the moment proxyFollowToAgent starts (there is
      // no clean way to downgrade that to an error or a fallback once bytes may already be
      // flowing), so unlike the bounded, non-follow path below, an agent that is actually down
      // must be caught *before* that commitment -- otherwise the caller is left staring at an
      // indefinitely open, silent connection instead of either a real fallback or a clear error.
      // A plain TCP connect probe, bounded the same way agentHttpClient's own connectTimeout
      // already bounds the non-follow path, catches exactly the case a real agent process being
      // stopped produces (the port refuses outright) without needing to speak HTTP at all.
      if (!isAgentReachable(apiAddress.get())) {
        if (muninnFallbackPath != null) {
          proxyToMuninn(exchange, muninnFallbackPath);
          return;
        }
        respond(
            exchange, 502, "agent " + apiAddress.get() + " unreachable, cannot follow live logs");
        return;
      }
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
    // Same level/contains filtering the agent's own log surface applies, applied here at the
    // reader for the one log stream this process serves itself rather than proxies.
    LogFilter filter = LogFilter.fromQuery(query);
    if (follow) {
      exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson; charset=utf-8");
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream out = exchange.getResponseBody()) {
        LogFileReader.streamFollow(file, maxFiles, cursor, Duration.ofMillis(500), out, filter);
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
      List<Map<String, Object>> lines = LogFileReader.readAfter(file, maxFiles, since, filter);
      String newerCursor =
          lines.isEmpty() ? since : String.valueOf(lines.get(lines.size() - 1).get("timestamp"));
      page = new LogFileReader.LogPage(lines, null, newerCursor);
    } else {
      page = LogFileReader.readOlder(file, maxFiles, cursor, limit, filter);
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

  /**
   * The caller-declared {@code ?tenant=} hint for a route addressing a Deployment/Job/CronJob/
   * DaemonSet/StatefulSet (or something scoped to one, like {@code /endpoints/*}, {@code
   * /volumes/*}, or an instance-log route) -- everywhere else in this class {@code ?tenant=} is
   * read with {@link #parseQuery}'s bare {@code Optional.ofNullable(...)}, but these five workload
   * kinds are different: {@code ManifestFields#parseTenantId} resolves a manifest's own omitted
   * {@code tenantId} to {@link Tenant#DEFAULT_TENANT_ID}, never {@code Optional.empty()}, so a real
   * PUT through this API can never create a workload actually keyed under the untenanted namespace.
   * Defaulting a caller's omitted {@code ?tenant=} to that same {@code default} tenant (Kubernetes'
   * own convention for an omitted {@code --namespace}) is what makes {@code GET}/{@code DELETE}
   * able to find what an equally tenant-omitting {@code PUT} actually wrote; defaulting it to
   * {@code Optional.empty()} instead -- as every other resource kind's own bare {@code ?tenant=}
   * read still correctly does, since those kinds have no such manifest-side default -- would
   * address a namespace no such workload can ever land in.
   */
  /**
   * The owning tenant of the volume a {@code /volumes/*} request addresses: {@code ?tenant=<id>}
   * for a tenanted one, an omitted or blank {@code ?tenant=} for a genuinely untenanted one.
   *
   * <p>Deliberately not {@link #workloadTenantHint}, which every other workload-scoped route uses.
   * That default exists because a workload manifest's own omitted {@code tenantId} resolves to
   * {@link Tenant#DEFAULT_TENANT_ID}, so no workload can ever land in the untenanted namespace and
   * a lookup addressing it would find nothing. A volume is different: its tenant comes from the
   * allocation record the agent wrote, not from a manifest, and untenanted volumes genuinely exist
   * on disk and are listed as {@code "tenantId": null} by {@code GET /volumes}. Defaulting an
   * omitted {@code ?tenant=} to {@code default} here made every one of those unaddressable, and --
   * far worse on a {@code DELETE} -- silently redirected a request naming a tenanted volume at the
   * default tenant's identically-named volume at the same set and index instead.
   */
  private static Optional<String> volumeTenant(HttpExchange exchange) {
    return Optional.ofNullable(parseQuery(exchange).get("tenant")).filter(t -> !t.isBlank());
  }

  private static Optional<String> workloadTenantHint(HttpExchange exchange) {
    return Optional.of(parseQuery(exchange).getOrDefault("tenant", Tenant.DEFAULT_TENANT_ID));
  }

  /**
   * {@link #workloadTenantHint} for a route addressing one instance by bare {@code (name, index)}:
   * an explicit {@code ?tenant=} still wins, but an omitted one resolves the owning tenant from
   * whichever workload spec actually carries {@code name} -- the same search {@code
   * /endpoints/{name}} and the instance-log routes already use -- before falling back to the
   * default namespace. Defaulting straight to {@code default} instead answered "no placement found"
   * for a perfectly healthy instance whose workload simply belongs to another tenant, even though
   * the caller had named it unambiguously.
   */
  private Optional<String> instanceTenantHint(HttpExchange exchange, String name) {
    Optional<String> declared = Optional.ofNullable(parseQuery(exchange).get("tenant"));
    if (declared.isPresent()) {
      return declared;
    }
    Optional<String> resolved = resolveTenantForWorkloadName(name);
    return resolved.isPresent() ? resolved : Optional.of(Tenant.DEFAULT_TENANT_ID);
  }

  // ---- /bootstrap/csr, /bootstrap/csr/{id}[/approve], /bootstrap/tokens ----

  private static final Duration LEAF_VALIDITY = Duration.ofDays(397);
  // How many submissions one address may spend at once, and how fast it earns another.
  static final String RATE_LIMIT_ENABLED_PROPERTY = "gimle.controlplane.rateLimit.enabled";
  static final String RATE_LIMIT_BURST_PROPERTY = "gimle.controlplane.rateLimit.burstPerAddress";
  static final String RATE_LIMIT_REFILL_MILLIS_PROPERTY =
      "gimle.controlplane.rateLimit.refillMillisPerAddress";

  /**
   * Empty when {@code gimle.controlplane.rateLimit.enabled} is set to {@code false} -- an explicit
   * operator decision to run the API unbounded, for a benchmark or a single-tenant lab where the
   * limit is only noise. On by default: an unbounded write path is the gap, not the limit.
   */
  private static Optional<RequestRateLimiter> buildRequestRateLimiter() {
    if (!Boolean.parseBoolean(System.getProperty(RATE_LIMIT_ENABLED_PROPERTY, "true"))) {
      return Optional.empty();
    }
    return Optional.of(
        new RequestRateLimiter(
            Integer.getInteger(RATE_LIMIT_BURST_PROPERTY, 600),
            Duration.ofMillis(Long.getLong(RATE_LIMIT_REFILL_MILLIS_PROPERTY, 5L))));
  }

  static final String CSR_BURST_PER_ADDRESS_PROPERTY = "gimle.controlplane.csr.burstPerAddress";
  static final String CSR_REFILL_MILLIS_PER_ADDRESS_PROPERTY =
      "gimle.controlplane.csr.refillMillisPerAddress";
  // The same pair for every caller together, so many addresses can't add up to an unbounded rate.
  static final String CSR_CLUSTER_BURST_PROPERTY = "gimle.controlplane.csr.burst";
  static final String CSR_CLUSTER_REFILL_MILLIS_PROPERTY = "gimle.controlplane.csr.refillMillis";

  /**
   * No blanket {@link #requireAuthorized} call here, deliberately: this is the one endpoint that by
   * design must be reachable without a client certificate -- it exists specifically to issue the
   * cert that makes mTLS possible everywhere else. Four distinct auth contexts, distinguished by
   * what the request carries: {@code purpose == TENANT_CLIENT} means an already-credentialed caller
   * minting a tenant-membership certificate (the one branch that does run {@code
   * requireAuthorized}); otherwise a verified peer certificate present at all means rotation
   * (subject must match); none present and {@code purpose == NODE_CLIENT} means a node join,
   * authenticated by a one-time bootstrap token; none present and {@code purpose ==
   * OPERATOR_CLIENT} means a human operator request, never auto-approved.
   */
  private void handleBootstrapCsrSubmit(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      // Charged before the body is even read, so a refused caller costs this process a socket
      // read and nothing more -- rate limiting that only kicked in after parsing the CSR would
      // have already paid the price it exists to avoid.
      Optional<Instant> retryAt = csrRateLimited(exchange);
      if (retryAt.isPresent()) {
        respondThrottled(exchange, retryAt.get());
        return;
      }
      Map<String, Object> body;
      try {
        Object parsed = Json.parse(readBody(exchange));
        if (!(parsed instanceof Map<?, ?>)) {
          respond(exchange, 400, "request body must be a JSON object");
          return;
        }
        body = Json.asObject(parsed);
      } catch (IllegalArgumentException e) {
        respond(exchange, 400, "request body is not valid JSON: " + e.getMessage());
        return;
      }
      CsrSubmission submission = csrSubmissionFromJson(body);
      PKCS10CertificationRequest csr = Pem.decodeCsr(submission.csrPem());

      // Checked before the rotation branch: a TENANT_CLIENT submission is authenticated by the
      // submitting operator's own certificate, which would otherwise route it into rotation.
      if (submission.purpose() == CsrPurpose.TENANT_CLIENT) {
        handleTenantClientRequest(exchange, csr, submission.tenantId().orElseThrow());
        return;
      }
      // Same reason: a WORKER_CLIENT submission is authenticated by the spawning agent's own node
      // certificate, and mints a *different* subject than the one presented, so it must never
      // reach the same-subject rotation branch below.
      if (submission.purpose() == CsrPurpose.WORKER_CLIENT) {
        handleWorkerClientRequest(exchange, csr, submission.tenantId());
        return;
      }
      Optional<X509Certificate> presented = peerCertificate(exchange);
      if (presented.isPresent()) {
        handleRotationRequest(exchange, csr, presented.get());
        return;
      }
      switch (submission.purpose()) {
        case NODE_CLIENT -> handleNodeJoinRequest(exchange, csr, submission.bootstrapToken());
        case OPERATOR_CLIENT -> handleOperatorJoinRequest(exchange, csr);
        case TENANT_CLIENT, WORKER_CLIENT -> throw new IllegalStateException("handled above");
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
   * Empty while a CSR submission may be served; otherwise the instant the caller may try again.
   * Every submission is charged, authenticated rotation included: a certificate holder is not the
   * threat this bounds, but exempting one would mean deciding that before the body is read, and the
   * limits are set far above any rate a real node's own join or rotation traffic reaches.
   */
  /**
   * Empty while this caller may be served; otherwise when it may try again. Charged before the
   * route runs and before any authentication, since the point is to bound work done on behalf of a
   * caller whose identity establishing would itself be the work.
   */
  private Optional<Instant> rateLimited(HttpExchange exchange) {
    return requestRateLimiter.flatMap(limiter -> limiter.acquire(remoteAddressKey(exchange)));
  }

  private Optional<Instant> csrRateLimited(HttpExchange exchange) {
    Optional<Instant> addressRetryAt = csrAddressRateLimiter.acquire(remoteAddressKey(exchange));
    if (addressRetryAt.isPresent()) {
      return addressRetryAt;
    }
    return csrClusterRateLimiter.acquire("cluster");
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

  /**
   * Neither branch has a pre-existing principal to run {@link #requireAuthorized} against -- a
   * one-time bootstrap token stands in for one here -- so both outcomes are audited directly
   * against {@link ResourceKind#CERTIFICATE_REQUEST}/{@link Verb#APPROVE} instead: this is exactly
   * "who was granted trust, and when," and a rejected token attempt is as worth a durable trace as
   * an accepted one.
   */
  private void handleNodeJoinRequest(
      HttpExchange exchange, PKCS10CertificationRequest csr, Optional<String> bootstrapToken)
      throws IOException {
    boolean tokenAccepted =
        bootstrapToken.isPresent() && bootstrapTokenRegistry.tryConsume(bootstrapToken.get());
    recordAuditEvent(
        new Principal("bootstrap-token", Set.of()),
        ResourceKind.CERTIFICATE_REQUEST,
        Verb.APPROVE,
        Optional.empty(),
        Optional.of(csr.getSubject().toString()),
        tokenAccepted);
    if (!tokenAccepted) {
      respond(exchange, 401, "missing or invalid bootstrap token");
      return;
    }
    // Server-stamped O=, never the CSR's own: a
    // NODE_CLIENT CSR that self-declared O=gimle:operators must not be signed with it. The SAN a
    // joining node requests (its own advertised host, so other components can dial it by hostname
    // and pass TLS hostname verification) is trusted only as far as this very connection's own
    // remote address can back it -- see respondSigned's verified-SAN overload.
    respondSigned(
        exchange,
        200,
        csr,
        Subjects.withOrganization(csr.getSubject(), BuiltinRoles.GROUP_NODES),
        remoteAddress(exchange));
  }

  /**
   * Not yet a grant of trust -- {@link #handleApprove} is, and already runs through {@link
   * #requireAuthorized} -- but recording the submission itself closes the timeline gap between "a
   * CSR arrived" and "an operator acted on it," the same forensic need {@link
   * #handleNodeJoinRequest}'s own audit call serves.
   */
  private void handleOperatorJoinRequest(HttpExchange exchange, PKCS10CertificationRequest csr)
      throws IOException {
    String requestId = pendingCsrStore.submit(Pem.encodeCsr(csr));
    recordAuditEvent(
        ANONYMOUS_PRINCIPAL,
        ResourceKind.CERTIFICATE_REQUEST,
        Verb.WRITE,
        Optional.empty(),
        Optional.of(requestId),
        true);
    respondJson(exchange, 202, csrResultToJson(CsrResult.pending(requestId)));
  }

  /**
   * Signed immediately, but only for a caller already authorized to approve certificate requests
   * under {@code tenantId}'s scope -- a cluster operator, or that tenant's own {@code
   * tenant-admin:} holder, minting a tenant-membership client certificate for one of the tenant's
   * callers. The issued certificate's {@code O=gimle:tenant:<id>} is stamped server-side like every
   * other issuance here, never taken from the CSR's own subject -- what makes the group a
   * trustworthy tenant claim for a TLS-terminating proxy to enforce a network policy against.
   */
  private void handleTenantClientRequest(
      HttpExchange exchange, PKCS10CertificationRequest csr, String tenantId) throws IOException {
    if (!requireAuthorized(
        exchange, ResourceKind.CERTIFICATE_REQUEST, Verb.APPROVE, Optional.of(tenantId))) {
      return;
    }
    respondSigned(
        exchange,
        200,
        csr,
        Subjects.withOrganization(csr.getSubject(), BuiltinRoles.tenantGroup(tenantId)));
  }

  /**
   * Signed immediately, but only over a node's own {@code gimle:nodes} certificate and only for a
   * subject that node may vouch for: the requested CN must be prefixed by the presenting node's own
   * id (a worker is named {@code <nodeId>:<instanceKey>}, so node-1 can never mint a certificate
   * that reads as one of node-2's workers), and a requested tenant must be one the node currently
   * holds an instance assignment for -- the identical level-triggered store check Fafnir already
   * makes before letting a node read that tenant's secrets, so a node may only ever obtain worker
   * identities for tenants the scheduler actually placed on it. The issued certificate carries
   * {@code O=gimle:workers} plus {@code O=gimle:tenant:<id>} when tenanted, stamped server-side
   * like every other issuance here and never taken from the CSR's own subject, and deliberately no
   * {@code gimle:nodes}: the worker's key material is what hosted-module code can reach, so it must
   * hold a worker's identity, not the node's. Both outcomes are audited under the node's own
   * principal, the same "who was granted trust, and when" trail every other issuance leaves.
   */
  private void handleWorkerClientRequest(
      HttpExchange exchange, PKCS10CertificationRequest csr, Optional<String> requestedTenantId)
      throws IOException {
    Optional<String> tenantId = requestedTenantId.filter(id -> !id.isBlank());
    Optional<Principal> resolved = resolvePrincipal(exchange);
    if (resolved.isEmpty()) {
      respond(
          exchange,
          401,
          "a worker certificate request must be authenticated by the node's own certificate");
      return;
    }
    Principal node = resolved.get();
    Optional<String> refusal = workerRequestRefusal(node, csr, tenantId);
    recordAuditEvent(
        node,
        ResourceKind.CERTIFICATE_REQUEST,
        Verb.APPROVE,
        tenantId,
        Optional.of(csr.getSubject().toString()),
        refusal.isEmpty());
    if (refusal.isPresent()) {
      respond(exchange, 403, refusal.get());
      return;
    }
    List<String> organizations = new ArrayList<>();
    organizations.add(BuiltinRoles.GROUP_WORKERS);
    tenantId.ifPresent(id -> organizations.add(BuiltinRoles.tenantGroup(id)));
    respondSigned(
        exchange,
        200,
        csr,
        Subjects.withOrganizations(csr.getSubject(), organizations),
        remoteAddress(exchange));
  }

  private Optional<String> workerRequestRefusal(
      Principal node, PKCS10CertificationRequest csr, Optional<String> tenantId) {
    if (!node.groups().contains(BuiltinRoles.GROUP_NODES)) {
      return Optional.of("only a node's own certificate may request a worker certificate");
    }
    String requiredPrefix = node.name() + ":";
    if (Subjects.commonNameOf(csr.getSubject())
        .filter(commonName -> commonName.startsWith(requiredPrefix))
        .isEmpty()) {
      return Optional.of(
          "a worker certificate's CN must be prefixed by the requesting node's own id ("
              + requiredPrefix
              + ")");
    }
    if (tenantId.isPresent() && !authorizer.isTenantAssignedToNode(node.name(), tenantId.get())) {
      return Optional.of(
          "node " + node.name() + " holds no instance assignment for tenant " + tenantId.get());
    }
    return Optional.empty();
  }

  /**
   * Signs with no SAN trusted from the CSR at all -- the correct default for rotation and
   * tenant-client issuance, neither of which has any legitimate use for one (see {@link
   * CertificateAuthority#signCertificateRequestWithVerifiedSan}'s own javadoc).
   */
  private void respondSigned(
      HttpExchange exchange, int status, PKCS10CertificationRequest csr, X500Name subjectOverride)
      throws IOException {
    respondSigned(exchange, status, csr, subjectOverride, Optional.empty());
  }

  private void respondSigned(
      HttpExchange exchange,
      int status,
      PKCS10CertificationRequest csr,
      X500Name subjectOverride,
      Optional<InetAddress> verifiedRequesterAddress)
      throws IOException {
    CertificateAuthority ca = certificateAuthority.orElseThrow();
    X509Certificate signed =
        ca.signCertificateRequestWithVerifiedSan(
            csr, subjectOverride, LEAF_VALIDITY, verifiedRequesterAddress);
    respondJson(
        exchange,
        status,
        csrResultToJson(
            CsrResult.approved(
                Pem.encodeCertificate(signed), Pem.encodeCertificate(ca.certificate()))));
  }

  /**
   * The connection's own remote address as a verified-SAN-ownership claim -- never a
   * client-supplied header, for the same reason {@link #remoteAddressKey} isn't either. Absent when
   * the exchange carries none, which the verified-SAN signing path already treats as "sign with no
   * SAN" rather than failing the request.
   */
  private static Optional<InetAddress> remoteAddress(HttpExchange exchange) {
    InetSocketAddress remote = exchange.getRemoteAddress();
    return remote == null ? Optional.empty() : Optional.ofNullable(remote.getAddress());
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
    // is never trusted verbatim either. No SAN is trusted from the CSR at all: this signs a
    // request submitted over a since-closed connection (a different operator approves it later),
    // so there's no live remote address to verify one against, and an operator client cert has no
    // legitimate use for a SAN in the first place.
    X509Certificate signed =
        ca.signCertificateRequestWithVerifiedSan(
            csr,
            Subjects.withOrganization(csr.getSubject(), BuiltinRoles.GROUP_OPERATORS),
            LEAF_VALIDITY,
            Optional.empty());
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

  /**
   * Every field is read by name and checked here rather than cast straight out of the map: a body
   * missing {@code purpose}, or carrying a number where a string belongs, used to reach a raw
   * {@code NullPointerException}/{@code ClassCastException} and answer 500 -- telling a caller that
   * this process had broken, when in fact its own request was incomplete and it was never told
   * which field.
   */
  private static CsrSubmission csrSubmissionFromJson(Map<String, Object> json) {
    CsrPurpose purpose = requiredCsrPurpose(json);
    String csrPem = requiredCsrField(json, "csrPem");
    Optional<String> bootstrapToken = optionalCsrField(json, "bootstrapToken");
    Optional<String> tenantId = optionalCsrField(json, "tenantId");
    // Checked here rather than at the branch that consumes it: that branch reaches for the value
    // with orElseThrow, which is a 500 for what is plainly a malformed request.
    if (purpose == CsrPurpose.TENANT_CLIENT && tenantId.isEmpty()) {
      throw new IllegalArgumentException(
          "'tenantId' is required for a " + CsrPurpose.TENANT_CLIENT + " submission");
    }
    return new CsrSubmission(purpose, csrPem, bootstrapToken, tenantId);
  }

  private static CsrPurpose requiredCsrPurpose(Map<String, Object> json) {
    String raw = requiredCsrField(json, "purpose");
    for (CsrPurpose purpose : CsrPurpose.values()) {
      if (purpose.name().equals(raw)) {
        return purpose;
      }
    }
    throw new IllegalArgumentException(
        "'purpose' must be one of "
            + Arrays.stream(CsrPurpose.values()).map(Enum::name).collect(Collectors.joining(", "))
            + ", got: "
            + raw);
  }

  private static String requiredCsrField(Map<String, Object> json, String field) {
    Object value = json.get(field);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException(
          "'" + field + "' is required and must be a non-blank string");
    }
    return text;
  }

  private static Optional<String> optionalCsrField(Map<String, Object> json, String field) {
    Object value = json.get(field);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException("'" + field + "' must be a string if present");
    }
    return Optional.of(text);
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
   * resource, and verb already in hand -- so for {@link Verb#WRITE}/{@link Verb#DELETE}/{@link
   * Verb#APPROVE} (never {@link Verb#READ}, matching Kubernetes' own default audit policy: a
   * console page-load's worth of {@code GET}s would dwarf the mutating-action volume actually worth
   * capturing) this is where the decision is recorded into the durable, queryable audit trail (see
   * {@link AuditEvent}), both allowed and denied alike -- a denial is exactly as auditable as a
   * grant. A bare {@code 401} (no principal resolved at all) is deliberately not audited, since
   * there's no principal to attribute the attempt to; only a resolved-but-denied principal produces
   * an event.
   */
  private boolean requireAuthorized(
      HttpExchange exchange,
      ResourceKind resource,
      Verb verb,
      Optional<String> tenant,
      Optional<String> targetId) {
    if (!(exchange instanceof HttpsExchange)) {
      // Plaintext mode has no identity to check -- fully open, matching the documented design --
      // but a mutation still happened, and the audit trail must say so rather than showing
      // nothing at all for every write/delete this process ever received in this mode. Attributed
      // to the same synthetic "anonymous" principal the console's own session endpoint already
      // reports for this mode (see handleAuthSession above).
      if (verb == Verb.WRITE
          || verb == Verb.DELETE
          || verb == Verb.APPROVE
          || (verb == Verb.READ && auditReadResourceKinds.contains(resource))) {
        recordAuditEvent(ANONYMOUS_PRINCIPAL, resource, verb, tenant, targetId, true);
      }
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
        || verb == Verb.APPROVE
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

  /**
   * {@code allowed}/{@code outcome} default to matching each other -- {@code outcome} follows
   * {@code allowed} via {@link AuditEvent}'s own convenience constructor -- correct for every
   * resource kind with no admission stage of its own to still reject an authorized write. {@link
   * #requireAuthorizedForWrite}'s callers use the explicit-{@link AuditOutcome} overload below
   * instead, once they know whether admission actually applied the write.
   */
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

  /**
   * Explicit-{@link AuditOutcome} overload for a write whose real outcome is only known after this
   * process's own admission chain runs, strictly after authorization -- see {@link
   * #requireAuthorizedForWrite}'s javadoc for why authorization alone can't tell the caller what to
   * record here.
   */
  private void recordAuditEvent(
      Principal principal,
      ResourceKind resource,
      Verb verb,
      Optional<String> tenant,
      Optional<String> targetId,
      boolean allowed,
      AuditOutcome outcome) {
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
                outcome,
                System.currentTimeMillis())));
  }

  /**
   * The exact same write as {@link #recordAuditEvent}'s explicit-{@link AuditOutcome} overload,
   * except a failure to record it is logged and swallowed rather than propagated -- for the two
   * {@link #dispatchResourceRequest} PUT call sites that run this strictly after the real HTTP
   * response (200/400/409, written by {@code put.run} or {@link #rejectIfReservedSystemTenant}
   * itself) is already decided. Letting a transient store hiccup on this purely-secondary
   * bookkeeping write escape from there would unwind into {@code dispatchResourceRequest}'s own
   * catch blocks, which would then try to write a *second*, contradictory response onto an exchange
   * whose real one may already be on the wire -- turning an already-successful request into a
   * corrupted one over a failure that has nothing to do with whether the write itself succeeded.
   * The other call sites ({@link #requireAuthorized}, {@link #requireAuthorizedForWrite}'s denial
   * path) run before any response is written, so a genuine propagated failure there still yields
   * one clean response, not two.
   */
  private void recordAuditEventBestEffort(
      Principal principal,
      ResourceKind resource,
      Verb verb,
      Optional<String> tenant,
      Optional<String> targetId,
      boolean allowed,
      AuditOutcome outcome) {
    try {
      recordAuditEvent(principal, resource, verb, tenant, targetId, allowed, outcome);
    } catch (RuntimeException e) {
      log.warn(
          "failed to record audit event for {} {} on {} (response already sent): {}",
          verb,
          resource,
          tenant.orElse("<untenanted>"),
          e.getMessage());
    }
  }

  /** {@code targetId}-less convenience overload for the majority of call sites that need none. */
  private boolean requireAuthorized(
      HttpExchange exchange, ResourceKind resource, Verb verb, Optional<String> tenant) {
    return requireAuthorized(exchange, resource, verb, tenant, Optional.empty());
  }

  /**
   * The deferred-audit sibling of {@link #requireAuthorized} used only by {@link
   * #dispatchResourceRequest}'s PUT branch: authorizes {@link Verb#WRITE} exactly the same way, but
   * an authorized caller is never audited here -- the workload PUT handlers this feeds
   * (deployment/job/cronjob/daemonset/statefulset) all run further admission (kind/name validation,
   * artifact resolution, and for Deployment specifically, tenant quota and LimitRange) whose
   * verdict this method can't see yet. Recording "allowed" here, before that verdict exists, is
   * exactly the bug this method exists to avoid: an audit entry that says a write succeeded when
   * admission goes on to reject it. Returns the {@link Principal} to audit with (the resolved
   * caller, or the synthetic {@code anonymous} principal in plaintext mode) so the caller can
   * record one event once the real {@link AuditOutcome} is known. A denied caller is recorded and
   * responded to exactly as {@link #requireAuthorized} already does -- a denial is always final,
   * with no further admission stage left to run -- and {@link Optional#empty()} signals the caller
   * to stop.
   */
  private Optional<Principal> requireAuthorizedForWrite(
      HttpExchange exchange, ResourceKind resource, Optional<String> tenant) {
    if (!(exchange instanceof HttpsExchange)) {
      return Optional.of(ANONYMOUS_PRINCIPAL);
    }
    Optional<Principal> principal = resolvePrincipal(exchange);
    if (principal.isEmpty()) {
      respondQuietly(exchange, 401, "authentication required");
      return Optional.empty();
    }
    boolean authorized =
        authorizer.authorize(principal.get(), resource, Verb.WRITE, tenant, Optional.empty());
    if (!authorized) {
      recordAuditEvent(principal.get(), resource, Verb.WRITE, tenant, Optional.empty(), false);
      respondQuietly(exchange, 403, "forbidden");
      return Optional.empty();
    }
    return principal;
  }

  /**
   * The collection-listing counterpart of {@link #requireAuthorized}: where a single-resource read
   * is a yes/no against that resource's own tenant, a listing is *filtered* -- an unscoped {@code
   * READ} grant sees every item, a caller holding only tenant-scoped {@code READ} grants sees
   * exactly the items whose own tenant it may read (an untenanted item is visible only to unscoped
   * readers), and a caller with no read grant for the kind at all gets the same 403 a
   * single-resource read would. Without this, every tenant-scoped grant -- the entire {@code
   * tenant-view}/{@code tenant-edit}/{@code tenant-admin} template family included -- was locked
   * out of every {@code GET /<collection>} endpoint outright, because those handlers demanded an
   * unscoped grant that a per-tenant template deliberately never carries.
   *
   * <p>Returns the predicate to filter items by their own tenant, or empty when the 401/403
   * response has already been written. Plaintext mode filters nothing, matching {@link
   * #requireAuthorized}'s own carve-out.
   */
  private Optional<Predicate<Optional<String>>> requireListAuthorized(
      HttpExchange exchange, ResourceKind resource) {
    if (!(exchange instanceof HttpsExchange)) {
      if (auditReadResourceKinds.contains(resource)) {
        recordAuditEvent(
            ANONYMOUS_PRINCIPAL, resource, Verb.READ, Optional.empty(), Optional.empty(), true);
      }
      return Optional.of(itemTenant -> true);
    }
    Optional<Principal> resolved = resolvePrincipal(exchange);
    if (resolved.isEmpty()) {
      respondQuietly(exchange, 401, "authentication required");
      return Optional.empty();
    }
    Principal principal = resolved.get();
    boolean unscopedRead =
        authorizer.authorize(principal, resource, Verb.READ, Optional.empty(), Optional.empty());
    // A filtered listing counts as an allowed read here -- what was allowed is the (possibly
    // empty) subset the caller's own grants cover, mirroring requireAuthorized's own opt-in
    // read-audit behavior for these kinds.
    boolean allowed = unscopedRead || authorizer.hasAnyReadGrant(principal, resource);
    if (auditReadResourceKinds.contains(resource)) {
      recordAuditEvent(principal, resource, Verb.READ, Optional.empty(), Optional.empty(), allowed);
    }
    if (!allowed) {
      respondQuietly(exchange, 403, "forbidden");
      return Optional.empty();
    }
    if (unscopedRead) {
      return Optional.of(itemTenant -> true);
    }
    return Optional.of(
        itemTenant ->
            itemTenant.isPresent()
                && authorizer.authorize(
                    principal, resource, Verb.READ, itemTenant, Optional.empty()));
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
   * Plaintext transport gives every caller the identical unauthenticated identity (see {@link
   * #isOperatorCaller}'s own javadoc) -- there is no way, not even after the fact, to distinguish a
   * legitimate co-tenant from an uninvited caller reaching into someone else's tenant. Rather than
   * quietly allowing shared multi-tenant use with no way to tell callers apart, plaintext mode is
   * treated as explicitly single-tenant: creating a second real tenant is refused outright, the
   * same "reject, don't silently allow" posture every other hard policy in this class already
   * takes. None of {@link Tenant#RESERVED_SYSTEM_TENANT_ID}, {@link Tenant#DEFAULT_TENANT_ID}, or
   * {@link Tenant#HILMIR_BOOKKEEPING_TENANT_ID} counts toward the limit -- all three are
   * platform-reserved bookkeeping tenants, not an operator's own, so neither creating one nor an
   * already-created one being present should trip this guard for a genuine second tenant a caller
   * actually asked for. A no-op for an update to an already-existing tenant (this id itself) and
   * for every mTLS caller, where a real peer identity exists for RBAC to actually check.
   */
  private boolean rejectSecondTenantUnderPlaintext(HttpExchange exchange, String id) {
    if (exchange instanceof HttpsExchange
        || storeClient.getTenant(id).isPresent()
        || id.equals(Tenant.HILMIR_BOOKKEEPING_TENANT_ID)) {
      return false;
    }
    boolean anotherRealTenantExists =
        storeClient.listTenants().stream()
            .anyMatch(
                tenant ->
                    !tenant.id().equals(Tenant.RESERVED_SYSTEM_TENANT_ID)
                        && !tenant.id().equals(Tenant.DEFAULT_TENANT_ID)
                        && !tenant.id().equals(Tenant.HILMIR_BOOKKEEPING_TENANT_ID));
    if (!anotherRealTenantExists) {
      return false;
    }
    respondQuietly(
        exchange,
        403,
        "plaintext mode has no caller identity to distinguish tenants -- only one real tenant may"
            + " exist at a time; use mTLS for real multi-tenancy");
    return true;
  }

  /**
   * True for a caller carrying the bootstrap-level {@code gimle:operators} group -- reusing the
   * exact signal {@link Authorizer#authorize} already special-cases as its implicit cluster-admin
   * bypass, rather than inventing a second notion of "trusted enough."
   *
   * <p>A caller presenting no credential at all is {@link #ANONYMOUS_PRINCIPAL}, which carries no
   * groups, so it is never an operator. That is the whole point of the reserved tenant's veto: it
   * exists precisely because a broad grant must not be enough, and "nobody authenticated" is weaker
   * than any grant, not stronger than all of them. Treating the credential-less case as the one
   * credential that can never be granted would leave the reserved tenant wide open exactly where
   * nothing verifies who is calling.
   */
  private boolean isOperatorCaller(HttpExchange exchange) {
    return callerIdentity(exchange)
        .map(principal -> principal.groups().contains(BuiltinRoles.GROUP_OPERATORS))
        .orElse(false);
  }

  /**
   * The request's effective identity for a group-membership question: whichever credential the
   * request actually presented, or the explicit anonymous principal when it presented none.
   * Distinct from {@link #resolvePrincipal}, which answers only "which credential did this request
   * present" and stays empty when there is none.
   *
   * <p>Privilege follows the credential, not the transport that carried it. Resolving a caller as
   * anonymous merely because the connection is plaintext would discard a session cookie or bearer
   * token the caller genuinely holds, and would contradict {@link #resolvePrincipal}, which honours
   * exactly those credentials on the same request. What must never confer privilege is presenting
   * nothing at all.
   */
  private Optional<Principal> callerIdentity(HttpExchange exchange) {
    return resolvePrincipal(exchange).or(() -> Optional.of(ANONYMOUS_PRINCIPAL));
  }

  /**
   * A verified client certificate wins over a session cookie when both are somehow present (mTLS is
   * the stronger proof) -- in practice only one is ever offered by a given caller (the CLI/node
   * agents never send a session cookie, the console never presents a client certificate). The
   * session-cookie branch's groups come from a live {@code storeClient.getAccount} read, not the
   * token itself -- a session token carries only {@code username} (see {@code SessionTokens}'s own
   * javadoc), so an account's {@code group:} membership, editable independently of its password, is
   * always read fresh rather than baked into a token that could outlive a later group change.
   */
  private Optional<Principal> resolvePrincipal(HttpExchange exchange) {
    // A bearer workload token, when presented, is the request's identity -- deliberately checked
    // before the peer certificate, because the one caller that sends both is a node agent
    // relaying a hosted module's read: the module must act as its own (narrower, deny-by-default)
    // workload principal, never ride the relaying agent's node identity. An invalid or expired
    // bearer resolves nothing at all rather than falling back to the certificate -- an explicit
    // credential that fails must fail, not silently escalate to the transport's broader one.
    Optional<String> bearer = bearerToken(exchange);
    if (bearer.isPresent()) {
      return verifyWorkloadToken(bearer.get());
    }
    Optional<X509Certificate> certificate = peerCertificate(exchange);
    if (certificate.isPresent()) {
      // The portable revocation check: a compromised leaf's serial lands on the store-backed
      // denylist and every request it makes from then on resolves no principal at all -- checked
      // before any authorization runs, the same per-request level-triggered store read the
      // Authorizer itself already makes. Keyed by serial, so a legitimately re-issued certificate
      // for the same identity is untouched.
      String serial = certificateSerial(certificate.get());
      if (storeClient.isCertificateRevoked(serial)) {
        log.warn(
            "rejecting revoked certificate serial {} presented by {}",
            serial,
            certificate.get().getSubjectX500Principal());
        return Optional.empty();
      }
      return Optional.of(Subjects.principalFrom(certificate.get()));
    }
    return sessionCookie(exchange)
        .flatMap(token -> SessionTokens.verify(token, sessionSigningKey))
        .filter(session -> !isSessionRevoked(session))
        .map(
            session ->
                new Principal(
                    session.username(),
                    storeClient
                        .getAccount(session.username())
                        .map(Account::groups)
                        .orElse(Set.of())));
  }

  /**
   * Mirrors the certificate-serial revocation check above for a different credential type: a
   * session token otherwise verifies purely by its own HMAC signature (see {@code SessionTokens}'s
   * own javadoc), so this is the one server-side check standing between a logged- out token and
   * continued access -- {@code handleAuthLogout} is this watermark's only writer.
   */
  private boolean isSessionRevoked(SessionTokens.VerifiedSession session) {
    return session.issuedAtEpochMilli()
        <= storeClient.getSessionRevokedBeforeEpochMilli(session.username());
  }

  /** Lowercase hex, the form {@code openssl x509 -serial} prints -- what operators paste back. */
  private static String certificateSerial(X509Certificate certificate) {
    return certificate.getSerialNumber().toString(16).toLowerCase(Locale.ROOT);
  }

  private static Optional<String> bearerToken(HttpExchange exchange) {
    String header = exchange.getRequestHeaders().getFirst("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      return Optional.empty();
    }
    String token = header.substring("Bearer ".length()).trim();
    return token.isBlank() ? Optional.empty() : Optional.of(token);
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

  /**
   * The cap on any request body this class buffers in memory. Enforced on the bytes as they stream
   * rather than by trusting {@code Content-Length}, which a caller is free to understate or omit
   * entirely. Deliberately not applied to the artifact proxy, which streams jars straight through
   * without ever buffering one (see {@link #handleArtifactsProxy}) and is bounded by Andvari's own
   * upload limit instead.
   */
  private static final long MAX_REQUEST_BODY_BYTES = 4L * 1024 * 1024;

  /**
   * The cap on a single {@code /config/*} value's plaintext. Well below {@link
   * ConfigEntry#MAX_VALUE_BYTES}, the storage row's own ceiling: an encrypted write stores this
   * value's *ciphertext*, so leaving room for that framing here means a value accepted by this
   * check can always actually be persisted, rather than passing it and then failing deeper down
   * with a message about a size the caller never sent. The same reasoning, and the same number, as
   * {@code SecretStore#MAX_VALUE_BYTES}.
   */
  private static final int MAX_CONFIG_VALUE_BYTES = ConfigEntry.MAX_VALUE_BYTES / 2;

  /** Thrown by {@link #readBody} once a request body has streamed past the cap; mapped to 413. */
  private static final class BodyTooLargeException extends RuntimeException {
    BodyTooLargeException(long maxBytes) {
      super("request body exceeds the maximum allowed size of " + maxBytes + " bytes");
    }
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    try (InputStream body =
        new SizeLimitedInputStream(
            exchange.getRequestBody(),
            MAX_REQUEST_BODY_BYTES,
            exceeded -> new BodyTooLargeException(MAX_REQUEST_BODY_BYTES))) {
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
