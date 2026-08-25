package com.gimle.mimir.raft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.time.TestClock;
import com.gimle.mimir.store.StateStore;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An in-process, virtual-time Raft cluster: {@link #nodeIds} real {@link RaftNode}s wired directly
 * to each other's {@link RaftRpcHandler} methods (no sockets, no {@link RaftTransport}), sharing
 * one {@link TestClock} so {@link #advanceVirtualTime} can fire every node's
 * election-timeout/check- quorum tick without spending real time waiting out the 150-300ms/300ms
 * production windows -- the same seam {@link RaftNodeVirtualTimeTest} exercises for one node,
 * generalized here to a full cluster plus a seeded fault-injection surface ({@link #isolate}/{@link
 * #heal}/{@link #restart}) so {@code NornRaftSimulationTest} can run far more
 * election/partition/recovery activity per real second than a live-timer test ever could.
 *
 * <p><strong>Honest about what is and isn't virtualized:</strong> only election-timeout and check-
 * quorum-tick firing move on {@link #clock} -- the same scope {@link RaftNode}'s own {@code
 * scheduler} seam covers today (see its javadoc). Peer RPC dispatch (this class's own {@link
 * NornPeerClient}, a direct in-process call, not a socket) and a leader's own replication heartbeat
 * pacing still run on {@link RaftNode}'s real background threads at real wall-clock speed -- {@link
 * RaftNode}'s own {@code peerSenderLoop}/{@code awaitAppliedThrowing} javadoc explains why those
 * two are deliberately not routed through the virtual-time seam. This harness is therefore not a
 * bit-for-bit-deterministic single-threaded simulator in the FoundationDB/TigerBeetle sense; what
 * it genuinely delivers is a seeded, replayable *fault schedule* (which node gets
 * isolated/restarted, and when, in virtual time) layered over a real Raft implementation with its
 * timing-sensitive majority (elections, quorum checks) compressed to near-zero real cost.
 */
final class NornCluster implements AutoCloseable {

  /**
   * Real time given to {@link RaftNode#propose} before this harness gives up on a round's write.
   */
  static final Duration PROPOSE_TIMEOUT = Duration.ofMillis(500);

  private final Path root;
  private final TestClock clock = new TestClock();
  private final Map<String, RaftNode> nodesById = new HashMap<>();
  private final Map<String, RaftLog> logsById = new HashMap<>();
  private final Map<String, StateStore> storesById = new HashMap<>();
  private final Map<String, NornScheduler> schedulersById = new HashMap<>();
  private final Set<String> isolated = new HashSet<>();
  private final List<String> nodeIds;

  NornCluster(Path root, List<String> nodeIds) {
    this.root = root;
    this.nodeIds = List.copyOf(nodeIds);
    for (String id : this.nodeIds) {
      buildAndStart(id);
    }
  }

  List<String> nodeIds() {
    return nodeIds;
  }

  TestClock clock() {
    return clock;
  }

  private void buildAndStart(String id) {
    Path dir = root.resolve(id);
    RaftLog raftLog = new RaftLog(dir.resolve("raft"));
    StateStore store = new StateStore();
    NornScheduler scheduler = new NornScheduler(clock);
    Map<String, RaftPeerClient> peers = new HashMap<>();
    for (String peerId : nodeIds) {
      if (!peerId.equals(id)) {
        peers.put(peerId, new NornPeerClient(id, peerId));
      }
    }
    RaftNode node = new RaftNode(id, peers, raftLog, store, PROPOSE_TIMEOUT, clock, scheduler);
    node.start();
    logsById.put(id, raftLog);
    storesById.put(id, store);
    schedulersById.put(id, scheduler);
    nodesById.put(id, node);
  }

  /**
   * A thin in-process peer link: every call re-resolves the current live {@link RaftNode} for
   * {@code toId} via {@link #nodesById} (so a {@link #restart} mid-simulation is transparent to
   * every other node still holding one of these) and checks {@link #isolated} for both ends first
   * -- the same "throw, don't silently drop" shape {@code RaftClusterTest}'s own {@code
   * ToggleablePeerClient} uses, since that's exactly what an unreachable real peer looks like to
   * {@link RaftNode}'s own RPC call sites.
   */
  private final class NornPeerClient implements RaftPeerClient {
    private final String fromId;
    private final String toId;

    NornPeerClient(String fromId, String toId) {
      this.fromId = fromId;
      this.toId = toId;
    }

    private RaftRpcHandler liveTarget() {
      if (isolated.contains(fromId) || isolated.contains(toId)) {
        throw new RuntimeException("norn: simulated partition between " + fromId + " and " + toId);
      }
      return nodesById.get(toId);
    }

    @Override
    public RequestVoteResponse requestVote(RequestVote request) {
      return liveTarget().onRequestVote(request);
    }

    @Override
    public AppendEntriesResponse appendEntries(AppendEntries request) {
      return liveTarget().onAppendEntries(request);
    }

    @Override
    public InstallSnapshotResponse installSnapshot(InstallSnapshot request) {
      return liveTarget().onInstallSnapshot(request);
    }
  }

  /** Cuts {@code id} off from every other node, both directions, until {@link #heal}. */
  void isolate(String id) {
    isolated.add(id);
  }

  void heal(String id) {
    isolated.remove(id);
  }

  void healAll() {
    isolated.clear();
  }

  /**
   * Simulates a process crash+restart: closes the current {@link RaftNode} for {@code id} (stops
   * its background threads without touching {@link #logsById}/{@link #storesById}) and rebuilds it
   * from fresh {@link RaftLog}/{@link StateStore} instances reopened at the same on-disk path --
   * exactly what a real {@code StoreMain} restart does, so any persisted term/vote/log survives
   * while every piece of in-memory-only role/leadership state is genuinely lost. Implicitly heals
   * any isolation on {@code id}, matching a real process coming back up with a working network
   * stack.
   */
  void restart(String id) {
    RaftNode old = nodesById.get(id);
    if (old != null) {
      old.close();
    }
    heal(id);
    Path dir = root.resolve(id);
    RaftLog raftLog = new RaftLog(dir.resolve("raft"));
    StateStore store = new StateStore();
    NornScheduler scheduler = new NornScheduler(clock);
    Map<String, RaftPeerClient> peers = new HashMap<>();
    for (String peerId : nodeIds) {
      if (!peerId.equals(id)) {
        peers.put(peerId, new NornPeerClient(id, peerId));
      }
    }
    RaftNode node = new RaftNode(id, peers, raftLog, store, PROPOSE_TIMEOUT, clock, scheduler);
    node.start();
    logsById.put(id, raftLog);
    storesById.put(id, store);
    schedulersById.put(id, scheduler);
    nodesById.put(id, node);
  }

  /**
   * Drives every node's {@link NornScheduler} forward in lockstep, dispatching whichever node's
   * next-due task is globally earliest one at a time -- the multi-scheduler interleaving {@link
   * NornScheduler#advance} alone doesn't provide (each instance owns its own private queue and
   * would otherwise run its whole window before another node's earlier-due tick ever fires). Ties
   * at the same virtual instant resolve in {@link #nodeIds}'s own fixed order, keeping the
   * interleaving itself deterministic for a given seed's fault schedule even though the underlying
   * per-round-trip thread timing is not (see this class's own javadoc).
   */
  int advanceVirtualTime(Duration amount) {
    Instant deadline = clock.instant().plus(amount);
    int ran = 0;
    while (true) {
      String earliestId = null;
      Instant earliestDue = null;
      for (String id : nodeIds) {
        List<Instant> due = schedulersById.get(id).pendingDueTimes();
        if (due.isEmpty()) {
          continue;
        }
        Instant head = due.get(0);
        if (earliestDue == null || head.isBefore(earliestDue)) {
          earliestDue = head;
          earliestId = id;
        }
      }
      if (earliestDue == null || earliestDue.isAfter(deadline)) {
        break;
      }
      // A real background thread belonging to a *different* node can reach into this scheduler
      // between the scan above and the advance() call below -- onAppendEntries/onRequestVote run
      // synchronously on the calling node's own thread (see NornPeerClient), and can themselves
      // reset the target node's election timer. Clamping a since-gone-stale/now-earlier delta to
      // zero rather than trusting it is what keeps that race from crashing the sweep: a zero-length
      // advance still safely runs whatever is actually due on this scheduler right now.
      Duration delta = Duration.between(clock.instant(), earliestDue);
      if (delta.isNegative()) {
        delta = Duration.ZERO;
      }
      ran += schedulersById.get(earliestId).advance(delta);
    }
    clock.setTo(deadline);
    return ran;
  }

  /**
   * A short, fixed real-time pause letting each node's own real background threads (vote-count
   * response handlers, peer-sender loops) actually run and land their effects -- the same tiny,
   * deliberately-real wait {@code RaftNodeVirtualTimeTest} takes after a virtual-time advance, for
   * the same reason: this harness has no way to virtualize genuine cross-thread concurrency, only
   * to bound how long it waits for it.
   */
  void settleReal() throws InterruptedException {
    Thread.sleep(30);
  }

  Optional<String> currentLeader() {
    return nodeIds.stream().filter(id -> nodesById.get(id).isLeader()).findFirst();
  }

  /** Best-effort: proposes through {@code id} if it's currently leader, swallowing rejection. */
  boolean tryPropose(String id, StateMutation mutation) {
    RaftNode node = nodesById.get(id);
    if (!node.isLeader()) {
      return false;
    }
    try {
      node.propose(mutation);
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * Raft's own Election Safety property (paper §5.2): at most one leader per term, checked directly
   * against every node's own {@link RaftLog#currentTerm()} rather than inferring term from role
   * alone -- two nodes both reporting {@link RaftNode#isLeader()} true is only a genuine violation
   * if they agree on which term that leadership is for; a stale leader isolated in a minority
   * partition and a freshly-elected one in the majority necessarily disagree on term, which this
   * correctly does not flag.
   */
  void assertElectionSafety(String context) {
    Map<Long, String> leaderByTerm = new HashMap<>();
    for (String id : nodeIds) {
      RaftNode node = nodesById.get(id);
      if (!node.isLeader()) {
        continue;
      }
      long term = logsById.get(id).currentTerm();
      String existing = leaderByTerm.putIfAbsent(term, id);
      if (existing != null && !existing.equals(id)) {
        fail(
            context
                + ": election safety violated -- both '"
                + existing
                + "' and '"
                + id
                + "' claim leadership in term "
                + term);
      }
    }
  }

  /**
   * Raft's own Log Matching property (paper §5.3): if two nodes' logs both have an entry at the
   * same index and that entry's term agrees, the entries must be identical -- checked pairwise
   * across every node and every index either one has ever appended, not just the
   * currently-committed prefix, since the property is defined over the full log, not just what's
   * been applied.
   */
  void assertLogMatching(String context) {
    for (int i = 0; i < nodeIds.size(); i++) {
      for (int j = i + 1; j < nodeIds.size(); j++) {
        String idA = nodeIds.get(i);
        String idB = nodeIds.get(j);
        RaftLog logA = logsById.get(idA);
        RaftLog logB = logsById.get(idB);
        long upper = Math.min(logA.lastIndex(), logB.lastIndex());
        for (long index = 1; index <= upper; index++) {
          Optional<LogEntry> entryA = logA.get(index);
          Optional<LogEntry> entryB = logB.get(index);
          if (entryA.isEmpty() || entryB.isEmpty()) {
            continue; // one side already compacted this index away via a snapshot
          }
          if (entryA.get().term() != entryB.get().term()) {
            continue; // different terms at this index -- not a log-matching candidate
          }
          assertEquals(
              entryA.get().payload(),
              entryB.get().payload(),
              context
                  + ": log matching violated at index "
                  + index
                  + " term "
                  + entryA.get().term()
                  + " between '"
                  + idA
                  + "' and '"
                  + idB
                  + "'");
        }
      }
    }
  }

  void assertSafetyInvariants(String context) {
    assertElectionSafety(context);
    assertLogMatching(context);
  }

  /**
   * Liveness, not safety: after every fault heals, a healthy cluster must eventually elect a leader
   * and let it commit a fresh write -- called only once a test has stopped injecting new faults, so
   * the timeout genuinely means "never recovered" rather than "still mid-storm".
   */
  void assertEventuallyLive(Duration budget) throws InterruptedException {
    Instant deadline = clock.instant().plus(budget);
    while (clock.instant().isBefore(deadline)) {
      advanceVirtualTime(Duration.ofMillis(50));
      settleReal();
      Optional<String> leader = currentLeader();
      if (leader.isPresent()
          && tryPropose(leader.get(), new StateMutation.PutTenant(livenessProbeTenant()))) {
        return;
      }
    }
    assertTrue(
        false,
        "cluster failed to elect a leader and commit a write within " + budget + " of recovery");
  }

  private static com.gimle.core.tenant.Tenant livenessProbeTenant() {
    return new com.gimle.core.tenant.Tenant(
        "norn-liveness-probe", new com.gimle.core.tenant.ResourceQuota(1, 1, 1));
  }

  @Override
  public void close() {
    for (RaftNode node : nodesById.values()) {
      node.close();
    }
  }
}
