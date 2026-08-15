package com.gimle.controlplane;

import com.gimle.controlplane.andvari.AndvariClient;
import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.autoscale.AutoscaleReconciler;
import com.gimle.controlplane.fafnir.FafnirClient;
import com.gimle.controlplane.muninn.MuninnClient;
import com.gimle.controlplane.reconcile.CronJobReconciler;
import com.gimle.controlplane.reconcile.DaemonSetReconciler;
import com.gimle.controlplane.reconcile.DeploymentReconciler;
import com.gimle.controlplane.reconcile.HealthReconciler;
import com.gimle.controlplane.reconcile.JobReconciler;
import com.gimle.controlplane.reconcile.QuotaReconciler;
import com.gimle.controlplane.reconcile.ReplicaCountReconciler;
import com.gimle.controlplane.reconcile.StatefulSetReconciler;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.core.banner.GimleBanner;
import com.gimle.core.banner.GimleVersion;
import com.gimle.core.logging.GimleLogging;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.core.web.BundledSpa;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.store.LeaseGrant;
import com.gimle.module.artifact.ArtifactPullCache;
import com.gimle.observability.GimleTracing;
import com.gimle.observability.MuninnShipper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The control plane's entry point: wires a {@link StoreClient} (talks over the network to a {@code
 * gimle-mimir} store cluster, replacing what used to be an in-process {@code StateStore}/{@code
 * RaftNode}), the scheduler, the reconcilers, and the API server together. The reconcilers are
 * independent in what they each compute, and each of the lease renewal, reconcile, and certificate
 * rotation ticks now runs on its own dedicated executor rather than one shared ticker thread -- see
 * {@link #scheduleIndependentTickers} for why. Tick order within a single reconcile pass still
 * matters for same-tick convergence, not for correctness across ticks: {@link
 * ReplicaCountReconciler} and {@link HealthReconciler} release assignments that are missing or
 * unhealthy, and {@link DeploymentReconciler} -- run last -- fills every gap that exists by the
 * time it runs, whether that gap is from a prior tick or this one. {@link AutoscaleReconciler} runs
 * just before it, for the identical reason: {@code DeploymentReconciler} reads whatever effective
 * replica count it just computed, same-tick.
 *
 * <p>The tick itself only ever runs on whichever {@code ApiServer} replica currently holds the
 * {@code reconciler-leader} lease -- a lease-based election, needed because {@code ApiServer}
 * replica count is decoupled from the store cluster's own Raft membership: this process is not
 * itself a Raft participant, so it cannot get "exactly one active controller" for free the way
 * {@code raftNode.isLeader()} used to provide. Backed by {@link
 * StoreClient#tryAcquireOrRenewLease}, a non-replicated, leader-local primitive on the store (the
 * same shape Kubernetes' own {@code coordination.k8s.io/v1 Lease} serves for {@code
 * kube-controller-manager}/{@code kube-scheduler} elections) -- renewed every tick this replica
 * holds it, attempted every tick it doesn't.
 */
public final class ControlPlaneMain {

  private static final Logger log = LoggerFactory.getLogger(ControlPlaneMain.class);

  // Heartbeats every 5s; a node is considered dark after 3 missed ones.
  private static final Duration NODE_DARK_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration RECONCILE_INTERVAL = Duration.ofSeconds(2);
  private static final String RECONCILER_LEASE_NAME = "reconciler-leader";
  // Comfortably longer than RECONCILE_INTERVAL so a brief store hiccup doesn't cost this replica
  // the lease before its very next renewal attempt.
  private static final Duration RECONCILER_LEASE_TTL = Duration.ofSeconds(10);
  private static final Duration MUNINN_SHIP_INTERVAL = Duration.ofSeconds(5);

  private ControlPlaneMain() {}

