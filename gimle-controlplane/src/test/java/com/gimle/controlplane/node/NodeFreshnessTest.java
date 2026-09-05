package com.gimle.controlplane.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.mimir.store.ObservedHeartbeat;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NodeFreshnessTest {

  private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
  private static final Duration STALE_AFTER = Duration.ofSeconds(15);

  private final NodeFreshness freshness = new NodeFreshness(STALE_AFTER);

  @Test
  void a_recent_heartbeat_is_healthy() {
    assertEquals(
        NodeFreshness.Status.HEALTHY,
        freshness.statusOf(true, heartbeatAgedSeconds(3), NOW.minusSeconds(3600), NOW));
  }

  @Test
  void a_heartbeat_older_than_the_threshold_is_stale_and_dark() {
    Optional<ObservedHeartbeat> old = heartbeatAgedSeconds(20);
    Instant longSettled = NOW.minusSeconds(3600);
    assertEquals(NodeFreshness.Status.STALE, freshness.statusOf(true, old, longSettled, NOW));
    assertTrue(freshness.hasGoneDark(true, old, longSettled, NOW));
  }

  /**
   * The whole point of the observation window. A store replica that has just become leader holds no
   * heartbeat for any node, however healthy the cluster is -- reading that emptiness as "every node
   * went dark at once" is what made a node whose agent never stopped reporting flap through STALE
   * and UNKNOWN and back.
   */
  @Test
  void an_absent_heartbeat_right_after_the_window_opened_is_pending_not_dark() {
    Instant justOpened = NOW.minusSeconds(2);
    assertEquals(
        NodeFreshness.Status.PENDING, freshness.statusOf(true, Optional.empty(), justOpened, NOW));
    assertFalse(freshness.hasGoneDark(true, Optional.empty(), justOpened, NOW));
  }

  @Test
  void an_absent_heartbeat_once_the_window_has_had_time_to_fill_is_unknown_and_dark() {
    Instant longOpen = NOW.minus(STALE_AFTER.multipliedBy(4));
    assertEquals(
        NodeFreshness.Status.UNKNOWN, freshness.statusOf(true, Optional.empty(), longOpen, NOW));
    assertTrue(freshness.hasGoneDark(true, Optional.empty(), longOpen, NOW));
  }

  /**
   * The grace window covers absence only. A node that did report, and whose report has since gone
   * stale, is stale however young the window is -- otherwise a store election would launder a
   * genuinely dead node back into looking fine.
   */
  @Test
  void a_stale_heartbeat_inside_a_young_window_is_still_stale() {
    Optional<ObservedHeartbeat> old = heartbeatAgedSeconds(60);
    assertEquals(
        NodeFreshness.Status.STALE, freshness.statusOf(true, old, NOW.minusSeconds(1), NOW));
    assertTrue(freshness.hasGoneDark(true, old, NOW.minusSeconds(1), NOW));
  }

  /**
   * The grace window is for a node the platform has a record of. One it has never heard of has no
   * report in flight to wait for, so absence is answered immediately however young the window is.
   */
  @Test
  void an_unregistered_node_gets_no_grace_window() {
    Instant justOpened = NOW.minusSeconds(1);
    assertEquals(
        NodeFreshness.Status.UNKNOWN, freshness.statusOf(false, Optional.empty(), justOpened, NOW));
    assertTrue(freshness.hasGoneDark(false, Optional.empty(), justOpened, NOW));
  }

  private static Optional<ObservedHeartbeat> heartbeatAgedSeconds(long seconds) {
    NodeHeartbeat heartbeat =
        new NodeHeartbeat(
            "node-alpha",
            new ResourceUsageSnapshot(4000, 0, 8L * 1024 * 1024 * 1024, 0),
            List.of());
    return Optional.of(new ObservedHeartbeat(heartbeat, NOW.minusSeconds(seconds)));
  }
}
