package com.gimle.controlplane;

import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.autoscale.AutoscaleReconciler;
import com.gimle.controlplane.fafnir.FafnirClient;
import com.gimle.controlplane.muninn.MuninnClient;
import com.gimle.controlplane.reconcile.DeploymentReconciler;
import com.gimle.controlplane.reconcile.HealthReconciler;
import com.gimle.controlplane.reconcile.QuotaReconciler;
import com.gimle.controlplane.reconcile.ReplicaCountReconciler;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.core.banner.GimleBanner;
import com.gimle.core.banner.GimleVersion;
import com.gimle.core.logging.GimleLogging;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.core.web.BundledSpa;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.store.LeaseGrant;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
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
 * The control plane's entry point: wires a {@link StoreClient} (etcd-store-extraction design doc --
 * talks over the network to a {@code gimle-mimir} store cluster, replacing what used to be an
 * in-process {@code StateStore}/{@code RaftNode}), the scheduler, the five reconcilers, and the API
 * server together. The reconcilers are independent in what they each compute, but share one ticker
 * thread here rather than separate timers -- the same "one shared ticker, independent per-check
 * logic" shape {@code gimle-worker}'s {@code ProbeLoop} already established; fixed-interval ticking
 * is what a level-triggered design needs, not literal thread independence. Tick order matters for
 * same-tick convergence, not for correctness across ticks: {@link ReplicaCountReconciler} and
 * {@link HealthReconciler} release assignments that are missing or unhealthy, and {@link
 * DeploymentReconciler} -- run last -- fills every gap that exists by the time it runs, whether
 * that gap is from a prior tick or this one. {@link AutoscaleReconciler} runs just before it, for
 * the identical reason: {@code DeploymentReconciler} reads whatever effective replica count it just
 * computed, same-tick.
 *
 * <p>The tick itself only ever runs on whichever {@code ApiServer} replica currently holds the
 * {@code reconciler-leader} lease -- a lease-based election (design decision made when the store
 * extraction decoupled {@code ApiServer} replica count from the store cluster's own Raft
 * membership: this process is no longer itself a Raft participant, so it can no longer get "exactly
 * one active controller" for free the way {@code raftNode.isLeader()} used to provide). Backed by
 * {@link StoreClient#tryAcquireOrRenewLease}, a non-replicated, leader-local primitive on the store
 * (the same shape Kubernetes' own {@code coordination.k8s.io/v1 Lease} serves for {@code
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
              + " [--host <hostname>] [--muninn-endpoint host:port]");
      System.exit(2);
      return;
    }
    int port = Integer.parseInt(args[0]);
    Path secretKeyFilePath = Path.of(args[1]);
    String selfHost = "127.0.0.1";
    List<SocketAddress> storeEndpoints = List.of();
    String fafnirEndpoint = null;
    String muninnEndpoint = null;
    for (int i = 2; i < args.length; i++) {
      if ("--host".equals(args[i]) && i + 1 < args.length) {
        selfHost = args[++i];
      } else if ("--store-endpoints".equals(args[i]) && i + 1 < args.length) {
        storeEndpoints = parseStoreEndpoints(args[++i]);
      } else if ("--fafnir-endpoint".equals(args[i]) && i + 1 < args.length) {
        fafnirEndpoint = args[++i];
      } else if ("--muninn-endpoint".equals(args[i]) && i + 1 < args.length) {
        muninnEndpoint = args[++i];
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
    // Optional, unlike fafnirClient above -- a cluster with no Muninn endpoint configured simply
    // never gets the /logs/* fallback for a gone node/instance (see MuninnClient's own javadoc).
    MuninnClient muninnClient = muninnEndpoint == null ? null : new MuninnClient(muninnEndpoint);

    Scheduler scheduler = new Scheduler();
    DeploymentReconciler deploymentReconciler =
        new DeploymentReconciler(storeClient, scheduler, storeClient);
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

    ApiServer apiServer =
        new ApiServer(storeClient, port, secretKeyFilePath, fafnirClient, muninnClient);
    apiServer.start();
    String selfApiAddress = selfHost + ":" + apiServer.port();

    ScheduledExecutorService ticker =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-controlplane-reconcile-tick").unstarted(r));
    AtomicBoolean isReconcilerLeader = new AtomicBoolean(false);
    ticker.scheduleAtFixedRate(
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
          if (isReconcilerLeader.get()) {
            reconcileTick(
                replicaCountReconciler,
                healthReconciler,
                autoscaleReconciler,
                quotaReconciler,
                deploymentReconciler);
          }
        },
        0,
        RECONCILE_INTERVAL.toMillis(),
        TimeUnit.MILLISECONDS);

    // Unconditional -- not lease-gated like reconcileTick above: this replica's own certificate
    // needs to stay fresh regardless of whether it currently holds the reconciler-leader lease,
    // per claudedocs/tls-transport-security-design.md §4b. No-op in plaintext mode.
    ticker.scheduleAtFixedRate(
        apiServer::checkAndRotateOwnCertificateIfDue,
        RECONCILE_INTERVAL.toMillis(),
        RECONCILE_INTERVAL.toMillis(),
        TimeUnit.MILLISECONDS);
    // Lease-gated like reconcileTick above (seedBootstrapAccountIfNeeded needs storeClient.propose,
    // which throws if no store leader is reachable) -- a no-op the instant an Account already
    // exists, so safe to keep checking every tick forever after, the same level-triggered posture
    // every reconciler here already has.
    ticker.scheduleAtFixedRate(
        () -> {
          if (isReconcilerLeader.get()) {
            apiServer.seedBootstrapAccountIfNeeded();
          }
        },
        0,
        RECONCILE_INTERVAL.toMillis(),
        TimeUnit.MILLISECONDS);
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
                      ticker.shutdownNow();
                      storeClient.close();
                      fafnirClient.close();
                      if (muninnClient != null) {
                        muninnClient.close();
                      }
                    }));
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
      DeploymentReconciler deploymentReconciler) {
    try {
      replicaCountReconciler.reconcileOnce();
      healthReconciler.reconcileOnce();
      autoscaleReconciler.reconcileOnce();
      quotaReconciler.reconcileOnce();
      deploymentReconciler.reconcileOnce();
    } catch (RuntimeException e) {
      log.error("reconcile tick failed: {}", e.getMessage(), e);
    }
  }
}