  public static void main(String[] args) throws IOException {
    GimleBanner.print(
        System.out,
        Map.of(
            "app.name", "Gimlé Control Plane",
            "app.description", "API server, scheduler, reconcilers",
            "app.version", GimleVersion.current()));
    if (args.length < 2) {
      System.err.println(
          "usage: ControlPlaneMain <port> <secretKeyPath> --store-endpoints "
              + "host1:clientPort1,host2:clientPort2,... --fafnir-endpoint host:port"
              + " [--host <hostname>] [--muninn-endpoint host1:port1,host2:port2,...]"
              + " [--andvari-endpoint host:port]");
      System.exit(2);
      return;
    }
    int port = Integer.parseInt(args[0]);
    Path secretKeyFilePath = Path.of(args[1]);
    String selfHost = "127.0.0.1";
    List<SocketAddress> storeEndpoints = List.of();
    String fafnirEndpoint = null;
    String muninnEndpoint = null;
    String andvariEndpoint = null;
    for (int i = 2; i < args.length; i++) {
      if ("--host".equals(args[i]) && i + 1 < args.length) {
        selfHost = args[++i];
      } else if ("--store-endpoints".equals(args[i]) && i + 1 < args.length) {
        storeEndpoints = parseStoreEndpoints(args[++i]);
      } else if ("--fafnir-endpoint".equals(args[i]) && i + 1 < args.length) {
        fafnirEndpoint = args[++i];
      } else if ("--muninn-endpoint".equals(args[i]) && i + 1 < args.length) {
        muninnEndpoint = args[++i];
      } else if ("--andvari-endpoint".equals(args[i]) && i + 1 < args.length) {
        andvariEndpoint = args[++i];
      }
    }
    if (storeEndpoints.isEmpty()) {
      System.err.println("--store-endpoints is required (at least one host:clientPort)");
      System.exit(2);
      return;
    }
    if (fafnirEndpoint == null || fafnirEndpoint.isBlank()) {
      System.err.println("--fafnir-endpoint is required (host:port of a gimle-fafnir replica)");
      System.exit(2);
      return;
    }

    System.setProperty("gimle.process.role", "CONTROLPLANE");
    System.setProperty("gimle.node.id", selfHost + ":" + port);
    Path logRoot = Path.of(System.getProperty("gimle.log.root", "gimle-logs"));
    GimleLogging.attachPlatformFileAppender(logRoot.resolve("controlplane-platform.log"));

    // PLAINTEXT is a deliberate default (see CLAUDE.md's "Not gaps" -- trivial local onboarding
    // matters more here than secure-by-default), not an oversight, but a silent one: nothing else
    // announces that every API call on this port is unauthenticated. One loud line at boot makes
    // the tradeoff visible instead of only discoverable by reading source.
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      log.warn(
          "running with no authentication (gimle.transport.protocol=plaintext) -- every API call"
              + " on this port is unauthenticated; do not expose it beyond a trusted local network."
              + " Set -Dgimle.transport.protocol=tls to require mTLS.");
    }

    StoreClient storeClient = new StoreClient(storeEndpoints);
    FafnirClient fafnirClient = new FafnirClient(fafnirEndpoint);
    // --muninn-endpoint accepts a comma-separated list of Muninn replicas as well as a single
    // endpoint; both muninnClient below and metricsShipper/tracesShipper further down fail over
    // across every configured replica rather than depending on any one of them.
    List<String> muninnEndpoints = MuninnShipper.parseEndpoints(muninnEndpoint);
    // Optional, unlike fafnirClient above -- a cluster with no Muninn endpoint configured simply
    // never gets the /logs/* fallback for a gone node/instance (see MuninnClient's own javadoc).
    MuninnClient muninnClient = muninnEndpoint == null ? null : new MuninnClient(muninnEndpoints);
    // Optional, same posture as muninnClient: a cluster with no Andvari endpoint keeps working
    // on local artifactPath manifests unchanged, and only registry-coordinate manifests need it.
    AndvariClient andvariClient =
        andvariEndpoint == null ? null : new AndvariClient(andvariEndpoint);
    ArtifactResolver artifactResolver =
        andvariClient == null
            ? ArtifactResolver.localOnly()
            : ArtifactResolver.withRegistry(
                andvariClient,
                new ArtifactPullCache(
                    Path.of(System.getProperty("gimle.data.root", "gimle-data"))
                        .resolve("artifact-cache")));

