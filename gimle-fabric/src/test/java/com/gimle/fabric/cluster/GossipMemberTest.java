package com.gimle.fabric.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleClusterException;
import com.gimle.fabric.testsupport.Await;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class GossipMemberTest {

  private static final GossipConfig FAST_CONFIG =
      new GossipConfig(Duration.ofMillis(80), Duration.ofMillis(40), Duration.ofMillis(200), 2, 6);

  private final List<GossipMember> members = new ArrayList<>();

  @AfterEach
  void tearDown() {
    members.forEach(GossipMember::close);
  }

  private GossipMember newMember(String nodeId) throws IOException {
    MemberId id = new MemberId(nodeId, new InetSocketAddress("127.0.0.1", 0));
    GossipMember member = new GossipMember(id, FAST_CONFIG);
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
  void multiple_unreachable_seeds_throw_gimle_cluster_exception() throws IOException {
    GossipMember a = newMember("node-a");
    a.start();
    InetSocketAddress seed1 = new InetSocketAddress("127.0.0.1", 1);
    InetSocketAddress seed2 = new InetSocketAddress("127.0.0.1", 2);
    assertThrows(GimleClusterException.class, () -> a.join(List.of(seed1, seed2)));
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

  private static boolean isAlive(GossipMember member, String nodeId) {
    return member.memberState(nodeId).map(s -> s.status() == MemberStatus.ALIVE).orElse(false);
  }
}
