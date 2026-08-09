package com.gimle.mimir.raft;

import com.gimle.core.exception.GimleRaftException;
import com.gimle.mimir.store.StateSnapshot;
import com.gimle.mimir.store.StateStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A single Raft node, implementing consensus directly against Figure 2 of Ongaro &amp; Ousterhout,
 * "In Search of an Understandable Consensus Algorithm," including the algorithm's safety-mechanics
 * rules -- election restriction, the {@code AppendEntries} consistency check, conflicting-entry
 * truncation, the commit-index term rule, and separate {@code commitIndex}/{@code lastApplied}
 * tracking. {@link #propose} is the only entry point a caller (an {@code ApiServer} handler, or a
 * reconciler via {@link MutationSink}) ever needs; every RPC handler here exists to serve
 * replication among {@link RaftNode} peers, not to be called directly by application code.
 *
 * <p>One {@link ReentrantLock} guards every piece of mutable state below -- role, term/vote (via
 * {@link RaftLog}), {@code commitIndex}/{@code lastApplied}, and the leader's per-peer {@code
 * nextIndex}/{@code matchIndex} tables. RPCs to peers are sent without holding the lock (they block
 * on real I/O once {@link RaftTransport}/{@link PeerConnection} back {@link #peers}); every
 * response is processed back under the lock.
 */
public final class RaftNode implements RaftRpcHandler, MutationSink {

  private static final Logger log = LoggerFactory.getLogger(RaftNode.class);

  private static final Duration HEARTBEAT_INTERVAL = Duration.ofMillis(50);
  private static final int ELECTION_TIMEOUT_MIN_MS = 150;
  private static final int ELECTION_TIMEOUT_MAX_MS = 300;
  private static final Duration PROPOSE_TIMEOUT = Duration.ofSeconds(5);

  /** A concrete, tunable threshold for triggering log compaction, not left open-ended. */
  private static final long SNAPSHOT_THRESHOLD = 10_000;

  private final String selfId;
  private Map<String, RaftPeerClient> peers;
  private final RaftLog raftLog;
  private final StateStore store;
  private final Duration proposeTimeout;

  /**
   * This node's live view of every peer's Raft/client network address, keyed the same as {@link
   * #peers} -- guarded by {@link #lock}, mutated only by {@link #reconfigurePeersLocked}. A node
   * built via the legacy {@code Map<String, RaftPeerClient>} constructor (every test that doesn't
   * exercise live membership changes) leaves this empty forever: it has no addresses to record, and
   * {@link #addServer}/{@link #removeServer} are simply never called on it.
   */
  private final Map<String, PeerAddress> peerAddresses = new HashMap<>();

  /**
   * The address-aware constructor's initial peer set, kept as the fallback {@link
   * #effectivePeerConfigLocked} falls back to once the log no longer has any {@link
   * MembershipChange} entry left to scan (either none was ever proposed, or the log has been
   * compacted past the last one -- see that method's own javadoc for the one narrow case this
   * doesn't cover). Empty for a legacy-constructed node.
   */
  private final Map<String, PeerAddress> bootstrapPeerAddresses;

  private final RaftPeerClientFactory peerClientFactory;
  private final Consumer<Map<String, PeerAddress>> membershipListener;

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition commitAdvanced = lock.newCondition();

  private volatile Role role = Role.FOLLOWER;
  private volatile String leaderHint;
  private volatile boolean running;

  private long commitIndex;
  private long lastApplied;
  private final Map<String, Long> nextIndex = new HashMap<>();
  private final Map<String, Long> matchIndex = new HashMap<>();
  private final Map<String, Semaphore> peerWake = new ConcurrentHashMap<>();
  private final Map<String, Thread> peerSenderThreads = new ConcurrentHashMap<>();

  /**
   * This node's own outstanding self-proposed {@link MembershipChange} log index, if any -- guarded
   * by {@link #lock}. The etcd-style safety rule this codebase ships in place of full joint
   * consensus: {@link #addServer}/{@link #removeServer} refuse to start a second one while this is
   * non-null, so at most one configuration is ever in flight. Recomputed from a log scan in {@link
   * #becomeLeaderLocked} (a newly-elected leader may inherit an uncommitted one from a previous
   * term) and cleared once that index is applied or truncated away.
   */
  private Long pendingMembershipChangeIndex;

  private final ScheduledExecutorService scheduler;
  private ScheduledFuture<?> electionTimeoutFuture;

  public RaftNode(
      String selfId, Map<String, RaftPeerClient> peers, RaftLog raftLog, StateStore store) {
    this(selfId, peers, raftLog, store, PROPOSE_TIMEOUT);
  }

  /**
   * Test-only: injects a short {@code proposeTimeout} so a test exercising the timeout path (F-14,
   * the ghost-write fix) doesn't have to wait out the real 5-second production value. Package-
   * private, exercised only by {@code RaftNodeSafetyMechanicsTest} in this same package.
   */
  RaftNode(
      String selfId,
      Map<String, RaftPeerClient> peers,
      RaftLog raftLog,
      StateStore store,
      Duration proposeTimeout) {
    this.selfId = selfId;
    this.peers = new HashMap<>(peers);
    this.bootstrapPeerAddresses = Map.of();
    this.peerClientFactory =
        address -> {
          throw new UnsupportedOperationException(
              "this RaftNode was constructed without peer addresses/a client factory; live "
                  + "membership changes (addServer/removeServer) are unsupported on it");
        };
    this.membershipListener = ignored -> {};
    this.raftLog = raftLog;
    this.store = store;
    this.proposeTimeout = proposeTimeout;
    this.scheduler = newTickScheduler(selfId);
    for (String peerId : this.peers.keySet()) {
      peerWake.put(peerId, new Semaphore(0));
    }
  }

  /**
   * The address-aware constructor: supports live membership changes ({@link #addServer}/{@link
   * #removeServer}). {@code peerClientFactory} builds a {@link RaftPeerClient} for any peer this
   * node doesn't already have a connection to -- both the {@code peers} bootstrap set given here
   * and any peer learned about later, either self-proposed or replicated from the current leader.
   * {@code membershipListener} is invoked with the complete new peer-address map every time
   * membership changes, under this node's own internal lock -- it must be fast and non-blocking
   * (updating a concurrent map, never real I/O); {@code StoreMain} wires this to keep {@code
   * StoreNode}'s client-redirect address book current.
   */
  public RaftNode(
      String selfId,
      Map<String, PeerAddress> peers,
      RaftPeerClientFactory peerClientFactory,
      RaftLog raftLog,
      StateStore store,
      Consumer<Map<String, PeerAddress>> membershipListener) {
    this(selfId, peers, peerClientFactory, raftLog, store, membershipListener, PROPOSE_TIMEOUT);
  }

  /** Test-only short-{@code proposeTimeout} counterpart to the address-aware constructor above. */
  RaftNode(
      String selfId,
      Map<String, PeerAddress> peers,
      RaftPeerClientFactory peerClientFactory,
      RaftLog raftLog,
      StateStore store,
      Consumer<Map<String, PeerAddress>> membershipListener,
      Duration proposeTimeout) {
    this.selfId = selfId;
    this.peerClientFactory = peerClientFactory;
    this.membershipListener = membershipListener;
    this.bootstrapPeerAddresses = Map.copyOf(peers);
    this.peers = new HashMap<>();
    for (Map.Entry<String, PeerAddress> e : peers.entrySet()) {
      this.peers.put(e.getKey(), peerClientFactory.connect(e.getValue()));
    }
    this.peerAddresses.putAll(peers);
    this.raftLog = raftLog;
    this.store = store;
    this.proposeTimeout = proposeTimeout;
    this.scheduler = newTickScheduler(selfId);
    for (String peerId : this.peers.keySet()) {
      peerWake.put(peerId, new Semaphore(0));
    }
  }

  private static ScheduledExecutorService newTickScheduler(String selfId) {
    return Executors.newSingleThreadScheduledExecutor(
        r -> Thread.ofVirtual().name("gimle-controlplane-raft-tick-" + selfId).unstarted(r));
  }

  public void start() {
    running = true;
    lock.lock();
    try {
      if (peers.isEmpty()) {
        // A single-node cluster's majority is 1 -- self alone -- so there is nothing to elect;
        // waiting out a real election timeout here would make every single-process caller (every
        // existing pre-Raft ApiServer/ControlPlaneMain usage) block for 150-300ms before its
        // first write. startElectionLocked() already becomes leader immediately once the
        // self-vote alone reaches majority (its own majority check runs before contacting any
        // peer), so this is the same real logic, just skipping the timer wait that peers.isEmpty()
        // makes pointless.
        startElectionLocked();
      } else {
        resetElectionTimerLocked();
      }
    } finally {
      lock.unlock();
    }
  }

  public void close() {
    running = false;
    lock.lock();
    try {
      if (electionTimeoutFuture != null) {
        electionTimeoutFuture.cancel(false);
      }
    } finally {
      lock.unlock();
    }
    scheduler.shutdownNow();
    for (Thread t : peerSenderThreads.values()) {
      t.interrupt();
    }
  }

  public boolean isLeader() {
    return role == Role.LEADER;
  }

  public Optional<String> leaderHint() {
    return Optional.ofNullable(leaderHint);
  }

  public String selfId() {
    return selfId;
  }

  // ---- propose: the only entry point application code calls ----

  /**
   * Replicates {@code mutation} through the cluster and applies it to {@link StateStore}, blocking
   * until this node itself has applied it. Throws {@link GimleRaftException#notLeader} immediately
   * if this node isn't currently leader -- nothing is appended, nothing is sent; a non-leader
   * rejects with a redirect rather than silently forwarding the write.
   */
  @Override
  public void propose(StateMutation mutation) {
    long index;
    lock.lock();
    try {
      if (role != Role.LEADER) {
        throw GimleRaftException.notLeader(selfId, leaderHint());
      }
      long term = raftLog.currentTerm();
      index = raftLog.lastIndex() + 1;
      raftLog.append(new LogEntry(term, index, mutation));
      advanceCommitIndexLocked();
    } finally {
      lock.unlock();
    }
    wakePeerSenders();
    awaitAppliedThrowing(index);
  }

  // ---- membership changes: etcd-style, one at a time, not full joint consensus ----

  /**
   * Adds {@code peerId} to the cluster's configuration, blocking until this node has applied the
   * resulting {@link MembershipChange} entry. Throws {@link GimleRaftException#notLeader} if this
   * node isn't currently leader, and {@link GimleRaftException#membershipChangeInFlight} if an
   * earlier {@link #addServer}/{@link #removeServer} on this node hasn't committed yet -- the one
   * safety rule this reduction needs in place of full joint consensus's dual-majority machinery.
   */
  public void addServer(String peerId, PeerAddress address) {
    proposeMembershipChange(
        current -> {
          if (current.containsKey(peerId) || peerId.equals(selfId)) {
            throw GimleRaftException.alreadyAMember(selfId, peerId);
          }
          Map<String, PeerAddress> updated = new LinkedHashMap<>(current);
          updated.put(peerId, address);
          return updated;
        });
  }

  /** The symmetric removal counterpart to {@link #addServer}. */
  public void removeServer(String peerId) {
    proposeMembershipChange(
        current -> {
          if (!current.containsKey(peerId)) {
            throw GimleRaftException.notAMember(selfId, peerId);
          }
          Map<String, PeerAddress> updated = new LinkedHashMap<>(current);
          updated.remove(peerId);
          return updated;
        });
  }

  private void proposeMembershipChange(UnaryOperator<Map<String, PeerAddress>> transform) {
    long index;
    lock.lock();
    try {
      if (role != Role.LEADER) {
        throw GimleRaftException.notLeader(selfId, leaderHint());
      }
      if (pendingMembershipChangeIndex != null) {
        throw GimleRaftException.membershipChangeInFlight(selfId);
      }
      Map<String, PeerAddress> updated = transform.apply(Map.copyOf(peerAddresses));
      long term = raftLog.currentTerm();
      index = raftLog.lastIndex() + 1;
      raftLog.append(new LogEntry(term, index, new MembershipChange(updated)));
      // Effective on append, not on commit -- the one genuinely new Raft rule single-server
      // membership changes require; see RaftLogPayload's own javadoc.
      reconfigurePeersLocked(updated);
      pendingMembershipChangeIndex = index;
      advanceCommitIndexLocked();
    } finally {
      lock.unlock();
    }
    wakePeerSenders();
    awaitAppliedThrowing(index);
  }

  private void applyIfMembershipChangeLocked(LogEntry entry) {
    if (entry.payload() instanceof MembershipChange change) {
      reconfigurePeersLocked(change.peers());
    }
  }

  /**
   * Reconciles every mutable piece of per-peer state ({@link #peers}, {@link #nextIndex}, {@link
   * #matchIndex}, {@link #peerWake}, {@link #peerSenderThreads}) against {@code newPeerAddresses},
   * the cluster's new complete configuration -- called the instant a {@link MembershipChange} is
   * appended (leader, self-proposed) or replicated in (follower), never waiting for it to commit. A
   * brand-new peer's {@code nextIndex} starts at the snapshot floor rather than this node's log tip
   * (unlike {@link #becomeLeaderLocked}'s reset for already-known peers): it has seen none of the
   * log yet and must either replay it from there or catch up via {@code InstallSnapshot}. {@link
   * #membershipListener} is notified last, still under {@link #lock} -- it must stay fast and
   * non-blocking, per its own constructor-parameter javadoc.
   */
  private void reconfigurePeersLocked(Map<String, PeerAddress> newPeerAddresses) {
    for (Map.Entry<String, PeerAddress> e : newPeerAddresses.entrySet()) {
      String peerId = e.getKey();
      if (peerAddresses.containsKey(peerId)) {
        continue; // already a known peer; its address never changes once a member
      }
      RaftPeerClient client = peerClientFactory.connect(e.getValue());
      peers.put(peerId, client);
      nextIndex.put(peerId, raftLog.snapshotLastIncludedIndex() + 1);
      matchIndex.put(peerId, 0L);
      peerWake.put(peerId, new Semaphore(0));
      if (role == Role.LEADER) {
        startPeerSenderThreadLocked(peerId, client);
      }
    }
    for (String peerId : new ArrayList<>(peerAddresses.keySet())) {
      if (newPeerAddresses.containsKey(peerId)) {
        continue;
      }
      Thread sender = peerSenderThreads.remove(peerId);
      if (sender != null) {
        sender.interrupt();
      }
      peers.remove(peerId);
      nextIndex.remove(peerId);
      matchIndex.remove(peerId);
      peerWake.remove(peerId);
    }
    peerAddresses.clear();
    peerAddresses.putAll(newPeerAddresses);
    membershipListener.accept(Map.copyOf(peerAddresses));
  }

  /**
   * Recomputes "what the peer configuration would be right now" by scanning the log's tail backward
   * for the most recent {@link MembershipChange} still present, falling back to {@link
   * #bootstrapPeerAddresses} if none is found -- used by {@link #truncateFromLocked} to roll live
   * peer state back after removing an entry that changed it. Never needs to look below the snapshot
   * floor: a truncation only ever removes an uncommitted (hence unapplied, hence never yet
   * compacted) entry, by Raft's own safety guarantee that a leader never overwrites a committed
   * one.
   */
  private Map<String, PeerAddress> effectivePeerConfigLocked() {
    for (long i = raftLog.lastIndex(); i > raftLog.snapshotLastIncludedIndex(); i--) {
      Optional<LogEntry> entry = raftLog.get(i);
      if (entry.isPresent() && entry.get().payload() instanceof MembershipChange change) {
        return change.peers();
      }
    }
    return bootstrapPeerAddresses;
  }

  private void awaitAppliedThrowing(long index) {
    lock.lock();
    try {
      long deadlineNanos = System.nanoTime() + proposeTimeout.toNanos();
      while (lastApplied < index) {
        if (role != Role.LEADER) {
          throw giveUpAndTruncateLocked(index);
        }
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
          throw giveUpAndTruncateLocked(index);
        }
        try {
          commitAdvanced.awaitNanos(remaining);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw giveUpAndTruncateLocked(index);
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Called only while still holding {@link #lock}, with the loop condition {@code lastApplied <
   * index} already known true at that exact moment -- {@link #lock} is held for this method's
   * entire body (including across {@code commitAdvanced.awaitNanos}, which atomically re-acquires
   * it before returning), so there is no gap between that check and this call for a commit to sneak
   * into. Without this, a proposal reported as failed to its caller could still commit later once
   * quorum returns -- the ghost-write this method exists to close. {@link RaftLog#truncateFrom}
   * also removes any later, still-uncommitted entries appended after this one (a Raft log has no
   * gaps, so a suffix can't be partially removed); those proposals will separately time out and be
   * truncated in turn once their own deadlines elapse, each reporting its own honest failure rather
   * than silently inheriting this one's fate.
   */
  private GimleRaftException giveUpAndTruncateLocked(long index) {
    truncateFromLocked(index);
    return GimleRaftException.proposalTimedOut(selfId, proposeTimeout);
  }

  /**
   * Wraps {@link RaftLog#truncateFrom} with membership-change awareness: {@link
   * #reconfigurePeersLocked} already applied any {@link MembershipChange} entry the moment it was
   * appended, so removing that entry (a proposal that timed out before committing, or a
   * conflicting-entry truncation on a follower) must also roll the *live* peer state back --
   * otherwise a node's actual peer set would stay permanently changed even after the log entry that
   * changed it was undone. Recomputes from {@link #effectivePeerConfigLocked} rather than tracking
   * a single before/after snapshot, since a follower's conflicting-entry truncation can remove a
   * membership change this node never itself proposed.
   */
  private void truncateFromLocked(long index) {
    boolean membershipAffected = false;
    for (long i = index; i <= raftLog.lastIndex(); i++) {
      Optional<LogEntry> entry = raftLog.get(i);
      if (entry.isPresent() && entry.get().payload() instanceof MembershipChange) {
        membershipAffected = true;
        break;
      }
    }
    raftLog.truncateFrom(index);
    if (pendingMembershipChangeIndex != null && pendingMembershipChangeIndex >= index) {
      pendingMembershipChangeIndex = null;
    }
    if (membershipAffected) {
      reconfigurePeersLocked(effectivePeerConfigLocked());
    }
  }

  // ---- election ----

  private void resetElectionTimerLocked() {
    if (electionTimeoutFuture != null) {
      electionTimeoutFuture.cancel(false);
    }
    if (!running) {
      return;
    }
    int delayMs =
        ThreadLocalRandom.current().nextInt(ELECTION_TIMEOUT_MIN_MS, ELECTION_TIMEOUT_MAX_MS + 1);
    electionTimeoutFuture =
        scheduler.schedule(this::onElectionTimeout, delayMs, TimeUnit.MILLISECONDS);
  }

  private void onElectionTimeout() {
    lock.lock();
    try {
      if (!running || role == Role.LEADER) {
        return;
      }
      startElectionLocked();
    } finally {
      lock.unlock();
    }
  }

  private void startElectionLocked() {
    role = Role.CANDIDATE;
    long newTerm = raftLog.currentTerm() + 1;
    raftLog.setTermAndVote(newTerm, Optional.of(selfId));
    resetElectionTimerLocked();

    int majority = (peers.size() + 1) / 2 + 1;
    int[] votesGranted = {1}; // self-vote
    if (votesGranted[0] >= majority) {
      becomeLeaderLocked();
      return;
    }

    long lastLogIndex = raftLog.lastIndex();
    long lastLogTerm = raftLog.lastTerm();
    for (Map.Entry<String, RaftPeerClient> peerEntry : peers.entrySet()) {
      String peerId = peerEntry.getKey();
      RaftPeerClient client = peerEntry.getValue();
      Thread.ofVirtual()
          .name("gimle-raft-vote-" + selfId + "-to-" + peerId)
          .start(
              () -> {
                RequestVoteResponse response;
                try {
                  response =
                      client.requestVote(
                          new RequestVote(newTerm, selfId, lastLogIndex, lastLogTerm));
                } catch (RuntimeException e) {
                  log.debug("requestVote to {} failed: {}", peerId, e.getMessage());
                  return;
                }
                lock.lock();
                try {
                  if (response.term() > raftLog.currentTerm()) {
                    stepDownLocked(response.term());
                    return;
                  }
                  if (role != Role.CANDIDATE || raftLog.currentTerm() != newTerm) {
                    return; // stale response from a since-ended election
                  }
                  if (response.voteGranted()) {
                    votesGranted[0]++;
                    if (votesGranted[0] >= majority) {
                      becomeLeaderLocked();
                    }
                  }
                } finally {
                  lock.unlock();
                }
              });
    }
  }

  private void becomeLeaderLocked() {
    role = Role.LEADER;
    leaderHint = selfId;
    if (electionTimeoutFuture != null) {
      electionTimeoutFuture.cancel(false);
      electionTimeoutFuture = null;
    }
    long nextIdx = raftLog.lastIndex() + 1;
    for (String peerId : peers.keySet()) {
      nextIndex.put(peerId, nextIdx);
      matchIndex.put(peerId, 0L);
    }
    // A newly-elected leader may inherit an uncommitted MembershipChange its predecessor proposed
    // in an earlier term -- rediscovered here by scanning the tail of the log, so addServer/
    // removeServer correctly refuse a second one until this one actually commits.
    pendingMembershipChangeIndex = findUncommittedMembershipChangeIndexLocked();
    advanceCommitIndexLocked(); // a single-node cluster commits its own writes immediately
    for (Map.Entry<String, RaftPeerClient> peerEntry : peers.entrySet()) {
      startPeerSenderThreadLocked(peerEntry.getKey(), peerEntry.getValue());
    }
  }

  private void startPeerSenderThreadLocked(String peerId, RaftPeerClient client) {
    Thread t =
        Thread.ofVirtual()
            .name("gimle-raft-peer-" + selfId + "-to-" + peerId)
            .start(() -> peerSenderLoop(peerId, client));
    peerSenderThreads.put(peerId, t);
  }

  private Long findUncommittedMembershipChangeIndexLocked() {
    for (long i = raftLog.lastIndex(); i > commitIndex; i--) {
      Optional<LogEntry> entry = raftLog.get(i);
      if (entry.isPresent() && entry.get().payload() instanceof MembershipChange) {
        return i;
      }
    }
    return null;
  }

  private void stepDownLocked(long newTerm) {
    raftLog.setTermAndVote(newTerm, Optional.empty());
    boolean wasLeader = role == Role.LEADER;
    role = Role.FOLLOWER;
    leaderHint = null;
    pendingMembershipChangeIndex = null;
    resetElectionTimerLocked();
    if (wasLeader) {
      for (Thread t : peerSenderThreads.values()) {
        t.interrupt();
      }
      peerSenderThreads.clear();
      commitAdvanced.signalAll(); // wake any propose() waiters so they observe the role change
    }
  }

  // ---- leader replication ----

  private void wakePeerSenders() {
    for (Semaphore wake : peerWake.values()) {
      wake.release();
    }
  }

  private void peerSenderLoop(String peerId, RaftPeerClient client) {
    Semaphore wake = peerWake.get(peerId);
    while (running && role == Role.LEADER && !Thread.currentThread().isInterrupted()) {
      try {
        sendOnce(peerId, client);
      } catch (RuntimeException e) {
        log.debug("Raft replication to {} failed: {}", peerId, e.getMessage());
      }
      try {
        // Whether this returns true (a permit arrived) or false (the timeout elapsed) doesn't
        // change what happens next either way: the loop re-checks role/running and calls
        // sendOnce again regardless, so the result is deliberately unused -- only the wait
        // itself (early wake vs. heartbeat-interval fallback) matters.
        boolean woken = wake.tryAcquire(HEARTBEAT_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        if (woken) {
          wake.drainPermits();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void sendOnce(String peerId, RaftPeerClient client) {
    long term;
    long peerNextIndex;
    long snapshotFloor;
    lock.lock();
    try {
      if (role != Role.LEADER) {
        return;
      }
      term = raftLog.currentTerm();
      peerNextIndex = nextIndex.getOrDefault(peerId, raftLog.lastIndex() + 1);
      snapshotFloor = raftLog.snapshotLastIncludedIndex();
    } finally {
      lock.unlock();
    }

    if (peerNextIndex <= snapshotFloor) {
      sendInstallSnapshot(peerId, client, term);
      return;
    }

    AppendEntries request;
    lock.lock();
    try {
      if (role != Role.LEADER) {
        return;
      }
      long prevIndex = peerNextIndex - 1;
      long prevTerm = prevIndex <= 0 ? 0 : raftLog.termAt(prevIndex);
      List<LogEntry> entries = raftLog.entriesFrom(peerNextIndex);
      request = new AppendEntries(term, selfId, prevIndex, prevTerm, entries, commitIndex);
    } finally {
      lock.unlock();
    }

    AppendEntriesResponse response;
    try {
      response = client.appendEntries(request);
    } catch (RuntimeException e) {
      // unreachable this cycle; the next tick retries. A bounded gap in replication is acceptable.
      return;
    }

    lock.lock();
    try {
      if (response.term() > raftLog.currentTerm()) {
        stepDownLocked(response.term());
        return;
      }
      if (role != Role.LEADER || raftLog.currentTerm() != request.term()) {
        return; // stale response from a previous term/role
      }
      if (response.success()) {
        long newMatchIndex = request.prevLogIndex() + request.entries().size();
        matchIndex.put(peerId, Math.max(matchIndex.getOrDefault(peerId, 0L), newMatchIndex));
        nextIndex.put(peerId, newMatchIndex + 1);
        advanceCommitIndexLocked();
      } else {
        // one-index-at-a-time backtrack: correct but O(divergence) in the worst case, acceptable
        // for a small control-plane cluster where divergence is rare and small.
        long backedOff = Math.max(1, nextIndex.getOrDefault(peerId, 1L) - 1);
        nextIndex.put(peerId, backedOff);
      }
    } finally {
      lock.unlock();
    }
  }

  private void sendInstallSnapshot(String peerId, RaftPeerClient client, long term) {
    long lastIncludedIndex;
    long lastIncludedTerm;
    byte[] snapshotBytes;
    lock.lock();
    try {
      if (role != Role.LEADER) {
        return;
      }
      lastIncludedIndex = raftLog.snapshotLastIncludedIndex();
      lastIncludedTerm = raftLog.snapshotLastIncludedTerm();
      snapshotBytes =
          raftLog
              .snapshotBytes()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "snapshot floor set but no snapshot bytes persisted"));
    } finally {
      lock.unlock();
    }

    InstallSnapshotResponse response;
    try {
      response =
          client.installSnapshot(
              new InstallSnapshot(
                  term, selfId, lastIncludedIndex, lastIncludedTerm, snapshotBytes));
    } catch (RuntimeException e) {
      return;
    }

    lock.lock();
    try {
      if (response.term() > raftLog.currentTerm()) {
        stepDownLocked(response.term());
        return;
      }
      if (role != Role.LEADER || raftLog.currentTerm() != term) {
        return;
      }
      matchIndex.put(peerId, Math.max(matchIndex.getOrDefault(peerId, 0L), lastIncludedIndex));
      nextIndex.put(peerId, lastIncludedIndex + 1);
      advanceCommitIndexLocked();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Forces this node into {@code LEADER} with the given {@code matchIndex} table and runs the real
   * commit-index advancement logic -- a deterministic seam for exercising the commit-index term
   * rule (the Figure 8 scenario from the Raft paper) and apply-ordering without driving a full,
   * timing-sensitive multi-node election. Package-private: exercised only by {@code
   * RaftNodeSafetyMechanicsTest} in this same package, never by application code.
   */
  void forceLeaderForTest(Map<String, Long> matchIndexOverrides) {
    lock.lock();
    try {
      role = Role.LEADER;
      leaderHint = selfId;
      matchIndex.putAll(matchIndexOverrides);
      advanceCommitIndexLocked();
    } finally {
      lock.unlock();
    }
  }

  long commitIndexForTest() {
    lock.lock();
    try {
      return commitIndex;
    } finally {
      lock.unlock();
    }
  }

  long lastAppliedForTest() {
    lock.lock();
    try {
      return lastApplied;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Advances {@code commitIndex} to the highest index a majority of {@code matchIndex} values
   * (including this leader's own, always fully caught up with itself) reach, but only if that entry
   * was written in this leader's own current term (the Figure 8 rule from the Raft paper) -- the
   * condition a naive implementation omits, so it is its own explicit guard rather than folded into
   * one boolean expression.
   */
  private void advanceCommitIndexLocked() {
    List<Long> matchIndexes = new ArrayList<>();
    matchIndexes.add(raftLog.lastIndex());
    for (String peerId : peers.keySet()) {
      matchIndexes.add(matchIndex.getOrDefault(peerId, 0L));
    }
    matchIndexes.sort(Comparator.reverseOrder());
    long candidate = matchIndexes.get(matchIndexes.size() / 2);
    if (candidate > commitIndex && raftLog.termAt(candidate) == raftLog.currentTerm()) {
      commitIndex = candidate;
      applyCommittedLocked();
    }
  }

  /**
   * Applies every entry from {@code lastApplied + 1} through {@code commitIndex}, strictly in
   * order, never skipping ahead -- {@code commitIndex} and {@code lastApplied} are two separate
   * fields for exactly this reason, never collapsed into one. A {@link StateMutation} payload is
   * applied to {@link StateStore} here as always; a {@link MembershipChange} payload has nothing
   * further to do here -- {@link #reconfigurePeersLocked} already took effect the moment it was
   * appended, so committing it only needs to advance {@code lastApplied} past it and clear {@link
   * #pendingMembershipChangeIndex} if this was the one this node itself was waiting on.
   */
  private void applyCommittedLocked() {
    while (lastApplied < commitIndex) {
      long nextApply = lastApplied + 1;
      LogEntry entry =
          raftLog
              .get(nextApply)
              .orElseThrow(
                  () -> new IllegalStateException("missing committed entry at index " + nextApply));
      if (entry.payload() instanceof StateMutation mutation) {
        mutation.applyTo(store);
      }
      if (pendingMembershipChangeIndex != null && pendingMembershipChangeIndex == nextApply) {
        pendingMembershipChangeIndex = null;
      }
      lastApplied = nextApply;
    }
    commitAdvanced.signalAll();
    maybeCompactLocked();
  }

  private void maybeCompactLocked() {
    long logSize = raftLog.lastIndex() - raftLog.snapshotLastIncludedIndex();
    if (logSize <= SNAPSHOT_THRESHOLD || lastApplied <= raftLog.snapshotLastIncludedIndex()) {
      return;
    }
    long newFloor = lastApplied;
    long newFloorTerm = raftLog.termAt(newFloor);
    StateSnapshot snapshot = store.snapshot();
    // Deliberately does not carry the current peer configuration into the snapshot itself (a
    // genuine, narrower scope reduction, not an oversight): a peer added via addServer catches up
    // through normal AppendEntries replication of its own MembershipChange entry (initialized to
    // start replicating from the snapshot floor, not the leader's current tip -- see
    // reconfigurePeersLocked), which only races this compaction past that entry once the log
    // exceeds SNAPSHOT_THRESHOLD entries during the catch-up window -- not a concern for the small,
    // operator-driven clusters this reduction targets. A subsequent membership change re-syncs any
    // node whose view was left stale by that race.
    raftLog.installSnapshot(newFloor, newFloorTerm, RaftCodec.encodeSnapshot(snapshot));
  }

  // ---- inbound RPC handling (RaftRpcHandler) ----

  @Override
  public RequestVoteResponse onRequestVote(RequestVote request) {
    lock.lock();
    try {
      if (request.term() < raftLog.currentTerm()) {
        return new RequestVoteResponse(raftLog.currentTerm(), false);
      }
      if (request.term() > raftLog.currentTerm()) {
        stepDownLocked(request.term());
      }
      Optional<String> votedFor = raftLog.votedFor();
      boolean canVote = votedFor.isEmpty() || votedFor.get().equals(request.candidateId());
      boolean candidateUpToDate =
          request.lastLogTerm() > raftLog.lastTerm()
              || (request.lastLogTerm() == raftLog.lastTerm()
                  && request.lastLogIndex() >= raftLog.lastIndex());
      if (canVote && candidateUpToDate) {
        raftLog.setTermAndVote(raftLog.currentTerm(), Optional.of(request.candidateId()));
        resetElectionTimerLocked();
        return new RequestVoteResponse(raftLog.currentTerm(), true);
      }
      return new RequestVoteResponse(raftLog.currentTerm(), false);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public AppendEntriesResponse onAppendEntries(AppendEntries request) {
    lock.lock();
    try {
      if (request.term() < raftLog.currentTerm()) {
        return new AppendEntriesResponse(raftLog.currentTerm(), false, 0);
      }
      if (request.term() > raftLog.currentTerm()) {
        stepDownLocked(request.term());
      } else if (role == Role.CANDIDATE) {
        role = Role.FOLLOWER;
      }
      leaderHint = request.leaderId();
      resetElectionTimerLocked();

      long prevIndex = request.prevLogIndex();
      long snapshotFloor = raftLog.snapshotLastIncludedIndex();
      if (prevIndex > 0 && prevIndex >= snapshotFloor) {
        long termAtPrev;
        try {
          termAtPrev = raftLog.termAt(prevIndex);
        } catch (IllegalArgumentException e) {
          return new AppendEntriesResponse(raftLog.currentTerm(), false, 0);
        }
        if (termAtPrev != request.prevLogTerm()) {
          return new AppendEntriesResponse(raftLog.currentTerm(), false, 0);
        }
      }

      for (LogEntry entry : request.entries()) {
        if (entry.index() <= snapshotFloor) {
          continue; // already compacted; a no-op replay of an old entry
        }
        Optional<LogEntry> existing = raftLog.get(entry.index());
        if (existing.isPresent() && existing.get().term() != entry.term()) {
          // conflicting-entry truncation: delete it and everything after before appending the
          // leader's version -- never a mix of old and new at overlapping indices.
          truncateFromLocked(entry.index());
          raftLog.append(entry);
          applyIfMembershipChangeLocked(entry);
        } else if (existing.isEmpty()) {
          raftLog.append(entry);
          applyIfMembershipChangeLocked(entry);
        }
      }

      long newMatchIndex = prevIndex + request.entries().size();
      if (request.leaderCommitIndex() > commitIndex) {
        commitIndex = Math.min(request.leaderCommitIndex(), raftLog.lastIndex());
        applyCommittedLocked();
      }
      return new AppendEntriesResponse(raftLog.currentTerm(), true, newMatchIndex);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public InstallSnapshotResponse onInstallSnapshot(InstallSnapshot request) {
    lock.lock();
    try {
      if (request.term() < raftLog.currentTerm()) {
        return new InstallSnapshotResponse(raftLog.currentTerm());
      }
      if (request.term() > raftLog.currentTerm()) {
        stepDownLocked(request.term());
      } else if (role == Role.CANDIDATE) {
        role = Role.FOLLOWER;
      }
      leaderHint = request.leaderId();
      resetElectionTimerLocked();

      if (request.lastIncludedIndex() <= raftLog.snapshotLastIncludedIndex()) {
        return new InstallSnapshotResponse(
            raftLog.currentTerm()); // already have an equal/newer one
      }

      StateSnapshot decoded = RaftCodec.decodeSnapshot(request.snapshotBytes());
      store.restoreFromSnapshot(decoded);
      raftLog.installSnapshot(
          request.lastIncludedIndex(), request.lastIncludedTerm(), request.snapshotBytes());
      commitIndex = Math.max(commitIndex, request.lastIncludedIndex());
      lastApplied = Math.max(lastApplied, request.lastIncludedIndex());
      commitAdvanced.signalAll();
      return new InstallSnapshotResponse(raftLog.currentTerm());
    } finally {
      lock.unlock();
    }
  }
}
