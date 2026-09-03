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
import com.gimle.testkit.Await;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Real loopback TCP, modeled on {@code gimle-fabric}'s {@code FabricServiceRegistryTest} pattern
 * (bind several real servers on {@code 127.0.0.1:0}, track for teardown, drive real calls). Each
 * peer-to-peer edge is wrapped in a {@link ToggleablePeerClient} so a test can simulate a network
 * partition without tearing down and rebuilding the whole cluster. Every node -- including the ones
 * a cluster starts with -- is built through the address-aware {@link RaftNode} constructor, so any
 * node, once elected leader, can call {@link RaftNode#addServer}/{@link RaftNode#removeServer} the
 * same way a real {@code StoreMain} process would.
 */
// Real multi-node in-process Raft cluster with real heartbeat/election-timeout-driven timing
// (awaitLeader/Await.until polling against wall-clock @Timeout budgets). Unlike a ResourceLock
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
    StateStore store = new StateStore();
    RaftLog raftLog = new RaftLog(dir.resolve("raft"));
    RaftNode node =
        new RaftNode(
            id,
            peerAddressOf(address),
            initialPeers,
            factoryFor(id),
            raftLog,
            store,
            ignored -> {});
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
   * matching the etcd-style one-at-a-time membership change. Deliberately never calls {@link
   * RaftNode#start} on the new node: with an empty initial peer set, {@code start()} would elect it
   * leader of its own one-node "cluster" immediately (see that method's own javadoc) -- exactly
   * wrong for a node about to join as a follower. Its RPC handlers work regardless of whether
   * {@code start()} was ever called (neither {@code onAppendEntries} nor {@code onInstallSnapshot}
   * checks {@code running}), so it still correctly catches up once the leader begins replicating to
   * it; it simply never arms its own election timer, which every test using this helper is fine
   * with.
   *
   * <p>Retried on {@link GimleRaftException}: a prior call's newly-joined learner can be
   * auto-promoted to a full voting member moments after that call returns (its own separate
   * membership change, appended by the replication loop's background thread, not this one) --
   * calling this again while that promotion is itself still uncommitted is exactly the transient,
   * expected rejection {@link GimleRaftException#membershipChangeInFlight} documents, not a bug.
   */
  private ClusterNode addNewNode(ClusterNode leader) throws IOException, InterruptedException {
    String id = "node-" + (nodeIdCounter++);
    InetSocketAddress address = reserveAddress();
    PeerAddress peerAddress = peerAddressOf(address);
    addressToId.put(peerAddress, id);
    ClusterNode node = buildNode(id, address, Map.of());
    node.transport().listen(node.address());
    long deadlineNanos = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (true) {
      try {
        leader.raftNode().addServer(id, peerAddress);
        return node;
      } catch (GimleRaftException e) {
        if (System.nanoTime() >= deadlineNanos) {
          throw e;
        }
        Thread.sleep(20);
      }
    }
  }

  private void partition(String nodeIdA, String nodeIdB) {
    edges.get(new Edge(nodeIdA, nodeIdB)).blocked = true;
    edges.get(new Edge(nodeIdB, nodeIdA)).blocked = true;
  }

  private static ClusterNode awaitLeader(List<ClusterNode> cluster) {
    return awaitLeader(cluster, Duration.ofSeconds(5));
  }

  private static ClusterNode awaitLeader(List<ClusterNode> cluster, Duration timeout) {
    Await.until(() -> cluster.stream().anyMatch(c -> c.raftNode().isLeader()), timeout);
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

    Await.until(
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
    Await.until(
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
    Await.until(
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

  // ---- MIM-1: check-quorum self-demotion ----

  @Test
  @Timeout(15)
  void a_leader_partitioned_from_the_majority_steps_down_on_its_own_via_check_quorum()
      throws Exception {
    List<ClusterNode> cluster = buildCluster(3, Set.of(0, 1, 2));
    ClusterNode isolated = awaitLeader(cluster);
    List<ClusterNode> majority = cluster.stream().filter(c -> c != isolated).toList();

    partition(isolated.id(), majority.get(0).id());
    partition(isolated.id(), majority.get(1).id());

    // No write is ever attempted here -- proves the isolated node's own isLeader() flips to false
    // purely from check-quorum's periodic self-assessment (it never observes a higher term: every
    // peer is unreachable, so nothing could ever carry one back), not as a side effect of a
    // proposal timing out the way a_partitioned_minority_cannot_elect_a_leader_or_commit_writes
    // above already covers.
    Await.until(() -> !isolated.raftNode().isLeader(), Duration.ofSeconds(2));

    // The majority side elects its own new leader independently, unaffected by the isolated
    // node's own self-demotion.
    ClusterNode newLeader = awaitLeader(majority);
    assertNotEquals(isolated.id(), newLeader.id());
  }

  @Test
  @Timeout(15)
  void a_leader_with_a_reachable_majority_never_self_demotes_via_check_quorum() throws Exception {
    // A negative-space companion to the test above: a *healthy* leader (every peer reachable, no
    // partition at all) must never trip check-quorum on its own -- if it did, this codebase's
    // own reconciler-leader lease (StateStore#tryAcquireOrRenewLease), gated purely on isLeader(),
    // would spuriously churn even absent any real network problem.
    List<ClusterNode> cluster = buildCluster(3, Set.of(0, 1, 2));
    ClusterNode leader = awaitLeader(cluster);

    // Comfortably longer than CHECK_QUORUM_WINDOW (300ms) so at least one real self-assessment
    // tick has definitely run and found a healthy majority.
    Thread.sleep(800);

    assertTrue(leader.raftNode().isLeader());
  }

  @Test
  @Timeout(10)
  void a_redirected_write_to_a_follower_returns_the_correct_leader_address() throws Exception {
    List<ClusterNode> cluster = buildCluster(3, Set.of(0, 1, 2));
    ClusterNode leader = awaitLeader(cluster);
    ClusterNode follower = cluster.stream().filter(c -> c != leader).findFirst().orElseThrow();

    // awaitLeader only confirms *some* node believes itself leader -- not that this specific
    // follower has processed that leader's first heartbeat/AppendEntries yet (leaderHint is set
    // on receipt of one, a separate event from the leader's own election). Without this, the
    // propose below can race a genuinely-just-elected leader's first heartbeat: leaderHint is
    // still null, and RaftNode#propose's rejection reports "leader unknown" rather than the
    // leader's address, failing the assertion below on a correct implementation, not a bug in it.
    Await.until(
        () -> follower.raftNode().leaderHint().equals(Optional.of(leader.id())),
        Duration.ofSeconds(5));

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

    // RaftLog is deliberately not internally synchronized (see its own class javadoc) --
    // RaftNode is its only intended caller, always under RaftNode's own lock. Reading
    // snapshotLastIncludedIndex() from this test thread as a one-shot check immediately after a
    // *different* condition (store.getTenant, a thread-safe ConcurrentHashMap read) succeeds
    // established no happens-before edge for this plain field specifically -- both are written by
    // the node's internal thread while applying InstallSnapshot, but only the ConcurrentHashMap
    // write carries its own visibility guarantee to a reader on another thread. Folding this into
    // the same polling predicate (matching this file's own Await.until idiom used everywhere else
    // to observe background-thread state) is the fix, not a one-shot read.
    Await.until(
        () ->
            farBehind.store().getTenant("t").isPresent()
                && farBehind.store().getTenant("t").get().quota().maxMemoryBytes() == 4
                && farBehind.raftLog().snapshotLastIncludedIndex() >= lastIndex,
        Duration.ofSeconds(10));
  }

  // ---- etcd-style live membership change ----

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

    Await.until(
        () ->
            cluster.stream().allMatch(c -> c.store().getTenant("grown").isPresent())
                && fourth.store().getTenant("grown").isPresent()
                && fifth.store().getTenant("grown").isPresent(),
        Duration.ofSeconds(10));
  }

  @Test
  @Tag("flaky")
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
    Await.until(() -> survivor.store().getTenant("shrunk").isPresent(), Duration.ofSeconds(5));
  }

  /**
   * Retries {@code leader.removeServer(peerId)} against whichever node currently believes itself
   * leader (leadership can move between retries, in particular right after {@link #addNewNode}'s
   * own learner promotion), tolerating {@link GimleRaftException} the same way {@link #addNewNode}
   * already does for its own transient {@code membershipChangeInFlight} window.
   */
  private static void removeServerWithRetry(
      List<ClusterNode> candidateLeaders, String peerId, Duration timeout)
      throws InterruptedException {
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    GimleRaftException last = null;
    while (System.nanoTime() < deadlineNanos) {
      ClusterNode currentLeader =
          candidateLeaders.stream().filter(c -> c.raftNode().isLeader()).findFirst().orElse(null);
      if (currentLeader == null) {
        Thread.sleep(20);
        continue;
      }
      try {
        currentLeader.raftNode().removeServer(peerId);
        return;
      } catch (GimleRaftException e) {
        last = e;
        Thread.sleep(20);
      }
    }
    throw last;
  }

  @Test
  @Timeout(30)
  void growing_to_four_then_removing_an_original_voter_converges_back_to_three_and_stays_healthy()
      throws Exception {
    List<ClusterNode> cluster = buildCluster(3, Set.of(0, 1, 2));
    ClusterNode leader = awaitLeader(cluster);
    ClusterNode fourth = addNewNode(leader);

    // Real work against the grown, 4-voter cluster before touching membership again.
    leader
        .raftNode()
        .propose(new StateMutation.PutTenant(new Tenant("grown", new ResourceQuota(1, 1, 1))));
    Await.until(
        () ->
            cluster.stream().allMatch(c -> c.store().getTenant("grown").isPresent())
                && fourth.store().getTenant("grown").isPresent(),
        Duration.ofSeconds(10));

    // Remove an ORIGINAL voter -- one of the three bootstrap nodes, never the just-added fourth --
    // the exact case M45 found broken end to end: a live cluster could grow past three voters but
    // never shrink back down by removing one of its original members, only by reverting the
    // addition (removing the peer it had itself just added).
    ClusterNode originalToRemove =
        cluster.stream().filter(c -> c != leader).findFirst().orElseThrow();
    List<ClusterNode> everyNode = new ArrayList<>(cluster);
    everyNode.add(fourth);
    removeServerWithRetry(everyNode, originalToRemove.id(), Duration.ofSeconds(10));

    List<ClusterNode> survivors = everyNode.stream().filter(c -> c != originalToRemove).toList();
    assertEquals(3, survivors.size());

    // Back to a healthy 3-voter cluster: a write from whichever node now leads must still reach
    // every surviving replica, including the one that was added live.
    ClusterNode postRemovalLeader = awaitLeader(survivors);
    postRemovalLeader
        .raftNode()
        .propose(
            new StateMutation.PutTenant(new Tenant("shrunk-to-three", new ResourceQuota(2, 2, 2))));
    Await.until(
        () -> survivors.stream().allMatch(c -> c.store().getTenant("shrunk-to-three").isPresent()),
        Duration.ofSeconds(10));
  }

  @Test
  @Timeout(30)
  void adding_a_voter_doing_real_work_removing_an_original_then_killing_the_leader_stays_healthy()
      throws Exception {
    // The M39-relevant regression: M45's inability to shrink a grown cluster back down was a
    // direct contributing cause of a real 4-voter incident that also involved leader loss -- this
    // exercises the whole sequence (grow, real work, shrink back down by removing an *original*
    // voter, then a leader kill) end to end, confirming 3-voter quorum both converges and survives
    // losing its own leader, not just that the removal call itself succeeds in isolation.
    List<ClusterNode> cluster = buildCluster(3, Set.of(0, 1, 2));
    ClusterNode firstLeader = awaitLeader(cluster);
    ClusterNode fourth = addNewNode(firstLeader);

    firstLeader
        .raftNode()
        .propose(
            new StateMutation.PutTenant(new Tenant("before-shrink", new ResourceQuota(1, 1, 1))));
    Await.until(
        () ->
            cluster.stream().allMatch(c -> c.store().getTenant("before-shrink").isPresent())
                && fourth.store().getTenant("before-shrink").isPresent(),
        Duration.ofSeconds(10));

    List<ClusterNode> everyNode = new ArrayList<>(cluster);
    everyNode.add(fourth);
    ClusterNode currentLeader =
        everyNode.stream().filter(c -> c.raftNode().isLeader()).findFirst().orElseThrow();
    ClusterNode originalToRemove =
        cluster.stream().filter(c -> c != currentLeader).findFirst().orElseThrow();
    removeServerWithRetry(everyNode, originalToRemove.id(), Duration.ofSeconds(10));

    List<ClusterNode> threeVoters = everyNode.stream().filter(c -> c != originalToRemove).toList();
    ClusterNode leaderOfThree = awaitLeader(threeVoters);

    // Kill the current leader of the now-3-voter cluster -- majority of the remaining 2 is 2, so
    // this proves quorum math genuinely shrank to 3 (not a stale 4-member view under which a
    // 2-node majority would never suffice) and that the cluster recovers a *new* leader, not just
    // that the one existing leader happened to still be up.
    leaderOfThree.transport().close();
    leaderOfThree.raftNode().close();
    List<ClusterNode> twoSurvivors = threeVoters.stream().filter(c -> c != leaderOfThree).toList();
    ClusterNode newLeader = awaitLeader(twoSurvivors);
    assertNotEquals(leaderOfThree.id(), newLeader.id());

    newLeader
        .raftNode()
        .propose(
            new StateMutation.PutTenant(
                new Tenant("after-leader-kill", new ResourceQuota(3, 3, 3))));
    Await.until(
        () ->
            twoSurvivors.stream()
                .allMatch(c -> c.store().getTenant("after-leader-kill").isPresent()),
        Duration.ofSeconds(10));
  }
}