    Scheduler scheduler = new Scheduler();
    DeploymentReconciler deploymentReconciler =
        new DeploymentReconciler(
            storeClient,
            scheduler,
            storeClient,
            NODE_DARK_TIMEOUT,
            Clock.systemUTC(),
            artifactResolver);
    ReplicaCountReconciler replicaCountReconciler =
        new ReplicaCountReconciler(storeClient, NODE_DARK_TIMEOUT, NODE_DARK_TIMEOUT, storeClient);
    HealthReconciler healthReconciler =
        new HealthReconciler(
            storeClient,
            Duration.ofSeconds(2),
            2.0,
            Duration.ofMinutes(1),
            5,
            Duration.ofMinutes(15),
            storeClient);
    AutoscaleReconciler autoscaleReconciler = new AutoscaleReconciler(storeClient, storeClient);
    QuotaReconciler quotaReconciler = new QuotaReconciler(storeClient, storeClient);
    // Touches only its own JobSpec/JobRun store types, invisible to
    // the four reconcilers above by construction (they only ever read Deployment-scoped store
    // methods) -- no interaction with, or dependency on, tick order relative to them.
    JobReconciler jobReconciler =
        new JobReconciler(
            storeClient,
            scheduler,
            storeClient,
            NODE_DARK_TIMEOUT,
            Clock.systemUTC(),
            artifactResolver);
    // A thin generator writing ordinary JobSpecs, never touching
    // JobRun/Scheduler directly -- see the class's own javadoc.
    CronJobReconciler cronJobReconciler =
        new CronJobReconciler(storeClient, storeClient, Clock.systemUTC(), artifactResolver);
    // Touches only its own DaemonSetSpec/DaemonSetAssignment store
    // types, invisible to every reconciler above by construction -- no interaction with, or
    // dependency on, tick order relative to them.
    DaemonSetReconciler daemonSetReconciler =
        new DaemonSetReconciler(
            storeClient,
            scheduler,
            storeClient,
            NODE_DARK_TIMEOUT,
            Clock.systemUTC(),
            artifactResolver);
    // Touches only its own
    // StatefulSetSpec/StatefulSetAssignment store types -- same "invisible to every reconciler
    // above by construction" independence the Job/CronJob/DaemonSet reconcilers already have.
    StatefulSetReconciler statefulSetReconciler =
        new StatefulSetReconciler(
            storeClient,
            scheduler,
            storeClient,
            NODE_DARK_TIMEOUT,
            Clock.systemUTC(),
            artifactResolver);

    ApiServer apiServer =
        new ApiServer(
            storeClient, port, secretKeyFilePath, fafnirClient, muninnClient, artifactResolver);
    apiServer.start();
    String selfApiAddress = selfHost + ":" + apiServer.port();

    // Same --muninn-endpoint value doing double duty: the MuninnClient above reads a gone
    // node/instance's shipped history back, this shipper ships this replica's own request
    // metrics out -- one configured Muninn address, two independent uses against it, rather
    // than a second flag naming the same process.
    MuninnShipper metricsShipper =
        muninnEndpoint == null
            ? null
            : new MuninnShipper(
                muninnEndpoints,
                "/ingest/metrics/CONTROLPLANE/" + selfApiAddress,
                MUNINN_SHIP_INTERVAL);
    if (metricsShipper != null) {
      metricsShipper.startShippingMetrics(apiServer.metrics().registry());
    }
    // The control plane is a genuine RPC-serving process, unlike gimle-agent (see AgentMain's
    // own javadoc on why it deliberately skips tracing installation). Shipped to Muninn when
    // configured, falling back to GimleTracing's existing WorkerMain-established default
    // (LoggingSpanExporter) otherwise -- spans real and correctly parented either way.
    MuninnShipper tracesShipper =
        muninnEndpoint == null
            ? null
            : new MuninnShipper(
                muninnEndpoints,
                "/ingest/traces/CONTROLPLANE/" + selfApiAddress,
                MUNINN_SHIP_INTERVAL);
    if (tracesShipper != null) {
      GimleTracing.installWithMuninnShipping(tracesShipper);
    } else {
      GimleTracing.installDefault();
    }

