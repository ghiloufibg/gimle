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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
 * service catalog rides on.
 *
 * <p><b>{@code gimle.transport.protocol=tls}</b>: gossip is UDP, so mTLS here means DTLS (see
 * {@code claudedocs/tls-transport-security-design.md} §2), driven per-peer through {@link
 * DtlsPeerSession} rather than a single connection-oriented socket. Because any member can ping any
 * other at any time, a full mesh needs a rule for who originates each pair's handshake, or two
 * nodes pinging each other in the same tick would send simultaneous, colliding ClientHellos. Rather
 * than resolve that after the fact by inspecting handshake bytes, this class prevents it by
 * construction: {@link #isDesignatedInitiator} deterministically picks the lexicographically lower
 * gossip address as the sole initiator for a given pair, computed identically by both sides, so the
 * higher-addressed side never creates a competing client session. The trade-off -- the
 * non-initiating side's own probe attempts toward that peer silently no-op until the peer dials in,
 * or a later tick's random target selection happens to favor the correctly-ordered direction -- is
 * a transport-establishment latency detail, not a correctness gap; {@link #join} is the one
 * exception, since a joining node must always be free to dial a configured seed regardless of
 * address ordering (a seed has no way to know about, and therefore dial, a not-yet-joined peer).
 */
public final class GossipMember implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(GossipMember.class);

  private final MemberId self;
  private final GossipConfig config;
  private final DatagramChannel channel;
  private final ScheduledExecutorService ticker;
  private final TransportProtocol transportProtocol;
  private final AtomicReference<SSLContext> dtlsContext;

  private final Map<String, MemberState> members = new ConcurrentHashMap<>();
  private final Map<String, Instant> suspectedSince = new ConcurrentHashMap<>();
  private final Deque<String> recentChangeOrder = new ArrayDeque<>();
  private final Deque<String> probeOrder = new ArrayDeque<>();
  private final Map<Long, PendingProbe> pendingProbes = new ConcurrentHashMap<>();
  private final Map<Long, CompletableFuture<MemberId>> joinWaiters = new ConcurrentHashMap<>();
  private final Map<InetSocketAddress, DtlsPeerSession> dtlsSessions = new ConcurrentHashMap<>();
  private final Map<InetSocketAddress, byte[]> pendingSecureOutbound = new ConcurrentHashMap<>();
  private final AtomicLong incarnation = new AtomicLong();
  private final AtomicLong seqCounter = new AtomicLong();
  private final AtomicInteger localHealthMultiplier = new AtomicInteger();
  private final AtomicReference<Instant> lastAntiEntropySync = new AtomicReference<>(Instant.now());
  private final AtomicInteger fullStateOffset = new AtomicInteger();

  /** See {@link #currentFullState}. */
  private static final int MAX_FULL_STATE_PAGE = 128;

  /**
   * Clamp on {@link #localHealthMultiplier} (P2-9, a simplified Lifeguard-style local-health
   * adaptation -- not the full paper: no buddy-system suspicion, no k-independent-confirmation
   * decay, just the multiplier itself). A node whose own probes keep timing out, or that keeps
   * getting suspected by others, is more likely sitting on a slow/overloaded host or link than
   * every peer it's probing is actually down -- scaling this node's own timeouts up avoids it
   * flooding the cluster with false suspicions of everyone else.
   */
  private static final int MAX_LOCAL_HEALTH_MULTIPLIER = 8;

  private volatile boolean running;
  private volatile PiggybackExtension catalogExtension = PiggybackExtension.NONE;

  public GossipMember(MemberId self, GossipConfig config) throws IOException {
    this.config = config;
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
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-gossip-ticker-" + this.self.nodeId()).unstarted(r));
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
   * Contacts every configured seed and blocks until at least one acks or {@code pingTimeout}
   * elapses. A single unreachable seed is treated as this being the very first node of a new
   * cluster rather than an error; two or more configured seeds with none reachable is a genuine
   * failure to join.
   */
  public void join(List<InetSocketAddress> seeds) {
    List<InetSocketAddress> others =
        seeds.stream().distinct().filter(address -> !address.equals(self.gossipAddress())).toList();
    if (others.isEmpty()) {
      log.info(
          "{}: no other seeds configured; starting as the first node of a new cluster",
          self.nodeId());
      return;
    }

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
        log.warn("{}: failed to contact seed {}: {}", self.nodeId(), seed, e.getMessage());
      }
    }

    boolean reachable;
    try {
      CompletableFuture.anyOf(futures.toArray(CompletableFuture[]::new))
          .get(config.pingTimeout().toMillis(), TimeUnit.MILLISECONDS);
      reachable = true;
    } catch (ExecutionException | InterruptedException | TimeoutException e) {
      reachable = false;
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    } finally {
      seqs.forEach(joinWaiters::remove);
    }

    if (!reachable) {
      if (others.size() <= 1) {
        log.info(
            "{}: its single configured seed {} is unreachable; treating this as a legitimate"
                + " empty-cluster start, not an error",
            self.nodeId(),
            others.get(0));
        return;
      }
      throw GimleClusterException.noReachableSeed(
          self.nodeId(), others.stream().map(InetSocketAddress::toString).toList());
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
    } catch (RuntimeException e) {
      log.warn("{}: gossip protocol tick failed: {}", self.nodeId(), e.getMessage(), e);
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
    Instant now = Instant.now();
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
   * Periodic full-state push-pull (P2-8): fires roughly every {@link
   * GossipConfig#antiEntropyInterval} to one random peer, using the same round-robin target
   * selection {@link #pingRandomMember} uses. Piggyback alone can't guarantee eventual convergence
   * -- it carries at most {@link GossipConfig#piggybackCount} entries per message, and only the 64
   * most-recently-changed members are ever eligible ({@link #markChanged}) -- so a node partitioned
   * or slow long enough to miss enough gossip rounds would otherwise diverge permanently. Tracked
   * by wall-clock elapsed time rather than a tick counter so it stays correct regardless of {@code
   * protocolPeriod}.
   */
  private void maybeSyncWithRandomMember() {
    Instant now = Instant.now();
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
    Instant now = Instant.now();
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
    Instant now = Instant.now();
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
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
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
    pending.overallDeadline = Instant.now().plus(scaledPingTimeout());
  }

  private void checkSuspectExpiry() {
    Instant now = Instant.now();
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
        Instant now = Instant.now();
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
    members.put(id.nodeId(), new MemberState(id, MemberStatus.ALIVE, incarnationToKeep));
    suspectedSince.remove(id.nodeId());
    markChanged(id.nodeId());
  }

  private void markSuspect(MemberId id) {
    MemberState current = members.get(id.nodeId());
    if (current == null || current.status() != MemberStatus.ALIVE) {
      return;
    }
    members.put(id.nodeId(), new MemberState(id, MemberStatus.SUSPECT, current.incarnation()));
    suspectedSince.put(id.nodeId(), Instant.now());
    markChanged(id.nodeId());
    log.info("{}: member {} is now SUSPECT", self.nodeId(), id.nodeId());
  }

  private void markDead(String nodeId) {
    MemberState current = members.get(nodeId);
    if (current == null || current.status() == MemberStatus.DEAD) {
      return;
    }
    members.put(nodeId, new MemberState(current.id(), MemberStatus.DEAD, current.incarnation()));
    suspectedSince.remove(nodeId);
    markChanged(nodeId);
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
    members.put(incoming.id().nodeId(), incoming);
    if (incoming.status() == MemberStatus.SUSPECT) {
      suspectedSince.putIfAbsent(incoming.id().nodeId(), Instant.now());
    } else {
      suspectedSince.remove(incoming.id().nodeId());
    }
    markChanged(incoming.id().nodeId());
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
    members.put(self.nodeId(), new MemberState(self, MemberStatus.ALIVE, bumped));
    markChanged(self.nodeId());
    log.info(
        "{}: refuting a suspicion of itself, bumping incarnation to {}", self.nodeId(), bumped);
  }

  private void markChanged(String nodeId) {
    synchronized (recentChangeOrder) {
      recentChangeOrder.remove(nodeId);
      recentChangeOrder.addFirst(nodeId);
      while (recentChangeOrder.size() > 64) {
        recentChangeOrder.removeLast();
      }
    }
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
   * §6 rotation hot-swap: rebuilds the DTLS {@link SSLContext} from whatever certificate material
   * now sits at {@code gimle.tls.certFile}/{@code keyFile} and swaps it in for every DTLS session
   * created from this point on -- both directions, unlike {@link RaftTransport}/{@code
   * FabricServer}, since {@link #dtlsContext} is read by both {@link #createClientSession} and
   * {@link #handleSecureDatagram}. No socket rebind: {@link #channel} is protocol-agnostic and
   * untouched by this. Already-established {@link DtlsPeerSession}s keep using the {@link
   * javax.net.ssl.SSLEngine} they were built with, the same "existing connections unaffected"
   * contract every other §6 reload has. No-op in plaintext mode.
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
