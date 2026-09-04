package com.gimle.mimir.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleRaftException;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.raft.AppendEntries;
import com.gimle.mimir.raft.AppendEntriesResponse;
import com.gimle.mimir.raft.InstallSnapshot;
import com.gimle.mimir.raft.InstallSnapshotResponse;
import com.gimle.mimir.raft.PeerAddress;
import com.gimle.mimir.raft.RaftLog;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.raft.RaftPeerClient;
import com.gimle.mimir.raft.RequestVote;
import com.gimle.mimir.raft.RequestVoteResponse;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.StateStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@link StoreNode} directly against a real {@link RaftNode}/{@link StateStore} pair, no
 * sockets -- the {@link StoreTransport}/{@link StoreConnection} network plumbing is covered
 * separately by {@code StoreClient}'s own integration test, so this test only needs to prove {@link
 * StoreNode}'s own dispatch and leader-routing logic.
 */
class StoreNodeTest {

  @TempDir Path tempDir;

  private final List<RaftNode> nodes = new ArrayList<>();

  @AfterEach
  void tearDown() {
    // None of these nodes are ever closed mid-test, so nothing stops a still-running
    // scheduler/peer-sender thread on its own once a test method returns -- a learner's
    // automatic promotion in particular means a background thread can still be actively
    // appending to a test's own raft log file after the test body finishes, racing @TempDir's
    // own post-test cleanup walk.
    for (RaftNode node : nodes) {
      node.close();
    }
  }

  private StoreNode leaderNode(String id) {
    Path dir = tempDir.resolve(id);
    StateStore store = new StateStore();
    RaftLog log = new RaftLog(dir.resolve("raft"));
    RaftNode raftNode = new RaftNode(id, Map.of(), log, store);
    nodes.add(raftNode);
    raftNode.start(); // an empty peer set becomes its own leader immediately
    return new StoreNode(raftNode, store, Map.of(id, id + "-client:9090"));
  }

  /** Never started, so it stays FOLLOWER with no leader hint at all -- the mid-election gap. */
  private StoreNode neverElectedNode(String id) {
    Path dir = tempDir.resolve(id);
    StateStore store = new StateStore();
    RaftLog log = new RaftLog(dir.resolve("raft"));
    RaftNode raftNode = new RaftNode(id, Map.of(), log, store);
    nodes.add(raftNode);
    return new StoreNode(raftNode, store, Map.of(id, id + "-client:9090"));
  }

  @Test
  void a_leader_answers_reads_from_its_own_store() {
    StoreNode node = leaderNode("reader");
    StoreRpc.Response response = node.handle(new StoreRpc.GetTenant("acme"));
    assertEquals(new StoreRpc.TenantResult(false, null), response);
  }

  @Test
  void a_leader_applies_a_propose_and_the_read_reflects_it() {
    StoreNode node = leaderNode("proposer");
    Tenant tenant = new Tenant("acme", new ResourceQuota(1024, 500, 10));

    StoreRpc.Response proposeResponse =
        node.handle(new StoreRpc.Propose(new StateMutation.PutTenant(tenant)));
    assertEquals(new StoreRpc.Ok(), proposeResponse);

    StoreRpc.Response readResponse = node.handle(new StoreRpc.GetTenant("acme"));
    assertEquals(new StoreRpc.TenantResult(true, tenant), readResponse);
  }

