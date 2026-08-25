package com.gimle.mimir.raft;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleRaftException;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.store.StateStore;
import com.gimle.testkit.Await;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
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
 *
 * <p>Also covers the non-voting learner stage every {@link RaftNode#addServer}-ed peer now starts
 * in (see {@link RaftNode#learners}'s own javadoc): it must not count toward election/commit quorum
 * until its log catches up, and it must be promoted automatically, via the same log-replicated
 * path, once it does.
 */
class RaftMembershipChangeTest {

  @TempDir Path tempDir;

  private static final PeerAddress SELF_ADDRESS = new PeerAddress("leader-host", 9000, 9100);

  private final List<RaftNode> nodes = new ArrayList<>();

  @AfterEach
  void tearDown() {
    // None of these nodes are ever killed mid-test, so nothing stops their scheduler/peer-sender
    // threads on its own once a test method returns -- a learner's automatic promotion in
    // particular means a background thread can still be actively appending to a test's own raft
    // log file after the test body finishes, racing @TempDir's own post-test cleanup walk.
    for (RaftNode node : nodes) {
      node.close();
    }
  }

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

  /**
   * A peer that only ever acknowledges {@code AppendEntries} up to a configurable ceiling on the
   * resulting match index -- anything past it is rejected (not merely ignored), forcing {@link
   * RaftNode#sendOnce}'s own one-index-at-a-time backoff, the same as a genuinely far-behind real
   * peer would. Simulates a learner mid-catch-up: {@link #advanceCeilingTo} lets a test move that
   * catch-up forward under its own control, deterministically, without real network lag.
   */
  private static final class LaggingPeer implements RaftPeerClient {
    private final AtomicLong ceiling;

    LaggingPeer(long initialCeiling) {
      this.ceiling = new AtomicLong(initialCeiling);
    }

    void advanceCeilingTo(long newCeiling) {
      ceiling.set(newCeiling);
    }

    @Override
    public RequestVoteResponse requestVote(RequestVote request) {
      return new RequestVoteResponse(request.term(), true);
    }

    @Override
    public AppendEntriesResponse appendEntries(AppendEntries request) {
      long naturalMatchIndex = request.prevLogIndex() + request.entries().size();
      if (naturalMatchIndex > ceiling.get()) {
        return new AppendEntriesResponse(request.term(), false, 0);
      }
      return new AppendEntriesResponse(request.term(), true, naturalMatchIndex);
    }

    @Override
    public InstallSnapshotResponse installSnapshot(InstallSnapshot request) {
      return new InstallSnapshotResponse(request.term());
    }
  }

  /**
   * Acks normally until {@link #acking} is flipped off, then behaves like {@link #unreachablePeer}.
   */
  private static final class ToggleableAckingPeer implements RaftPeerClient {
    volatile boolean acking = true;

    @Override
    public RequestVoteResponse requestVote(RequestVote request) {
      checkAcking();
      return new RequestVoteResponse(request.term(), true);
    }

    @Override
    public AppendEntriesResponse appendEntries(AppendEntries request) {
      checkAcking();
      long matchIndex = request.prevLogIndex() + request.entries().size();
      return new AppendEntriesResponse(request.term(), true, matchIndex);
    }

    @Override
    public InstallSnapshotResponse installSnapshot(InstallSnapshot request) {
      checkAcking();
      return new InstallSnapshotResponse(request.term());
    }

    private void checkAcking() {
      if (!acking) {
        throw new RuntimeException("simulated unreachable peer");
      }
    }
  }

  private static void removeServerWithRetry(RaftNode leader, String peerId, Duration timeout)
      throws InterruptedException {
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    while (true) {
      try {
        leader.removeServer(peerId);
        return;
      } catch (GimleRaftException e) {
        if (System.nanoTime() >= deadlineNanos) {
          throw e;
        }
        Thread.sleep(20);
      }
    }
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
    StateStore store = new StateStore();
    RaftLog raftLog = new RaftLog(dir.resolve("raft"));
    RaftNode node =
        new RaftNode(
            "leader",
            SELF_ADDRESS,
            Map.of(),
            factory,
            raftLog,
            store,
            membershipListener,
            proposeTimeout);
    nodes.add(node);
    node.start(); // becomes leader immediately: an empty initial peer set has majority 1
    return node;
  }

  @Test
  @Timeout(10)
  void adding_a_server_joins_it_and_a_subsequent_mutation_still_commits() throws Exception {
    Map<String, PeerAddress> announced = new ConcurrentHashMap<>();
    RaftNode leader =
        newSingleNodeLeader(
            Path.of("add"), addr -> echoAckingPeer(), announced::putAll, Duration.ofSeconds(5));

    leader.addServer("node-2", new PeerAddress("10.0.0.2", 7100, 7200));

    assertTrue(announced.containsKey("node-2"));
    leader.propose(new StateMutation.PutTenant(new Tenant("t1", new ResourceQuota(1, 1, 1))));
    // The fake peer acks everything immediately, so this always-caught-up learner is promoted to
    // a full voting member right away rather than staying a learner indefinitely.
    Await.until(() -> !leader.isLearnerForTest("node-2"), Duration.ofSeconds(5));
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

    // node-2's own join committed the instant addServer returned (a fresh learner never needs its
    // own ack to join, see the learner-quorum tests below) -- but the fake peer acks everything, so
    // it is then auto-promoted to a full voting member on the replication loop's very next tick, a
    // second, *separate* membership change. Retried rather than awaited-then-called: that
    // promotion can itself still be uncommitted (one more real round trip away) in the instant
    // right after it's appended, so a removeServer landing in that exact window is a real,
    // expected transient GimleRaftException -- exactly what that exception's own javadoc already
    // documents callers must tolerate, not a bug in this reduction.
    removeServerWithRetry(leader, "node-2", Duration.ofSeconds(5));

    // Back down to a single-voter cluster: majority 1, self alone, no peer ack required.
    leader.propose(new StateMutation.PutTenant(new Tenant("t2", new ResourceQuota(2, 2, 2))));
  }

  @Test
  @Timeout(10)
  void a_non_leader_cannot_propose_a_membership_change() {
    Path dir = tempDir.resolve("follower");
    StateStore store = new StateStore();
    RaftLog raftLog = new RaftLog(dir.resolve("raft"));
    // Never started: stays a FOLLOWER, exactly like RaftNodeSafetyMechanicsTest's own
    // never-started fixtures.
    RaftNode follower =
        new RaftNode(
            "follower",
            SELF_ADDRESS,
            Map.of("node-2", new PeerAddress("10.0.0.2", 7100, 7200)),
            addr -> echoAckingPeer(),
            raftLog,
            store,
            ignored -> {});
    nodes.add(follower);

    assertThrows(GimleRaftException.class, () -> follower.addServer("node-3", null));
  }

  @Test
  @Timeout(15)
  void a_second_membership_change_is_rejected_while_an_earlier_one_is_still_uncommitted()
      throws Exception {
    // A freshly-added learner never needs its own ack to commit its own join (see the
    // learner-quorum tests below), so an unreachable *new* peer alone can no longer keep a change
    // pending the way it could before learners existed -- it takes an *already-promoted, existing*
    // voting member going unreachable to make a later change genuinely stay uncommitted, since that
    // member still counts toward the majority the later change's own commit needs.
    ToggleableAckingPeer voterClient = new ToggleableAckingPeer();
    PeerAddress voterAddress = new PeerAddress("10.0.0.2", 7100, 7200);
    // Fires the instant the background addServer call reaches its own peerClientFactory --
    // synchronously, while it still holds RaftNode's internal lock (reconfigurePeersLocked runs
    // inside appendMembershipChangeLocked's locked section). Waiting on this latch before this
    // thread's own addServer call means that call's lock.lock() cannot succeed until the
    // background call's entire locked section -- including setting pendingMembershipChangeIndex --
    // has completed, eliminating the race a fixed sleep or bounded retry loop would only reduce,
    // not remove.
    CountDownLatch newPeerConnected = new CountDownLatch(1);
    RaftNode leader =
        newSingleNodeLeader(
            Path.of("in-flight"),
            addr -> {
              if (addr.equals(voterAddress)) {
                return voterClient;
              }
              newPeerConnected.countDown();
              return unreachablePeer();
            },
            ignored -> {},
            Duration.ofSeconds(5));

    leader.addServer("voter", voterAddress);
    // Not just "no longer a learner": membership changes are effective on append, so
    // isLearnerForTest already flips the instant voter's own promotion is appended, well before
    // that promotion entry itself commits (it needs voter's own ack, being a full voting member
    // as of that same entry). Waiting for the pending-change gate to clear too means voter's
    // promotion has genuinely settled before this test starts relying on a *second* change
    // finding that gate held open -- otherwise this scenario can race voter's own still-in-flight
    // promotion instead of node-3's change, this test's actual subject.
    Await.until(() -> !leader.isLearnerForTest("voter"), Duration.ofSeconds(5));
    Await.until(() -> !leader.hasPendingMembershipChangeForTest(), Duration.ofSeconds(5));
    voterClient.acking = false; // the one existing voting member goes unreachable

    Thread background =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    // Needs both this leader's own vote and voter's ack now that voter is a full
                    // voting member -- voter never acks again, so this blocks until the 5s
                    // proposeTimeout elapses.
                    leader.addServer("node-3", new PeerAddress("10.0.0.3", 7100, 7200));
                  } catch (GimleRaftException ignored) {
                    // expected once the timeout fires
                  }
                });

    newPeerConnected.await();
    GimleRaftException rejected =
        assertThrows(
            GimleRaftException.class,
            () -> leader.addServer("node-4", new PeerAddress("10.0.0.4", 7100, 7200)));
    assertTrue(rejected.getMessage().contains("uncommitted"), rejected.getMessage());

    background.join(Duration.ofSeconds(6).toMillis());
  }

  // ---- learner stage: non-voting until caught up ----

  @Test
  @Timeout(10)
  void a_freshly_added_learner_does_not_block_or_count_toward_commit_quorum() throws Exception {
    // Never acks a single entry -- if it counted toward a 2-member cluster's quorum (majority 2),
    // the propose below would hang until its own timeout instead of committing immediately.
    LaggingPeer neverCatchesUp = new LaggingPeer(0);
    RaftNode leader = newSingleNodeLeader(Path.of("learner-quorum"), addr -> neverCatchesUp);

    leader.addServer("node-2", new PeerAddress("10.0.0.2", 7100, 7200));
    assertTrue(leader.isLearnerForTest("node-2"));

    leader.propose(new StateMutation.PutTenant(new Tenant("t3", new ResourceQuota(1, 1, 1))));

    // Still a learner: nothing has caught it up, yet the write above committed anyway.
    assertTrue(leader.isLearnerForTest("node-2"));
  }

  @Test
  @Timeout(10)
  void a_learner_is_promoted_to_a_full_voting_member_once_its_log_catches_up() throws Exception {
    LaggingPeer laggingThenCaughtUp = new LaggingPeer(0);
    RaftNode leader = newSingleNodeLeader(Path.of("learner-promote"), addr -> laggingThenCaughtUp);

    leader.addServer("node-2", new PeerAddress("10.0.0.2", 7100, 7200));
    assertTrue(leader.isLearnerForTest("node-2"));

    laggingThenCaughtUp.advanceCeilingTo(Long.MAX_VALUE);
    Await.until(() -> !leader.isLearnerForTest("node-2"), Duration.ofSeconds(5));
  }

  @Test
  @Timeout(10)
  void a_never_caught_up_learner_stays_non_voting_indefinitely() throws Exception {
    LaggingPeer neverCatchesUp = new LaggingPeer(0);
    RaftNode leader = newSingleNodeLeader(Path.of("learner-stuck"), addr -> neverCatchesUp);

    leader.addServer("node-2", new PeerAddress("10.0.0.2", 7100, 7200));
    for (int i = 0; i < 5; i++) {
      leader.propose(new StateMutation.PutTenant(new Tenant("t" + i, new ResourceQuota(1, 1, 1))));
    }

    // Several heartbeat rounds have passed (HEARTBEAT_INTERVAL is 50ms) with node-2 never
    // acknowledging a single entry -- it must never have been promoted.
    Thread.sleep(300);
    assertTrue(leader.isLearnerForTest("node-2"));
  }
}
