package com.gimle.mimir.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.raft.AppendEntries;
import com.gimle.mimir.raft.AppendEntriesResponse;
import com.gimle.mimir.raft.InstallSnapshot;
import com.gimle.mimir.raft.InstallSnapshotResponse;
import com.gimle.mimir.raft.MutationOutcome;
import com.gimle.mimir.raft.PeerConnection;
import com.gimle.mimir.raft.RaftLog;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.raft.RaftPeerClient;
import com.gimle.mimir.raft.RaftRpcHandler;
import com.gimle.mimir.raft.RaftTransport;
import com.gimle.mimir.raft.RequestVote;
import com.gimle.mimir.raft.RequestVoteResponse;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.LeaseGrant;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StateStore;
import com.gimle.testkit.Await;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * The load-bearing checkpoint proving the store-client-to-cluster protocol works end to end: a real
 * 3-node {@code StoreNode} cluster (real Raft consensus over real sockets, real {@code
 * StoreTransport} listeners) driven entirely through one {@link StoreClient}, proving the whole
 * protocol works -- including leader-follow-retry across a forced failover -- before {@code
 * ApiServer} is ever rewired onto it. Modeled directly on {@code RaftClusterTest}'s
 * real-loopback-TCP pattern.
 */
@Isolated
class StoreClientClusterTest {

  @TempDir Path tempDir;