  @Test
  void a_leader_accepts_a_heartbeat_directly_without_going_through_propose() {
    StoreNode node = leaderNode("heartbeat-leader");
    NodeHeartbeat heartbeat =
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1024, 512, 4000, 1000),
            List.of(
                InstanceObservation.builder(
                        "greeter",
                        0,
                        new ModuleId("greeter", Version.parse("1.0.0")),
                        "ACTIVE",
                        true,
                        true)
                    .build()));

    StoreRpc.Response response = node.handle(new StoreRpc.PutHeartbeat(heartbeat));

    assertEquals(new StoreRpc.Ok(), response);
  }

  @Test
  void a_leader_grants_renews_and_releases_a_lease() {
    StoreNode node = leaderNode("lease-leader");

    StoreRpc.LeaseResult granted =
        (StoreRpc.LeaseResult)
            node.handle(new StoreRpc.AcquireOrRenewLease("reconciler-leader", "node-a", 15_000L));
    assertTrue(granted.granted());
    assertEquals("node-a", granted.holderId());

    StoreRpc.LeaseResult denied =
        (StoreRpc.LeaseResult)
            node.handle(new StoreRpc.AcquireOrRenewLease("reconciler-leader", "node-b", 15_000L));
    assertFalse(denied.granted());
    assertEquals("node-a", denied.holderId());

    StoreRpc.Response released =
        node.handle(new StoreRpc.ReleaseLease("reconciler-leader", "node-a"));
    assertEquals(new StoreRpc.Ok(), released);

    StoreRpc.LeaseResult grantedAfterRelease =
        (StoreRpc.LeaseResult)
            node.handle(new StoreRpc.AcquireOrRenewLease("reconciler-leader", "node-b", 15_000L));
    assertTrue(grantedAfterRelease.granted());
  }

  @Test
  void a_non_leader_rejects_a_propose_with_not_leader_and_no_hint_yet() {
    StoreNode node = neverElectedNode("follower");

    StoreRpc.Response response =
        node.handle(new StoreRpc.Propose(new StateMutation.RemoveTenant("acme")));

    assertEquals(new StoreRpc.NotLeader(""), response);
  }

  @Test
  void a_non_leader_rejects_a_heartbeat_a_lease_acquire_and_a_lease_release() {
    StoreNode node = neverElectedNode("follower-writes");

    assertEquals(
        new StoreRpc.NotLeader(""),
        node.handle(
            new StoreRpc.PutHeartbeat(
                new NodeHeartbeat("node-a", new ResourceUsageSnapshot(0, 0, 0, 0), List.of()))));
    assertEquals(
        new StoreRpc.NotLeader(""),
        node.handle(new StoreRpc.AcquireOrRenewLease("reconciler-leader", "node-a", 1000L)));
    assertEquals(
        new StoreRpc.NotLeader(""),
        node.handle(new StoreRpc.ReleaseLease("reconciler-leader", "node-a")));
    // GetNodeHeartbeat would have to be refused even if reads in general weren't: heartbeats are
    // never replicated, so a non-leader's own map is empty rather than merely behind.
    assertEquals(new StoreRpc.NotLeader(""), node.handle(new StoreRpc.GetNodeHeartbeat("node-a")));
  }

  @Test
  void a_leader_reads_back_a_heartbeat_it_just_accepted() {
    StoreNode node = leaderNode("heartbeat-read-leader");
    NodeHeartbeat heartbeat =
        new NodeHeartbeat("node-a", new ResourceUsageSnapshot(1024, 512, 4000, 1000), List.of());
    assertEquals(new StoreRpc.Ok(), node.handle(new StoreRpc.PutHeartbeat(heartbeat)));

    StoreRpc.HeartbeatResult result =
        (StoreRpc.HeartbeatResult) node.handle(new StoreRpc.GetNodeHeartbeat("node-a"));

    assertTrue(result.present());
    assertEquals("node-a", result.value().heartbeat().nodeId());
  }

  @Test
  void a_non_leader_refuses_a_read_rather_than_answering_from_its_own_replica() {
    StoreNode node = neverElectedNode("follower-reads");

    // An empty list is exactly what this node's own store holds, and answering it would look
    // perfectly successful to the caller -- which is the failure mode: a replica that has not
    // caught up cannot tell "nothing was ever created" apart from "I have not seen it yet", so it
    // refuses and the client redirects to a node that can establish a read index.
    assertEquals(new StoreRpc.NotLeader(""), node.handle(new StoreRpc.ListDeployments()));
    assertEquals(new StoreRpc.NotLeader(""), node.handle(new StoreRpc.ListTenants()));
    assertEquals(new StoreRpc.NotLeader(""), node.handle(new StoreRpc.GetTenant("acme")));
  }

  @Test
  void a_non_leader_still_answers_status_for_itself() {
    StoreNode node = neverElectedNode("follower-status");

    // The one request that must survive having no leader at all: it reports this node's own view
    // of leadership and membership, which is what an operator asks for precisely when routing to a
    // leader is what has stopped working.
    StoreRpc.StatusResult status = (StoreRpc.StatusResult) node.handle(new StoreRpc.Status());

    assertEquals("follower-status", status.selfId());
    assertFalse(status.leader());
  }

  @Test
  void a_leader_appends_an_instance_event_and_the_list_read_reflects_it() {
    StoreNode node = leaderNode("events-leader");
    InstanceEvent event =
        new InstanceEvent("evt-1", "greeter", 0, InstanceEventKind.ACTIVE, "module active", 1_000L);

    StoreRpc.Response proposeResponse =
        node.handle(
            new StoreRpc.Propose(new StateMutation.AppendInstanceEvent(Optional.empty(), event)));
    assertEquals(new StoreRpc.Ok(), proposeResponse);

    StoreRpc.Response listResponse =
        node.handle(new StoreRpc.ListInstanceEvents(Optional.empty(), "greeter", 0));
    assertEquals(new StoreRpc.InstanceEventListResult(List.of(event)), listResponse);
  }

  @Test
  void listing_instance_events_for_an_unknown_instance_is_empty() {
    StoreNode node = leaderNode("events-empty");
    assertEquals(
        new StoreRpc.InstanceEventListResult(List.of()),
        node.handle(new StoreRpc.ListInstanceEvents(Optional.empty(), "never-deployed", 0)));
  }

  @Test
  void a_cluster_wide_instance_events_read_merges_every_instances_own_timeline_over_the_wire() {
    StoreNode node = leaderNode("events-cluster-wide");
    InstanceEvent orders =
        new InstanceEvent("evt-1", "orders", 0, InstanceEventKind.ACTIVE, "module active", 1_000L);
    InstanceEvent ledger =
        new InstanceEvent("evt-2", "ledger", 0, InstanceEventKind.ACTIVE, "module active", 2_000L);

    node.handle(
        new StoreRpc.Propose(new StateMutation.AppendInstanceEvent(Optional.empty(), orders)));
    node.handle(
        new StoreRpc.Propose(new StateMutation.AppendInstanceEvent(Optional.empty(), ledger)));

    StoreRpc.Response listResponse =
        node.handle(new StoreRpc.ListAllInstanceEvents(Optional.empty(), Optional.empty()));
    assertEquals(new StoreRpc.InstanceEventListResult(List.of(ledger, orders)), listResponse);
  }

  @Test
  void a_cluster_wide_instance_events_read_with_no_matches_is_empty() {
    StoreNode node = leaderNode("events-cluster-wide-empty");
    assertEquals(
        new StoreRpc.InstanceEventListResult(List.of()),
        node.handle(new StoreRpc.ListAllInstanceEvents(Optional.empty(), Optional.empty())));
  }

  // ---- ListAuditEvents / AppendAuditEvent (audit trail) ----

  @Test
  void a_leader_appends_an_audit_event_and_the_list_read_reflects_it() {
    StoreNode node = leaderNode("audit-leader");
    AuditEvent event =
        new AuditEvent(
            "audit-1",
            "alice",
            Set.of("gimle:operators"),
            "DEPLOYMENT",
            "WRITE",
            Optional.of("tenant-1"),
            Optional.of("greeter"),
            true,
            1_000L);

    StoreRpc.Response proposeResponse =
        node.handle(new StoreRpc.Propose(new StateMutation.AppendAuditEvent(event)));
    assertEquals(new StoreRpc.Ok(), proposeResponse);

    StoreRpc.Response listResponse =
        node.handle(
            new StoreRpc.ListAuditEvents(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    assertEquals(new StoreRpc.AuditEventListResult(List.of(event)), listResponse);
  }

  @Test
  void listing_audit_events_with_no_matches_is_empty() {
    StoreNode node = leaderNode("audit-empty");
    assertEquals(
        new StoreRpc.AuditEventListResult(List.of()),
        node.handle(
            new StoreRpc.ListAuditEvents(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));
  }

  /**
   * Exercises {@code ListAuditEvents}' optional filter parameters actually surviving the wire --
   * the one thing this request shape needs that {@code ListInstanceEvents} never did.
   */
  @Test
  void a_resource_kind_filter_narrows_the_list_read_over_the_wire() {
    StoreNode node = leaderNode("audit-filter");
    AuditEvent deploymentEvent =
        new AuditEvent(
            "audit-1",
            "alice",
            Set.of(),
            "DEPLOYMENT",
            "WRITE",
            Optional.empty(),
            Optional.empty(),
            true,
            1_000L);
    AuditEvent secretEvent =
        new AuditEvent(
            "audit-2",
            "alice",
            Set.of(),
            "SECRET",
            "WRITE",
            Optional.empty(),
            Optional.empty(),
            true,
            2_000L);
    node.handle(new StoreRpc.Propose(new StateMutation.AppendAuditEvent(deploymentEvent)));
    node.handle(new StoreRpc.Propose(new StateMutation.AppendAuditEvent(secretEvent)));

    StoreRpc.Response listResponse =
        node.handle(
            new StoreRpc.ListAuditEvents(
                Optional.empty(), Optional.of("SECRET"), Optional.empty(), Optional.empty()));
    assertEquals(new StoreRpc.AuditEventListResult(List.of(secretEvent)), listResponse);
  }

  // ---- AddServer: etcd-style membership change ----

  /** Always acks immediately, as if the peer had a real, always-caught-up log of its own. */
  private static RaftPeerClient echoAckingPeer() {
    return new RaftPeerClient() {
      @Override
      public RequestVoteResponse requestVote(RequestVote request) {
        return new RequestVoteResponse(request.term(), true);
      }

      @Override
      public AppendEntriesResponse appendEntries(AppendEntries request) {
        long matchIndex = request.prevLogIndex() + request.entries().size();
        return new AppendEntriesResponse(request.term(), true, matchIndex);
      }

      @Override
      public InstallSnapshotResponse installSnapshot(InstallSnapshot request) {
        return new InstallSnapshotResponse(request.term());
      }
    };
  }

  @Test
  void a_leader_adds_a_server_and_the_client_address_book_learns_its_address() {
    Path dir = tempDir.resolve("add-server-leader");
    StateStore store = new StateStore();
    RaftLog log = new RaftLog(dir.resolve("raft"));
    Map<String, String> raftIdToClientAddress = new ConcurrentHashMap<>();
    raftIdToClientAddress.put("leader", "leader-client:9090");
    RaftNode raftNode =
        new RaftNode(
            "leader",
            new PeerAddress("leader-host", 9000, 9100),
            Map.of(),
            addr -> echoAckingPeer(),
            log,
            store,
            peers ->
                peers.forEach(
                    (peerId, address) ->
                        raftIdToClientAddress.put(peerId, address.clientAddress())));
    nodes.add(raftNode);
    raftNode.start(); // an empty peer set becomes its own leader immediately
    StoreNode node = new StoreNode(raftNode, store, raftIdToClientAddress);

    StoreRpc.Response response =
        node.handle(new StoreRpc.AddServer("node-2", "10.0.0.2", 7100, 7200));

    assertEquals(new StoreRpc.Ok(), response);
    assertEquals("10.0.0.2:7200", raftIdToClientAddress.get("node-2"));
  }

  @Test
  void a_non_leader_rejects_an_add_server_request_with_not_leader() {
    StoreNode node = neverElectedNode("follower-add-server");

    StoreRpc.Response response =
        node.handle(new StoreRpc.AddServer("node-2", "10.0.0.2", 7100, 7200));

    assertEquals(new StoreRpc.NotLeader(""), response);
  }

  @Test
  void a_leader_removes_a_server_it_previously_added() throws Exception {
    Path dir = tempDir.resolve("remove-server-leader");
    StateStore store = new StateStore();
    RaftLog log = new RaftLog(dir.resolve("raft"));
    RaftNode raftNode =
        new RaftNode(
            "leader",
            new PeerAddress("leader-host", 9000, 9100),
            Map.of(),
            addr -> echoAckingPeer(),
            log,
            store,
            ignored -> {});
    nodes.add(raftNode);
    raftNode.start();
    StoreNode node = new StoreNode(raftNode, store, new ConcurrentHashMap<>(Map.of("leader", "x")));
    assertEquals(
        new StoreRpc.Ok(), node.handle(new StoreRpc.AddServer("node-2", "10.0.0.2", 7100, 7200)));

    // node-2's own join committed the instant AddServer returned (a fresh learner never needs its
    // own ack to join), but the fake peer acks everything, so it is then auto-promoted to a full
    // voting member on the replication loop's very next tick -- a second, separate membership
    // change that can itself still be uncommitted the instant it's appended. Retried rather than
    // awaited-then-called: a RemoveServer landing in that exact window is a real, expected
    // transient rejection (GimleRaftException#membershipChangeInFlight), not a bug.
    StoreRpc.Response response = removeServerWithRetry(node, "node-2", Duration.ofSeconds(5));

    assertEquals(new StoreRpc.Ok(), response);
  }

  /**
   * This test's leader never actually loses leadership, so the only way {@code RemoveServer} ever
   * comes back as anything but {@link StoreRpc.Ok} here is the transient {@link
   * GimleRaftException#membershipChangeInFlight} rejection {@code StoreNode} maps onto the same
   * {@link StoreRpc.NotLeader} shape a genuine redirect uses -- so retrying on any non-{@code Ok}
   * response until {@code timeout} is unambiguous here, not a guess.
   */
  private static StoreRpc.Response removeServerWithRetry(
      StoreNode node, String peerId, Duration timeout) throws InterruptedException {
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    while (true) {
      StoreRpc.Response response = node.handle(new StoreRpc.RemoveServer(peerId));
      if (response instanceof StoreRpc.Ok || System.nanoTime() >= deadlineNanos) {
        return response;
      }
      Thread.sleep(20);
    }
  }

  @Test
  void a_non_leader_rejects_a_remove_server_request_with_not_leader() {
    StoreNode node = neverElectedNode("follower-remove-server");

    StoreRpc.Response response = node.handle(new StoreRpc.RemoveServer("node-2"));

    assertEquals(new StoreRpc.NotLeader(""), response);
  }
}
