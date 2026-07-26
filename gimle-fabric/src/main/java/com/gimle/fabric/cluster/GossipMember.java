package com.gimle.fabric.cluster;

import com.gimle.core.exception.GimleClusterException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
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
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One node agent's SWIM membership protocol participant (Phase 4 §3): a protocol-period loop (ping
 * a random member; on timeout, ask {@code indirectFanout} random relays to probe on this node's
 * behalf; no ack from anyone within the suspicion timeout flips the member to {@code SUSPECT}, and
 * unrefuted {@code SUSPECT} past its grace period becomes {@code DEAD}), running entirely over
 * {@link DatagramChannel} independent of the control plane -- gossip must keep functioning with the
 * control plane down or unreachable.
 *
 * <p>A member can refute its own suspicion by gossiping a higher incarnation of itself (§3); this
 * class does that automatically the moment it observes a piggyback entry naming itself as anything
 * other than {@code ALIVE}. Every message this node sends piggybacks its own current {@link
 * MemberState} plus a bounded number of the most-recently-changed other members' states -- the same
 * slot the service catalog (§5) rides on.
 */
public final class GossipMember implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(GossipMember.class);

  private final MemberId self;
  private final GossipConfig config;
  private final DatagramChannel channel;
  private final ScheduledExecutorService ticker;

  private final Map<String, MemberState> members = new ConcurrentHashMap<>();
  private final Map<String, Instant> suspectedSince = new ConcurrentHashMap<>();
  private final Deque<String> recentChangeOrder = new ArrayDeque<>();
  private final Map<Long, PendingProbe> pendingProbes = new ConcurrentHashMap<>();
  private final Map<Long, CompletableFuture<MemberId>> joinWaiters = new ConcurrentHashMap<>();
  private final AtomicLong incarnation = new AtomicLong();
  private final AtomicLong seqCounter = new AtomicLong();

  private volatile boolean running;
  private volatile PiggybackExtension catalogExtension = PiggybackExtension.NONE;

  public GossipMember(MemberId self, GossipConfig config) throws IOException {
    this.config = config;
    this.channel = DatagramChannel.open();
    channel.bind(self.gossipAddress());
    // Rebind self's advertised address to whatever the OS actually assigned -- most relevant
    // when the caller requested an ephemeral port (port 0): every other member learns this
    // node's address only from what it advertises about itself (§3's "no dedicated 'from' field"
    // design), so advertising the literal port 0 it was constructed with would make this node
    // permanently undialable by anyone except the very first UDP reply-to-source-address hop.
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
   * channel -- {@code com.gimle.fabric.catalog.ServiceCatalog} in practice (§5).
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
   * cluster (design §4) rather than an error; two or more configured seeds with none reachable is a
   * genuine failure to join.
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
        send(seed, new SwimMessage.Ping(seq, currentPiggyback(), catalogPayload()));
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
      pingRandomMember();
    } catch (RuntimeException e) {
      log.warn("{}: gossip protocol tick failed: {}", self.nodeId(), e.getMessage(), e);
    }
  }

  private void pingRandomMember() {
    List<MemberState> candidates =
        members.values().stream()
            .filter(state -> !state.id().nodeId().equals(self.nodeId()))
            .filter(state -> state.status() != MemberStatus.DEAD)
            .toList();
    if (candidates.isEmpty()) {
      return;
    }
    MemberState target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    long seq = nextSeq();
    Instant now = Instant.now();
    pendingProbes.put(
        seq,
        new PendingProbe(
            target.id(), null, 0, now.plus(config.pingTimeout()), now.plus(config.pingTimeout())));
    try {
      send(
          target.id().gossipAddress(),
          new SwimMessage.Ping(seq, currentPiggyback(), catalogPayload()));
    } catch (IOException e) {
      log.warn("{}: failed to ping {}: {}", self.nodeId(), target.id().nodeId(), e.getMessage());
    }
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
    pending.overallDeadline = Instant.now().plus(config.pingTimeout());
  }

  private void checkSuspectExpiry() {
    Instant now = Instant.now();
    for (Map.Entry<String, Instant> entry : List.copyOf(suspectedSince.entrySet())) {
      if (now.isAfter(entry.getValue().plus(config.suspicionTimeout()))) {
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
      SwimMessage message;
      try {
        message = SwimCodec.decode(datagram, datagram.length);
      } catch (RuntimeException e) {
        log.warn(
            "{}: dropping malformed gossip datagram from {}: {}",
            self.nodeId(),
            source,
            e.getMessage());
        continue;
      }
      handle(message, source);
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
    }
  }

  private void onProbeResolved(PendingProbe pending, MemberId responder) {
    markAliveDirect(responder);
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
    byte[] bytes = SwimCodec.encode(message);
    channel.send(ByteBuffer.wrap(bytes), address);
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