  private final List<RaftNode> raftNodes = new ArrayList<>();
  private final List<RaftTransport> raftTransports = new ArrayList<>();
  private final List<StoreTransport> storeTransports = new ArrayList<>();
  private StoreClient client;
  private int clusterCounter;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
    for (StoreTransport transport : storeTransports) {
      transport.close();
    }
    for (RaftNode node : raftNodes) {
      node.close();
    }
    for (RaftTransport transport : raftTransports) {
      transport.close();
    }
  }

  private record ClusterNode(
      String id,
      RaftNode raftNode,
      RaftTransport raftTransport,
      StoreTransport storeTransport,
      SocketAddress clientAddress,
      StateStore store) {}

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

  private static InetSocketAddress reserveAddress() throws IOException {
    try (ServerSocketChannel probe = ServerSocketChannel.open()) {
      probe.bind(new InetSocketAddress("127.0.0.1", 0));
      return (InetSocketAddress) probe.getLocalAddress();
    }
  }

  private List<ClusterNode> buildCluster(int n) throws IOException {
    List<String> ids = new ArrayList<>();
    List<InetSocketAddress> raftAddresses = new ArrayList<>();
    List<InetSocketAddress> clientAddresses = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      ids.add("node-" + i);
      raftAddresses.add(reserveAddress());
      clientAddresses.add(reserveAddress());
    }
    Map<String, String> raftIdToClientAddress = new HashMap<>();
    for (int i = 0; i < n; i++) {
      String raftId = ids.get(i) + ":" + raftAddresses.get(i).getPort();
      raftIdToClientAddress.put(
          raftId, clientAddresses.get(i).getHostString() + ":" + clientAddresses.get(i).getPort());
    }

    List<ClusterNode> clusterNodes = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      HandlerRef ref = new HandlerRef();
      RaftTransport raftTransport = new RaftTransport(ref);
      raftTransports.add(raftTransport);

      Map<String, RaftPeerClient> peers = new HashMap<>();
      for (int j = 0; j < n; j++) {
        if (j == i) {
          continue;
        }
        String peerRaftId = ids.get(j) + ":" + raftAddresses.get(j).getPort();
        peers.put(peerRaftId, new PeerConnection(raftAddresses.get(j)));
      }

      Path dir = tempDir.resolve("cluster-" + (clusterCounter++) + "-node-" + i);
      StateStore store = new StateStore();
      RaftLog raftLog = new RaftLog(dir.resolve("raft"));
      String selfRaftId = ids.get(i) + ":" + raftAddresses.get(i).getPort();
      RaftNode raftNode = new RaftNode(selfRaftId, peers, raftLog, store);
      ref.delegate = raftNode;
      raftNodes.add(raftNode);

      StoreNode storeNode = new StoreNode(raftNode, store, raftIdToClientAddress);
      StoreTransport storeTransport = new StoreTransport(storeNode);
      storeTransports.add(storeTransport);

      raftTransport.listen(raftAddresses.get(i));
      storeTransport.listen(clientAddresses.get(i));
      raftNode.start();

      clusterNodes.add(
          new ClusterNode(
              selfRaftId, raftNode, raftTransport, storeTransport, clientAddresses.get(i), store));
    }
    return clusterNodes;
  }

  /**
   * Unlike {@link Await#until}, returns the value the poll actually observed present -- reads
   * round-robin across every node, with no linearizability requirement, so a follow-up call after
   * an {@code Await.until}-then-refetch pattern can legitimately land on a still-lagging replica
   * and see it absent again, a false failure that has nothing to do with {@link StoreClient}
   * correctness.
   */
  private static <T> T awaitPresent(Supplier<Optional<T>> poll, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Optional<T> value = poll.get();
      if (value.isPresent()) {
        return value.get();
      }
      Thread.sleep(20);
    }
    throw new AssertionError("condition not met within " + timeout);
  }

  private static ClusterNode awaitLeader(List<ClusterNode> cluster) {
    Await.until(
        () -> cluster.stream().anyMatch(c -> c.raftNode().isLeader()), Duration.ofSeconds(5));
    ClusterNode leader =
        cluster.stream().filter(c -> c.raftNode().isLeader()).findFirst().orElseThrow();
    // A node believing itself leader doesn't mean every other node has processed that leader's
    // first heartbeat/AppendEntries yet -- leaderHint is set on receipt of one, a separate event
    // from the election itself. A StoreClient write immediately after this returns can otherwise
    // race a follower whose leaderHint is still unset, exhausting its own redirect-retry budget
    // against a "leader unknown" response instead of a real address to follow.
    Await.until(
        () ->
            cluster.stream()
                .allMatch(
                    c -> c == leader || c.raftNode().leaderHint().equals(Optional.of(leader.id()))),
        Duration.ofSeconds(5));
    return leader;
  }

  private static List<SocketAddress> clientAddresses(List<ClusterNode> cluster) {
    return cluster.stream().map(ClusterNode::clientAddress).toList();
  }

  @Test
  @Timeout(15)
  void a_client_can_read_and_write_through_any_endpoint_once_a_leader_is_elected()
      throws Exception {
    List<ClusterNode> cluster = buildCluster(3);
    awaitLeader(cluster);
    client = new StoreClient(clientAddresses(cluster));

    assertEquals(List.of(), client.listTenants());

    client.propose(
        new StateMutation.PutTenant(new Tenant("acme", new ResourceQuota(1024, 500, 10))));

    Tenant found = awaitPresent(() -> client.getTenant("acme"), Duration.ofSeconds(3));
    assertEquals("acme", found.id());
  }

  @Test
  @Timeout(15)
  void status_names_the_leader_and_the_full_membership_from_any_endpoint() throws Exception {
    List<ClusterNode> cluster = buildCluster(3);
    ClusterNode leader = awaitLeader(cluster);
    client = new StoreClient(clientAddresses(cluster));

    StoreRpc.StatusResult status = client.status();
    assertEquals(leader.id(), status.leaderId());
    assertEquals(3, status.memberIds().size());
    assertTrue(status.memberIds().contains(status.selfId()));
    assertTrue(status.memberIds().contains(leader.id()));
    // Whichever node answered, its self-reported leadership must agree with its leader hint.
    assertEquals(status.selfId().equals(leader.id()), status.leader());
  }

  @Test
  @Timeout(15)
  void leases_are_acquired_renewed_and_released_through_the_client() throws Exception {
    List<ClusterNode> cluster = buildCluster(3);
    awaitLeader(cluster);
    client = new StoreClient(clientAddresses(cluster));

    LeaseGrant granted =
        client.tryAcquireOrRenewLease("reconciler-leader", "api-1", Duration.ofSeconds(10));
    assertTrue(granted.granted());

    LeaseGrant denied =
        client.tryAcquireOrRenewLease("reconciler-leader", "api-2", Duration.ofSeconds(10));
    assertTrue(!denied.granted());
    assertEquals("api-1", denied.holderId());

    client.releaseLease("reconciler-leader", "api-1");
    LeaseGrant grantedAfterRelease =
        client.tryAcquireOrRenewLease("reconciler-leader", "api-2", Duration.ofSeconds(10));
    assertTrue(grantedAfterRelease.granted());
  }

  @Test
  @Timeout(15)
  void heartbeat_reads_are_leader_routed_and_never_answer_empty_from_a_stale_follower()
      throws Exception {
    List<ClusterNode> cluster = buildCluster(3);
    awaitLeader(cluster);
    client = new StoreClient(clientAddresses(cluster));

    client.putHeartbeat(
        new NodeHeartbeat("node-a", new ResourceUsageSnapshot(1024, 512, 4000, 1000), List.of()));

    // Heartbeats are deliberately leader-local, never replicated -- every one of these
    // reads must come back present. Before the fix, getNodeHeartbeat round-robinned across all
    // three endpoints via sendRead, so roughly 2 of every 3 calls landed on a follower whose local
    // map never had this heartbeat at all and answered empty forever, not just occasionally.
    for (int i = 0; i < 9; i++) {
      Optional<ObservedHeartbeat> observed = client.getNodeHeartbeat("node-a");
      assertTrue(observed.isPresent(), "call " + i + " returned empty");
      assertEquals("node-a", observed.get().heartbeat().nodeId());
    }
  }

  @Test
  @Tag("flaky")
  @Timeout(30)
  void a_client_keeps_writing_successfully_across_a_forced_leader_failover() throws Exception {
    List<ClusterNode> cluster = buildCluster(3);
    ClusterNode firstLeader = awaitLeader(cluster);
    client = new StoreClient(clientAddresses(cluster));

    client.propose(
        new StateMutation.PutTenant(new Tenant("before-failover", new ResourceQuota(1, 1, 1))));
    awaitPresent(() -> client.getTenant("before-failover"), Duration.ofSeconds(3));

    // Force this client's cached preferred leader to point at the soon-to-be-killed node, so the
    // next write is guaranteed to exercise both the dead-endpoint-skip path (connection refused)
    // and the leader-follow-retry path (a survivor's NotLeader hint), not just get lucky.
    for (int i = 0; i < 5; i++) {
      client.propose(
          new StateMutation.PutTenant(new Tenant("warm-" + i, new ResourceQuota(1, 1, 1))));
    }

    firstLeader.storeTransport().close();
    firstLeader.raftTransport().close();
    firstLeader.raftNode().close();

    List<ClusterNode> remaining = cluster.stream().filter(c -> c != firstLeader).toList();
    awaitLeader(remaining);

    client.propose(
        new StateMutation.PutTenant(new Tenant("after-failover", new ResourceQuota(2, 2, 2))));
    Tenant found = awaitPresent(() -> client.getTenant("after-failover"), Duration.ofSeconds(5));
    assertEquals("after-failover", found.id());
  }

  /**
   * The real value proposition of {@link StoreClient#getSnapshot()}/{@link
   * StoreClient#restore(byte[])}: a restore proposed through any endpoint lands on every replica's
   * own local {@link StateStore}, not just the leader's -- proven here by reading each node's store
   * directly, bypassing {@link StoreClient} entirely, so a restore that only patched the leader
   * (leaving followers silently diverged) would fail this test even though every client-routed read
   * afterward would still happen to answer correctly by landing on the leader.
   */
  @Test
  @Timeout(15)
  void a_restored_backup_lands_on_every_replicas_own_store_not_just_the_leaders() throws Exception {
    List<ClusterNode> cluster = buildCluster(3);
    awaitLeader(cluster);
    client = new StoreClient(clientAddresses(cluster));

    client.propose(
        new StateMutation.PutTenant(new Tenant("pre-backup", new ResourceQuota(1, 1, 1))));
    awaitPresent(() -> client.getTenant("pre-backup"), Duration.ofSeconds(3));
    byte[] backup = client.getSnapshot();

    // Diverges every replica from the backed-up state -- restoring must overwrite this on every
    // node, not merely leave the leader's own post-backup writes in place.
    client.propose(
        new StateMutation.PutTenant(new Tenant("post-backup", new ResourceQuota(2, 2, 2))));
    awaitPresent(() -> client.getTenant("post-backup"), Duration.ofSeconds(3));

    MutationOutcome outcome = client.restore(backup);
    assertTrue(outcome instanceof MutationOutcome.Accepted, "restore was rejected: " + outcome);

    for (ClusterNode node : cluster) {
      Await.until(
          () ->
              node.store().getTenant("pre-backup").isPresent()
                  && node.store().getTenant("post-backup").isEmpty(),
          Duration.ofSeconds(5));
    }
  }
}
