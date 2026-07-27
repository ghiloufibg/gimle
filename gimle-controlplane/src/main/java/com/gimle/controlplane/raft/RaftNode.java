package com.gimle.controlplane.raft;

import com.gimle.controlplane.store.StateSnapshot;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.exception.GimleRaftException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
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
  private final Map<String, RaftPeerClient> peers;
  private final RaftLog raftLog;
  private final StateStore store;

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
  private final List<Thread> peerSenderThreads = new CopyOnWriteArrayList<>();

  private final ScheduledExecutorService scheduler;
  private ScheduledFuture<?> electionTimeoutFuture;

  public RaftNode(
      String selfId, Map<String, RaftPeerClient> peers, RaftLog raftLog, StateStore store) {
    this.selfId = selfId;
    this.peers = Map.copyOf(peers);
    this.raftLog = raftLog;
    this.store = store;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-controlplane-raft-tick-" + selfId).unstarted(r));
    for (String peerId : this.peers.keySet()) {
      peerWake.put(peerId, new Semaphore(0));
    }
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
    for (Thread t : peerSenderThreads) {
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

  private void awaitAppliedThrowing(long index) {
    lock.lock();
    try {
      long deadlineNanos = System.nanoTime() + PROPOSE_TIMEOUT.toNanos();
      while (lastApplied < index) {
        if (role != Role.LEADER) {
          throw GimleRaftException.proposalTimedOut(selfId, PROPOSE_TIMEOUT);
        }
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
          throw GimleRaftException.proposalTimedOut(selfId, PROPOSE_TIMEOUT);
        }
        try {
          commitAdvanced.awaitNanos(remaining);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw GimleRaftException.proposalTimedOut(selfId, PROPOSE_TIMEOUT);
        }
      }
    } finally {
      lock.unlock();
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
    advanceCommitIndexLocked(); // a single-node cluster commits its own writes immediately
    for (Map.Entry<String, RaftPeerClient> peerEntry : peers.entrySet()) {
      String peerId = peerEntry.getKey();
      RaftPeerClient client = peerEntry.getValue();
      Thread t =
          Thread.ofVirtual()
              .name("gimle-raft-peer-" + selfId + "-to-" + peerId)
              .start(() -> peerSenderLoop(peerId, client));
      peerSenderThreads.add(t);
    }
  }

  private void stepDownLocked(long newTerm) {
    raftLog.setTermAndVote(newTerm, Optional.empty());
    boolean wasLeader = role == Role.LEADER;
    role = Role.FOLLOWER;
    leaderHint = null;
    resetElectionTimerLocked();
    if (wasLeader) {
      for (Thread t : peerSenderThreads) {
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
        wake.tryAcquire(HEARTBEAT_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        wake.drainPermits();
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
   * fields for exactly this reason, never collapsed into one.
   */
  private void applyCommittedLocked() {
    while (lastApplied < commitIndex) {
      long nextApply = lastApplied + 1;
      LogEntry entry =
          raftLog
              .get(nextApply)
              .orElseThrow(
                  () -> new IllegalStateException("missing committed entry at index " + nextApply));
      entry.mutation().applyTo(store);
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
          raftLog.truncateFrom(entry.index());
          raftLog.append(entry);
        } else if (existing.isEmpty()) {
          raftLog.append(entry);
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
