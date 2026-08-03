package com.gimle.controlplane;

import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.autoscale.AutoscaleReconciler;
import com.gimle.controlplane.raft.PeerConnection;
import com.gimle.controlplane.raft.RaftLog;
import com.gimle.controlplane.raft.RaftNode;
import com.gimle.controlplane.raft.RaftPeerClient;
import com.gimle.controlplane.raft.RaftTransport;
import com.gimle.controlplane.reconcile.DeploymentReconciler;
import com.gimle.controlplane.reconcile.HealthReconciler;
import com.gimle.controlplane.reconcile.QuotaReconciler;
import com.gimle.controlplane.reconcile.ReplicaCountReconciler;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.logging.GimleLogging;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The control plane's entry point: wires the Raft-replicated state store, scheduler, the five
 * reconcilers, and the API server together. The reconcilers are independent in what they each
 * compute, but share one ticker thread here rather than separate timers -- the same "one shared
 * ticker, independent per-check logic" shape {@code gimle-worker}'s {@code ProbeLoop} already
 * established; fixed-interval ticking is what a level-triggered design needs, not literal thread
 * independence. Tick order matters for same-tick convergence, not for correctness across ticks:
 * {@link ReplicaCountReconciler} and {@link HealthReconciler} release assignments that are missing
 * or unhealthy, and {@link DeploymentReconciler} -- run last -- fills every gap that exists by the
 * time it runs, whether that gap is from a prior tick or this one. {@link AutoscaleReconciler} runs
 * just before it, for the identical reason: {@code DeploymentReconciler} reads whatever effective
 * replica count it just computed, same-tick. The tick itself only ever runs on whichever replica is
 * currently Raft leader: a follower's local state may be momentarily behind and about to be
 * overwritten by replication, and any mutation a reconciler produces must go through consensus for
 * the other replicas to ever see it.
 */
public final class ControlPlaneMain {

  private static final Logger log = LoggerFactory.getLogger(ControlPlaneMain.class);

  // Heartbeats every 5s; a node is considered dark after 3 missed ones.
  private static final Duration NODE_DARK_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration RECONCILE_INTERVAL = Duration.ofSeconds(2);

  private ControlPlaneMain() {}

  private record PeerSpec(String host, int raftPort, int apiPort) {}

  public static void main(String[] args) throws IOException {
    if (args.length < 3) {
      System.err.println(
          "usage: ControlPlaneMain <port> <stateDir> <raftPort> [--host <hostname>] "
              + "[--peers host1:raftPort1:apiPort1,host2:raftPort2:apiPort2,...]");
      System.exit(2);
      return;
    }
    int port = Integer.parseInt(args[0]);
    Path stateDir = Path.of(args[1]);
    int raftPort = Integer.parseInt(args[2]);
    String selfHost = "127.0.0.1";
    List<PeerSpec> peerSpecs = List.of();
    for (int i = 3; i < args.length; i++) {
      if ("--host".equals(args[i]) && i + 1 < args.length) {
        selfHost = args[++i];
      } else if ("--peers".equals(args[i]) && i + 1 < args.length) {
        peerSpecs = parsePeers(args[++i]);
      }
    }
    String selfRaftId = selfHost + ":" + raftPort;

    System.setProperty("gimle.process.role", "CONTROLPLANE");
    System.setProperty("gimle.node.id", selfRaftId);
    Path logRoot = Path.of(System.getProperty("gimle.log.root", "gimle-logs"));
    GimleLogging.attachPlatformFileAppender(logRoot.resolve("controlplane-platform.log"));

    StateStore store = new StateStore(stateDir);
    RaftLog raftLog = new RaftLog(stateDir.resolve("raft"));

    Map<String, RaftPeerClient> raftPeers = new LinkedHashMap<>();
    Map<String, String> peerApiAddresses = new LinkedHashMap<>();
    for (PeerSpec peer : peerSpecs) {
      String peerRaftId = peer.host() + ":" + peer.raftPort();
      raftPeers.put(
          peerRaftId, new PeerConnection(new InetSocketAddress(peer.host(), peer.raftPort())));
      peerApiAddresses.put(peerRaftId, peer.host() + ":" + peer.apiPort());
    }

    RaftNode raftNode = new RaftNode(selfRaftId, raftPeers, raftLog, store);
    RaftTransport raftTransport = new RaftTransport(raftNode);
    raftTransport.listen(new InetSocketAddress(raftPort));
    raftNode.start();

    Scheduler scheduler = new Scheduler();
    DeploymentReconciler deploymentReconciler =
        new DeploymentReconciler(store, scheduler, raftNode);
    ReplicaCountReconciler replicaCountReconciler =
        new ReplicaCountReconciler(store, NODE_DARK_TIMEOUT, NODE_DARK_TIMEOUT, raftNode);
    HealthReconciler healthReconciler =
        new HealthReconciler(
            store,
            Duration.ofSeconds(2),
            2.0,
            Duration.ofMinutes(1),
            5,
            Duration.ofMinutes(15),
            raftNode);
    AutoscaleReconciler autoscaleReconciler = new AutoscaleReconciler(store, raftNode);
    QuotaReconciler quotaReconciler = new QuotaReconciler(store, raftNode);

    ScheduledExecutorService ticker =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-controlplane-reconcile-tick").unstarted(r));
    ticker.scheduleAtFixedRate(
        () -> {
          if (raftNode.isLeader()) {
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

    ApiServer apiServer =
        new ApiServer(store, port, stateDir.resolve("secret.key"), raftNode, peerApiAddresses);
    apiServer.start();

    // Unconditional -- not leader-gated like reconcileTick above: a follower's own certificate
    // needs to stay fresh too, per claudedocs/tls-transport-security-design.md §4b. No-op in
    // plaintext mode (checkAndRotateOwnCertificateIfDue returns immediately).
    ticker.scheduleAtFixedRate(
        apiServer::checkAndRotateOwnCertificateIfDue,
        RECONCILE_INTERVAL.toMillis(),
        RECONCILE_INTERVAL.toMillis(),
        TimeUnit.MILLISECONDS);
    log.info(
        "control plane listening on port {} (raft: {}, state: {})",
        apiServer.port(),
        selfRaftId,
        stateDir);

    Optional<Path> consoleRoot = BundledConsole.resolve(ControlPlaneMain.class.getClassLoader());
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
                      raftTransport.close();
                      raftNode.close();
                    }));
  }

  private static List<PeerSpec> parsePeers(String spec) {
    if (spec == null || spec.isBlank()) {
      return List.of();
    }
    List<PeerSpec> peers = new ArrayList<>();
    for (String entry : spec.split(",")) {
      String[] parts = entry.split(":");
      if (parts.length != 3) {
        throw new IllegalArgumentException(
            "malformed --peers entry (expected host:raftPort:apiPort): " + entry);
      }
      peers.add(new PeerSpec(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
    }
    return peers;
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
