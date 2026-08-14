package com.gimle.mimir.raft;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleRaftException;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.store.StateStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit-level coverage of {@link RaftNode#addServer}/{@link RaftNode#removeServer} and the
 * "effective on append" {@link MembershipChange} mechanics -- driven against fake, always-acking or
 * always-unreachable {@link RaftPeerClient}s rather than real sockets, the same "no networking at
 * all" style {@code RaftNodeSafetyMechanicsTest} already uses for the rest of this class's safety
 * mechanics. Real multi-node grow/shrink over real loopback TCP, with a genuinely far-behind new
 * peer catching up, is {@code RaftClusterTest}'s job, not this file's.
 */
class RaftMembershipChangeTest {

  @TempDir Path tempDir;

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

  /** Simulates a peer that can never be reached -- every call fails immediately. */
  private static RaftPeerClient unreachablePeer() {
    return new RaftPeerClient() {
      @Override
      public RequestVoteResponse requestVote(RequestVote request) {
        throw new RuntimeException("simulated unreachable peer");
      }

      @Override
      public AppendEntriesResponse appendEntries(AppendEntries request) {
        throw new RuntimeException("simulated unreachable peer");
      }

      @Override
      public InstallSnapshotResponse installSnapshot(InstallSnapshot request) {
        throw new RuntimeException("simulated unreachable peer");
      }
    };
  }

  private RaftNode newSingleNodeLeader(Path dirName, RaftPeerClientFactory factory) {
    return newSingleNodeLeader(dirName, factory, ignored -> {}, Duration.ofSeconds(5));
  }

  private RaftNode newSingleNodeLeader(
      Path dirName,
      RaftPeerClientFactory factory,
      Consumer<Map<String, PeerAddress>> membershipListener,
      Duration proposeTimeout) {
    Path dir = tempDir.resolve(dirName);
    StateStore store = new StateStore(dir.resolve("store"));
    RaftLog raftLog = new RaftLog(dir.resolve("raft"));
    RaftNode node =
        new RaftNode(
            "leader", Map.of(), factory, raftLog, store, membershipListener, proposeTimeout);
    node.start(); // becomes leader immediately: an empty initial peer set has majority 1
    return node;
  }

  @Test
  @Timeout(10)
  void adding_a_server_makes_it_a_voting_member_and_a_subsequent_mutation_still_commits()
      throws Exception {
    Map<String, PeerAddress> announced = new ConcurrentHashMap<>();
    RaftNode leader =
        newSingleNodeLeader(
            Path.of("add"), addr -> echoAckingPeer(), announced::putAll, Duration.ofSeconds(5));

    leader.addServer("node-2", new PeerAddress("10.0.0.2", 7100, 7200));

    assertTrue(announced.containsKey("node-2"));
    leader.propose(new StateMutation.PutTenant(new Tenant("t1", new ResourceQuota(1, 1, 1))));
  }

  @Test
  @Timeout(10)
  void adding_a_server_that_is_already_a_member_is_rejected() throws Exception {
    RaftNode leader = newSingleNodeLeader(Path.of("dupe"), addr -> echoAckingPeer());
    leader.addServer("node-2", new PeerAddress("10.0.0.2", 7100, 7200));

    assertThrows(
        GimleRaftException.class,
        () -> leader.addServer("node-2", new PeerAddress("10.0.0.2", 7100, 7200)));
  }

  @Test
  @Timeout(10)
  void removing_a_server_that_is_not_a_member_is_rejected() throws Exception {
    RaftNode leader = newSingleNodeLeader(Path.of("remove-unknown"), addr -> echoAckingPeer());

    assertThrows(GimleRaftException.class, () -> leader.removeServer("node-nonexistent"));
  }

  @Test
  @Timeout(10)
  void removing_a_server_drops_it_from_the_peer_set_and_the_lone_remaining_node_still_commits()
      throws Exception {
    RaftNode leader = newSingleNodeLeader(Path.of("remove"), addr -> echoAckingPeer());
    leader.addServer("node-2", new PeerAddress("10.0.0.2", 7100, 7200));

    leader.removeServer("node-2");

    // Back down to a single-voter cluster: majority 1, self alone, no peer ack required.
    leader.propose(new StateMutation.PutTenant(new Tenant("t2", new ResourceQuota(2, 2, 2))));
  }

  @Test
  @Timeout(10)
  void a_non_leader_cannot_propose_a_membership_change() {
    Path dir = tempDir.resolve("follower");
    StateStore store = new StateStore(dir.resolve("store"));
    RaftLog raftLog = new RaftLog(dir.resolve("raft"));
    // Never started: stays a FOLLOWER, exactly like RaftNodeSafetyMechanicsTest's own
    // never-started fixtures.
    RaftNode follower =
        new RaftNode(
            "follower",
            Map.of("node-2", new PeerAddress("10.0.0.2", 7100, 7200)),
            addr -> echoAckingPeer(),
            raftLog,
            store,
            ignored -> {});

    assertThrows(GimleRaftException.class, () -> follower.addServer("node-3", null));
  }

  @Test
  @Timeout(15)
  void a_second_membership_change_is_rejected_while_an_earlier_one_is_still_uncommitted()
      throws Exception {
    // Fires the instant the background addServer call reaches its own peerClientFactory --
    // synchronously, while it still holds RaftNode's internal lock (reconfigurePeersLocked runs
    // inside proposeMembershipChange's locked section). Waiting on this latch before this thread's
    // own addServer call means that call's lock.lock() cannot succeed until the background call's
    // entire locked section -- including setting pendingMembershipChangeIndex -- has completed,
    // eliminating the race a fixed sleep or bounded retry loop would only reduce, not remove.
    CountDownLatch peerConnected = new CountDownLatch(1);
    RaftNode leader =
        newSingleNodeLeader(
            Path.of("in-flight"),
            addr -> {
              peerConnected.countDown();
              return unreachablePeer();
            },
            ignored -> {},
            Duration.ofSeconds(5));

    Thread background =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    // Never acked (the fake peer always throws) -- blocks until the 5s
                    // proposeTimeout elapses.
                    leader.addServer("node-2", new PeerAddress("10.0.0.2", 7100, 7200));
                  } catch (GimleRaftException ignored) {
                    // expected once the timeout fires
                  }
                });

    peerConnected.await();
    GimleRaftException rejected =
        assertThrows(
            GimleRaftException.class,
            () -> leader.addServer("node-3", new PeerAddress("10.0.0.3", 7100, 7200)));
    assertTrue(rejected.getMessage().contains("uncommitted"), rejected.getMessage());

    background.join(Duration.ofSeconds(6).toMillis());
  }
}
