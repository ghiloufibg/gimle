package com.gimle.mimir.raft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleRaftException;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.store.StateSnapshot;
import com.gimle.mimir.store.StateStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Real loopback TCP, modeled on {@code gimle-fabric}'s {@code FabricServiceRegistryTest} pattern
 * (bind several real servers on {@code 127.0.0.1:0}, track for teardown, drive real calls). Each
 * peer-to-peer edge is wrapped in a {@link ToggleablePeerClient} so a test can simulate a network
 * partition without tearing down and rebuilding the whole cluster. Every node -- including the ones
 * a cluster starts with -- is built through the address-aware {@link RaftNode} constructor (design
 * plan P1-5), so any node, once elected leader, can call {@link RaftNode#addServer}/{@link
 * RaftNode#removeServer} the same way a real {@code StoreMain} process would.
 */
// Real multi-node in-process Raft cluster with real heartbeat/election-timeout-driven timing
// (awaitLeader/awaitTrue polling against wall-clock @Timeout budgets). Unlike a ResourceLock
// (which only prevents collision with specific other named-resource holders), @Isolated forces
// the whole suite to pause around this class -- CPU contention from unrelated concurrent classes,
// not a shared-resource collision, is what made this flaky once class-level concurrency (root
// pom.xml) started running many classes on this machine's cores at once.
@Isolated
class RaftClusterTest {

  @TempDir Path tempDir;

  private final List<RaftNode> nodes = new ArrayList<>();
  private final List<RaftTransport> transports = new ArrayList<>();
  private final Map<Edge, ToggleablePeerClient> edges = new HashMap<>();

  /**
   * Reverse lookup from a {@link PeerAddress} back to the symbolic "node-N" id it belongs to --
   * shared across every node's {@link RaftPeerClientFactory} closure, and grown (not just built
   * once) by {@link #addNewNode} so a dynamically-added server's address resolves too.
   */
  private final Map<PeerAddress, String> addressToId = new HashMap<>();

  private int clusterCounter;
  private int nodeIdCounter;

  @AfterEach
  void tearDown() {
    for (RaftNode node : nodes) {
      node.close();
    }
    for (RaftTransport transport : transports) {
      transport.close();
    }
  }

  private record Edge(String from, String to) {}

  private record ClusterNode(
      String id,
      RaftNode raftNode,
      RaftTransport transport,
      InetSocketAddress address,
      StateStore store,
      RaftLog raftLog) {}

  /**
   * Forwards to a delegate set only after construction -- breaks the RaftTransport/RaftNode cycle.
   */
  private static final class HandlerRef implements RaftRpcHandler {
    volatile RaftRpcHandler delegate;

    @Override
    public RequestVoteResponse onRequestVote(RequestVote request) {
      return delegate.onRequestVote(request);
    }

    @Override
    public AppendEntriesResponse onAppendEntries(AppendEntries request) {
      return delegate.onAppendEntries(request);
    }

    @Override
    public InstallSnapshotResponse onInstallSnapshot(InstallSnapshot request) {
      return delegate.onInstallSnapshot(request);
    }
  }

  /** Wraps a real {@link PeerConnection} so a test can simulate a bidirectional partition. */
  private static final class ToggleablePeerClient implements RaftPeerClient {
    private final RaftPeerClient delegate;
    volatile boolean blocked;

    ToggleablePeerClient(RaftPeerClient delegate) {
      this.delegate = delegate;
    }

    private void checkNotBlocked() {
      if (blocked) {
        throw new RuntimeException("simulated network partition");
      }
    }

    @Override
    public RequestVoteResponse requestVote(RequestVote request) {
      checkNotBlocked();
      return delegate.requestVote(request);
    }

    @Override
    public AppendEntriesResponse appendEntries(AppendEntries request) {
      checkNotBlocked();
      return delegate.appendEntries(request);
    }

    @Override
    public InstallSnapshotResponse installSnapshot(InstallSnapshot request) {
      checkNotBlocked();
      return delegate.installSnapshot(request);
    }
  }

  private static InetSocketAddress reserveAddress() throws IOException {
    try (ServerSocketChannel probe = ServerSocketChannel.open()) {
      probe.bind(new InetSocketAddress("127.0.0.1", 0));
      return (InetSocketAddress) probe.getLocalAddress();
    }
  }

  /** {@code clientPort} is unused by anything this test exercises -- a fixed placeholder. */
  private static PeerAddress peerAddressOf(InetSocketAddress socketAddress) {
    return new PeerAddress(socketAddress.getHostString(), socketAddress.getPort(), 0);
  }

  /**
   * Builds a {@link RaftPeerClientFactory} for node {@code selfId}: resolves the target {@link
   * PeerAddress} back to a symbolic id via {@link #addressToId}, dials a real {@link
   * PeerConnection}, and registers the resulting {@link ToggleablePeerClient} into {@link #edges}
   * so {@link #partition} keeps working for both originally-wired and later dynamically-added edges
   * alike.
   */
  private RaftPeerClientFactory factoryFor(String selfId) {
    return address -> {
      String peerId = addressToId.get(address);
      if (peerId == null) {
        throw new IllegalStateException("unknown peer address in test fixture: " + address);
      }
      ToggleablePeerClient toggleable =
          new ToggleablePeerClient(
              new PeerConnection(new InetSocketAddress(address.host(), address.raftPort())));
      edges.put(new Edge(selfId, peerId), toggleable);
      return toggleable;
    };
  }

  private ClusterNode buildNode(
      String id, InetSocketAddress address, Map<String, PeerAddress> initialPeers) {
    HandlerRef ref = new HandlerRef();
    RaftTransport transport = new RaftTransport(ref);
    transports.add(transport);

    Path dir = tempDir.resolve("cluster-" + (clusterCounter++) + "-" + id);
    StateStore store = new StateStore(dir.resolve("store"));
    RaftLog raftLog = new RaftLog(dir.resolve("raft"));
    RaftNode node = new RaftNode(id, initialPeers, factoryFor(id), raftLog, store, ignored -> {});
    ref.delegate = node;
    nodes.add(node);
    return new ClusterNode(id, node, transport, address, store, raftLog);
  }

  /**
   * Builds {@code n} fully-wired cluster nodes (every peer edge reachable in principle) but only
   * binds a real listener and starts Raft's own timers for {@code indicesOnlineNow} -- the rest
   * stay reachable-in-address-only until {@link #bringOnline} is called on them explicitly, which
   * is how the InstallSnapshot test simulates a node joining late.
   */
  private List<ClusterNode> buildCluster(int n, Set<Integer> indicesOnlineNow) throws IOException {
    List<String> ids = new ArrayList<>();
    List<InetSocketAddress> addresses = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      ids.add("node-" + (nodeIdCounter++));
      addresses.add(reserveAddress());
    }
    for (int i = 0; i < n; i++) {
      addressToId.put(peerAddressOf(addresses.get(i)), ids.get(i));
    }

    List<ClusterNode> clusterNodes = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Map<String, PeerAddress> initialPeers = new HashMap<>();
      for (int j = 0; j < n; j++) {
        if (j != i) {
          initialPeers.put(ids.get(j), peerAddressOf(addresses.get(j)));
        }
      }
      clusterNodes.add(buildNode(ids.get(i), addresses.get(i), initialPeers));
    }

    for (int i = 0; i < n; i++) {
      if (indicesOnlineNow.contains(i)) {
        bringOnline(clusterNodes.get(i));
      }
    }
    return clusterNodes;
  }

  private void bringOnline(ClusterNode node) throws IOException {
    node.transport().listen(node.address());
    node.raftNode().start();
  }

  /**
   * Adds a brand-new node to the cluster live, via {@code leader}'s own {@link RaftNode#addServer},
   * matching P1-5's etcd-style one-at-a-time membership change. Deliberately never calls {@link
   * RaftNode#start} on the new node: with an empty initial peer set, {@code start()} would elect it
   * leader of its own one-node "cluster" immediately (see that method's own javadoc) -- exactly
   * wrong for a node about to join as a follower. Its RPC handlers work regardless of whether
   * {@code start()} was ever called (neither {@code onAppendEntries} nor {@code onInstallSnapshot}
   * checks {@code running}), so it still correctly catches up once the leader begins replicating to
   * it; it simply never arms its own election timer, which every test using this helper is fine
   * with.
   */
  private ClusterNode addNewNode(ClusterNode leader) throws IOException {
    String id = "node-" + (nodeIdCounter++);
    InetSocketAddress address = reserveAddress();
    PeerAddress peerAddress = peerAddressOf(address);
    addressToId.put(peerAddress, id);
    ClusterNode node = buildNode(id, address, Map.of());
    node.transport().listen(node.address());
    leader.raftNode().addServer(id, peerAddress);
    return node;
  }

  private void partition(String nodeIdA, String nodeIdB) {
    edges.get(new Edge(nodeIdA, nodeIdB)).blocked = true;
    edges.get(new Edge(nodeIdB, nodeIdA)).blocked = true;
  }

  private static void awaitTrue(BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), "condition not met within " + timeout);
  }

  private static ClusterNode awaitLeader(List<ClusterNode> cluster) throws InterruptedException {
    awaitTrue(() -> cluster.stream().anyMatch(c -> c.raftNode().isLeader()), Duration.ofSeconds(5));
    return cluster.stream().filter(c -> c.raftNode().isLeader()).findFirst().orElseThrow();
  }

  @Test
  @Timeout(10)
  void leader_election_converges_to_exactly_one_leader() throws Exception {
    List<ClusterNode> cluster = buildCluster(3, Set.of(0, 1, 2));
    awaitLeader(cluster);
    long leaderCount = cluster.stream().filter(c -> c.raftNode().isLeader()).count();
    assertEquals(1, leaderCount);
  }

  @Test
  @Timeout(10)
  void a_submitted_write_becomes_visible_on_every_replica_after_the_next_append_entries_round()
      throws Exception {
    List<ClusterNode> cluster = buildCluster(3, Set.of(0, 1, 2));
    ClusterNode leader = awaitLeader(cluster);

    leader
        .raftNode()
        .propose(new StateMutation.PutTenant(new Tenant("t1", new ResourceQuota(10, 10, 10))));

    awaitTrue(
        () -> cluster.stream().allMatch(c -> c.store().getTenant("t1").isPresent()),
        Duration.ofSeconds(3));
    for (ClusterNode c : cluster) {
      assertTrue(c.store().getTenant("t1").isPresent());
    }
  }

  @Test
  @Timeout(15)
  void killing_the_leader_triggers_re_election_and_the_new_leader_keeps_serving_writes()
      throws Exception {
    List<ClusterNode> cluster = buildCluster(3, Set.of(0, 1, 2));
    ClusterNode firstLeader = awaitLeader(cluster);

    firstLeader.transport().close();
    firstLeader.raftNode().close();

    List<ClusterNode> remaining = cluster.stream().filter(c -> c != firstLeader).toList();
    ClusterNode newLeader = awaitLeader(remaining);
    assertNotEquals(firstLeader.id(), newLeader.id());

    newLeader
        .raftNode()
        .propose(new StateMutation.PutTenant(new Tenant("t2", new ResourceQuota(20, 20, 20))));
    awaitTrue(
        () -> remaining.stream().allMatch(c -> c.store().getTenant("t2").isPresent()),
        Duration.ofSeconds(3));
  }

  @Test
  @Timeout(20)
  void a_partitioned_minority_cannot_elect_a_leader_or_commit_writes() throws Exception {
    List<ClusterNode> cluster = buildCluster(3, Set.of(0, 1, 2));
    ClusterNode isolated = awaitLeader(cluster);
    List<ClusterNode> majority = cluster.stream().filter(c -> c != isolated).toList();

    partition(isolated.id(), majority.get(0).id());
    partition(isolated.id(), majority.get(1).id());

    ClusterNode newLeader = awaitLeader(majority);
    newLeader
        .raftNode()
        .propose(new StateMutation.PutTenant(new Tenant("t3", new ResourceQuota(1, 1, 1))));
    awaitTrue(
        () -> majority.stream().allMatch(c -> c.store().getTenant("t3").isPresent()),
        Duration.ofSeconds(3));

    assertThrows(
        GimleRaftException.class,
        () ->
            isolated
                .raftNode()
                .propose(
                    new StateMutation.PutTenant(new Tenant("t4", new ResourceQuota(1, 1, 1)))));
    assertTrue(isolated.store().getTenant("t4").isEmpty());
  }

  @Test
  @Timeout(10)
  void a_redirected_write_to_a_follower_returns_the_correct_leader_address() throws Exception {
    List<ClusterNode> cluster = buildCluster(3, Set.of(0, 1, 2));
    ClusterNode leader = awaitLeader(cluster);
    ClusterNode follower = cluster.stream().filter(c -> c != leader).findFirst().orElseThrow();

    GimleRaftException thrown =
        assertThrows(
            GimleRaftException.class,
            () ->
                follower
                    .raftNode()
                    .propose(
                        new StateMutation.PutTenant(new Tenant("t5", new ResourceQuota(1, 1, 1)))));
    assertTrue(
        thrown.getMessage().contains(leader.id()),
        "leader=" + leader.id() + " follower=" + follower.id() + " message=" + thrown.getMessage());
  }

  @Test
  @Timeout(20)
  void a_far_behind_follower_catches_up_via_install_snapshot_not_full_log_replay()
      throws Exception {
    List<ClusterNode> cluster = buildCluster(3, Set.of(0, 1));
    ClusterNode leader = awaitLeader(cluster.subList(0, 2));

    for (int i = 0; i < 5; i++) {
      leader
          .raftNode()
          .propose(new StateMutation.PutTenant(new Tenant("t", new ResourceQuota(i, i, i))));
    }
    long lastIndex = leader.raftLog().lastIndex();
    long termAtLast = leader.raftLog().termAt(lastIndex);
    StateSnapshot snapshot = leader.store().snapshot();
    leader.raftLog().installSnapshot(lastIndex, termAtLast, RaftCodec.encodeSnapshot(snapshot));

    ClusterNode farBehind = cluster.get(2);
    bringOnline(farBehind);

    awaitTrue(
        () ->
            farBehind.store().getTenant("t").isPresent()
                && farBehind.store().getTenant("t").get().quota().maxMemoryBytes() == 4,
        Duration.ofSeconds(10));
    assertTrue(farBehind.raftLog().snapshotLastIncludedIndex() >= lastIndex);
  }

  // ---- P1-5: etcd-style live membership change ----

  @Test
  @Timeout(20)
  void a_three_node_cluster_grows_to_five_live_and_writes_continue_succeeding() throws Exception {
    List<ClusterNode> cluster = buildCluster(3, Set.of(0, 1, 2));
    ClusterNode leader = awaitLeader(cluster);

    ClusterNode fourth = addNewNode(leader);
    ClusterNode fifth = addNewNode(leader);

    leader
        .raftNode()
        .propose(new StateMutation.PutTenant(new Tenant("grown", new ResourceQuota(5, 5, 5))));

    awaitTrue(
        () ->
            cluster.stream().allMatch(c -> c.store().getTenant("grown").isPresent())
                && fourth.store().getTenant("grown").isPresent()
                && fifth.store().getTenant("grown").isPresent(),
        Duration.ofSeconds(10));
  }

  @Test
  @Timeout(20)
  void
      removing_a_server_shrinks_the_quorum_requirement_so_writes_still_succeed_after_losing_a_node()
          throws Exception {
    List<ClusterNode> cluster = buildCluster(4, Set.of(0, 1, 2, 3));
    ClusterNode leader = awaitLeader(cluster);
    List<ClusterNode> followers = cluster.stream().filter(c -> c != leader).toList();

    // Down to a 3-member cluster: majority becomes 2 (of leader + 2 remaining followers), not 3.
    leader.raftNode().removeServer(followers.get(0).id());
    // The removed node itself doesn't learn it was removed (a known, deliberate limitation of
    // this etcd-style reduction, not full joint consensus's disruption-mitigation machinery --
    // see RaftLogPayload's own javadoc) -- closed here purely so it can't keep calling
    // RequestVote against the survivors and disrupt this test's own timing.
    followers.get(0).transport().close();
    followers.get(0).raftNode().close();

    // Kill one more of the now-3-member configuration's REAL remaining members: only the leader
    // and one follower are left standing. If quorum math hadn't actually shrunk (still requiring
    // 3 acks out of a stale 4-member view), this write could never commit with just 2 nodes alive.
    ClusterNode killed = followers.get(1);
    killed.transport().close();
    killed.raftNode().close();

    leader
        .raftNode()
        .propose(new StateMutation.PutTenant(new Tenant("shrunk", new ResourceQuota(3, 3, 3))));

    ClusterNode survivor = followers.get(2);
    awaitTrue(() -> survivor.store().getTenant("shrunk").isPresent(), Duration.ofSeconds(5));
  }
}
