package com.gimle.fabric.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.core.time.TestClock;
import com.gimle.core.time.TestScheduler;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.testkit.Await;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Real UDP sockets with millisecond-scale gossip/failure-detection timing ({@link #FAST_CONFIG}).
 * Observed flaking under this module's own class-level concurrency (root pom.xml) during a QA
 * hardening pass -- the same CPU-contention-under-class-level-concurrency cause {@code
 * RaftClusterTest} (gimle-mimir) and {@code SecretStoreTest} (gimle-fafnir) already needed {@link
 * Isolated} for.
 */
@Isolated
class GossipMemberTest {

  private static final GossipConfig FAST_CONFIG =
      new GossipConfig(
          Duration.ofMillis(80),
          Duration.ofMillis(40),
          Duration.ofMillis(200),
          2,
          6,
          Duration.ofSeconds(30),
          Duration.ofSeconds(30));

  private final List<GossipMember> members = new ArrayList<>();

  @AfterEach
  void tearDown() {
    members.forEach(GossipMember::close);
  }

  private GossipMember newMember(String nodeId) throws IOException {
    return newMember(nodeId, FAST_CONFIG);
  }

  private GossipMember newMember(String nodeId, GossipConfig config) throws IOException {
    MemberId id = new MemberId(nodeId, new InetSocketAddress("127.0.0.1", 0));
    GossipMember member = new GossipMember(id, config);
    members.add(member);
    return member;
  }

  @Test
  @Timeout(15)
  void a_lone_node_with_no_seeds_starts_as_a_new_cluster() throws IOException {
    GossipMember a = newMember("node-a");
    a.start();
    a.join(List.of());
    assertEquals(MemberStatus.ALIVE, a.memberState("node-a").orElseThrow().status());
  }

  @Test
  @Timeout(15)
  void a_single_unreachable_seed_is_a_legitimate_bootstrap_not_an_error() throws IOException {
    GossipMember a = newMember("node-a");
    a.start();
    InetSocketAddress unreachableSeed = new InetSocketAddress("127.0.0.1", 1);
    a.join(List.of(unreachableSeed));
    assertEquals(MemberStatus.ALIVE, a.memberState("node-a").orElseThrow().status());
  }

  @Test
  @Timeout(15)
  void several_unreachable_seeds_do_not_throw_and_leave_the_node_running_unjoined()
      throws IOException {
    // FUNC-43: join() used to throw GimleClusterException here, and nothing anywhere caught it --
    // a routine startup networking blip with >=1 configured seeds would crash the caller outright.
    // It must never throw at all now: every seed unreachable, whether there's one or several, is
    // the same unresolvable-at-join-time ambiguity, handled the same way (see join()'s own
    // javadoc).
    GossipMember a = newMember("node-a");
    a.start();
    InetSocketAddress seed1 = new InetSocketAddress("127.0.0.1", 1);
    InetSocketAddress seed2 = new InetSocketAddress("127.0.0.1", 2);
    a.join(List.of(seed1, seed2));
    assertEquals(MemberStatus.ALIVE, a.memberState("node-a").orElseThrow().status());
  }

  @Test
  @Timeout(20)
  void a_node_still_isolated_after_join_returns_finds_its_seed_once_it_recovers() throws Exception {
    // The actual gap JOIN_ATTEMPTS's own javadoc used to admit: node-a is not started at all
    // during node-b's entire join() budget, so every attempt genuinely fails and join() returns
    // having found nobody -- distinct from join_retries_recover_once_a_transiently_unreachable_
    // seed_starts_receiving above, which recovers *within* a single join() call. Without
    // retrySeedsIfIsolated running on every later tick, node-b would stay isolated forever even
    // once node-a starts.
    GossipMember a = newMember("node-a");
    GossipMember b = newMember("node-b");
    b.start();

    b.join(List.of(a.self().gossipAddress()));
    assertTrue(
        !isAlive(b, "node-a"), "the seed genuinely wasn't up yet during join()'s own budget");

    a.start(); // the seed recovers only after join() already gave up

    Await.until(() -> isAlive(a, "node-b") && isAlive(b, "node-a"), Duration.ofSeconds(5));
  }

  @Test
  @Timeout(20)
  void two_nodes_discover_each_other_via_join() throws Exception {
    GossipMember a = newMember("node-a");
    GossipMember b = newMember("node-b");
    a.start();
    b.start();

    b.join(List.of(a.self().gossipAddress()));

    Await.until(() -> isAlive(a, "node-b") && isAlive(b, "node-a"), Duration.ofSeconds(5));
  }

  @Test
  @Timeout(20)
  void join_retries_recover_once_a_transiently_unreachable_seed_starts_receiving()
      throws Exception {
    // node-a's socket is bound (it has an address to send to) but its receive loop hasn't started
    // yet -- the exact race a freshly-created container's not-yet-scheduled process hits: node-b's
    // first join ping(s) go unanswered even though node-a is about to come up. Without retries,
    // join() would give up on the first attempt and node-b would silently start its own,
    // permanently separate one-node cluster.
    GossipMember a = newMember("node-a");
    GossipMember b = newMember("node-b");
    b.start();

    Thread joiner = new Thread(() -> b.join(List.of(a.self().gossipAddress())));
    joiner.start();
    // Longer than one pingTimeout (40ms) so at least the first attempt is guaranteed to fail, but
    // well short of every attempt's budget (5 * 40ms = 200ms) so join() must be retrying to
    // succeed at all.
    Thread.sleep(60);
    a.start();
    joiner.join(Duration.ofSeconds(5).toMillis());
    assertTrue(!joiner.isAlive(), "join() did not return within its own retry budget");

    assertTrue(isAlive(a, "node-b"));
    assertTrue(isAlive(b, "node-a"));
  }

  @Test
  @Timeout(30)
  void a_killed_member_converges_to_dead_across_the_rest() throws Exception {
    GossipMember a = newMember("node-a");
    GossipMember b = newMember("node-b");
    GossipMember c = newMember("node-c");
    a.start();
    b.start();
    c.start();
    b.join(List.of(a.self().gossipAddress()));
    c.join(List.of(a.self().gossipAddress()));

    Await.until(
        () ->
            isAlive(a, "node-b")
                && isAlive(a, "node-c")
                && isAlive(b, "node-a")
                && isAlive(b, "node-c")
                && isAlive(c, "node-a")
                && isAlive(c, "node-b"),
        Duration.ofSeconds(10));

    // "kill" node-c by closing its channel without a graceful leave -- the rest must detect this
    // purely through the SWIM failure detector (ping/ping-req timeouts + suspicion grace period).
    c.close();

    Await.until(
        () ->
            a.memberState("node-c").map(s -> s.status() == MemberStatus.DEAD).orElse(false)
                && b.memberState("node-c").map(s -> s.status() == MemberStatus.DEAD).orElse(false),
        Duration.ofSeconds(15));
  }

  @Test
  @Timeout(30)
  void a_node_marked_dead_via_gossip_has_its_catalog_entries_evicted_without_a_breaker_trip()
      throws Exception {
    ServiceExport export = new ServiceExport("com.gimle.example.Greeter", Version.parse("1.0.0"));
    ModuleId module = new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0"));

    GossipMember a = newMember("node-a");
    GossipMember b = newMember("node-b");
    GossipMember c = newMember("node-c");

    // Only node-a's catalog is under test -- wired to its own GossipMember's membership
    // transitions exactly as production code (AgentMain) wires it, so this exercises the real
    // integration rather than calling ServiceCatalog.onMembershipChange directly.
    ServiceCatalog catalogOnA = new ServiceCatalog();
    a.attachCatalog(catalogOnA);
    a.onMembershipChange(catalogOnA::onMembershipChange);

    ServiceCatalog catalogOnC = new ServiceCatalog();
    c.attachCatalog(catalogOnC);
    catalogOnC.localRegister(
        c.self(), "worker-c", module, export, Optional.empty(), new InetSocketAddress(9000));

    a.start();
    b.start();
    c.start();
    b.join(List.of(a.self().gossipAddress()));
    c.join(List.of(a.self().gossipAddress()));

    // node-c's own registration must first ride gossip's piggyback channel to node-a before this
    // test can meaningfully assert on its eviction.
    Await.until(() -> !catalogOnA.endpointsFor(export).isEmpty(), Duration.ofSeconds(10));

    // "kill" node-c the same way a_killed_member_converges_to_dead_across_the_rest does -- no
    // graceful unregister, purely the SWIM failure detector converging to DEAD.
    c.close();

    Await.until(
        () -> a.memberState("node-c").map(s -> s.status() == MemberStatus.DEAD).orElse(false),
        Duration.ofSeconds(15));

    // The catalog eviction is driven directly by the membership transition above, not by any
    // caller ever attempting (and failing) a call against node-c's endpoint -- nothing in this
    // test ever exercises a circuit breaker.
    Await.until(() -> catalogOnA.endpointsFor(export).isEmpty(), Duration.ofSeconds(5));
    assertTrue(catalogOnA.endpointsForInterface(export.interfaceName()).isEmpty());
  }

  @Test
  void a_long_dead_member_is_eventually_forgotten_not_kept_forever() throws IOException {
    // Driven directly via mergeAll (same technique the self-refutation tests below use) rather
    // than a real multi-node kill-and-detect scenario: with a second live peer still gossiping,
    // an unchanging DEAD entry that's one of only a couple of members in a low-churn cluster stays
    // pinned at the front of both sides' recentChangeOrder forever, so each side's own reap keeps
    // getting undone by the other re-teaching it -- a real eventual-consistency property of SWIM
    // (see reapExpiredDeadMembers' own javadoc: no cluster-wide coordination), not a bug this test
    // needs to chase. Injecting the DEAD claim once, with nothing else re-asserting it, isolates
    // exactly the mechanism this item adds: a node forgets an entry that's stayed DEAD long enough.
    //
    // No network traffic is ever exercised by this scenario (a lone node ticking against an empty
    // peer set), so its timing is entirely internal to GossipMember -- exactly the case a
    // TestClock/TestScheduler pair can drive deterministically instead of a real sleep.
    GossipConfig config =
        new GossipConfig(
            Duration.ofMillis(50),
            Duration.ofMillis(40),
            Duration.ofMillis(200),
            2,
            6,
            Duration.ofSeconds(30),
            Duration.ofMillis(150));
    TestClock clock = new TestClock();
    TestScheduler scheduler = new TestScheduler(clock);
    MemberId id = new MemberId("node-a", new InetSocketAddress("127.0.0.1", 0));
    GossipMember a = new GossipMember(id, config, clock, scheduler);
    members.add(a);
    a.start();

    MemberId longGoneNodeId = new MemberId("node-gone", new InetSocketAddress("127.0.0.1", 9));
    a.mergeAll(List.of(new MemberState(longGoneNodeId, MemberStatus.DEAD, 0)));
    assertEquals(MemberStatus.DEAD, a.memberState("node-gone").orElseThrow().status());

    // Ten protocol-period ticks, comfortably past deadMemberReapAfter (150ms) -- the same
    // production durations the real-time version of this test waited out, but costing no wall
    // clock time.
    scheduler.advance(Duration.ofMillis(500));

    // Once reaped, the entry is gone entirely -- not merely relabeled DEAD forever -- so a
    // long-running cluster's membership table doesn't grow unboundedly with node churn.
    assertTrue(a.memberState("node-gone").isEmpty());
    assertTrue(a.members().stream().noneMatch(s -> s.id().nodeId().equals("node-gone")));
  }

  @Test
  void a_member_refutes_a_suspicion_of_itself_by_bumping_incarnation() throws IOException {
    GossipMember b = newMember("node-b");
    b.start();
    long incarnationBefore = b.incarnation();

    // Simulate having received a gossip piggyback entry (from anyone) claiming this node is
    // SUSPECT at its current incarnation -- exercised directly against the merge/refutation
    // logic so the assertion is deterministic, rather than racing b's own real probe traffic in
    // a multi-node setup (a direct Ack from a live b would otherwise keep clearing the claim).
    MemberState falseSuspicion = new MemberState(b.self(), MemberStatus.SUSPECT, incarnationBefore);
    b.mergeAll(List.of(falseSuspicion));

    assertTrue(b.incarnation() > incarnationBefore);
    assertEquals(MemberStatus.ALIVE, b.memberState("node-b").orElseThrow().status());
    // Being suspected is a local-health signal too, alongside a self-originated probe timing
    // out -- both suggest this node itself may be running slow, not just its peers.
    assertTrue(b.localHealthMultiplier() > 0);
  }

  @Test
  void the_local_health_multiplier_clamps_rather_than_growing_unbounded() throws IOException {
    GossipMember b = newMember("node-b");
    b.start();

    for (int i = 0; i < 20; i++) {
      // Each call bumps the incarnation past the last claim, so every one is a fresh, non-stale
      // suspicion to refute -- otherwise refuteIfNeeded's own staleness check would no-op most of
      // these and the multiplier would never climb past one bump.
      b.mergeAll(List.of(new MemberState(b.self(), MemberStatus.SUSPECT, b.incarnation())));
    }

    assertEquals(8, b.localHealthMultiplier());
  }

  @Test
  void probe_target_selection_visits_every_live_member_within_one_cycle() throws IOException {
    // Round-robin coverage: a pure-random pick gives no such guarantee, so this would be
    // flaky under a random-pick implementation but must hold deterministically under this one.
    GossipMember a = newMember("node-a");
    a.start();
    List<String> peers = List.of("node-b", "node-c", "node-d", "node-e");
    for (String peer : peers) {
      a.mergeAll(
          List.of(
              new MemberState(
                  new MemberId(peer, new InetSocketAddress("127.0.0.1", 0)),
                  MemberStatus.ALIVE,
                  0)));
    }

    Set<String> visited = new HashSet<>();
    for (int i = 0; i < peers.size(); i++) {
      visited.add(a.nextProbeTarget().id().nodeId());
    }

    assertEquals(Set.copyOf(peers), visited);
  }

  @Test
  void a_stale_suspicion_below_the_current_incarnation_is_ignored() throws IOException {
    GossipMember b = newMember("node-b");
    b.start();
    // Bump once for real first, then present a stale (lower-incarnation) suspicion.
    b.mergeAll(List.of(new MemberState(b.self(), MemberStatus.SUSPECT, 0)));
    long incarnationAfterFirstRefutation = b.incarnation();

    b.mergeAll(List.of(new MemberState(b.self(), MemberStatus.SUSPECT, 0)));

    assertEquals(incarnationAfterFirstRefutation, b.incarnation());
    assertEquals(MemberStatus.ALIVE, b.memberState("node-b").orElseThrow().status());
  }

  @Test
  @Timeout(20)
  void anti_entropy_sync_delivers_a_change_piggyback_alone_cannot_carry() throws Exception {
    // piggybackCount=1 means currentPiggyback() has room only for the sender's own state -- no
    // other member's change can ever ride the regular Ping/Ack piggyback channel under this
    // config, so node-c's arrival can *only* reach node-a through the periodic full-state sync.
    GossipConfig config =
        new GossipConfig(
            Duration.ofMillis(50),
            Duration.ofMillis(40),
            Duration.ofMillis(300),
            2,
            1,
            Duration.ofMillis(150),
            Duration.ofSeconds(30));
    GossipMember a = newMember("node-a", config);
    GossipMember b = newMember("node-b", config);
    a.start();
    b.start();
    b.join(List.of(a.self().gossipAddress()));
    Await.until(() -> isAlive(a, "node-b") && isAlive(b, "node-a"), Duration.ofSeconds(5));

    // Injected directly into b's local table, with no network traffic of its own -- a can only
    // learn of it via a full-state exchange b initiates.
    MemberId c = new MemberId("node-c", new InetSocketAddress("127.0.0.1", 9));
    b.mergeAll(List.of(new MemberState(c, MemberStatus.ALIVE, 0)));

    Await.until(() -> a.memberState("node-c").isPresent(), Duration.ofSeconds(10));
  }

  private static boolean isAlive(GossipMember member, String nodeId) {
    return member.memberState(nodeId).map(s -> s.status() == MemberStatus.ALIVE).orElse(false);
  }
}