    AtomicBoolean isReconcilerLeader = new AtomicBoolean(false);

    // Owns the reconciler-leader lease and the lease-gated bootstrap seeding -- both are cheap
    // store calls, kept together and off the reconcile tick's own thread so a stuck tick can
    // never cause this replica to lose (or fail to notice it lost) the lease.
    Runnable leaseTick =
        () -> {
          try {
            LeaseGrant grant =
                storeClient.tryAcquireOrRenewLease(
                    RECONCILER_LEASE_NAME, selfApiAddress, RECONCILER_LEASE_TTL);
            isReconcilerLeader.set(grant.granted());
          } catch (RuntimeException e) {
            isReconcilerLeader.set(false);
            log.warn("reconciler-leader lease attempt failed: {}", e.getMessage());
          }
          // seedBootstrapAccountIfNeeded needs storeClient.propose, which throws if no store
          // leader is reachable -- a no-op the instant an Account already exists, so safe to
          // keep checking every tick forever after, the same level-triggered posture every
          // reconciler here already has.
          if (isReconcilerLeader.get()) {
            apiServer.seedBootstrapAccountIfNeeded();
          }
        };
    Runnable reconcileTickRunnable =
        () -> {
          if (isReconcilerLeader.get()) {
            reconcileTick(
                replicaCountReconciler,
                healthReconciler,
                autoscaleReconciler,
                quotaReconciler,
                deploymentReconciler,
                jobReconciler,
                cronJobReconciler,
                daemonSetReconciler,
                statefulSetReconciler);
          }
        };
    // Unconditional -- not lease-gated like the reconcile tick above: this replica's own
    // certificate needs to stay fresh regardless of whether it currently holds the
    // reconciler-leader lease, per claudedocs/tls-transport-security-design.md §4b. No-op in
    // plaintext mode.
    Runnable certRotationTick = apiServer::checkAndRotateOwnCertificateIfDue;

    List<ScheduledExecutorService> tickers =
        scheduleIndependentTickers(
            leaseTick, reconcileTickRunnable, certRotationTick, RECONCILE_INTERVAL);
    log.info(
        "control plane listening on port {} (self: {}, store endpoints: {}, fafnir: {}, muninn:"
            + " {})",
        apiServer.port(),
        selfApiAddress,
        storeEndpoints,
        fafnirEndpoint,
        muninnEndpoint == null ? "none" : muninnEndpoint);

    Optional<Path> consoleRoot =
        BundledSpa.resolve(ControlPlaneMain.class.getClassLoader(), "console/index.html");
    if (consoleRoot.isPresent()) {
      apiServer.serveConsole(consoleRoot.get());
      log.info("serving bundled web console at /console");
    } else {
      log.info("no bundled web console found on the classpath; /console disabled");
    }

