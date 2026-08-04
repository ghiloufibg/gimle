package com.gimle.mimir.raft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleRaftException;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.store.StateStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@link RaftNode} instances directly through their {@link RaftRpcHandler} methods -- no
 * sockets, no {@code RaftTransport} -- under full test control of message ordering, exactly the
 * design §2.3 safety mechanics this class targets: election restriction, the {@code AppendEntries}
 * consistency check with conflicting-entry truncation, the commit-index term rule (the paper's
 * Figure 8 scenario), and strict, gap-free apply ordering.
 */
class RaftNodeSafetyMechanicsTest {

  @TempDir Path tempDir;

  /** Never invoked in these tests -- forceLeaderForTest drives commit logic without real RPCs. */
  private static RaftPeerClient unusedPeer() {
    return new RaftPeerClient() {
      @Override
      public RequestVoteResponse requestVote(RequestVote request) {
        throw new UnsupportedOperationException("not used in this test");
      }

      @Override
      public AppendEntriesResponse appendEntries(AppendEntries request) {
        throw new UnsupportedOperationException("not used in this test");
      }

      @Override
      public InstallSnapshotResponse installSnapshot(InstallSnapshot request) {
        throw new UnsupportedOperationException("not used in this test");
      }
    };
  }

  @Test
  void a_candidate_with_a_stale_log_never_wins_even_when_its_request_vote_arrives_first() {
    Path dir = tempDir.resolve("voter-seeded");
    StateStore store = new StateStore(dir.resolve("store"));
    RaftLog log = new RaftLog(dir.resolve("raft"));
    log.setTermAndVote(2, Optional.empty());
    log.append(new LogEntry(2, 1, new StateMutation.RemoveTenant("t1")));
    log.append(new LogEntry(2, 2, new StateMutation.RemoveTenant("t2")));
    RaftNode voterNode = new RaftNode("voter", Map.of(), log, store);

    // Stale candidate: same term, but a shorter/less-advanced log than the voter's own.
    RequestVoteResponse staleResponse =
        voterNode.onRequestVote(new RequestVote(2, "stale-candidate", 1, 1));
    assertFalse(staleResponse.voteGranted());

    // Called afterwards ("arrives" second), but a fully up-to-date candidate still wins the vote
    // the stale one never could -- proving it's log up-to-dateness that decides, not arrival order.
    RequestVoteResponse upToDateResponse =
        voterNode.onRequestVote(new RequestVote(2, "up-to-date-candidate", 2, 2));
    assertTrue(upToDateResponse.voteGranted());
  }

  @Test
  void a_follower_truncates_a_conflicting_entry_and_everything_after_it_before_appending() {
    Path dir = tempDir.resolve("follower");
    StateStore store = new StateStore(dir.resolve("store"));
    RaftLog log = new RaftLog(dir.resolve("raft"));
    log.setTermAndVote(1, Optional.empty());
    log.append(new LogEntry(1, 1, new StateMutation.RemoveTenant("t1")));
    log.append(new LogEntry(1, 2, new StateMutation.RemoveTenant("t2")));
    log.append(new LogEntry(1, 3, new StateMutation.RemoveTenant("t3")));
    RaftNode follower = new RaftNode("follower", Map.of(), log, store);

    AppendEntries request =
        new AppendEntries(
            2,
            "leader",
            1,
            1,
            List.of(new LogEntry(2, 2, new StateMutation.RemoveTenant("replacement"))),
            0);
    AppendEntriesResponse response = follower.onAppendEntries(request);

    assertTrue(response.success());
    assertEquals(2L, response.matchIndex());
    assertEquals(2L, log.lastIndex());
    assertTrue(log.get(3).isEmpty());
    assertEquals(2L, log.get(2).get().term());
  }

  @Test
  void the_leader_only_commits_an_entry_from_its_own_current_term() {
    Path dir = tempDir.resolve("figure8-leader");
    StateStore store = new StateStore(dir.resolve("store"));
    RaftLog log = new RaftLog(dir.resolve("raft"));
    log.setTermAndVote(4, Optional.empty());
    // An entry replicated to a majority under an EARLIER term (e.g. by a previous leader that
    // crashed before committing it) -- the paper's Figure 8 setup.
    log.append(
        new LogEntry(
            2, 1, new StateMutation.PutTenant(new Tenant("old-term", new ResourceQuota(1, 1, 1)))));
    RaftNode leader =
        new RaftNode("leader", Map.of("B", unusedPeer(), "C", unusedPeer()), log, store);

    // A majority (self + B + C) all report matchIndex 1 -- but that entry is from term 2, not the
    // leader's current term 4. The naive "majority alone" rule would commit it; the correct rule
    // must not.
    leader.forceLeaderForTest(Map.of("B", 1L, "C", 1L));
    assertEquals(0L, leader.commitIndexForTest());
    assertEquals(0L, leader.lastAppliedForTest());
    assertTrue(store.getTenant("old-term").isEmpty());

    // Now the leader appends and replicates an entry in ITS OWN current term (4) to a majority.
    log.append(
        new LogEntry(
            4, 2, new StateMutation.PutTenant(new Tenant("new-term", new ResourceQuota(2, 2, 2)))));
    leader.forceLeaderForTest(Map.of("B", 2L, "C", 2L));

    // Committing the current-term entry (index 2) also commits everything before it (index 1) --
    // the standard corollary, and the only way the earlier, majority-replicated entry ever
    // legitimately becomes visible.
    assertEquals(2L, leader.commitIndexForTest());
    assertEquals(2L, leader.lastAppliedForTest());
    assertTrue(store.getTenant("old-term").isPresent());
    assertTrue(store.getTenant("new-term").isPresent());
  }

