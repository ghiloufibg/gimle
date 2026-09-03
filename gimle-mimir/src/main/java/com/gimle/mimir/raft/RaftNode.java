package com.gimle.mimir.raft;

import com.gimle.core.exception.GimleRaftException;
import com.gimle.mimir.store.StateSnapshot;
import com.gimle.mimir.store.StateStore;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

  /**
   * Overridable via {@code -Dgimle.raft.proposeTimeoutSeconds} -- production default stays 5s
   * unless explicitly configured, but a shared/loaded test sandbox can raise this without touching
   * every call site: the root pom's surefire configuration sets it to a more generous value for
   * exactly that reason. {@link #awaitAppliedThrowing} is the sole reader of the resolved value
   * (via {@link #proposeTimeout}, captured once at construction).
   */
  private static final Duration PROPOSE_TIMEOUT =
      Duration.ofSeconds(Long.getLong("gimle.raft.proposeTimeoutSeconds", 5));

  /** A concrete, tunable threshold for triggering log compaction, not left open-ended. */
  private static final long SNAPSHOT_THRESHOLD = 10_000;

  /**
   * How close a learner's {@link #matchIndex} must be to the leader's own {@link RaftLog#lastIndex}
   * before {@link #maybePromoteLearnerLocked} promotes it to a full voting member. Not zero: {@link
   * #sendOnce} builds each {@code AppendEntries} request and processes its response under two
   * separate lock acquisitions with the real network round trip unlocked in between, so a proposal
   * from another thread can land in that gap and advance {@link RaftLog#lastIndex} past what this
   * exact response covers -- an exact-match requirement could keep missing that receding target
   * under any steady trickle of writes. A handful of entries is close enough that ordinary
   * replication (running at least every {@link #HEARTBEAT_INTERVAL}) closes the remainder within
   * one or two more rounds after promotion, same as any other voting follower's normal small lag.
   */
  private static final long LEARNER_CATCH_UP_THRESHOLD = 10;

  /**
   * Check-quorum window (Raft dissertation §6.2/etcd's {@code CheckQuorum}): a leader that hasn't
   * completed an RPC round trip -- success or reject, either proves the peer is reachable and on a
   * term this leader still recognizes; only a thrown exception (unreachable this cycle) leaves
   * {@link #lastContactAt} untouched -- with a majority of its own voting peers within this window
   * steps itself down, on its own, without waiting to observe a higher term. Set to {@link
   * #ELECTION_TIMEOUT_MAX_MS}, the same "one election timeout" upper bound a healthy follower
   * itself tolerates before concluding its leader is gone; {@link #checkQuorumTick} runs every
   * {@link #HEARTBEAT_INTERVAL} (far more often than this window), so this is the binding bound in
   * practice, not the check's own polling granularity.
   *
   * <p>This governs a leader already exchanging heartbeats with its peers. Getting those exchanges
   * started in the first place is a much slower one-off, bounded by {@link
   * #LEADERSHIP_CONTACT_GRACE} instead -- applying this window to it judges every new leader
   * against a deadline its own transport cannot meet.
   */
  private static final Duration CHECK_QUORUM_WINDOW = Duration.ofMillis(ELECTION_TIMEOUT_MAX_MS);

  /**
   * How long a freshly-elected leader may go without having reached a majority before {@link
   * #checkQuorumTick} is willing to call that a quorum failure rather than an answer it has not
   * received yet. This is deliberately far larger than {@link #CHECK_QUORUM_WINDOW}, and for a
   * different question: that window measures how long an *established* leader tolerates silence
   * from peers it has already been talking to every {@link #HEARTBEAT_INTERVAL}, whereas this one
   * covers the one-off cost of getting those conversations started at all.
   *
   * <p>A new leader clears {@link #lastContactAt} and starts its peer senders from nothing: every
   * peer socket must be opened from scratch -- a TCP connect, and under mTLS a full TLS handshake
   * on top of it -- before a single round trip can complete. {@link PeerConnection} allows one
   * attempt {@link PeerConnection#worstCaseCallDuration()} to finish or fail, which is more than an
   * order of magnitude longer than {@code CHECK_QUORUM_WINDOW}. Judged against that window, a new
   * leader whose first connects are merely slow -- a loaded machine, a peer still starting up
   * during a rolling restart -- looks exactly like one whose peers are all gone, and demotes on its
   * first tick. Its successor inherits the same empty table and the same deadline and does the same
   * thing, so the cluster elects leaders indefinitely without any of them living long enough to
   * serve a write. Allowing one full attempt (plus a tick's scheduling slop) means the check only
   * ever fires on evidence of silence, never on the absence of evidence yet.
   */
  private static final Duration LEADERSHIP_CONTACT_GRACE =
      PeerConnection.worstCaseCallDuration().plus(HEARTBEAT_INTERVAL);

  /**
   * {@link InstallSnapshot} chunk size (Raft paper Figure 13). Not tuned against a real network MTU
   * -- this project has no cross-datacenter link to size against -- just kept well under {@link
   * RaftCodec}'s {@code MAX_FRAME_LENGTH} so a chunk is a small fraction of that ceiling, not a
   * value that could itself approach it for an unusually large snapshot.
   */
  private static final int SNAPSHOT_CHUNK_BYTES = 512 * 1024;

  private final String selfId;
  private Map<String, RaftPeerClient> peers;
  private final RaftLog raftLog;
  private final StateStore store;
  private final Duration proposeTimeout;

  /**
   * Read by {@link #checkQuorumTick} (via {@link #lastContactAt}) to decide whether a peer has gone
   * stale. Not consulted anywhere {@link #propose}'s own wait blocks on real time -- see {@link
   * #awaitAppliedThrowing}'s own comment for why that wait stays on {@code System.nanoTime()}
   * regardless of what this is set to. Production always uses {@link Clock#systemUTC()}; a test can
   * inject a virtual one (paired with a matching {@link #scheduler}) to exercise check-quorum
   * self-demotion and election-timeout scheduling without waiting out the real windows.
   */
  private final Clock clock;

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

  /**
   * This node's own {@code host:raftPort:clientPort}, {@code null} for a legacy-constructed node
   * (which, per {@link #peerAddresses}'s own javadoc, never calls {@link #addServer}/{@link
   * #removeServer} anyway). Folded into the full, self-inclusive membership every {@link
   * MembershipChange} log entry now carries (see {@link #appendMembershipChangeLocked}) -- without
   * it, a {@link MembershipChange} built from this node's own {@link #peerAddresses} (which, by
   * that field's own invariant, never lists this node itself) would omit the proposer from every
   * *other* node's resulting peer set once replicated, since each receiving node applies the exact
   * same map. Self-inclusion here is what lets {@link #reconfigurePeersLocked} correctly recover
   * every node's own peer set -- proposer included -- by dropping only its own id.
   */
  private final PeerAddress selfAddress;

  /**
   * The current membership's non-voting learner ids -- guarded by {@link #lock}, kept in sync the
   * same "effective on append" way {@link #peerAddresses} is (see {@link #reconfigurePeersLocked}).
   * Unlike {@link #peerAddresses}, deliberately left self-inclusive when this node's own id is a
   * member of it: a node needs to see its own id here to know it is currently a learner and must
   * not campaign for election (see {@link #onElectionTimeout}) -- the disruptive-candidate hazard
   * an immediately-voting new peer otherwise creates: its lagging log means it can never actually
   * win an election, but Raft's {@code RequestVote} handling still makes every real voter --
   * including a perfectly healthy leader -- bump its term and step down the instant the request
   * arrives, before the log up-to-date check is even consulted (see {@link #onRequestVote}). A
   * newly {@link #addServer}-ed peer starts here; {@link #maybePromoteLearnerLocked} removes it,
   * via that same log-replicated path, once its {@link #matchIndex} has caught up.
   */
  private final Set<String> learners = new HashSet<>();

  private final RaftPeerClientFactory peerClientFactory;
  private final Consumer<Map<String, PeerAddress>> membershipListener;

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition commitAdvanced = lock.newCondition();

  private volatile Role role = Role.FOLLOWER;
  private volatile String leaderHint;
  private volatile boolean running;

  private long commitIndex;
  private long lastApplied;

  /**
   * Every committed {@link StateMutation}'s own {@link MutationOutcome}, guarded by {@link #lock}
   * like {@link #lastApplied} itself -- populated only while this node believes itself leader (a
   * follower never has anyone waiting on {@link #awaitAppliedThrowing} for these indices, so
   * recording them there would only leak) and consumed exactly once, by the one {@link #propose}
   * call that appended this exact index, immediately after {@link #awaitAppliedThrowing} returns. A
   * {@link MembershipChange} entry never populates this map; {@link #awaitAppliedThrowing} defaults
   * to {@link MutationOutcome#accepted()} for an index with no entry, which every caller except
   * {@link #propose} itself (addServer/removeServer) simply discards.
   */
  private final Map<Long, MutationOutcome> pendingOutcomes = new HashMap<>();

  private final Map<String, Long> nextIndex = new HashMap<>();
  private final Map<String, Long> matchIndex = new HashMap<>();
  private final Map<String, Semaphore> peerWake = new ConcurrentHashMap<>();
  private final Map<String, Thread> peerSenderThreads = new ConcurrentHashMap<>();

  /**
   * Check-quorum bookkeeping (see {@link #CHECK_QUORUM_WINDOW}): per-peer {@link #clock} instant of
   * the most recent RPC round trip this leader completed with it, guarded by {@link #lock} like
   * every other per-peer table here. Cleared on every {@link #becomeLeaderLocked} (a peer's
   * staleness is only meaningful relative to *this* leadership tenure, not a previous one) and
   * simply never consulted while not leader -- {@link #checkQuorumTick} is itself only scheduled
   * for the duration of one. Read against {@link #clock} rather than {@code System.nanoTime()} so a
   * test driving this node with an injected virtual clock/scheduler observes the same passage of
   * time this bookkeeping does.
   */
  private final Map<String, Instant> lastContactAt = new HashMap<>();

  /**
   * When this node last became leader, guarded by {@link #lock} -- the instant {@link
   * #LEADERSHIP_CONTACT_GRACE} is measured from. Only ever read by {@link #checkQuorumTick}, which
   * returns early unless this node is currently leader, so the value left behind after a demotion
   * is never consulted.
   */
  private Instant leadershipEstablishedAt;

  /**
   * Whether this leadership tenure has ever seen a majority of its own voting peers inside {@link
   * #CHECK_QUORUM_WINDOW} -- guarded by {@link #lock}, reset on every {@link #becomeLeaderLocked}.
   * Once true, {@link #LEADERSHIP_CONTACT_GRACE} stops applying: the grace exists only to cover
   * establishing contact in the first place, so a leader that has been talking to a majority and
   * then loses them is judged by the ordinary window, and still steps down within it.
   */
  private boolean initialQuorumContactMade;

  private ScheduledFuture<?> checkQuorumFuture;

  /**
   * This node's own outstanding self-proposed {@link MembershipChange} log index, if any -- guarded
   * by {@link #lock}. The etcd-style safety rule this codebase ships in place of full joint
   * consensus: {@link #addServer}/{@link #removeServer} refuse to start a second one while this is
   * non-null, so at most one configuration is ever in flight. Recomputed from a log scan in {@link
   * #becomeLeaderLocked} (a newly-elected leader may inherit an uncommitted one from a previous
   * term) and cleared once that index is applied or truncated away.
   */
  private Long pendingMembershipChangeIndex;

  /**
   * As-follower accumulator for an in-progress chunked {@link InstallSnapshot} transfer (Raft paper
   * Figure 13) -- guarded by {@link #lock}, all four reset together whenever an {@code offset == 0}
   * chunk arrives (a genuinely new transfer, or the leader restarting the send loop after a
   * mid-transfer failure; {@link #sendInstallSnapshot} always restarts from byte 0, never resumes).
   * {@code null}/{@code -1} when no transfer is in flight.
   */
  private String pendingSnapshotLeaderId;

  private long pendingSnapshotTerm = -1;
  private long pendingSnapshotLastIncludedIndex;
  private long pendingSnapshotLastIncludedTerm;
  private ByteArrayOutputStream pendingSnapshotBuffer;

  /**
   * Fires {@link #electionTimeoutFuture}/{@link #checkQuorumFuture} -- production always uses a
   * real single-thread {@link #newTickScheduler}; a test can substitute a {@code TestScheduler}
   * (paired with the matching {@link #clock} it fires against) to drive both deterministically via
   * {@code advance(Duration)} instead of waiting out the real windows. {@link #peerSenderLoop}'s
   * own heartbeat pacing and {@link #awaitAppliedThrowing}'s propose-commit wait are deliberately
   * not routed through this seam -- see their own comments for why.
   */
  private final ScheduledExecutorService scheduler;

  private ScheduledFuture<?> electionTimeoutFuture;

  public RaftNode(
      String selfId, Map<String, RaftPeerClient> peers, RaftLog raftLog, StateStore store) {
    this(selfId, peers, raftLog, store, PROPOSE_TIMEOUT);
  }

  /**
   * Test-only: injects a short {@code proposeTimeout} so a test exercising the timeout path (a
   * timed-out proposal must be truncated so it can't silently ghost-commit once quorum returns --
   * see {@code RaftNodeSafetyMechanicsTest}) doesn't have to wait out the real 5-second production
   * value. Package-private, exercised only by that test class, in this same package.
   */
  RaftNode(
      String selfId,
      Map<String, RaftPeerClient> peers,
      RaftLog raftLog,
      StateStore store,
      Duration proposeTimeout) {
    this(
        selfId, peers, raftLog, store, proposeTimeout, Clock.systemUTC(), newTickScheduler(selfId));
  }

  /**
   * Test-only: the full legacy-constructor implementation, with the {@link #clock}/{@link
   * #scheduler} seam also exposed -- injecting a virtual pair (a {@code TestClock} plus a {@code
   * TestScheduler} built from it) lets a test drive election-timeout and check-quorum-tick firing
   * with {@code advance(Duration)} rather than real sleeps. Package-private, exercised only by test
   * code in this same package.
   */
  RaftNode(
      String selfId,
      Map<String, RaftPeerClient> peers,
      RaftLog raftLog,
      StateStore store,
      Duration proposeTimeout,
      Clock clock,
      ScheduledExecutorService scheduler) {
    this.selfId = selfId;
    this.peers = new HashMap<>(peers);
    this.bootstrapPeerAddresses = Map.of();
    this.selfAddress = null;
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
    this.clock = clock;
    this.scheduler = scheduler;
    // A reopened RaftLog already covers everything up to its own persisted snapshot floor (that
    // floor is exactly the lastApplied index at the moment it was taken -- see
    // maybeCompactLocked); starting these at 0 instead would make applyCommittedLocked walk from
    // index 1 the moment this node next advances its commit index, which throws once any entry
    // below the floor has been physically discarded by compaction. Mirrors the floor bump
    // onInstallSnapshot already applies when a snapshot arrives from a live peer instead of disk.
    this.commitIndex = raftLog.snapshotLastIncludedIndex();
    this.lastApplied = raftLog.snapshotLastIncludedIndex();
    // The state machine holds nothing durable of its own: rebuild it here from the log's persisted
    // snapshot, and let ordinary commit advancement re-apply whatever committed entries sit above
    // the floor -- a follower learns its leader's commit index on first contact, and a fresh
    // leader's own no-op entry (see becomeLeaderLocked) forces the same catch-up on election.
    raftLog.loadSnapshot().ifPresent(store::restoreFromSnapshot);
    for (String peerId : this.peers.keySet()) {
      peerWake.put(peerId, new Semaphore(0));
    }
  }

  /**
   * The address-aware constructor: supports live membership changes ({@link #addServer}/{@link
   * #removeServer}). {@code selfAddress} is this node's own dialable address, needed only to build
   * the self-inclusive {@link MembershipChange} entries {@link #appendMembershipChangeLocked}
   * proposes (see {@link #selfAddress}'s own javadoc) -- a node that never calls {@code
   * addServer}/{@code removeServer} could in principle pass anything here, but every real caller
   * has a real address to give. {@code peerClientFactory} builds a {@link RaftPeerClient} for any
   * peer this node doesn't already have a connection to -- both the {@code peers} bootstrap set
   * given here and any peer learned about later, either self-proposed or replicated from the
   * current leader. {@code membershipListener} is invoked with the complete new peer-address map
   * every time membership changes, under this node's own internal lock -- it must be fast and
   * non-blocking (updating a concurrent map, never real I/O); {@code StoreMain} wires this to keep
   * {@code StoreNode}'s client-redirect address book current.
   */
  public RaftNode(
      String selfId,
      PeerAddress selfAddress,
      Map<String, PeerAddress> peers,
      RaftPeerClientFactory peerClientFactory,
      RaftLog raftLog,
      StateStore store,
      Consumer<Map<String, PeerAddress>> membershipListener) {
    this(
        selfId,
        selfAddress,
        peers,
        peerClientFactory,
        raftLog,
        store,
        membershipListener,
        PROPOSE_TIMEOUT);
  }

  /** Test-only short-{@code proposeTimeout} counterpart to the address-aware constructor above. */
  RaftNode(
      String selfId,
      PeerAddress selfAddress,
      Map<String, PeerAddress> peers,
      RaftPeerClientFactory peerClientFactory,
      RaftLog raftLog,
      StateStore store,
      Consumer<Map<String, PeerAddress>> membershipListener,
      Duration proposeTimeout) {
    this(
        selfId,
        selfAddress,
        peers,
        peerClientFactory,
        raftLog,
        store,
        membershipListener,
        proposeTimeout,
        Clock.systemUTC(),
        newTickScheduler(selfId));
  }

  /**
   * Test-only: the full address-aware-constructor implementation, with the {@link #clock}/{@link
   * #scheduler} seam also exposed -- see the short-{@code proposeTimeout} legacy-constructor
   * overload's own javadoc for what injecting a virtual pair here buys a test. Package-private,
   * exercised only by test code in this same package.
   */
  RaftNode(
      String selfId,
      PeerAddress selfAddress,
      Map<String, PeerAddress> peers,
      RaftPeerClientFactory peerClientFactory,
      RaftLog raftLog,
      StateStore store,
      Consumer<Map<String, PeerAddress>> membershipListener,
      Duration proposeTimeout,
      Clock clock,
      ScheduledExecutorService scheduler) {
    this.selfId = selfId;
    this.selfAddress = selfAddress;
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
    this.clock = clock;
    this.scheduler = scheduler;
    // A reopened RaftLog already covers everything up to its own persisted snapshot floor (that
    // floor is exactly the lastApplied index at the moment it was taken -- see
    // maybeCompactLocked); starting these at 0 instead would make applyCommittedLocked walk from
    // index 1 the moment this node next advances its commit index, which throws once any entry
    // below the floor has been physically discarded by compaction. Mirrors the floor bump
    // onInstallSnapshot already applies when a snapshot arrives from a live peer instead of disk.
    this.commitIndex = raftLog.snapshotLastIncludedIndex();
    this.lastApplied = raftLog.snapshotLastIncludedIndex();
    // The state machine holds nothing durable of its own: rebuild it here from the log's persisted
    // snapshot, and let ordinary commit advancement re-apply whatever committed entries sit above
    // the floor -- a follower learns its leader's commit index on first contact, and a fresh
    // leader's own no-op entry (see becomeLeaderLocked) forces the same catch-up on election.
    raftLog.loadSnapshot().ifPresent(store::restoreFromSnapshot);
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
      if (checkQuorumFuture != null) {
        checkQuorumFuture.cancel(false);
      }
    } finally {
      lock.unlock();
    }
    scheduler.shutdownNow();
    for (Thread t : peerSenderThreads.values()) {
      t.interrupt();
    }
    raftLog.close();
    for (RaftPeerClient client : peers.values()) {
      if (client instanceof AutoCloseable closeable) {
        try {
          closeable.close();
        } catch (Exception e) {
          log.warn("failed to close peer connection: {}", e.getMessage());
        }
      }
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

  /**
   * This node's currently configured membership, itself included, sorted for a stable wire shape --
   * a snapshot taken under the lock, since {@link #peers} is reconfigured in place by membership
   * changes.
   */
  public List<String> memberIds() {
    lock.lock();
    try {
      final List<String> members = new ArrayList<>(peers.keySet());
      members.add(selfId);
      members.sort(String::compareTo);
      return List.copyOf(members);
    } finally {
      lock.unlock();
    }
  }

  // ---- propose: the only entry point application code calls ----

  /**
   * Replicates {@code mutation} through the cluster and applies it to {@link StateStore}, blocking
   * until this node itself has applied it, then returning the resulting {@link MutationOutcome}.
   * Throws {@link GimleRaftException#notLeader} immediately if this node isn't currently leader --
   * nothing is appended, nothing is sent; a non-leader rejects with a redirect rather than silently
   * forwarding the write. A {@link MutationOutcome.Rejected} result is a normal, expected value for
   * a CAS-guarded mutation whose precondition no longer held -- not an exception, since retrying
   * against this same, correct leader would reject identically.
   */
  @Override
  public MutationOutcome propose(StateMutation mutation) {
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
    return awaitAppliedThrowing(index);
  }

  // ---- membership changes: etcd-style, one at a time, not full joint consensus ----

  /**
   * Adds {@code peerId} to the cluster's configuration as a non-voting learner, blocking until this
   * node has applied the resulting {@link MembershipChange} entry -- not yet a full voting member;
   * see {@link #learners} and {@link #maybePromoteLearnerLocked} for the automatic promotion once
   * its log catches up. Throws {@link GimleRaftException#notLeader} if this node isn't currently
   * leader, and {@link GimleRaftException#membershipChangeInFlight} if an earlier {@link
   * #addServer}/{@link #removeServer}/promotion on this node hasn't committed yet -- the one safety
   * rule this reduction needs in place of full joint consensus's dual-majority machinery.
   */
  public void addServer(String peerId, PeerAddress address) {
    long index;
    lock.lock();
    try {
      requireReadyForMembershipChangeLocked();
      if (peerAddresses.containsKey(peerId) || peerId.equals(selfId)) {
        throw GimleRaftException.alreadyAMember(selfId, peerId);
      }
      Map<String, PeerAddress> updatedPeers = new LinkedHashMap<>(peerAddresses);
      updatedPeers.put(peerId, address);
      Set<String> updatedLearners = new LinkedHashSet<>(learners);
      updatedLearners.add(peerId);
      index = appendMembershipChangeLocked(updatedPeers, updatedLearners);
    } finally {
      lock.unlock();
    }
    wakePeerSenders();
    awaitAppliedThrowing(index);
  }

  /** The symmetric removal counterpart to {@link #addServer}. */
  public void removeServer(String peerId) {
    long index;
    lock.lock();
    try {
      requireReadyForMembershipChangeLocked();
      if (!peerAddresses.containsKey(peerId)) {
        throw GimleRaftException.notAMember(selfId, peerId);
      }
      Map<String, PeerAddress> updatedPeers = new LinkedHashMap<>(peerAddresses);
      updatedPeers.remove(peerId);
      Set<String> updatedLearners = new LinkedHashSet<>(learners);
      updatedLearners.remove(peerId);
      index = appendMembershipChangeLocked(updatedPeers, updatedLearners);
    } finally {
      lock.unlock();
    }
    wakePeerSenders();
    awaitAppliedThrowing(index);
  }

  private void requireReadyForMembershipChangeLocked() {
    if (role != Role.LEADER) {
      throw GimleRaftException.notLeader(selfId, leaderHint());
    }
    if (pendingMembershipChangeIndex != null) {
      throw GimleRaftException.membershipChangeInFlight(selfId);
    }
    if (selfAddress == null) {
      throw new UnsupportedOperationException(
          "this RaftNode was constructed without a self address (the legacy fixed-peer-map "
              + "constructor); live membership changes (addServer/removeServer) are unsupported on "
              + "it");
    }
  }

  /**
   * Appends and locally applies a {@link MembershipChange} -- effective on append, not on commit
   * (see {@link RaftLogPayload}'s own javadoc) -- shared by {@link #addServer}/{@link
   * #removeServer} (which then block their own caller on {@link #awaitAppliedThrowing}) and {@link
   * #maybePromoteLearnerLocked} (which does not: nothing calls it synchronously, so nothing needs
   * to wait on its result; it will commit on this same replication loop's own next successful round
   * if not this one). {@code newPeers} is still this leader's own self-exclusive view (the {@link
   * #peerAddresses} invariant every call site already relies on); the log entry itself carries the
   * full self-inclusive membership -- {@code newPeers} plus this leader's own {@link #selfAddress}
   * -- so that every *other* node, applying the identical entry, can recover the leader as one of
   * its own peers too by dropping only its own id (see {@link #reconfigurePeersLocked}).
   */
  private long appendMembershipChangeLocked(
      Map<String, PeerAddress> newPeers, Set<String> newLearners) {
    long term = raftLog.currentTerm();
    long index = raftLog.lastIndex() + 1;
    Map<String, PeerAddress> replicated = new LinkedHashMap<>(newPeers);
    replicated.put(selfId, selfAddress);
    raftLog.append(new LogEntry(term, index, new MembershipChange(replicated, newLearners)));
    reconfigurePeersLocked(newPeers, newLearners);
    pendingMembershipChangeIndex = index;
    advanceCommitIndexLocked();
    return index;
  }

  /**
   * Checked every time this leader records a successful {@code AppendEntries}/{@code
   * InstallSnapshot} acknowledgment from a peer ({@link #sendOnce}, {@link #sendInstallSnapshot})
   * -- a learner (see {@link #learners}) whose {@link #matchIndex} has caught up to within {@link
   * #LEARNER_CATCH_UP_THRESHOLD} of this leader's own {@link RaftLog#lastIndex} is promoted to a
   * full voting member automatically, via the same log-replicated path {@link #addServer}/{@link
   * #removeServer} use, so every node -- not just this leader's own local memory -- durably agrees
   * on the promotion. A no-op if a membership change is already in flight (the same one-at-a-time
   * rule {@link #addServer}/{@link #removeServer} enforce -- promotion simply retries on this same
   * peer's next successful round) or if {@code peerId} isn't actually a currently-known learner (an
   * already-voting peer, or one that raced a concurrent {@link #removeServer}).
   */
  private void maybePromoteLearnerLocked(String peerId) {
    if (role != Role.LEADER
        || pendingMembershipChangeIndex != null
        || !learners.contains(peerId)
        || !peerAddresses.containsKey(peerId)) {
      return;
    }
    long gap = raftLog.lastIndex() - matchIndex.getOrDefault(peerId, 0L);
    if (gap > LEARNER_CATCH_UP_THRESHOLD) {
      return;
    }
    Set<String> updatedLearners = new LinkedHashSet<>(learners);
    updatedLearners.remove(peerId);
    long index = appendMembershipChangeLocked(Map.copyOf(peerAddresses), updatedLearners);
    wakePeerSenders();
    log.info(
        "promoted learner {} to a full voting member (matchIndex within {} of leader's log)",
        peerId,
        LEARNER_CATCH_UP_THRESHOLD);
    // The promotion entry itself now needs the just-promoted peer's own ack to commit (it counts
    // toward majority arithmetic the instant reconfigurePeersLocked applied it, above) -- if that
    // peer goes unreachable right as it's promoted, nothing else would ever resolve this, and
    // pendingMembershipChangeIndex would stay set forever, blocking every future addServer/
    // removeServer on this leader. Unlike addServer/removeServer's own caller, nothing here is
    // waiting synchronously on this index (see this method's own javadoc), so the same bounded
    // give-up/truncate awaitAppliedThrowing already provides is run on its own thread instead --
    // reusing that method rather than duplicating its timeout-then-truncate logic. A
    // GimleRaftException
    // here just means "didn't commit in time," already handled by the truncation inside
    // awaitAppliedThrowing itself; nothing further to do with it.
    Thread.ofVirtual()
        .name("gimle-raft-promotion-watchdog-" + peerId)
        .start(
            () -> {
              try {
                awaitAppliedThrowing(index);
              } catch (GimleRaftException ignored) {
                // Already truncated (and, if still a learner-eligible peer, will simply be
                // re-promoted on its next successful ack) -- see this call site's own comment.
              }
            });
  }

  private void applyIfMembershipChangeLocked(LogEntry entry) {
    if (entry.payload() instanceof MembershipChange change) {
      reconfigurePeersLocked(change.peers(), change.learners());
    }
  }

  /**
   * Reconciles every mutable piece of per-peer state ({@link #peers}, {@link #nextIndex}, {@link
   * #matchIndex}, {@link #peerWake}, {@link #peerSenderThreads}, {@link #learners}) against {@code
   * newPeerAddresses}/{@code newLearners}, the cluster's new complete configuration -- called the
   * instant a {@link MembershipChange} is appended (leader, self-proposed) or replicated in
   * (follower), never waiting for it to commit. {@code newPeerAddresses} is the full,
   * self-inclusive membership {@link MembershipChange#peers} now carries (see {@link
   * #appendMembershipChangeLocked}), so this node's own id is dropped from it first -- a node never
   * lists itself as its own peer (see {@link #peerAddresses}'s own invariant). {@code newLearners},
   * by contrast, is stored as given, self-inclusive: see {@link #learners}'s own javadoc for why. A
   * brand-new peer's {@code nextIndex} starts at the snapshot floor rather than this node's log tip
   * (unlike {@link #becomeLeaderLocked}'s reset for already-known peers): it has seen none of the
   * log yet and must either replay it from there or catch up via {@code InstallSnapshot}. {@link
   * #membershipListener} is notified last, still under {@link #lock} -- it must stay fast and
   * non-blocking, per its own constructor-parameter javadoc.
   */
  private void reconfigurePeersLocked(
      Map<String, PeerAddress> newPeerAddresses, Set<String> newLearners) {
    Map<String, PeerAddress> effectivePeers = newPeerAddresses;
    if (effectivePeers.containsKey(selfId)) {
      effectivePeers = new LinkedHashMap<>(effectivePeers);
      effectivePeers.remove(selfId);
    }
    for (Map.Entry<String, PeerAddress> e : effectivePeers.entrySet()) {
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
      if (effectivePeers.containsKey(peerId)) {
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
    peerAddresses.putAll(effectivePeers);
    learners.clear();
    learners.addAll(newLearners);
    membershipListener.accept(Map.copyOf(peerAddresses));
  }

  /**
   * Recomputes "what the peer configuration would be right now" by scanning the log's tail backward
   * for the most recent {@link MembershipChange} still present, falling back to {@link
   * #bootstrapPeerAddresses}/no learners if none is found -- used by {@link #truncateFromLocked} to
   * roll live peer state back after removing an entry that changed it. Never needs to look below
   * the snapshot floor: a truncation only ever removes an uncommitted (hence unapplied, hence never
   * yet compacted) entry, by Raft's own safety guarantee that a leader never overwrites a committed
   * one.
   */
  private MembershipChange effectivePeerConfigLocked() {
    for (long i = raftLog.lastIndex(); i > raftLog.snapshotLastIncludedIndex(); i--) {
      Optional<LogEntry> entry = raftLog.get(i);
      if (entry.isPresent() && entry.get().payload() instanceof MembershipChange change) {
        return change;
      }
    }
    return new MembershipChange(bootstrapPeerAddresses, Set.of());
  }

  /**
   * Blocks until {@code index} is applied, then returns whatever {@link MutationOutcome} {@link
   * #applyCommittedLocked} recorded for it -- {@link MutationOutcome#accepted()} for a {@link
   * MembershipChange} entry or any index with no recorded outcome, which every caller except {@link
   * #propose} itself (addServer/removeServer, proposing no {@link StateMutation}) discards unread.
   * Read-and-remove happens here, still under {@link #lock}, in the same atomic step as the loop
   * condition becoming false -- no window exists for a second caller to observe or clear this
   * index's entry first.
   */
  private MutationOutcome awaitAppliedThrowing(long index) {
    lock.lock();
    try {
      // Deliberately System.nanoTime(), not clock: the actual wait below blocks this calling
      // thread on the Condition for real wall-clock nanoseconds regardless of what Clock the
      // deadline math uses, so computing that deadline from an injectable clock would only be
      // misleading -- a virtual clock that a test never advances would leave this looping on
      // real awaitNanos calls for the real proposeTimeout anyway, not returning early the way
      // advancing a TestClock elsewhere in this class does for the scheduler-driven timers.
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
      MutationOutcome outcome = pendingOutcomes.remove(index);
      return outcome != null ? outcome : MutationOutcome.accepted();
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
      MembershipChange effective = effectivePeerConfigLocked();
      reconfigurePeersLocked(effective.peers(), effective.learners());
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
      if (learners.contains(selfId)) {
        // A learner deliberately never campaigns -- see the #learners field javadoc for the
        // disruptive-candidate hazard this avoids. Re-arm the timer rather than just returning:
        // this node still needs to notice once it's promoted, or a genuine leader failure.
        resetElectionTimerLocked();
        return;
      }
      startElectionLocked();
    } finally {
      lock.unlock();
    }
  }

  /** {@link #peers} minus {@link #learners} -- who actually counts for election/commit majority. */
  private Map<String, RaftPeerClient> votingPeersLocked() {
    if (learners.isEmpty()) {
      return peers;
    }
    Map<String, RaftPeerClient> voting = new HashMap<>(peers);
    voting.keySet().removeAll(learners);
    return voting;
  }

  private void startElectionLocked() {
    role = Role.CANDIDATE;
    long newTerm = raftLog.currentTerm() + 1;
    raftLog.setTermAndVote(newTerm, Optional.of(selfId));
    resetElectionTimerLocked();

    Map<String, RaftPeerClient> votingPeers = votingPeersLocked();
    int majority = (votingPeers.size() + 1) / 2 + 1;
    int[] votesGranted = {1}; // self-vote
    if (votesGranted[0] >= majority) {
      becomeLeaderLocked();
      return;
    }

    long lastLogIndex = raftLog.lastIndex();
    long lastLogTerm = raftLog.lastTerm();
    for (Map.Entry<String, RaftPeerClient> peerEntry : votingPeers.entrySet()) {
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
    // The standard new-leader no-op: Raft's commit rule only lets a leader commit an entry from
    // its own current term, so without one entry of its own a fresh leader over a quiet cluster
    // could never advance its commit index past what it inherited -- and with a state machine
    // rebuilt from snapshot plus committed replay, entries between the snapshot floor and the log
    // tip would stay unapplied on every node until the next client write happened to arrive.
    raftLog.append(new LogEntry(raftLog.currentTerm(), raftLog.lastIndex() + 1, new Noop()));
    advanceCommitIndexLocked(); // a single-node cluster commits its own writes immediately
    for (Map.Entry<String, RaftPeerClient> peerEntry : peers.entrySet()) {
      startPeerSenderThreadLocked(peerEntry.getKey(), peerEntry.getValue());
    }
    lastContactAt.clear();
    leadershipEstablishedAt = clock.instant();
    initialQuorumContactMade = false;
    // Started only for the duration of this leadership tenure -- see checkQuorumTick's own
    // javadoc. Ticking from one window in costs nothing while LEADERSHIP_CONTACT_GRACE is still
    // running: those early ticks observe the peer senders' progress and record the moment contact
    // with a majority is first established, which is what ends the grace.
    checkQuorumFuture =
        scheduler.scheduleAtFixedRate(
            this::checkQuorumTick,
            CHECK_QUORUM_WINDOW.toMillis(),
            HEARTBEAT_INTERVAL.toMillis(),
            TimeUnit.MILLISECONDS);
  }

  /**
   * Check-quorum self-demotion (Raft dissertation §6.2): if this leader hasn't completed an RPC
   * round trip with a majority of its own voting peers within {@link #CHECK_QUORUM_WINDOW}, it
   * steps itself down on its own -- additive to every existing {@link #stepDownLocked} call site (a
   * higher observed term), not a replacement for them. This is what closes the gap a bare {@link
   * #isLeader} role read leaves open: a leader isolated in a network partition's minority side
   * would otherwise never learn to step down until it observed a higher term, which a full
   * partition (no peer ever reachable to carry one back) prevents outright. Scheduled only while
   * leader (see {@link #becomeLeaderLocked}/{@link #demoteToFollowerLocked}), so a stale/cancelled
   * run firing just after this node already stopped being leader is guarded by the {@code role !=
   * Role.LEADER} check below, not by assuming the scheduler cancellation itself is instantaneous.
   *
   * <p>Until this tenure has reached a majority even once, {@link #LEADERSHIP_CONTACT_GRACE}
   * applies instead of the window -- see that constant for why a leader that has not yet finished
   * opening its peer connections must not be read as one whose peers are gone.
   */
  private void checkQuorumTick() {
    lock.lock();
    try {
      if (!running || role != Role.LEADER) {
        return;
      }
      Map<String, RaftPeerClient> votingPeers = votingPeersLocked();
      if (votingPeers.isEmpty()) {
        return; // a single-voter cluster (self alone) trivially always has quorum
      }
      Instant earliestAcceptable = clock.instant().minus(CHECK_QUORUM_WINDOW);
      long recentlyContacted = 0;
      for (String peerId : votingPeers.keySet()) {
        Instant last = lastContactAt.get(peerId);
        if (last != null && !last.isBefore(earliestAcceptable)) {
          recentlyContacted++;
        }
      }
      int majority = (votingPeers.size() + 1) / 2 + 1;
      boolean haveQuorum = recentlyContacted + 1 >= majority; // +1: self, trivially reachable
      if (haveQuorum) {
        initialQuorumContactMade = true;
        return;
      }
      // No majority right now -- but for a leader that has not yet reached one even once, that is
      // only the absence of an answer, not evidence of silence: its first round trip to each peer
      // may still be in flight, and one attempt is allowed to run far longer than
      // CHECK_QUORUM_WINDOW before it reports anything at all. See LEADERSHIP_CONTACT_GRACE.
      if (!initialQuorumContactMade
          && clock.instant().isBefore(leadershipEstablishedAt.plus(LEADERSHIP_CONTACT_GRACE))) {
        return;
      }
      log.warn(
          "{} stepping down: only {} of {} voting peers responded within the last {} -- "
              + "check-quorum self-demotion (possible network partition)",
          selfId,
          recentlyContacted,
          votingPeers.size(),
          initialQuorumContactMade ? CHECK_QUORUM_WINDOW : LEADERSHIP_CONTACT_GRACE);
      demoteToFollowerLocked();
      leaderHint = null;
      pendingMembershipChangeIndex = null;
    } finally {
      lock.unlock();
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
    demoteToFollowerLocked();
    leaderHint = null;
    pendingMembershipChangeIndex = null;
  }

  /**
   * The role-and-leader-state half of {@link #stepDownLocked}, split out so an equal-term demotion
   * (this node's own current term unchanged, so nothing to reset there -- see the {@code role !=
   * Role.FOLLOWER} branches in {@link #onAppendEntries}/{@link #onInstallSnapshot}) gets the exact
   * same cleanup a higher-term step-down gets, not just a flipped {@link #role} field. Without
   * this, a node that reached {@link Role#LEADER} without ever losing a real election at this term
   * (the only way that happens: {@link #start()}'s empty-peers bootstrap path self-elects a node
   * instantly -- a real, previously-observed hazard for a node standing by to {@code
   * addServer}-join an existing cluster rather than bootstrap a new one) would keep its
   * already-started {@link #peerSenderThreads} running after being told to step down, broadcasting
   * competing heartbeats at the genuine leader's own term indefinitely: a real, sustained
   * split-brain, not a self-resolving glitch.
   */
  private void demoteToFollowerLocked() {
    boolean wasLeader = role == Role.LEADER;
    role = Role.FOLLOWER;
    resetElectionTimerLocked();
    if (wasLeader) {
      for (Thread t : peerSenderThreads.values()) {
        t.interrupt();
      }
      peerSenderThreads.clear();
      if (checkQuorumFuture != null) {
        checkQuorumFuture.cancel(false);
        checkQuorumFuture = null;
      }
      commitAdvanced.signalAll(); // wake any propose() waiters so they observe the role change
    }
  }

  // ---- leader replication ----

  private void wakePeerSenders() {
    for (Semaphore wake : peerWake.values()) {
      wake.release();
    }
  }

  /**
   * Runs on its own real background thread for as long as this node is leader, doing real blocking
   * {@link RaftPeerClient} RPCs -- genuine concurrency across as many peers as this node has, not a
   * single-threaded tick a test could fire deterministically. Its own pacing wait below is a real
   * {@link Semaphore#tryAcquire}, not routed through {@link #scheduler}/{@link #clock}: unlike the
   * election-timeout and check-quorum tasks (which the scheduler itself fires, so a virtual one can
   * fire them on demand), this loop's own thread decides when to re-check, and virtualizing that
   * would mean replacing the loop with something the scheduler drives instead -- a materially
   * larger change than this seam attempts.
   */
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
      // A response arrived at all -- success or reject -- so the peer is reachable and processing
      // this leader's own RPCs, exactly what check-quorum (see CHECK_QUORUM_WINDOW) needs to know;
      // recorded before any of the staleness checks below so even a stale-term/stale-role response
      // still counts (this leader's *previous* term successfully reached that peer, too).
      lastContactAt.put(peerId, clock.instant());
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
        maybePromoteLearnerLocked(peerId);
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

    // Sent as a sequence of offset/done chunks (Raft paper Figure 13), always restarting from
    // byte 0 on this call: a mid-transfer failure below just returns, and the next tick calls
    // this method again from scratch rather than trying to resume a partial transfer -- simplest
    // correct recovery, matching sendAppendEntries' own "the next tick retries" posture for a
    // bounded-gap failure.
    long offset = 0;
    boolean done;
    InstallSnapshotResponse response;
    do {
      int end = (int) Math.min(offset + SNAPSHOT_CHUNK_BYTES, snapshotBytes.length);
      byte[] chunk = Arrays.copyOfRange(snapshotBytes, (int) offset, end);
      done = end == snapshotBytes.length;
      try {
        response =
            client.installSnapshot(
                new InstallSnapshot(
                    term, selfId, lastIncludedIndex, lastIncludedTerm, offset, chunk, done));
      } catch (RuntimeException e) {
        return;
      }
      lock.lock();
      try {
        // Same check-quorum bookkeeping as sendOnce's own response handling -- a chunk response
        // arriving at all proves this peer is reachable, regardless of what it turns out to say.
        lastContactAt.put(peerId, clock.instant());
        if (response.term() > raftLog.currentTerm()) {
          stepDownLocked(response.term());
          return;
        }
        if (role != Role.LEADER || raftLog.currentTerm() != term) {
          return; // stale response from a previous term/role
        }
      } finally {
        lock.unlock();
      }
      offset = end;
    } while (!done);

    lock.lock();
    try {
      if (role != Role.LEADER || raftLog.currentTerm() != term) {
        return;
      }
      matchIndex.put(peerId, Math.max(matchIndex.getOrDefault(peerId, 0L), lastIncludedIndex));
      nextIndex.put(peerId, lastIncludedIndex + 1);
      advanceCommitIndexLocked();
      maybePromoteLearnerLocked(peerId);
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
      // Keeps leadershipEstablishedAt's "non-null whenever this node is leader" invariant true on
      // this path too, even though nothing here schedules the check-quorum tick that reads it.
      leadershipEstablishedAt = clock.instant();
      initialQuorumContactMade = false;
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
   * Whether {@code peerId} is currently a non-voting learner (see {@link #learners}) -- exercised
   * only by {@code RaftMembershipChangeTest}/{@code RaftClusterTest} in this same package, never by
   * application code.
   */
  boolean isLearnerForTest(String peerId) {
    lock.lock();
    try {
      return learners.contains(peerId);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Whether a membership change is currently appended-but-not-yet-committed (see {@link
   * #pendingMembershipChangeIndex}) -- exercised only by {@code RaftMembershipChangeTest} to wait
   * out a promotion's own commit, not just its (earlier, effective-on-append) removal from {@link
   * #learners}, before driving a scenario that depends on the membership-change gate being clear.
   */
  boolean hasPendingMembershipChangeForTest() {
    lock.lock();
    try {
      return pendingMembershipChangeIndex != null;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Advances {@code commitIndex} to the highest index a majority of {@code matchIndex} values
   * (including this leader's own, always fully caught up with itself) reach, but only if that entry
   * was written in this leader's own current term (the Figure 8 rule from the Raft paper) -- the
   * condition a naive implementation omits, so it is its own explicit guard rather than folded into
   * one boolean expression. A learner's {@link #matchIndex} is excluded from the majority
   * denominator entirely (see {@link #learners}'s own javadoc) -- it doesn't yet count as a voting
   * member, so its replication progress, while tracked exactly like any other peer's, can't help
   * commit an entry until it's promoted.
   */
  private void advanceCommitIndexLocked() {
    List<Long> matchIndexes = new ArrayList<>();
    matchIndexes.add(raftLog.lastIndex());
    for (String peerId : peers.keySet()) {
      if (learners.contains(peerId)) {
        continue;
      }
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
        MutationOutcome outcome = mutation.applyTo(store);
        if (role == Role.LEADER) {
          pendingOutcomes.put(nextApply, outcome);
        }
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
      } else if (role != Role.FOLLOWER) {
        // Not just CANDIDATE: a node can reach LEADER at this same term without ever losing an
        // election to the peer now sending this AppendEntries -- start()'s empty-peers bootstrap
        // path self-elects instantly (majority of a 1-node cluster is itself, see start()'s own
        // comment), which a node standing by to join an existing cluster via a later addServer
        // also hits, since it begins with zero configured peers. If that self-elected term happens
        // to coincide with the real cluster's (routine for two clusters both freshly bootstrapped
        // at term 1), this node is otherwise never told to step down -- CANDIDATE was the only
        // case handled here, so a stray LEADER kept believing it, and once addServer's replicated
        // MembershipChange reached it, reconfigurePeersLocked's own "still LEADER" check made it
        // start broadcasting competing heartbeats at the genuine leader's own term: a real,
        // sustained split-brain, not a self-resolving glitch. Demoting on any non-FOLLOWER role
        // here is the correct general Raft rule regardless of how a same-term rival arose --
        // demoteToFollowerLocked (not a bare role flip) also stops any peer-sender threads a stray
        // LEADER already started.
        demoteToFollowerLocked();
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
      } else if (role != Role.FOLLOWER) {
        // Same reasoning as the identical branch in onAppendEntries: not just CANDIDATE, and via
        // demoteToFollowerLocked so a stray LEADER's peer-sender threads actually stop too.
        demoteToFollowerLocked();
      }
      leaderHint = request.leaderId();
      resetElectionTimerLocked();

      if (request.lastIncludedIndex() <= raftLog.snapshotLastIncludedIndex()) {
        // Already have an equal/newer one: drop any in-flight chunked transfer for this snapshot
        // and decline to buffer further chunks of it. The leader still learns this node is caught
        // up from its next AppendEntries probe regardless of what this reply carries.
        pendingSnapshotBuffer = null;
        return new InstallSnapshotResponse(raftLog.currentTerm());
      }

      if (request.offset() == 0) {
        // A fresh transfer -- either genuinely the first chunk, or the leader restarting its send
        // loop after a mid-transfer failure (sendInstallSnapshot always restarts from byte 0,
        // never resumes) -- so anything buffered from a prior attempt is now stale.
        pendingSnapshotLeaderId = request.leaderId();
        pendingSnapshotTerm = request.term();
        pendingSnapshotLastIncludedIndex = request.lastIncludedIndex();
        pendingSnapshotLastIncludedTerm = request.lastIncludedTerm();
        pendingSnapshotBuffer = new ByteArrayOutputStream();
      }
      if (pendingSnapshotBuffer == null
          || !Objects.equals(pendingSnapshotLeaderId, request.leaderId())
          || pendingSnapshotTerm != request.term()
          || pendingSnapshotLastIncludedIndex != request.lastIncludedIndex()
          || pendingSnapshotBuffer.size() != request.offset()) {
        // Out-of-order, duplicate, or a chunk for a transfer this node never started buffering
        // (e.g. it missed the offset == 0 chunk) -- the leader's own restart-from-offset-0
        // recovery handles this on its next attempt; just acknowledge without accepting data this
        // node can't place correctly.
        return new InstallSnapshotResponse(raftLog.currentTerm());
      }
      pendingSnapshotBuffer.writeBytes(request.data());

      if (!request.done()) {
        return new InstallSnapshotResponse(raftLog.currentTerm());
      }

      byte[] snapshotBytes = pendingSnapshotBuffer.toByteArray();
      pendingSnapshotBuffer = null;
      StateSnapshot decoded = RaftCodec.decodeSnapshot(snapshotBytes);
      store.restoreFromSnapshot(decoded);
      raftLog.installSnapshot(
          request.lastIncludedIndex(), request.lastIncludedTerm(), snapshotBytes);
      commitIndex = Math.max(commitIndex, request.lastIncludedIndex());
      lastApplied = Math.max(lastApplied, request.lastIncludedIndex());
      commitAdvanced.signalAll();
      return new InstallSnapshotResponse(raftLog.currentTerm());
    } finally {
      lock.unlock();
    }
  }
}
