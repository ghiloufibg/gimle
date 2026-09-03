package com.gimle.mimir.raft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.time.TestClock;
import com.gimle.core.time.TestScheduler;
import com.gimle.mimir.store.StateStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the {@link RaftNode} constructor overload that accepts an injected {@code Clock}/
 * {@code ScheduledExecutorService} pair: a {@link TestScheduler} built from a {@link TestClock}
 * fires the election-timeout and check-quorum tasks deterministically via {@code
 * advance(Duration)}, instead of a test waiting out the real 150-300ms election window or the real
 * 300ms check-quorum window.
 *
 * <p>The peer here is a synchronous in-memory stub, not a real socket -- unlike {@code
 * RaftClusterTest}'s real multi-node network fixture, so nothing about becoming leader or
 * replicating waits on real I/O. The one real (and deliberately tiny) wait below is for the vote
 * response's own background thread (see {@code RaftNode#startElectionLocked}) to actually run and
 * flip this node's role -- genuine cross-thread concurrency that this seam does not, and is not
 * meant to, virtualize; see {@code RaftNode}'s own {@code scheduler}/{@code peerSenderLoop} field
 * and method javadoc for exactly what is and isn't covered.
 */
class RaftNodeVirtualTimeTest {

  @TempDir Path tempDir;

  /** Always grants the vote at the requested term; always refuses to actually replicate. */
  private static RaftPeerClient votingButUnreachablePeer() {
    return new RaftPeerClient() {
      @Override
      public RequestVoteResponse requestVote(RequestVote request) {
        return new RequestVoteResponse(request.term(), true);
      }

      @Override
      public AppendEntriesResponse appendEntries(AppendEntries request) {
        throw new RuntimeException("stub peer -- never actually reachable for replication");
      }

      @Override
      public InstallSnapshotResponse installSnapshot(InstallSnapshot request) {
        throw new RuntimeException("stub peer -- never actually reachable for replication");
      }
    };
  }

  private static void awaitTrue(BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean(), "condition not met within " + timeout);
  }

  @Test
  @Timeout(10)
  void
      election_timeout_and_check_quorum_self_demotion_both_fire_purely_from_advancing_virtual_time()
          throws Exception {
    Path dir = tempDir.resolve("virtual-time");
    StateStore store = new StateStore();
    RaftLog raftLog = new RaftLog(dir.resolve("raft"));
    TestClock clock = new TestClock();
    TestScheduler scheduler = new TestScheduler(clock);
    RaftNode node =
        new RaftNode(
            "self",
            Map.of("peer", votingButUnreachablePeer()),
            raftLog,
            store,
            Duration.ofSeconds(5),
            clock,
            scheduler);
    try {
      node.start();
      // Nothing has fired yet -- no real or virtual time has passed since start().
      assertFalse(node.isLeader());

      // Advances straight past even the longest possible election timeout (150-300ms in
      // production) without spending a single real millisecond waiting for it.
      scheduler.advance(Duration.ofMillis(300));
      // The vote itself resolves on a real background thread (see class javadoc) -- this bound
      // is generous, but in practice resolves in well under a millisecond for an in-memory stub.
      awaitTrue(node::isLeader, Duration.ofSeconds(2));
      // Lets becomeLeaderLocked's own checkQuorumFuture registration (a few statements after the
      // role flip observed above, on that same background thread) actually land.
      Thread.sleep(20);

      // The peer never once acknowledges a real RPC (see votingButUnreachablePeer), but a leader
      // this new has not yet had time to learn that: its very first attempt to reach the peer is
      // allowed to run far longer than the 300ms check-quorum window before it reports anything
      // at all, so the window alone must not be read as evidence the peer is gone.
      scheduler.advance(Duration.ofMillis(300));
      Thread.sleep(20);
      assertTrue(node.isLeader(), "a brand-new leader must not self-demote within one window");

      // Still true an order of magnitude later, while a single peer RPC could legitimately still
      // be in flight. This is the whole defect: judged against the 300ms window instead, every
      // leader demotes on its first tick and its successor repeats it, so a cluster whose peers
      // are merely slow to accept connections -- a loaded machine, or one restarting underneath a
      // rolling platform upgrade -- elects leaders indefinitely without any of them surviving
      // long enough to serve a write.
      scheduler.advance(Duration.ofSeconds(3));
      Thread.sleep(20);
      assertTrue(
          node.isLeader(), "a new leader must survive while its first RPCs could be in flight");

      // Once one full attempt has had time to finish or fail, silence becomes real evidence and
      // the self-demotion does fire. Stepped in small increments rather than one long jump: this
      // stub still grants votes, so a demoted node re-elects itself and begins a fresh grace, and
      // a single advance long enough to cover the grace can land either side of that cycle.
      boolean selfDemoted = false;
      for (int step = 0; step < 200 && !selfDemoted; step++) {
        scheduler.advance(Duration.ofMillis(100));
        selfDemoted = !node.isLeader();
      }
      assertTrue(selfDemoted, "a leader whose peer never answers must still self-demote");
    } finally {
      node.close();
    }
  }
}