    Runtime.getRuntime()
        .addShutdownHook(
            Thread.ofPlatform()
                .unstarted(
                    () -> {
                      apiServer.close();
                      tickers.forEach(ScheduledExecutorService::shutdownNow);
                      storeClient.close();
                      fafnirClient.close();
                      if (muninnClient != null) {
                        muninnClient.close();
                      }
                      if (andvariClient != null) {
                        andvariClient.close();
                      }
                      if (metricsShipper != null) {
                        metricsShipper.close();
                      }
                      if (tracesShipper != null) {
                        tracesShipper.close();
                      }
                    }));
  }

  /**
   * Schedules {@code leaseTick}, {@code reconcileTick}, and {@code certRotationTick} at a fixed
   * rate, each on its own dedicated single-thread executor -- not one shared ticker. A reconcile
   * tick can block (a hung store connection -- {@code StoreConnection}'s own connect/read timeouts
   * bound this today, but a slow failover sweep across every configured endpoint can still take
   * several seconds) or simply run long, and a single shared thread would let that one stuck tick
   * starve this replica's own certificate rotation and its reconciler-leader lease renewal right
   * along with it. Package-private and separated out of {@link #main} purely so a test can drive it
   * directly with a deliberately blocking/throwing reconcile task, proving the other two keep
   * running without needing a whole control plane process to observe it.
   *
   * <p>Returns the three executors so the caller can shut them down; {@code certRotationTick}'s
   * first run is deliberately delayed by one {@code interval} (matching the other two ticks'
   * steady-state cadence) rather than firing immediately alongside them.
   */
  static List<ScheduledExecutorService> scheduleIndependentTickers(
      Runnable leaseTick, Runnable reconcileTick, Runnable certRotationTick, Duration interval) {
    ScheduledExecutorService leaseExecutor =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-controlplane-lease-renew").unstarted(r));
    ScheduledExecutorService reconcileExecutor =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-controlplane-reconcile-tick").unstarted(r));
    ScheduledExecutorService certRotationExecutor =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-controlplane-cert-rotate").unstarted(r));

    leaseExecutor.scheduleAtFixedRate(leaseTick, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    reconcileExecutor.scheduleAtFixedRate(
        reconcileTick, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    certRotationExecutor.scheduleAtFixedRate(
        certRotationTick, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);

    return List.of(leaseExecutor, reconcileExecutor, certRotationExecutor);
  }

  private static List<SocketAddress> parseStoreEndpoints(String spec) {
    if (spec == null || spec.isBlank()) {
      return List.of();
    }
    List<SocketAddress> endpoints = new ArrayList<>();
    for (String entry : spec.split(",")) {
      int colon = entry.lastIndexOf(':');
      if (colon < 0) {
        throw new IllegalArgumentException(
            "malformed --store-endpoints entry (expected host:clientPort): " + entry);
      }
      String host = entry.substring(0, colon);
      int clientPort = Integer.parseInt(entry.substring(colon + 1));
      endpoints.add(new InetSocketAddress(host, clientPort));
    }
    return endpoints;
  }

  private static void reconcileTick(
      ReplicaCountReconciler replicaCountReconciler,
      HealthReconciler healthReconciler,
      AutoscaleReconciler autoscaleReconciler,
      QuotaReconciler quotaReconciler,
      DeploymentReconciler deploymentReconciler,
      JobReconciler jobReconciler,
      CronJobReconciler cronJobReconciler,
      DaemonSetReconciler daemonSetReconciler,
      StatefulSetReconciler statefulSetReconciler) {
    try {
      replicaCountReconciler.reconcileOnce();
      healthReconciler.reconcileOnce();
      autoscaleReconciler.reconcileOnce();
      quotaReconciler.reconcileOnce();
      deploymentReconciler.reconcileOnce();
      // Runs before jobReconciler, matching autoscaleReconciler's own "policy generator before its
      // consumer" ordering above -- a firing materialized here is then immediately placeable by
      // jobReconciler in this same tick, not left waiting for the next one.
      cronJobReconciler.reconcileOnce();
      jobReconciler.reconcileOnce();
      daemonSetReconciler.reconcileOnce();
      statefulSetReconciler.reconcileOnce();
    } catch (RuntimeException e) {
      log.error("reconcile tick failed: {}", e.getMessage(), e);
    }
  }
}
