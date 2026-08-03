package com.gimle.controlplane.raft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.store.StateSnapshot;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.exception.GimleRaftException;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
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
 * partition without tearing down and rebuilding the whole cluster.
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
  private int clusterCounter;

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
      ids.add("node-" + i);
      addresses.add(reserveAddress());
    }

    List<ClusterNode> clusterNodes = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      HandlerRef ref = new HandlerRef();
      RaftTransport transport = new RaftTransport(ref);
      transports.add(transport);

      Map<String, RaftPeerClient> peers = new HashMap<>();
      for (int j = 0; j < n; j++) {
        if (j == i) {
          continue;
        }
        ToggleablePeerClient toggleable =
            new ToggleablePeerClient(new PeerConnection(addresses.get(j)));
        edges.put(new Edge(ids.get(i), ids.get(j)), toggleable);
        peers.put(ids.get(j), toggleable);
      }

      Path dir = tempDir.resolve("cluster-" + (clusterCounter++) + "-node-" + i);
      StateStore store = new StateStore(dir.resolve("store"));
      RaftLog raftLog = new RaftLog(dir.resolve("raft"));
      RaftNode node = new RaftNode(ids.get(i), peers, raftLog, store);
      ref.delegate = node;
      nodes.add(node);
      clusterNodes.add(
          new ClusterNode(ids.get(i), node, transport, addresses.get(i), store, raftLog));
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
    assertTrue(thrown.getMessage().contains(leader.id()));
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
}