  @Test
  void apply_never_runs_ahead_of_commit_index_and_never_skips_an_entry() {
    Path dir = tempDir.resolve("apply-ordering");
    StateStore store = new StateStore(dir.resolve("store"));
    RaftLog log = new RaftLog(dir.resolve("raft"));
    log.setTermAndVote(1, Optional.empty());
    log.append(
        new LogEntry(
            1, 1, new StateMutation.PutTenant(new Tenant("t", new ResourceQuota(100, 100, 100)))));
    log.append(new LogEntry(1, 2, new StateMutation.RemoveTenant("t")));
    log.append(
        new LogEntry(
            1, 3, new StateMutation.PutTenant(new Tenant("t", new ResourceQuota(200, 200, 200)))));
    RaftNode leader =
        new RaftNode("leader", Map.of("B", unusedPeer(), "C", unusedPeer()), log, store);

    leader.forceLeaderForTest(Map.of("B", 3L, "C", 3L));

    // Applied strictly in order (put -> remove -> put): if entry 2 (the remove) had been skipped
    // or applied out of order, the tenant's final quota would not be 200.
    assertEquals(3L, leader.commitIndexForTest());
    assertEquals(3L, leader.lastAppliedForTest());
    assertEquals(200L, store.getTenant("t").orElseThrow().quota().maxMemoryBytes());

    // A majority claiming an index this leader's own log doesn't have (an inconsistent state that
    // should never arise from real replication, since matchIndex can never exceed the leader's own
    // log) must fail loudly rather than silently skip the missing entry.
    RaftNode inconsistentLeader =
        new RaftNode("leader2", Map.of("B", unusedPeer(), "C", unusedPeer()), log, store);
    assertThrows(
        RuntimeException.class,
        () -> inconsistentLeader.forceLeaderForTest(Map.of("B", 99L, "C", 99L)));
  }

  // ---- F-14: a client-visible-failed proposal must not silently commit once quorum returns ----

  @Test
  @Timeout(5)
  void a_timed_out_proposal_is_truncated_so_it_cannot_ghost_commit_once_quorum_returns() {
    Path dir = tempDir.resolve("timeout-truncate");
    StateStore store = new StateStore(dir.resolve("store"));
    RaftLog raftLog = new RaftLog(dir.resolve("raft"));
    raftLog.setTermAndVote(1, Optional.empty());
    // A short injected timeout, not the real 5s production value -- forceLeaderForTest leaves
    // matchIndex at its default (0) for both peers, so nothing ever reaches majority and the
    // proposal below can never commit, mirroring an isolated leader that still believes it's
    // leader but cannot reach a quorum.
    RaftNode leader =
        new RaftNode(
            "leader",
            Map.of("B", unusedPeer(), "C", unusedPeer()),
            raftLog,
            store,
            Duration.ofMillis(200));
    leader.forceLeaderForTest(Map.of());

    GimleRaftException thrown =
        assertThrows(
            GimleRaftException.class,
            () -> leader.propose(new StateMutation.RemoveTenant("ghost")));

    assertTrue(
        thrown.getMessage().contains("did not commit"), "expected the timeout message: " + thrown);
    assertEquals(
        0L, raftLog.lastIndex(), "the never-committed entry must be truncated, not left behind");
    assertTrue(raftLog.get(1).isEmpty());
  }

  @Test
  @Timeout(5)
  void a_proposal_that_commits_just_before_its_timeout_fires_is_not_truncated() throws Exception {
    Path dir = tempDir.resolve("timeout-race-committed-first");
    StateStore store = new StateStore(dir.resolve("store"));
    RaftLog raftLog = new RaftLog(dir.resolve("raft"));
    raftLog.setTermAndVote(1, Optional.empty());
    RaftNode leader =
        new RaftNode(
            "leader",
            Map.of("B", unusedPeer(), "C", unusedPeer()),
            raftLog,
            store,
            Duration.ofSeconds(5));
    leader.forceLeaderForTest(Map.of());

    // Simulates a delayed peer catching up and acking the entry shortly after propose() appends
    // it: a real production quorum-return would arrive via sendOnce's own response handling, but
    // forceLeaderForTest is this test file's existing seam for driving the same commit-index
    // advancement logic deterministically, without a real network round trip.
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                Thread.sleep(100);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              leader.forceLeaderForTest(Map.of("B", 1L, "C", 1L));
            });

    leader.propose(
        new StateMutation.PutTenant(new Tenant("late-but-real", new ResourceQuota(1, 1, 1))));

    assertEquals(1L, leader.lastAppliedForTest());
    assertTrue(raftLog.get(1).isPresent(), "a proposal that legitimately committed must survive");
    assertTrue(store.getTenant("late-but-real").isPresent());
  }
}
