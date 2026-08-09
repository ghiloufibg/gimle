package com.gimle.mimir;

import com.gimle.core.logging.GimleLogging;
import com.gimle.core.tls.TlsSettings;
import com.gimle.mimir.raft.PeerAddress;
import com.gimle.mimir.raft.PeerConnection;
import com.gimle.mimir.raft.RaftLog;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.raft.RaftPeerClientFactory;
import com.gimle.mimir.raft.RaftTransport;
import com.gimle.mimir.rpc.StoreNode;
import com.gimle.mimir.rpc.StoreTransport;
import com.gimle.mimir.store.StateStore;
import com.gimle.pki.OwnCertificateRotator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The store process's entry point (etcd-store-extraction design doc): wires the Raft-replicated
 * {@code StateStore}, the peer-to-peer {@code RaftTransport}, and the client-facing {@code
 * StoreNode}/{@code StoreTransport} together -- everything {@code ControlPlaneMain} used to
 * construct itself before the split, minus anything API-server-shaped (no {@code Scheduler}, no
 * reconcilers, no HTTP surface). Deliberately mirrors {@code ControlPlaneMain}'s own
 * argument-parsing and ticker shape for a reader already familiar with that class.
 */
public final class StoreMain {

  private static final Logger log = LoggerFactory.getLogger(StoreMain.class);

  private static final Duration CERT_ROTATION_CHECK_INTERVAL = Duration.ofSeconds(2);

  private StoreMain() {}

  private record PeerSpec(String host, int raftPort, int clientPort) {}

  public static void main(String[] args) throws IOException {
    if (args.length < 3) {
      System.err.println(
          "usage: StoreMain <stateDir> <raftPort> <clientPort> [--host <hostname>] "
              + "[--peers host1:raftPort1:clientPort1,host2:raftPort2:clientPort2,...] "
              + "[--csr-endpoint <host:port>]");
      System.exit(2);
      return;
    }
    Path stateDir = Path.of(args[0]);
    int raftPort = Integer.parseInt(args[1]);
    int clientPort = Integer.parseInt(args[2]);
    String selfHost = "127.0.0.1";
    List<PeerSpec> peerSpecs = List.of();
    URI csrEndpoint = null;
    for (int i = 3; i < args.length; i++) {
      if ("--host".equals(args[i]) && i + 1 < args.length) {
        selfHost = args[++i];
      } else if ("--peers".equals(args[i]) && i + 1 < args.length) {
        peerSpecs = parsePeers(args[++i]);
      } else if ("--csr-endpoint".equals(args[i]) && i + 1 < args.length) {
        csrEndpoint = URI.create("https://" + args[++i] + "/bootstrap/csr");
      }
    }
    String selfRaftId = selfHost + ":" + raftPort;

    System.setProperty("gimle.process.role", "STORE");
    System.setProperty("gimle.node.id", selfRaftId);
    Path logRoot = Path.of(System.getProperty("gimle.log.root", "gimle-logs"));
    GimleLogging.attachPlatformFileAppender(logRoot.resolve("mimir-platform.log"));

    StateStore store = new StateStore(stateDir);
    RaftLog raftLog = new RaftLog(stateDir.resolve("raft"));

    // Bootstrap configuration only, per P1-5's etcd-style membership change: peers is where a
    // brand-new cluster starts, not a fixed configuration for its lifetime -- `gimle-cli`'s
    // add-peer/remove-peer surface (StoreClient#addServer) grows or shrinks it afterward. Both
    // maps are mutable and live: RaftNode's membershipListener keeps raftIdToClientAddress current
    // as membership changes, and StoreNode holds the same reference rather than a defensive copy.
    Map<String, PeerAddress> raftPeers = new LinkedHashMap<>();
    Map<String, String> raftIdToClientAddress = new ConcurrentHashMap<>();
    for (PeerSpec peer : peerSpecs) {
      raftPeers.put(
          peer.host() + ":" + peer.raftPort(),
          new PeerAddress(peer.host(), peer.raftPort(), peer.clientPort()));
    }
    raftIdToClientAddress.put(selfRaftId, selfHost + ":" + clientPort);

    RaftPeerClientFactory peerClientFactory =
        address -> new PeerConnection(new InetSocketAddress(address.host(), address.raftPort()));
    RaftNode raftNode =
        new RaftNode(
            selfRaftId,
            raftPeers,
            peerClientFactory,
            raftLog,
            store,
            peers ->
                peers.forEach(
                    (peerId, address) ->
                        raftIdToClientAddress.put(peerId, address.clientAddress())));
    RaftTransport raftTransport = new RaftTransport(raftNode);
    raftTransport.listen(new InetSocketAddress(raftPort));
    raftNode.start();

    StoreNode storeNode = new StoreNode(raftNode, store, raftIdToClientAddress);
    StoreTransport storeTransport = new StoreTransport(storeNode);
    storeTransport.listen(new InetSocketAddress(clientPort));

    URI finalCsrEndpoint = csrEndpoint;
    ScheduledExecutorService ticker =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-mimir-cert-rotation-tick").unstarted(r));
    // Unconditional, not leader-gated -- every store replica's own certificate needs to stay
    // fresh, matching ControlPlaneMain's identical posture for ApiServer's own rotation.
    ticker.scheduleAtFixedRate(
        () -> {
          boolean rotated =
              OwnCertificateRotator.checkAndRotateIfDue(TlsSettings.fromConfig(), finalCsrEndpoint);
          if (rotated) {
            raftTransport.reloadTlsMaterial();
            storeTransport.reloadTlsMaterial();
          }
        },
        CERT_ROTATION_CHECK_INTERVAL.toMillis(),
        CERT_ROTATION_CHECK_INTERVAL.toMillis(),
        TimeUnit.MILLISECONDS);

    log.info(
        "store node listening on client port {} (raft: {}, state: {})",
        clientPort,
        selfRaftId,
        stateDir);

    Runtime.getRuntime()
        .addShutdownHook(
            Thread.ofPlatform()
                .unstarted(
                    () -> {
                      storeTransport.close();
                      ticker.shutdownNow();
                      raftTransport.close();
                      raftNode.close();
                    }));

    // Every listener/ticker thread started above is a virtual thread, which the JVM always treats
    // as a daemon -- unlike ApiServer (kept alive by HttpServer#start's own non-daemon dispatch
    // thread) or AgentMain (an explicit loop on the main thread itself), nothing here anchors the
    // process. Without this, main() returns immediately and the JVM exits right behind it, tearing
    // the listeners back down before a single client connection could ever land. Released only by
    // process termination (SIGTERM/kill), at which point the shutdown hook above already runs.
    try {
      new CountDownLatch(1).await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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
            "malformed --peers entry (expected host:raftPort:clientPort): " + entry);
      }
      peers.add(new PeerSpec(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
    }
    return peers;
  }
}
