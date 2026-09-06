package com.gimle.mimir.raft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.time.TestClock;
import com.gimle.core.time.TestScheduler;
import com.gimle.mimir.store.StateStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

  /**
   * Grants the vote, but every {@code appendEntries}/{@code installSnapshot} call blocks on {@code
   * gate} before throwing -- simulating a slow connect attempt (a loaded machine, a peer still
   * starting up) that has not yet resolved one way or the other, as opposed to {@link
   * #votingButUnreachablePeer} above, whose failure is immediate and synchronous.
   */
  private static final class SlowThenUnreachablePeer implements RaftPeerClient {
    private final CountDownLatch gate;

    SlowThenUnreachablePeer(CountDownLatch gate) {
      this.gate = gate;
    }

    @Override
    public RequestVoteResponse requestVote(RequestVote request) {
      return new RequestVoteResponse(request.term(), true);
    }

    @Override
    public AppendEntriesResponse appendEntries(AppendEntries request) {
      await();
      throw new RuntimeException("stub peer -- resolves unreachable only once released");
    }

    @Override
    public InstallSnapshotResponse installSnapshot(InstallSnapshot request) {
      await();
      throw new RuntimeException("stub peer -- resolves unreachable only once released");
    }

    private void await() {
      try {
        gate.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Answers normally until {@code slow} is armed, then blocks the one call that catches it on
   * {@code gate} before answering successfully -- a round trip that merely runs slow (a loaded
   * host, a GC pause) and then completes, as opposed to one that resolves unreachable.
   */
  private static final class SlowOnceThenHealthyPeer implements RaftPeerClient {
    private final CountDownLatch gate = new CountDownLatch(1);
    private final AtomicBoolean slow = new AtomicBoolean();
    private final AtomicInteger completedCalls = new AtomicInteger();

    @Override
    public RequestVoteResponse requestVote(RequestVote request) {
      return new RequestVoteResponse(request.term(), true);
    }

    @Override
    public AppendEntriesResponse appendEntries(AppendEntries request) {
      if (slow.compareAndSet(true, false)) {
        try {
          gate.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }
      completedCalls.incrementAndGet();
      return new AppendEntriesResponse(
          request.term(), true, request.prevLogIndex() + request.entries().size());
    }

    @Override
    public InstallSnapshotResponse installSnapshot(InstallSnapshot request) {
      return new InstallSnapshotResponse(request.term());
    }
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
  void a_leader_whose_first_attempt_is_still_genuinely_in_flight_is_granted_the_full_grace()
      throws Exception {
    Path dir = tempDir.resolve("virtual-time-slow");
    StateStore store = new StateStore();
    RaftLog raftLog = new RaftLog(dir.resolve("raft"));
    TestClock clock = new TestClock();
    TestScheduler scheduler = new TestScheduler(clock);
    CountDownLatch gate = new CountDownLatch(1);
    RaftNode node =
        new RaftNode(
            "self",
            Map.of("peer", new SlowThenUnreachablePeer(gate)),
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
      // role flip observed above, on that same background thread) actually land, and gives the
      // peer sender its first chance to call appendEntries and block on the gate below.
      Thread.sleep(20);

      // The peer's first attempt is still blocked on the gate -- genuinely in flight, not yet a
      // definite answer either way. A leader this new must not read the mere absence of an
      // answer within the ordinary 300ms check-quorum window as evidence the peer is gone.
      scheduler.advance(Duration.ofMillis(300));
      Thread.sleep(20);
      assertTrue(
          node.isLeader(), "a brand-new leader must not self-demote while an attempt is in flight");

      // Still true an order of magnitude later, while that one attempt could still legitimately
      // be in flight. This is the whole defect faa5282 fixed: judged against the 300ms window
      // instead, every leader demotes on its first tick and its successor repeats it, so a
      // cluster whose peers are merely slow to accept connections -- a loaded machine, or one
      // restarting underneath a rolling platform upgrade -- elects leaders indefinitely without
      // any of them surviving long enough to serve a write.
      scheduler.advance(Duration.ofSeconds(3));
      Thread.sleep(20);
      assertTrue(
          node.isLeader(), "a new leader must survive while its first RPC could be in flight");

      // Releases the gate: the blocked attempt now resolves as a definite failure. Once every
      // voting peer's first attempt has definitely resolved, waiting out the rest of the
      // worst-case grace buys nothing, so self-demotion should follow promptly (within about one
      // more ordinary check-quorum window), not require the full grace to elapse first.
      gate.countDown();
      boolean selfDemoted = false;
      for (int step = 0; step < 10 && !selfDemoted; step++) {
        scheduler.advance(Duration.ofMillis(100));
        Thread.sleep(5);
        selfDemoted = !node.isLeader();
      }
      assertTrue(
          selfDemoted,
          "once the in-flight attempt resolves to failure, self-demotion must follow promptly, "
              + "not wait out the rest of the worst-case grace");
    } finally {
      node.close();
    }
  }

  /**
   * The steady-state counterpart of the grace above, and the defect it did not cover: once a leader
   * has reached quorum at least once, a peer whose round trip merely runs slow was counted as
   * silent the moment the check-quorum window elapsed -- self-demoting a perfectly healthy leader
   * while that very heartbeat was still on its way to succeeding. One attempt may legitimately run
   * far longer than that window, so an attempt still in flight is not evidence of unreachability.
   */
  @Test
  @Timeout(20)
  void an_established_leader_survives_a_round_trip_that_merely_runs_slow() throws Exception {
    Path dir = tempDir.resolve("virtual-time-slow-steady-state");
    StateStore store = new StateStore();
    RaftLog raftLog = new RaftLog(dir.resolve("raft"));
    TestClock clock = new TestClock();
    TestScheduler scheduler = new TestScheduler(clock);
    SlowOnceThenHealthyPeer peer = new SlowOnceThenHealthyPeer();
    RaftNode node =
        new RaftNode(
            "self", Map.of("peer", peer), raftLog, store, Duration.ofSeconds(5), clock, scheduler);
    try {
      node.start();
      scheduler.advance(Duration.ofMillis(300));
      awaitTrue(node::isLeader, Duration.ofSeconds(2));
      // Let several heartbeats complete, so this leader has genuinely reached quorum and is past
      // the new-leader grace entirely -- the state the QA cluster was in when it self-demoted.
      awaitTrue(() -> peer.completedCalls.get() >= 2, Duration.ofSeconds(5));
      assertTrue(node.isLeader());
      // The term is what makes this test meaningful: a self-demotion is immediately followed by
      // this node winning the next election (its peer still grants votes), so leadership alone
      // cannot tell "never demoted" apart from "demoted and re-elected" -- which is exactly the
      // churn the defect produced. A stable term proves no demotion happened at all.
      long termWhileHealthy = raftLog.currentTerm();

      // The next round trip blocks. Nothing is wrong with the peer; the call simply takes a while.
      peer.slow.set(true);
      // compareAndSet clears the flag as a call enters the slow path, so this waits for one to be
      // genuinely blocked rather than merely armed.
      awaitTrue(() -> !peer.slow.get(), Duration.ofSeconds(5));
      // Well past the 600ms check-quorum window, while that one attempt is still outstanding.
      scheduler.advance(Duration.ofSeconds(3));
      Thread.sleep(50);

      assertEquals(
          termWhileHealthy,
          raftLog.currentTerm(),
          "an established leader must not self-demote while a round trip is still in flight");
      assertTrue(node.isLeader());

      peer.gate.countDown();
      awaitTrue(() -> peer.completedCalls.get() >= 4, Duration.ofSeconds(5));
      assertTrue(node.isLeader(), "the slow round trip succeeded, so leadership must be intact");
      assertEquals(termWhileHealthy, raftLog.currentTerm());
    } finally {
      node.close();
    }
  }

  @Test
  @Timeout(10)
  void a_leader_whose_peer_fails_immediately_self_demotes_within_the_ordinary_window()
      throws Exception {
    Path dir = tempDir.resolve("virtual-time-fast-fail");
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
      assertFalse(node.isLeader());

      scheduler.advance(Duration.ofMillis(300));
      awaitTrue(node::isLeader, Duration.ofSeconds(2));
      // Lets the peer sender's first (immediately-failing) attempt actually run and mark this
      // peer's first attempt resolved before check-quorum's own next tick observes it.
      Thread.sleep(50);

      // The peer's failure is immediate and synchronous (see votingButUnreachablePeer), not a
      // slow connect that merely hasn't resolved yet -- once that one, only voting peer's first
      // attempt has definitely failed, there is no genuine uncertainty left for the grace period
      // to extend, so self-demotion must follow within roughly the ordinary check-quorum window,
      // not stall out for the full multi-second worst-case grace regardless. This is the
      // regression a static, unconditional grace period introduced: a fast-failing partition
      // must still be caught fast.
      boolean selfDemoted = false;
      for (int step = 0; step < 10 && !selfDemoted; step++) {
        scheduler.advance(Duration.ofMillis(100));
        Thread.sleep(5);
        selfDemoted = !node.isLeader();
      }
      assertTrue(
          selfDemoted,
          "a leader whose only peer fails immediately must self-demote within roughly the "
              + "ordinary check-quorum window, not the full worst-case grace");
    } finally {
      node.close();
    }
  }
}
