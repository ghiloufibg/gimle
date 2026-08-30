package com.gimle.fabric.cluster;

import com.gimle.core.exception.GimleClusterException;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One node agent's SWIM membership protocol participant: a protocol-period loop (ping a random
 * member; on timeout, ask {@code indirectFanout} random relays to probe on this node's behalf; no
 * ack from anyone within the suspicion timeout flips the member to {@code SUSPECT}, and unrefuted
 * {@code SUSPECT} past its grace period becomes {@code DEAD}), running entirely over {@link
 * DatagramChannel} independent of the control plane -- gossip must keep functioning with the
 * control plane down or unreachable.
 *
 * <p>A member can refute its own suspicion by gossiping a higher incarnation of itself; this class
 * does that automatically the moment it observes a piggyback entry naming itself as anything other
 * than {@code ALIVE}. Every message this node sends piggybacks its own current {@link MemberState}
 * plus a bounded number of the most-recently-changed other members' states -- the same slot the
 * service catalog rides on. Every local status transition, whether detected directly or learned
 * secondhand, also fans out synchronously to any {@link #onMembershipChange} listener, so a
 * component such as the service catalog can react to a member going {@code DEAD} the moment this
 * node's own gossip view says so, rather than only discovering it once its own circuit breaker
 * happens to trip against that member's endpoints.
 *
 * <p><b>{@code gimle.transport.protocol=tls}</b>: gossip is UDP, so mTLS here means DTLS, driven
 * per-peer through {@link DtlsPeerSession} rather than a single connection-oriented socket. Because
 * any member can ping any other at any time, a full mesh needs a rule for who originates each
 * pair's handshake, or two nodes pinging each other in the same tick would send simultaneous,
 * colliding ClientHellos. Rather than resolve that after the fact by inspecting handshake bytes,
 * this class prevents it by construction: {@link #isDesignatedInitiator} deterministically picks
 * the lexicographically lower gossip address as the sole initiator for a given pair, computed
 * identically by both sides, so the higher-addressed side never creates a competing client session.
 * The trade-off -- the non-initiating side's own probe attempts toward that peer silently no-op
 * until the peer dials in, or a later tick's random target selection happens to favor the
 * correctly-ordered direction -- is a transport-establishment latency detail, not a correctness
 * gap; {@link #join} is the one exception, since a joining node must always be free to dial a
 * configured seed regardless of address ordering (a seed has no way to know about, and therefore
 * dial, a not-yet-joined peer).
 */
public final class GossipMember implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(GossipMember.class);

  private final MemberId self;
  private final GossipConfig config;
  private final DatagramChannel channel;
  private final ScheduledExecutorService ticker;
  private final TransportProtocol transportProtocol;
  private final AtomicReference<SSLContext> dtlsContext;
  private final Clock clock;

  private final Map<String, MemberState> members = new ConcurrentHashMap<>();
  private final Map<String, Instant> suspectedSince = new ConcurrentHashMap<>();
  private final Map<String, Instant> deadSince = new ConcurrentHashMap<>();
  private final Deque<String> recentChangeOrder = new ArrayDeque<>();
  private final Deque<String> probeOrder = new ArrayDeque<>();
  private final Map<Long, PendingProbe> pendingProbes = new ConcurrentHashMap<>();
  private final Map<Long, CompletableFuture<MemberId>> joinWaiters = new ConcurrentHashMap<>();
  private final Map<InetSocketAddress, DtlsPeerSession> dtlsSessions = new ConcurrentHashMap<>();
  private final Map<InetSocketAddress, byte[]> pendingSecureOutbound = new ConcurrentHashMap<>();
  private final AtomicLong incarnation = new AtomicLong();
  private final AtomicLong seqCounter = new AtomicLong();
  private final AtomicInteger localHealthMultiplier = new AtomicInteger();
  private final AtomicReference<Instant> lastAntiEntropySync;
  private final AtomicInteger fullStateOffset = new AtomicInteger();

  /** See {@link #currentFullState}. */
  private static final int MAX_FULL_STATE_PAGE = 128;

  /**
   * Clamp on {@link #localHealthMultiplier}, a simplified Lifeguard-style local-health adaptation
   * -- not the full paper: no buddy-system suspicion, no k-independent-confirmation decay, just the
   * multiplier itself. A node whose own probes keep timing out, or that keeps getting suspected by
   * others, is more likely sitting on a slow/overloaded host or link than every peer it's probing
   * is actually down -- scaling this node's own timeouts up avoids it flooding the cluster with
   * false suspicions of everyone else.
   */
  private static final int MAX_LOCAL_HEALTH_MULTIPLIER = 8;

  private final List<Consumer<MemberState>> membershipListeners = new CopyOnWriteArrayList<>();

  private volatile boolean running;
  private volatile PiggybackExtension catalogExtension = PiggybackExtension.NONE;

  /**
   * The seed list {@link #join} was last called with (self-filtered, deduplicated), remembered so
   * {@link #retrySeedsIfIsolated} can keep dialing them in the background -- see that method's own
   * javadoc for why. Empty until {@link #join} is called at least once; a node constructed but
   * never joined (every test that only exercises {@link #mergeAll} directly, for instance) simply
   * never retries anything, matching {@link #join}'s own "no seeds configured" no-op.
   */
  private volatile List<InetSocketAddress> seeds = List.of();

  public GossipMember(MemberId self, GossipConfig config) throws IOException {
    this(self, config, Clock.systemUTC());
  }

  /**
   * Every deadline this class compares against -- suspicion, probe, DTLS-handshake, anti-entropy,
   * dead-member-reap -- is read from this {@link Clock} rather than {@link Instant#now()}, so a
   * test can exercise the real production timeouts (protocol period, suspicion timeout, and so on)
   * without waiting for them; see {@code TestClock} in {@code gimle-core}'s test-jar. The periodic
   * tick itself still fires off the real {@link ScheduledExecutorService} this constructor builds
   * -- pair this with the package-visible constructor below to additionally substitute a
   * deterministic one (see {@code TestScheduler}) where the ticker's own firing needs to be driven
   * by the test rather than a background thread.
   */
  public GossipMember(MemberId self, GossipConfig config, Clock clock) throws IOException {
    this(self, config, clock, null);
  }

  /**
   * Package-visible seam for {@code GossipMemberTest}: a non-null {@code ticker} replaces the
   * virtual-thread-backed {@link ScheduledExecutorService} this class otherwise builds, so a test
   * can supply a {@code TestScheduler} sharing {@code clock} and drive {@link #tick}
   * deterministically via its own {@code advance} rather than a background thread's real firing.
   */
  GossipMember(MemberId self, GossipConfig config, Clock clock, ScheduledExecutorService ticker)
      throws IOException {
    this.config = config;
    this.clock = clock;
    this.transportProtocol = TransportProtocol.fromConfig();
    this.dtlsContext =
        new AtomicReference<>(
            transportProtocol == TransportProtocol.TLS
                ? SslContexts.forMutualDtls(TlsSettings.fromConfig())
                : null);
    this.channel = DatagramChannel.open();
    channel.bind(self.gossipAddress());
    // Rebind self's advertised address to whatever the OS actually assigned -- most relevant
    // when the caller requested an ephemeral port (port 0): every other member learns this
    // node's address only from what it advertises about itself, since no message carries a
    // dedicated "from" field, so advertising the literal port 0 it was constructed with would
    // make this node permanently undialable by anyone except the very first UDP
    // reply-to-source-address hop.
    this.self = new MemberId(self.nodeId(), (InetSocketAddress) channel.getLocalAddress());
    this.ticker =
        ticker != null
            ? ticker
            : Executors.newSingleThreadScheduledExecutor(
                r ->
                    Thread.ofVirtual()
                        .name("gimle-gossip-ticker-" + this.self.nodeId())
                        .unstarted(r));
    this.lastAntiEntropySync = new AtomicReference<>(clock.instant());
    members.put(this.self.nodeId(), new MemberState(this.self, MemberStatus.ALIVE, 0));
  }

  public MemberId self() {
    return self;
  }

  /**
   * Attaches the (optional) application payload that rides on this member's gossip piggyback
   * channel -- {@code com.gimle.fabric.catalog.ServiceCatalog} in practice.
   */
  public void attachCatalog(PiggybackExtension extension) {
    this.catalogExtension = extension;
  }

  /**
   * Registers a callback invoked with a member's resulting {@link MemberState} every time this
   * node's own local view of that member's status actually changes -- {@code ALIVE -> SUSPECT},
   * {@code SUSPECT -> DEAD}, a fresh {@code ALIVE} addition/rejoin, or the same transitions learned
   * secondhand via gossip/anti-entropy rather than this node's own probe. Never fired for a no-op
   * merge (an unchanged status at an unchanged incarnation). {@code
   * com.gimle.fabric.catalog.ServiceCatalog} is the intended consumer: SWIM already detects a dead
   * node cluster-wide in a few seconds, so the catalog subscribes here to evict that node's
   * endpoints proactively instead of waiting for every independent caller to rediscover the same
   * fact through its own circuit breaker.
   */
  public void onMembershipChange(Consumer<MemberState> listener) {
    membershipListeners.add(listener);
  }

  /** Starts the receive loop and the protocol-period ticker. Idempotent-unsafe: call once. */
  public void start() {
    running = true;
    Thread.ofVirtual().name("gimle-gossip-recv-" + self.nodeId()).start(this::receiveLoop);
    ticker.scheduleAtFixedRate(
        this::tick,
        config.protocolPeriod().toMillis(),
        config.protocolPeriod().toMillis(),
        TimeUnit.MILLISECONDS);
  }

  /**
   * Number of ping-and-wait rounds {@link #join} attempts against configured seeds before giving up
   * on its own synchronous attempt and falling back to {@link #retrySeedsIfIsolated}'s background
   * retries. A single UDP ping with no retry is fragile against exactly the kind of transient
   * container-startup networking churn (a peer's hostname not yet resolvable, its network namespace
   * still initializing, its own receive loop not yet scheduled) that a multi-container
   * Compose/Kubernetes bring-up routinely hits.
   */
  private static final int JOIN_ATTEMPTS = 5;

  /**
   * Contacts every configured seed, retrying up to {@link #JOIN_ATTEMPTS} times, and blocks until
   * at least one acks or every attempt's {@code pingTimeout} elapses. Never throws and never
   * distinguishes "one seed configured" from "several" -- both a single unreachable seed and every
   * configured seed being briefly unreachable are the same underlying, unresolvable-at-join-time
   * ambiguity ("is this genuinely the first node of a new cluster, or did every seed just not
   * happen to answer five pings in a row"), so both fall back the same way: start running now,
   * unjoined, and let {@link #retrySeedsIfIsolated} keep dialing this same seed list in the
   * background on every tick until one answers -- the "organic path back" {@link #JOIN_ATTEMPTS}'s
   * own siblings before this fix never had. This is what keeps a routine container-startup
   * networking blip from crashing the caller (a thrown {@link GimleClusterException} used to
   * propagate straight out of a caller like {@code AgentMain} with nothing catching it) or
   * silently, permanently forking the cluster in two.
   */
  public void join(List<InetSocketAddress> seedsToJoin) {
    List<InetSocketAddress> others =
        seedsToJoin.stream()
            .distinct()
            .filter(address -> !address.equals(self.gossipAddress()))
            .toList();
    this.seeds = others;
    if (others.isEmpty()) {
      log.info(
          "{}: no other seeds configured; starting as the first node of a new cluster",
          self.nodeId());
      return;
    }

    boolean reachable = false;
    for (int attempt = 1;
        attempt <= JOIN_ATTEMPTS && !reachable && !Thread.currentThread().isInterrupted();
        attempt++) {
      reachable = attemptJoinRound(others, attempt);
    }

    if (!reachable) {
      log.warn(
          "{}: none of its {} configured seed(s) {} answered within {} attempts; starting"
              + " unjoined and retrying them in the background every protocol period until one"
              + " answers",
          self.nodeId(),
          others.size(),
          others,
          JOIN_ATTEMPTS);
    }
  }

  /**
   * Best-effort, no-coordination-needed re-attempt at the seed list {@link #join} was last called
   * with, run every protocol period alongside every other {@link #tick} step -- the fix for the gap
   * {@link #JOIN_ATTEMPTS}'s own javadoc describes: unlike this method, ordinary steady-state
   * probing ({@link #pingRandomMember}/{@link #maybeSyncWithRandomMember}) only ever targets a
   * member already present in {@link #members}, so a node still isolated after {@link #join}
   * returned (every seed down, not merely slow, at that moment) would otherwise stay isolated
   * forever even once its seeds recover. A bare, unregistered {@link SwimMessage.Ping} is enough --
   * {@link #handle}'s own {@code Ack} case falls through to {@link #markAliveDirect} for any
   * unrecognized sequence number, the same path an ordinary probe's reply takes, so no {@code
   * joinWaiters} bookkeeping is needed here.
   *
   * <p>Fires only while genuinely isolated (no other member currently known {@code ALIVE}) -- the
   * instant any peer is known, whether from this method's own success or from any other path (a
   * peer dialing this node first, for instance), ordinary SWIM probing and anti-entropy take over
   * and this stops firing on its own, with nothing to clear or reset.
   */
  private void retrySeedsIfIsolated() {
    List<InetSocketAddress> currentSeeds = seeds;
    if (currentSeeds.isEmpty() || hasAnyOtherAliveMember()) {
      return;
    }
    for (InetSocketAddress seed : currentSeeds) {
      try {
        send(seed, new SwimMessage.Ping(nextSeq(), currentPiggyback(), catalogPayload()), true);
      } catch (IOException e) {
        log.debug("{}: rejoin ping to seed {} failed: {}", self.nodeId(), seed, e.getMessage());
      }
    }
  }

  private boolean hasAnyOtherAliveMember() {
    return members.values().stream()
        .anyMatch(
            member ->
                !member.id().nodeId().equals(self.nodeId())
                    && member.status() == MemberStatus.ALIVE);
  }

  /**
   * One ping-and-wait round against every seed in {@code others}, in parallel -- {@code true} the
   * moment any one acks, {@code false} if none does within {@link GossipConfig#pingTimeout()}.
   */
  private boolean attemptJoinRound(List<InetSocketAddress> others, int attempt) {
    List<Long> seqs = new ArrayList<>();
    List<CompletableFuture<MemberId>> futures = new ArrayList<>();
    for (InetSocketAddress seed : others) {
      long seq = nextSeq();
      CompletableFuture<MemberId> future = new CompletableFuture<>();
      joinWaiters.put(seq, future);
      seqs.add(seq);
      futures.add(future);
      try {
        send(seed, new SwimMessage.Ping(seq, currentPiggyback(), catalogPayload()), true);
      } catch (IOException e) {
        log.warn(
            "{}: failed to contact seed {} (attempt {}/{}): {}",
            self.nodeId(),
            seed,
            attempt,
            JOIN_ATTEMPTS,
            e.getMessage());
      }
    }

    try {
      CompletableFuture.anyOf(futures.toArray(CompletableFuture[]::new))
          .get(config.pingTimeout().toMillis(), TimeUnit.MILLISECONDS);
      return true;
    } catch (ExecutionException | InterruptedException | TimeoutException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    } finally {
      seqs.forEach(joinWaiters::remove);
    }
  }

  public Optional<MemberState> memberState(String nodeId) {
    return Optional.ofNullable(members.get(nodeId));
  }

  public List<MemberState> members() {
    return List.copyOf(members.values());
  }

  public long incarnation() {
    return incarnation.get();
  }

  // ---- protocol period ----

  private void tick() {
    try {
      checkSuspectExpiry();
      checkProbeTimeouts();
      checkDtlsHandshakeTimeouts();
      pingRandomMember();
      maybeSyncWithRandomMember();
      reapExpiredDeadMembers();
      retrySeedsIfIsolated();
    } catch (RuntimeException e) {
      log.warn("{}: gossip protocol tick failed: {}", self.nodeId(), e.getMessage(), e);
    }
  }

  /**
   * Forgets (P3) a member that has sat {@code DEAD} for longer than {@link
   * GossipConfig#deadMemberReapAfter} -- without this, {@link #members} only ever grows across a
   * long-running cluster's life as nodes churn, since {@link #markDead} alone never removes an
   * entry, only relabels it. Purely a local decision with no cluster-wide coordination: each node
   * reaps on its own timeline once it individually decides enough time has passed, the same way
   * {@link #checkSuspectExpiry} promotes SUSPECT to DEAD independently on every node -- no "forget"
   * message is broadcast, since a node that hasn't yet reaped simply keeps gossiping the DEAD state
   * to whoever still asks.
   */
  private void reapExpiredDeadMembers() {
    Instant now = clock.instant();
    for (Map.Entry<String, Instant> entry : List.copyOf(deadSince.entrySet())) {
      String nodeId = entry.getKey();
      if (now.isAfter(entry.getValue().plus(config.deadMemberReapAfter()))) {
        members.remove(nodeId);
        deadSince.remove(nodeId);
        synchronized (recentChangeOrder) {
          recentChangeOrder.remove(nodeId);
        }
        log.debug("{}: reaped long-dead member {}", self.nodeId(), nodeId);
      }
    }
  }

  /**
   * Drops (rather than precisely retransmits) any DTLS session that's been handshaking longer than
   * {@code suspicionTimeout} -- the next attempt to reach that peer starts a fresh handshake.
   * Simpler than reproducing RFC 6347's exact-flight retransmission, and sufficient here: SWIM
   * already retries periodically on its own, so a dropped, not-yet-established session costs at
   * most one more protocol period before the next attempt, well within the tolerance the suspicion
   * timeout itself already represents.
   */
  private void checkDtlsHandshakeTimeouts() {
    if (transportProtocol != TransportProtocol.TLS) {
      return;
    }
    Instant now = clock.instant();
    for (Map.Entry<InetSocketAddress, DtlsPeerSession> entry :
        List.copyOf(dtlsSessions.entrySet())) {
      DtlsPeerSession session = entry.getValue();
      Instant startedAt = session.handshakeStartedAt();
      if (!session.isEstablished()
          && startedAt != null
          && now.isAfter(startedAt.plus(config.suspicionTimeout()))) {
        dtlsSessions.remove(entry.getKey(), session);
        pendingSecureOutbound.remove(entry.getKey());
        log.debug(
            "{}: dropping a stalled DTLS handshake toward {}; a future send retries fresh",
            self.nodeId(),
            entry.getKey());
      }
    }
  }

  /**
   * Periodic full-state push-pull: fires roughly every {@link GossipConfig#antiEntropyInterval} to
   * one random peer, using the same round-robin target selection {@link #pingRandomMember} uses.
   * Piggyback alone can't guarantee eventual convergence -- it carries at most {@link
   * GossipConfig#piggybackCount} entries per message, and only the 64 most-recently-changed members
   * are ever eligible ({@link #markChanged}) -- so a node partitioned or slow long enough to miss
   * enough gossip rounds would otherwise diverge permanently. Tracked by wall-clock elapsed time
   * rather than a tick counter so it stays correct regardless of {@code protocolPeriod}.
   */
  private void maybeSyncWithRandomMember() {
    Instant now = clock.instant();
    Instant last = lastAntiEntropySync.get();
    if (now.isBefore(last.plus(config.antiEntropyInterval()))) {
      return;
    }
    if (!lastAntiEntropySync.compareAndSet(last, now)) {
      return; // another thread already claimed this cycle
    }
    MemberState target = nextProbeTarget();
    if (target == null) {
      return;
    }
    try {
      send(
          target.id().gossipAddress(),
          new SwimMessage.SyncRequest(nextSeq(), currentFullState(), catalogPayload()));
    } catch (IOException e) {
      log.warn(
          "{}: failed to send anti-entropy sync to {}: {}",
          self.nodeId(),
          target.id().nodeId(),
          e.getMessage());
    }
  }

  /**
   * The full membership table, capped to {@link #MAX_FULL_STATE_PAGE} entries per exchange --
   * {@link #receiveLoop}'s fixed 65535-byte UDP buffer makes an unbounded table a real
   * fragmentation/drop risk at cluster scale. A cluster larger than one page rotates which slice it
   * sends on successive syncs (via {@link #fullStateOffset}) so the whole table still gets
   * exchanged over a handful of anti-entropy rounds rather than the same head slice forever.
   */
  private List<MemberState> currentFullState() {
    List<MemberState> all = List.copyOf(members.values());
    if (all.size() <= MAX_FULL_STATE_PAGE) {
      return all;
    }
    int start = Math.floorMod(fullStateOffset.getAndAdd(MAX_FULL_STATE_PAGE), all.size());
    List<MemberState> page = new ArrayList<>(MAX_FULL_STATE_PAGE);
    for (int i = 0; i < MAX_FULL_STATE_PAGE; i++) {
      page.add(all.get((start + i) % all.size()));
    }
    return List.copyOf(page);
  }

  private void pingRandomMember() {
    MemberState target = nextProbeTarget();
    if (target == null) {
      return;
    }
    long seq = nextSeq();
    Instant now = clock.instant();
    Duration timeout = scaledPingTimeout();
    pendingProbes.put(
        seq, new PendingProbe(target.id(), null, 0, now.plus(timeout), now.plus(timeout)));
    try {
      send(
          target.id().gossipAddress(),
          new SwimMessage.Ping(seq, currentPiggyback(), catalogPayload()));
    } catch (IOException e) {
      log.warn("{}: failed to ping {}: {}", self.nodeId(), target.id().nodeId(), e.getMessage());
    }
  }

  /**
   * Round-robins over a shuffled copy of the current membership rather than picking uniformly at
   * random each tick -- SWIM's own paper specifies this for a bounded worst-case failure-detection
   * time: pure independent random sampling gives no per-cycle coverage guarantee, so an unlucky
   * member could in principle go unprobed for arbitrarily many ticks. Reshuffled once exhausted;
   * membership changes mid-cycle are tolerated by simply skipping any queued id that's gone stale
   * (removed, or since marked {@code DEAD}) rather than rebuilding the queue eagerly.
   *
   * <p>Package-visible, same rationale as {@link #mergeAll} -- lets {@code GossipMemberTest} assert
   * per-cycle coverage directly, without needing real network probe traffic to drive it.
   */
  MemberState nextProbeTarget() {
    synchronized (probeOrder) {
      while (true) {
        if (probeOrder.isEmpty()) {
          List<String> candidates =
              new ArrayList<>(
                  members.values().stream()
                      .filter(state -> !state.id().nodeId().equals(self.nodeId()))
                      .filter(state -> state.status() != MemberStatus.DEAD)
                      .map(state -> state.id().nodeId())
                      .toList());
          if (candidates.isEmpty()) {
            return null;
          }
          Collections.shuffle(candidates, ThreadLocalRandom.current());
          probeOrder.addAll(candidates);
        }
        String nodeId = probeOrder.pollFirst();
        MemberState state = members.get(nodeId);
        if (state != null && !nodeId.equals(self.nodeId()) && state.status() != MemberStatus.DEAD) {
          return state;
        }
        // Stale queued id (removed, or turned DEAD since it was queued) -- try the next one.
      }
    }
  }

  private Duration scaledPingTimeout() {
    return config.pingTimeout().multipliedBy(1 + localHealthMultiplier.get());
  }

  private Duration scaledSuspicionTimeout() {
    return config.suspicionTimeout().multipliedBy(1 + localHealthMultiplier.get());
  }

  private void bumpLocalHealthMultiplier() {
    localHealthMultiplier.updateAndGet(n -> Math.min(n + 1, MAX_LOCAL_HEALTH_MULTIPLIER));
  }

  private void decayLocalHealthMultiplier() {
    localHealthMultiplier.updateAndGet(n -> Math.max(n - 1, 0));
  }

  /** Package-visible for {@code GossipMemberTest} to assert the multiplier's clamped movement. */
  int localHealthMultiplier() {
    return localHealthMultiplier.get();
  }

  private void checkProbeTimeouts() {
    Instant now = clock.instant();
    for (Map.Entry<Long, PendingProbe> entry : pendingProbes.entrySet()) {
      PendingProbe pending = entry.getValue();
      if (pending.onBehalfOf == null && !pending.escalated && now.isAfter(pending.directDeadline)) {
        escalate(entry.getKey(), pending);
      }
    }
    for (Map.Entry<Long, PendingProbe> entry : List.copyOf(pendingProbes.entrySet())) {
      PendingProbe pending = entry.getValue();
      if (now.isAfter(pending.overallDeadline) && pendingProbes.remove(entry.getKey()) != null) {
        if (pending.onBehalfOf == null) {
          // Bump only on a fresh ALIVE -> SUSPECT transition, not on every subsequent tick's
          // repeated timeout against a target that's already SUSPECT (SWIM keeps re-probing
          // SUSPECT members while they wait out the suspicion grace period): once a target is
          // down, continuing to fail to reach it is confirming *its* state, not this node's own
          // health, and re-bumping on every one of those ticks would clamp the multiplier at its
          // ceiling for as long as the dead member sits in the table -- inflating this node's own
          // suspicionTimeout for every *other* member too, well past what the actual signal
          // warrants.
          MemberState current = members.get(pending.target.nodeId());
          if (current != null && current.status() == MemberStatus.ALIVE) {
            bumpLocalHealthMultiplier();
          }
          markSuspect(pending.target);
        }
      }
    }
  }

  private void escalate(long seq, PendingProbe pending) {
    pending.escalated = true;
    List<MemberState> relayCandidates =
        members.values().stream()
            .filter(state -> !state.id().nodeId().equals(self.nodeId()))
            .filter(state -> !state.id().nodeId().equals(pending.target.nodeId()))
            .filter(state -> state.status() != MemberStatus.DEAD)
            .collect(Collectors.toCollection(ArrayList::new));
    Collections.shuffle(relayCandidates, ThreadLocalRandom.current());
    int fanout = Math.min(config.indirectFanout(), relayCandidates.size());
    for (int i = 0; i < fanout; i++) {
      MemberId relay = relayCandidates.get(i).id();
      try {
        send(
            relay.gossipAddress(),
            new SwimMessage.PingReq(seq, pending.target, currentPiggyback(), catalogPayload()));
      } catch (IOException e) {
        log.warn(
            "{}: failed to send PingReq to relay {}: {}",
            self.nodeId(),
            relay.nodeId(),
            e.getMessage());
      }
    }
    pending.overallDeadline = clock.instant().plus(scaledPingTimeout());
  }

  private void checkSuspectExpiry() {
    Instant now = clock.instant();
    for (Map.Entry<String, Instant> entry : List.copyOf(suspectedSince.entrySet())) {
      if (now.isAfter(entry.getValue().plus(scaledSuspicionTimeout()))) {
        markDead(entry.getKey());
      }
    }
  }

  // ---- receive loop ----

  private void receiveLoop() {
    ByteBuffer buffer = ByteBuffer.allocate(65535);
    while (running) {
      buffer.clear();
      InetSocketAddress source;
      try {
        source = (InetSocketAddress) channel.receive(buffer);
      } catch (ClosedChannelException e) {
        return;
      } catch (IOException e) {
        if (running) {
          log.warn("{}: gossip receive failed: {}", self.nodeId(), e.getMessage());
        }
        continue;
      }
      buffer.flip();
      byte[] datagram = new byte[buffer.remaining()];
      buffer.get(datagram);
      if (transportProtocol == TransportProtocol.TLS) {
        handleSecureDatagram(source, datagram);
      } else {
        decodeAndHandle(datagram, source);
      }
    }
  }

  private void decodeAndHandle(byte[] datagram, InetSocketAddress source) {
    SwimMessage message;
    try {
      message = SwimCodec.decode(datagram, datagram.length);
    } catch (RuntimeException e) {
      log.warn(
          "{}: dropping malformed gossip datagram from {}: {}",
          self.nodeId(),
          source,
          e.getMessage());
      return;
    }
    handle(message, source);
  }

  /**
   * Routes one inbound DTLS datagram to its peer session (creating a server-mode one reactively if
   * this is the first datagram ever seen from {@code source} -- see the class doc for why this side
   * never needs to guess whether it's a fresh ClientHello or not: by construction, only the
   * lexicographically lower-addressed side ever creates a client session, so any session this
   * method finds or creates for an inbound datagram is never in conflict with one this node itself
   * initiated). Any decrypted application payloads are decoded and dispatched exactly as the
   * plaintext path does; any handshake response bytes the engine produced are sent back
   * immediately.
   */
  private void handleSecureDatagram(InetSocketAddress source, byte[] datagram) {
    DtlsPeerSession session =
        dtlsSessions.computeIfAbsent(source, ignored -> DtlsPeerSession.server(dtlsContext.get()));
    DtlsPeerSession.Result result;
    try {
      result = session.unwrap(datagram);
    } catch (SSLException e) {
      log.warn(
          "{}: DTLS handshake/decrypt failure from {}: {}", self.nodeId(), source, e.getMessage());
      dtlsSessions.remove(source, session);
      pendingSecureOutbound.remove(source);
      return;
    }
    try {
      for (byte[] packet : result.outboundPackets()) {
        sendRaw(source, packet);
      }
    } catch (IOException e) {
      log.warn(
          "{}: failed to send a DTLS handshake response to {}: {}",
          self.nodeId(),
          source,
          e.getMessage());
    }
    if (session.isEstablished()) {
      flushPendingSecureOutbound(source, session);
    }
    for (byte[] plaintext : result.messages()) {
      decodeAndHandle(plaintext, source);
    }
  }

  private void flushPendingSecureOutbound(InetSocketAddress address, DtlsPeerSession session) {
    byte[] queued = pendingSecureOutbound.remove(address);
    if (queued == null) {
      return;
    }
    try {
      for (byte[] packet : session.wrap(queued)) {
        sendRaw(address, packet);
      }
    } catch (IOException e) {
      log.warn(
          "{}: failed to encrypt queued gossip message to {}: {}",
          self.nodeId(),
          address,
          e.getMessage());
    }
  }

  private void handle(SwimMessage message, InetSocketAddress source) {
    mergeAll(message.piggyback());
    catalogExtension.onReceived(message.catalogPayload());
    switch (message) {
      case SwimMessage.Ping ping -> {
        try {
          send(source, new SwimMessage.Ack(ping.seq(), self, currentPiggyback(), catalogPayload()));
        } catch (IOException e) {
          log.warn("{}: failed to ack ping from {}: {}", self.nodeId(), source, e.getMessage());
        }
      }
      case SwimMessage.PingReq req -> {
        long relaySeq = nextSeq();
        Instant now = clock.instant();
        pendingProbes.put(
            relaySeq,
            new PendingProbe(
                req.target(),
                source,
                req.seq(),
                now.plus(config.pingTimeout()),
                now.plus(config.pingTimeout())));
        try {
          send(
              req.target().gossipAddress(),
              new SwimMessage.Ping(relaySeq, currentPiggyback(), catalogPayload()));
        } catch (IOException e) {
          pendingProbes.remove(relaySeq);
          log.warn(
              "{}: failed to relay ping to {} on behalf of {}: {}",
              self.nodeId(),
              req.target().nodeId(),
              source,
              e.getMessage());
        }
      }
      case SwimMessage.Ack ack -> {
        completeJoinWaiter(ack.seq(), ack.from());
        PendingProbe pending = pendingProbes.remove(ack.seq());
        if (pending != null) {
          onProbeResolved(pending, ack.from());
        } else {
          markAliveDirect(ack.from());
        }
      }
      case SwimMessage.IndirectAck indirectAck -> {
        PendingProbe pending = pendingProbes.remove(indirectAck.seq());
        if (pending != null) {
          onProbeResolved(pending, indirectAck.originalTarget());
        }
      }
      case SwimMessage.SyncRequest req -> {
        // The push half already happened: mergeAll(message.piggyback()) above just merged the
        // requester's full table into ours. The pull half is replying with ours in turn.
        try {
          send(
              source,
              new SwimMessage.SyncResponse(req.seq(), currentFullState(), catalogPayload()));
        } catch (IOException e) {
          log.warn(
              "{}: failed to reply to an anti-entropy sync from {}: {}",
              self.nodeId(),
              source,
              e.getMessage());
        }
      }
      case SwimMessage.SyncResponse ignored -> {
        // Nothing beyond the mergeAll(message.piggyback()) already done above -- this case exists
        // only so the switch stays exhaustive over the sealed SwimMessage interface.
      }
    }
  }

  private void onProbeResolved(PendingProbe pending, MemberId responder) {
    markAliveDirect(responder);
    if (pending.onBehalfOf == null) {
      // A self-originated probe (not a PingReq relayed on someone else's behalf) got answered --
      // this node's own view of the cluster is keeping up, so ease the multiplier back down.
      decayLocalHealthMultiplier();
    }
    if (pending.onBehalfOf != null) {
      try {
        send(
            pending.onBehalfOf,
            new SwimMessage.IndirectAck(
                pending.onBehalfOfSeq, pending.target, currentPiggyback(), catalogPayload()));
      } catch (IOException e) {
        log.warn(
            "{}: failed to send indirect ack to {}: {}",
            self.nodeId(),
            pending.onBehalfOf,
            e.getMessage());
      }
    }
  }

  private void completeJoinWaiter(long seq, MemberId from) {
    CompletableFuture<MemberId> waiter = joinWaiters.get(seq);
    if (waiter != null) {
      waiter.complete(from);
    }
  }

  // ---- membership table ----

  private void markAliveDirect(MemberId id) {
    MemberState current = members.get(id.nodeId());
    if (current != null && current.status() == MemberStatus.ALIVE) {
      return;
    }
    long incarnationToKeep = current == null ? 0 : current.incarnation();
    MemberState updated = new MemberState(id, MemberStatus.ALIVE, incarnationToKeep);
    members.put(id.nodeId(), updated);
    suspectedSince.remove(id.nodeId());
    deadSince.remove(id.nodeId());
    markChanged(updated);
  }

  private void markSuspect(MemberId id) {
    MemberState current = members.get(id.nodeId());
    if (current == null || current.status() != MemberStatus.ALIVE) {
      return;
    }
    MemberState updated = new MemberState(id, MemberStatus.SUSPECT, current.incarnation());
    members.put(id.nodeId(), updated);
    suspectedSince.put(id.nodeId(), clock.instant());
    markChanged(updated);
    log.info("{}: member {} is now SUSPECT", self.nodeId(), id.nodeId());
  }

  private void markDead(String nodeId) {
    MemberState current = members.get(nodeId);
    if (current == null || current.status() == MemberStatus.DEAD) {
      return;
    }
    MemberState updated = new MemberState(current.id(), MemberStatus.DEAD, current.incarnation());
    members.put(nodeId, updated);
    suspectedSince.remove(nodeId);
    deadSince.putIfAbsent(nodeId, clock.instant());
    markChanged(updated);
    log.info("{}: member {} is now DEAD", self.nodeId(), nodeId);
  }

  /**
   * Package-visible for {@code GossipMemberTest}'s refutation test to inject a claim directly,
   * without needing full multi-node network fault injection to exercise the merge/refutation logic
   * in isolation.
   */
  void mergeAll(List<MemberState> piggyback) {
    for (MemberState incoming : piggyback) {
      mergeOne(incoming);
    }
  }

  private void mergeOne(MemberState incoming) {
    if (incoming.id().nodeId().equals(self.nodeId())) {
      refuteIfNeeded(incoming);
      return;
    }
    MemberState current = members.get(incoming.id().nodeId());
    boolean adopt =
        current == null
            || incoming.incarnation() > current.incarnation()
            || (incoming.incarnation() == current.incarnation()
                && incoming.status().severity() > current.status().severity());
    if (!adopt) {
      return;
    }
    MemberStatus previousStatus = current == null ? null : current.status();
    members.put(incoming.id().nodeId(), incoming);
    if (incoming.status() == MemberStatus.SUSPECT) {
      suspectedSince.putIfAbsent(incoming.id().nodeId(), clock.instant());
    } else {
      suspectedSince.remove(incoming.id().nodeId());
    }
    if (incoming.status() == MemberStatus.DEAD) {
      deadSince.putIfAbsent(incoming.id().nodeId(), clock.instant());
    } else {
      deadSince.remove(incoming.id().nodeId());
    }
    markChanged(incoming);
    // {@link #markSuspect}/{@link #markDead} are the only places that log a status transition,
    // but both fire only for a status *this node itself* locally detected via its own probe
    // timeout -- a node that instead learns of a peer's SUSPECT/DEAD status secondhand, via an
    // incoming message's piggyback or an anti-entropy sync, adopted it silently right here with
    // no log line at all. That left an operator grepping cluster logs for "is now DEAD" with an
    // incomplete picture: only whichever node(s) happened to directly detect a failure ever said
    // so, even though every other node's own membership table was updated correctly. Same exact
    // wording as markSuspect/markDead precisely so a single substring search catches a status
    // change regardless of how this node learned it -- deliberately still scoped to SUSPECT/DEAD
    // only (not ALIVE), matching those two methods' own existing noise-level convention.
    if (previousStatus != incoming.status()
        && (incoming.status() == MemberStatus.SUSPECT || incoming.status() == MemberStatus.DEAD)) {
      log.info("{}: member {} is now {}", self.nodeId(), incoming.id().nodeId(), incoming.status());
    }
  }

  private void refuteIfNeeded(MemberState claimAboutSelf) {
    if (claimAboutSelf.status() == MemberStatus.ALIVE) {
      return;
    }
    if (claimAboutSelf.incarnation() < incarnation.get()) {
      return; // stale claim; our current incarnation already outranks it
    }
    // Someone out there suspects this node -- another local-health signal alongside a
    // self-originated probe timing out, since both suggest this node itself may be running slow.
    bumpLocalHealthMultiplier();
    long bumped = incarnation.updateAndGet(n -> Math.max(n, claimAboutSelf.incarnation()) + 1);
    MemberState updated = new MemberState(self, MemberStatus.ALIVE, bumped);
    members.put(self.nodeId(), updated);
    suspectedSince.remove(self.nodeId());
    deadSince.remove(self.nodeId());
    markChanged(updated);
    log.info(
        "{}: refuting a suspicion of itself, bumping incarnation to {}", self.nodeId(), bumped);
  }

  /**
   * Records {@code state}'s node as recently changed (for piggyback prioritization) and notifies
   * every {@link #onMembershipChange} listener with the resulting state -- the single choke point
   * every status-changing call site below routes through, so a listener never has to be wired into
   * more than one place to see every transition.
   */
  private void markChanged(MemberState state) {
    String nodeId = state.id().nodeId();
    synchronized (recentChangeOrder) {
      recentChangeOrder.remove(nodeId);
      recentChangeOrder.addFirst(nodeId);
      while (recentChangeOrder.size() > 64) {
        recentChangeOrder.removeLast();
      }
    }
    membershipListeners.forEach(listener -> listener.accept(state));
  }

  private List<MemberState> currentPiggyback() {
    List<MemberState> result = new ArrayList<>();
    MemberState selfState = members.get(self.nodeId());
    result.add(selfState);
    Set<String> seen = new LinkedHashSet<>();
    seen.add(self.nodeId());
    synchronized (recentChangeOrder) {
      for (String nodeId : recentChangeOrder) {
        if (result.size() >= config.piggybackCount()) {
          break;
        }
        if (seen.contains(nodeId)) {
          continue;
        }
        MemberState state = members.get(nodeId);
        if (state != null) {
          result.add(state);
          seen.add(nodeId);
        }
      }
    }
    return List.copyOf(result);
  }

  // ---- transport ----

  private long nextSeq() {
    return seqCounter.incrementAndGet();
  }

  private byte[] catalogPayload() {
    return catalogExtension.currentPayload();
  }

  private void send(InetSocketAddress address, SwimMessage message) throws IOException {
    send(address, message, false);
  }

  /**
   * {@code alwaysInitiate} is set only by {@link #join}: a joining node must always be free to dial
   * a configured seed regardless of address ordering (see the class doc). Every other call site
   * gates through {@link #isDesignatedInitiator}.
   */
  private void send(InetSocketAddress address, SwimMessage message, boolean alwaysInitiate)
      throws IOException {
    byte[] payload = SwimCodec.encode(message);
    if (transportProtocol == TransportProtocol.PLAINTEXT) {
      sendRaw(address, payload);
      return;
    }
    sendSecure(address, payload, alwaysInitiate);
  }

  private void sendSecure(InetSocketAddress address, byte[] payload, boolean alwaysInitiate)
      throws IOException {
    DtlsPeerSession existing = dtlsSessions.get(address);
    if (existing == null && !alwaysInitiate && !isDesignatedInitiator(address)) {
      return;
    }
    DtlsPeerSession session =
        dtlsSessions.computeIfAbsent(address, ignored -> createClientSession(address));
    if (session.isEstablished()) {
      try {
        for (byte[] packet : session.wrap(payload)) {
          sendRaw(address, packet);
        }
      } catch (SSLException e) {
        // SSLException is itself an IOException, so this still surfaces to the exact same
        // catch (IOException) every existing call site already has -- a failed secure send is
        // just another kind of failed send, from the caller's perspective.
        dtlsSessions.remove(address, session);
        throw e;
      }
    } else {
      // Handshake still in flight -- keep only the latest message per peer (SWIM's own periodic
      // retries make stale queued pings/acks pointless to preserve) and flush it once the receive
      // loop observes the handshake complete.
      pendingSecureOutbound.put(address, payload);
    }
  }

  /**
   * Only the lexicographically lower gossip address (by {@code host:port}) ever originates a DTLS
   * handshake toward the other -- both sides compute this identically, so it's never possible for
   * both to decide they're the initiator for the same pair. See the class doc for the full
   * rationale and its trade-off.
   */
  private boolean isDesignatedInitiator(InetSocketAddress peer) {
    return addressKey(self.gossipAddress()).compareTo(addressKey(peer)) < 0;
  }

  private static String addressKey(InetSocketAddress address) {
    return address.getHostString() + ":" + address.getPort();
  }

  /**
   * Invoked only from inside a {@code Map.computeIfAbsent} lambda (its mapping function can't
   * declare checked exceptions), so failures here are necessarily best-effort: log and return the
   * session anyway. A handshake whose very first flight failed to send will simply stall and get
   * cleaned up by {@link #checkDtlsHandshakeTimeouts}, the same as any other stalled handshake.
   */
  private DtlsPeerSession createClientSession(InetSocketAddress address) {
    DtlsPeerSession session = DtlsPeerSession.client(dtlsContext.get(), address);
    try {
      for (byte[] packet : session.beginHandshake()) {
        sendRaw(address, packet);
      }
    } catch (IOException e) {
      log.warn(
          "{}: failed to start DTLS handshake toward {}: {}",
          self.nodeId(),
          address,
          e.getMessage());
    }
    return session;
  }

  private void sendRaw(InetSocketAddress address, byte[] bytes) throws IOException {
    channel.send(ByteBuffer.wrap(bytes), address);
  }

  /**
   * Rotation hot-swap: rebuilds the DTLS {@link SSLContext} from whatever certificate material now
   * sits at {@code gimle.tls.certFile}/{@code keyFile} and swaps it in for every DTLS session
   * created from this point on -- both directions, unlike {@link RaftTransport}/{@code
   * FabricServer}, since {@link #dtlsContext} is read by both {@link #createClientSession} and
   * {@link #handleSecureDatagram}. No socket rebind: {@link #channel} is protocol-agnostic and
   * untouched by this. Already-established {@link DtlsPeerSession}s keep using the {@link
   * javax.net.ssl.SSLEngine} they were built with, the same "existing connections unaffected"
   * contract every other TLS-material reload in this codebase has. No-op in plaintext mode.
   */
  public void reloadDtlsMaterial() {
    if (transportProtocol == TransportProtocol.PLAINTEXT) {
      return;
    }
    dtlsContext.set(SslContexts.forMutualDtls(TlsSettings.fromConfig()));
    log.info("{}: reloaded DTLS material", self.nodeId());
  }

  @Override
  public void close() {
    running = false;
    ticker.shutdownNow();
    try {
      channel.close();
    } catch (IOException e) {
      log.warn("{}: failed to close gossip channel: {}", self.nodeId(), e.getMessage());
    }
  }

  /**
   * A probe this node originated (its own periodic ping, or relaying a {@code PingReq} on another
   * member's behalf) that hasn't yet resolved. Mutable, ticker-thread-confined.
   */
  private static final class PendingProbe {
    final MemberId target;
    final InetSocketAddress onBehalfOf;
    final long onBehalfOfSeq;
    final Instant directDeadline;
    volatile Instant overallDeadline;
    volatile boolean escalated;

    PendingProbe(
        MemberId target,
        InetSocketAddress onBehalfOf,
        long onBehalfOfSeq,
        Instant directDeadline,
        Instant overallDeadline) {
      this.target = target;
      this.onBehalfOf = onBehalfOf;
      this.onBehalfOfSeq = onBehalfOfSeq;
      this.directDeadline = directDeadline;
      this.overallDeadline = overallDeadline;
    }
  }
}
