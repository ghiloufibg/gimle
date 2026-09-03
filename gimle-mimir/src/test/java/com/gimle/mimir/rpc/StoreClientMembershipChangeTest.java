package com.gimle.mimir.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleRaftException;
import com.gimle.mimir.raft.PeerAddress;
import com.gimle.mimir.raft.PeerConnection;
import com.gimle.mimir.raft.RaftLog;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.raft.RaftPeerClientFactory;
import com.gimle.mimir.raft.RaftTransport;
import com.gimle.mimir.store.StateStore;
import com.gimle.testkit.Await;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * {@code StoreClient#addServer}/{@code #removeServer} driven over a real, address-aware, real-TCP
 * {@code StoreNode}/{@code RaftNode} cluster -- the exact protocol surface {@code hilmir store
 * add}/{@code remove} sit on top of, one layer below {@link
 * com.gimle.hilmir.store.StoreCommandsClusterTest}'s own CLI-process-level coverage. Proves the M45
 * fix: a leader that genuinely evaluates {@code addServer}/{@code removeServer} and rejects it for
 * a real, deterministic reason must answer with that reason, never a self-referential {@code
 * NotLeader} that sends the client chasing a redirect back to the very node that already answered.
 */
@Isolated
class StoreClientMembershipChangeTest {

  @TempDir Path tempDir;

  private record Node(
      String id,
      RaftNode raftNode,
      RaftTransport raftTransport,
      StoreTransport storeTransport,
      SocketAddress clientAddress) {}

  private final List<Node> nodes = new ArrayList<>();
  private StoreClient client;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
    for (Node node : nodes) {
      node.storeTransport().close();
      node.raftTransport().close();
      node.raftNode().close();
    }
  }

  private static InetSocketAddress reserveAddress() throws IOException {
    try (ServerSocketChannel probe = ServerSocketChannel.open()) {
      probe.bind(new InetSocketAddress("127.0.0.1", 0));
      return (InetSocketAddress) probe.getLocalAddress();
    }
  }

  /** Builds an {@code n}-node cluster of live, addressable, address-aware store replicas. */
  private List<Node> buildCluster(int n, Map<String, String> sharedRaftIdToClientAddress)
      throws IOException {
    List<InetSocketAddress> raftAddrs = new ArrayList<>();
    List<InetSocketAddress> clientAddrs = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      raftAddrs.add(reserveAddress());
      clientAddrs.add(reserveAddress());
    }
    for (int i = 0; i < n; i++) {
      sharedRaftIdToClientAddress.put(
          raftAddrs.get(i).getHostString() + ":" + raftAddrs.get(i).getPort(),
          clientAddrs.get(i).getHostString() + ":" + clientAddrs.get(i).getPort());
    }

    List<Node> built = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      String selfId = raftAddrs.get(i).getHostString() + ":" + raftAddrs.get(i).getPort();
      PeerAddress selfAddress =
          new PeerAddress(
              raftAddrs.get(i).getHostString(),
              raftAddrs.get(i).getPort(),
              clientAddrs.get(i).getPort());
      Map<String, PeerAddress> initialPeers = new LinkedHashMap<>();
      for (int j = 0; j < n; j++) {
        if (j != i) {
          initialPeers.put(
              raftAddrs.get(j).getHostString() + ":" + raftAddrs.get(j).getPort(),
              new PeerAddress(
                  raftAddrs.get(j).getHostString(),
                  raftAddrs.get(j).getPort(),
                  clientAddrs.get(j).getPort()));
        }
      }
      Node node =
          startNode(selfId, selfAddress, initialPeers, sharedRaftIdToClientAddress, "node-" + i);
      built.add(node);
    }
    nodes.addAll(built);
    return built;
  }

  private Node startNode(
      String selfId,
      PeerAddress selfAddress,
      Map<String, PeerAddress> initialPeers,
      Map<String, String> raftIdToClientAddress,
      String dirName)
      throws IOException {
    RaftPeerClientFactory factory =
        addr -> new PeerConnection(new InetSocketAddress(addr.host(), addr.raftPort()));
    StateStore store = new StateStore();
    RaftLog raftLog = new RaftLog(tempDir.resolve(dirName).resolve("raft"));
    RaftNode raftNode =
        new RaftNode(
            selfId,
            selfAddress,
            initialPeers,
            factory,
            raftLog,
            store,
            peers ->
                peers.forEach(
                    (peerId, address) ->
                        raftIdToClientAddress.put(peerId, address.clientAddress())));
    RaftTransport raftTransport = new RaftTransport(raftNode);
    raftTransport.listen(new InetSocketAddress(selfAddress.host(), selfAddress.raftPort()));
    StoreNode storeNode = new StoreNode(raftNode, store, raftIdToClientAddress);
    StoreTransport storeTransport = new StoreTransport(storeNode);
    storeTransport.listen(new InetSocketAddress(selfAddress.host(), selfAddress.clientPort()));
    raftNode.start();
    return new Node(
        selfId,
        raftNode,
        raftTransport,
        storeTransport,
        new InetSocketAddress(selfAddress.host(), selfAddress.clientPort()));
  }

  private static List<SocketAddress> clientAddressesOf(List<Node> cluster) {
    return cluster.stream().map(Node::clientAddress).toList();
  }

  private static void addServerWithRetry(
      StoreClient client, String peerId, PeerAddress address, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      try {
        client.addServer(peerId, address);
        return;
      } catch (GimleRaftException e) {
        if (System.nanoTime() >= deadline) {
          throw e;
        }
        Thread.sleep(50);
      }
    }
  }

  private static void removeServerWithRetry(StoreClient client, String peerId, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      try {
        client.removeServer(peerId);
        return;
      } catch (GimleRaftException e) {
        if (System.nanoTime() >= deadline) {
          throw e;
        }
        Thread.sleep(50);
      }
    }
  }

  @Test
  @Timeout(30)
  void a_deterministic_rejection_from_the_real_leader_reports_its_real_reason_not_unreachable()
      throws Exception {
    Map<String, String> raftIdToClientAddress = new ConcurrentHashMap<>();
    List<Node> cluster = buildCluster(1, raftIdToClientAddress);
    Await.until(() -> cluster.get(0).raftNode().isLeader(), Duration.ofSeconds(5));

    client = new StoreClient(clientAddressesOf(cluster));

    // The lone node is genuinely the leader and genuinely evaluates this request -- "not a
    // cluster member" is a real, deterministic answer, never resolved by retrying elsewhere. Before
    // the fix, StoreNode.handleRemoveServer mapped this (and every other GimleRaftException) onto a
    // NotLeader response with a self-referential hint, sending the client chasing a pointless
    // redirect back to the exact node that already answered until it gave up and reported the
    // misleading, generic "no reachable store leader" instead of this real reason.
    GimleRaftException thrown =
        assertThrows(GimleRaftException.class, () -> client.removeServer("127.0.0.1:59999"));
    assertTrue(thrown.getMessage().contains("not a cluster member"), thrown.getMessage());
    assertFalse(thrown.getMessage().contains("no reachable store leader"), thrown.getMessage());
  }

  @Test
  @Timeout(40)
  void grows_to_four_then_removes_an_original_peer_through_the_real_client_and_protocol()
      throws Exception {
    Map<String, String> raftIdToClientAddress = new ConcurrentHashMap<>();
    List<Node> originalCluster = buildCluster(3, raftIdToClientAddress);
    Await.until(
        () -> originalCluster.stream().anyMatch(n -> n.raftNode().isLeader()),
        Duration.ofSeconds(5));

    // Deliberately built with ONLY the three original endpoints -- mirroring an operator's
    // topology file that was never updated with the newly-added fourth machine's own address, the
    // exact shape M45 reported failing against.
    List<SocketAddress> originalOnlyEndpoints = clientAddressesOf(originalCluster);
    client = new StoreClient(originalOnlyEndpoints);

    InetSocketAddress fourthRaft = reserveAddress();
    InetSocketAddress fourthClient = reserveAddress();
    String fourthId = fourthRaft.getHostString() + ":" + fourthRaft.getPort();
    PeerAddress fourthAddress =
        new PeerAddress(fourthRaft.getHostString(), fourthRaft.getPort(), fourthClient.getPort());
    Node fourth =
        startNode(fourthId, fourthAddress, Map.of(), raftIdToClientAddress, "node-fourth");
    nodes.add(fourth);

    addServerWithRetry(client, fourthId, fourthAddress, Duration.ofSeconds(10));
    assertTrue(
        client.status().memberIds().contains(fourthId), client.status().memberIds()::toString);

    // Give the fresh learner a real chance to catch up and be auto-promoted to a full voter before
    // touching membership again.
    Await.until(
        () -> !client.status().memberIds().isEmpty() && client.status().memberIds().size() == 4,
        Duration.ofSeconds(5));

    // Any original peer that isn't the current leader -- removing a node's own self is a
    // different, narrower case (rejected outright, since a node never lists itself among its own
    // peers) this test isn't about.
    String currentLeaderId = client.status().leaderId();
    String originalPeerId =
        originalCluster.stream()
            .map(Node::id)
            .filter(id -> !id.equals(currentLeaderId))
            .findFirst()
            .orElseThrow();
    removeServerWithRetry(client, originalPeerId, Duration.ofSeconds(15));

    StoreRpc.StatusResult status = client.status();
    assertEquals(3, status.memberIds().size());
    assertFalse(status.memberIds().contains(originalPeerId), status.memberIds()::toString);
    assertTrue(status.memberIds().contains(fourthId), status.memberIds()::toString);
  }
}
