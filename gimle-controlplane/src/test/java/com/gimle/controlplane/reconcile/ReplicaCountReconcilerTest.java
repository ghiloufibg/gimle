package com.gimle.controlplane.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.time.TestClock;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.DisruptionBudget;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ReconcilerInstanceState;
import com.gimle.mimir.store.StateStore;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

class ReplicaCountReconcilerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static final ModuleId ORDERS =
      new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));

  /**
   * The genuine production values {@code ControlPlaneMain} configures ({@code NODE_DARK_TIMEOUT},
   * passed for both), rather than the millisecond stand-ins these tests used to compress them to so
   * a {@code Thread.sleep} could outrun them. A {@link TestClock} makes the real 15s as cheap to
   * cross as a 50ms fake, and exactly rather than approximately.
   */
  private static final Duration NODE_DARK_TIMEOUT = Duration.ofSeconds(15);

  /**
   * Deliberately well inside {@link #NODE_DARK_TIMEOUT}, preserving the relationship the original
   * millisecond values had (50ms grace against a 15s dark timeout): advancing past the grace period
   * must not also age the heartbeat into darkness, or a test meant to isolate the grace-period
   * mechanic would really be exercising the dark-timeout one. Production happens to configure both
   * to the same 15s, which is why this is stated here rather than reused from there.
   */
  private static final Duration GRACE_PERIOD = Duration.ofSeconds(5);

  private static boolean hasAssignment(StateStore store, String deploymentName, int index) {
    return store.listAssignmentsFor(Optional.empty(), deploymentName).stream()
        .anyMatch(a -> a.instanceIndex() == index);
  }

  private static Set<Integer> assignedIndices(StateStore store, String deploymentName) {
    return store.listAssignmentsFor(Optional.empty(), deploymentName).stream()
        .map(InstanceAssignment::instanceIndex)
        .collect(Collectors.toSet());
  }

  private static DeploymentSpec deploymentWithDisruption(
      String name, int replicas, DisruptionBudget disruption) {
    return new DeploymentSpec(
        name,
        ORDERS,
        "",
        replicas,
        PlacementConstraints.NONE,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(disruption));
  }

  /**
   * Simulates what {@code DeploymentReconciler}'s own missing-index placement logic does once it
   * sees a node-death-released index: places a fresh assignment on a healthy node and immediately
   * confirms it via that node's own heartbeat -- the only way a released index actually stops
   * counting as "unavailable" against a deployment's disruption budget. {@code
   * alreadyConfirmedOnThatNode} must include every index this node already hosts, not just the new
   * one -- {@code putNodeHeartbeat} replaces a node's entire observation list each call (see {@code
   * DeploymentReconcilerRollingUpdateTest#markManyReady}'s identical note), so confirming a second
   * index on the same node without re-stating the first would silently un-confirm it.
   */
  private static void confirmOnHealthyNode(
      StateStore store,
      String deploymentName,
      String nodeId,
      Set<Integer> alreadyConfirmedOnThatNode,
      int newlyConfirmedIndex) {
    store.putAssignment(new InstanceAssignment(deploymentName, newlyConfirmedIndex, nodeId));
    Set<Integer> allConfirmed = new HashSet<>(alreadyConfirmedOnThatNode);
    allConfirmed.add(newlyConfirmedIndex);
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            nodeId,
            new ResourceUsageSnapshot(1000, 0, 1000, 0),
            allConfirmed.stream()
                .map(
                    index ->
                        InstanceObservation.builder(
                                deploymentName, index, ORDERS, "ACTIVE", true, true)
                            .build())
                .toList()));
  }

  /**
   * Zero grace period: "not confirmed" is acted on the very first tick, for tests that don't care
   * about the grace-period mechanic itself.
   */
  private static ReplicaCountReconciler immediateReconciler(
      StateStore store, Duration nodeDarkTimeout) {
    return new ReplicaCountReconciler(store, nodeDarkTimeout, Duration.ZERO);
  }

  @Test
  void an_assignment_confirmed_by_a_fresh_heartbeat_survives() {
    StateStore store = new StateStore();
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1000, 0, 1000, 0),
            List.of(
                InstanceObservation.builder("orders-service", 0, ORDERS, "ACTIVE", true, true)
                    .build())));

    immediateReconciler(store, Duration.ofSeconds(15)).reconcileOnce();

    assertTrue(hasAssignment(store, "orders-service", 0));
  }

  @Test
  void an_assignment_the_node_never_mentions_is_removed_once_the_grace_period_elapses() {
    StateStore store = new StateStore();
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    // node-a heartbeats, but reports running nothing at all for this deployment
    store.putNodeHeartbeat(
        new NodeHeartbeat("node-a", new ResourceUsageSnapshot(1000, 0, 1000, 0), List.of()));

    immediateReconciler(store, Duration.ofSeconds(15)).reconcileOnce();

    assertFalse(hasAssignment(store, "orders-service", 0));
  }

  @Test
  void a_brand_new_assignment_not_yet_mentioned_survives_within_the_grace_period() {
    // The bug this grace period fixes: a freshly-placed assignment is, by construction, never in
    // any heartbeat sent before the owning agent has fetched and started it. Removing on the very
    // first "not mentioned yet" tick would undo the placement before the agent had a chance to act
    // on it -- this reproduces exactly that timing and asserts it survives.
    StateStore store = new StateStore();
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat("node-a", new ResourceUsageSnapshot(1000, 0, 1000, 0), List.of()));

    TestClock clock = TestClock.startingNow();
    ReplicaCountReconciler reconciler =
        new ReplicaCountReconciler(store, NODE_DARK_TIMEOUT, GRACE_PERIOD, clock);
    reconciler.reconcileOnce();
    assertTrue(
        hasAssignment(store, "orders-service", 0),
        "should survive the first tick, within the grace period");

    clock.advance(GRACE_PERIOD.minusSeconds(1));
    reconciler.reconcileOnce();
    assertTrue(hasAssignment(store, "orders-service", 0), "still within the grace period");
  }

  /**
   * The store holds heartbeats only on whichever replica is currently leader, and never replicates
   * them, so leadership moving leaves the new leader with none for any node -- while the node
   * registrations, which are replicated, are all still there. Read naively that says every
   * registered node in the cluster went dark at the same instant, and this reconciler would release
   * every assignment in the cluster over it. It must instead wait for the reports it now knows are
   * coming.
   */
  @Test
  void assignments_survive_the_store_losing_every_heartbeat_to_a_leadership_change() {
    TestClock clock = TestClock.startingNow();
    StateStore store = new StateStore(clock);
    store.putNodeRegistration(
        new NodeRegistration(
            "node-a", new NodeCapabilities(Set.of(IsolationTier.TIER_1), Set.of())));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1000, 0, 1000, 0),
            List.of(
                InstanceObservation.builder("orders-service", 0, ORDERS, "ACTIVE", true, true)
                    .build())));

    ReplicaCountReconciler reconciler =
        new ReplicaCountReconciler(store, NODE_DARK_TIMEOUT, GRACE_PERIOD, clock);
    reconciler.reconcileOnce();
    assertTrue(hasAssignment(store, "orders-service", 0));

    store.beginNodeObservationWindow(); // leadership moves here: every heartbeat is gone
    clock.advance(Duration.ofSeconds(1));
    reconciler.reconcileOnce(); // would start the removal grace period if this read as "gone"
    clock.advance(GRACE_PERIOD.plusSeconds(1));
    reconciler.reconcileOnce(); // and would act on it here

    assertTrue(
        hasAssignment(store, "orders-service", 0),
        "an emptied heartbeat map is the store not having heard yet, not the node being gone");
  }

  /**
   * The grace window is bounded: once it has had time to fill and the node still has not reported,
   * the silence is the node's own and its assignments are released as before.
   */
  @Test
  void an_assignment_is_still_released_once_the_reopened_window_has_had_time_to_fill() {
    TestClock clock = TestClock.startingNow();
    StateStore store = new StateStore(clock);
    store.putNodeRegistration(
        new NodeRegistration(
            "node-a", new NodeCapabilities(Set.of(IsolationTier.TIER_1), Set.of())));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1000, 0, 1000, 0),
            List.of(
                InstanceObservation.builder("orders-service", 0, ORDERS, "ACTIVE", true, true)
                    .build())));

    ReplicaCountReconciler reconciler =
        new ReplicaCountReconciler(store, NODE_DARK_TIMEOUT, GRACE_PERIOD, clock);
    reconciler.reconcileOnce();

    store.beginNodeObservationWindow();
    clock.advance(NODE_DARK_TIMEOUT.multipliedBy(3));
    reconciler.reconcileOnce(); // past the window's grace: starts the removal grace period
    clock.advance(GRACE_PERIOD.plusSeconds(1));
    reconciler.reconcileOnce();

    assertFalse(hasAssignment(store, "orders-service", 0));
  }

  @Test
  void an_unmentioned_assignment_is_removed_once_the_grace_period_elapses() {
    StateStore store = new StateStore();
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat("node-a", new ResourceUsageSnapshot(1000, 0, 1000, 0), List.of()));

    TestClock clock = TestClock.startingNow();
    ReplicaCountReconciler reconciler =
        new ReplicaCountReconciler(store, NODE_DARK_TIMEOUT, GRACE_PERIOD, clock);
    reconciler.reconcileOnce(); // starts the grace period
    assertTrue(hasAssignment(store, "orders-service", 0));

    clock.advance(GRACE_PERIOD.plusSeconds(1));
    reconciler.reconcileOnce(); // grace period elapsed: now it removes it

    assertFalse(hasAssignment(store, "orders-service", 0));
  }

  @Test
  void becoming_confirmed_within_the_grace_period_cancels_the_pending_removal() {
    StateStore store = new StateStore();
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat("node-a", new ResourceUsageSnapshot(1000, 0, 1000, 0), List.of()));

    TestClock clock = TestClock.startingNow();
    ReplicaCountReconciler reconciler =
        new ReplicaCountReconciler(store, NODE_DARK_TIMEOUT, GRACE_PERIOD, clock);
    reconciler.reconcileOnce(); // starts the grace period

    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1000, 0, 1000, 0),
            List.of(
                InstanceObservation.builder("orders-service", 0, ORDERS, "ACTIVE", true, true)
                    .build())));

    clock.advance(GRACE_PERIOD.plusSeconds(1)); // past what would have been the deadline
    reconciler.reconcileOnce();

    assertTrue(
        hasAssignment(store, "orders-service", 0),
        "the agent caught up before the grace period expired");
  }

  @Test
  void an_assignment_to_a_node_that_never_heartbeated_at_all_is_removed() {
    StateStore store = new StateStore();
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-ghost"));

    immediateReconciler(store, Duration.ofSeconds(15)).reconcileOnce();

    assertFalse(hasAssignment(store, "orders-service", 0));
  }

  @Test
  void
      an_assignment_whose_nodes_heartbeat_has_gone_stale_is_removed_even_if_it_mentions_the_instance() {
    StateStore store = new StateStore();
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1000, 0, 1000, 0),
            List.of(
                InstanceObservation.builder("orders-service", 0, ORDERS, "ACTIVE", true, true)
                    .build())));

    // The heartbeat's own receivedAt is stamped by StateStore with the system clock, so the clock
    // is anchored at real now and advanced past the real production dark timeout from there.
    TestClock clock = TestClock.startingNow().advance(NODE_DARK_TIMEOUT.plusSeconds(1));

    new ReplicaCountReconciler(store, NODE_DARK_TIMEOUT, Duration.ZERO, clock).reconcileOnce();

    assertFalse(hasAssignment(store, "orders-service", 0));
  }

  @Test
  void unrelated_assignments_on_a_healthy_node_are_left_alone() {
    StateStore store = new StateStore();
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putAssignment(new InstanceAssignment("catalog-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1000, 0, 1000, 0),
            List.of(
                InstanceObservation.builder("orders-service", 0, ORDERS, "ACTIVE", true, true)
                    .build(),
                InstanceObservation.builder("catalog-service", 0, ORDERS, "ACTIVE", true, true)
                    .build())));

    immediateReconciler(store, Duration.ofSeconds(15)).reconcileOnce();

    assertTrue(hasAssignment(store, "orders-service", 0));
    assertTrue(hasAssignment(store, "catalog-service", 0));
  }

  @Test
  void grace_period_state_survives_a_reconciler_reconstruction_against_the_same_store() {
    // Simulates a reconciler-leader failover: a fresh ReplicaCountReconciler instance, backed by
    // the same on-disk store, must resume the already-elapsing grace-period timer rather than
    // restarting it, which would delay a legitimate reschedule.
    Path dir = tempDir.resolve("survives-reconstruction");
    StateStore store = new StateStore();
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat("node-a", new ResourceUsageSnapshot(1000, 0, 1000, 0), List.of()));

    TestClock clock = TestClock.startingNow();
    ReplicaCountReconciler original =
        new ReplicaCountReconciler(store, NODE_DARK_TIMEOUT, GRACE_PERIOD, clock);
    original.reconcileOnce(); // starts the grace period
    assertTrue(hasAssignment(store, "orders-service", 0));

    // Construct a brand-new reconciler against the same store: the store (gimle-mimir) is its own
    // process and doesn't restart with a failed-over reconciler leader, so only the reconciler's
    // own in-memory history is lost -- everything it must resume from lives in the store.
    ReplicaCountReconciler resumed =
        new ReplicaCountReconciler(store, NODE_DARK_TIMEOUT, GRACE_PERIOD, clock);

    clock.advance(GRACE_PERIOD.plusSeconds(1)); // past the original deadline, not a fresh one
    resumed.reconcileOnce();

    assertFalse(
        hasAssignment(store, "orders-service", 0),
        "the resumed reconciler should have completed the grace period it didn't start itself");
  }

  @Test
  void converges_correctly_from_an_arbitrary_mix_of_persisted_grace_period_state() {
    // A brand-new reconciler must handle every one of these correctly on its very first tick, with
    // no history of its own -- exactly what a reconciler-leader failover leaves behind.
    StateStore store = new StateStore();
    long now = Instant.now().toEpochMilli();

    // 1. Confirmed by a fresh heartbeat -- must survive untouched.
    store.putAssignment(new InstanceAssignment("confirmed-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1000, 0, 1000, 0),
            List.of(
                InstanceObservation.builder("confirmed-service", 0, ORDERS, "ACTIVE", true, true)
                    .build())));

    // 2. Not confirmed, grace-period timer already elapsed before this reconciler even existed --
    // must be removed on this very first tick, not treated as freshly missing.
    store.putAssignment(new InstanceAssignment("overdue-service", 0, "node-b"));
    store.putNodeHeartbeat(
        new NodeHeartbeat("node-b", new ResourceUsageSnapshot(1000, 0, 1000, 0), List.of()));
    store.putReconcilerInstanceState(
        new ReconcilerInstanceState(
            "overdue-service",
            0,
            0,
            ReconcilerInstanceState.ABSENT,
            ReconcilerInstanceState.ABSENT,
            false,
            false,
            now - 60_000));

    // 3. Not confirmed, no persisted timer yet -- the grace period starts now, so it must not be
    // removed on this same first tick.
    store.putAssignment(new InstanceAssignment("fresh-missing-service", 0, "node-c"));
    store.putNodeHeartbeat(
        new NodeHeartbeat("node-c", new ResourceUsageSnapshot(1000, 0, 1000, 0), List.of()));

    new ReplicaCountReconciler(store, Duration.ofSeconds(15), Duration.ofSeconds(30))
        .reconcileOnce();

    assertTrue(hasAssignment(store, "confirmed-service", 0));
    assertFalse(hasAssignment(store, "overdue-service", 0));
    assertTrue(hasAssignment(store, "fresh-missing-service", 0));
  }

  /**
   * The core regression this class guards against (GIMLE-669/FUNC-29): before disruption-budget
   * throttling existed, a single dead node hosting several replicas of one anti-affinity-less
   * deployment (cross-node anti-affinity is opt-in, see {@code PlacementConstraints}) had every one
   * of them released in the very same tick, regardless of any {@code DisruptionBudget} the operator
   * configured. This fails against the pre-fix reconciler: it releases all three assignments on the
   * very first tick instead of throttling to {@code maxUnavailable: 1}.
   */
  @Test
  void disruption_budget_throttles_node_death_eviction_of_multiple_replicas_on_one_dead_node() {
    StateStore store = new StateStore();
    store.putDeployment(deploymentWithDisruption("orders-service", 3, new DisruptionBudget(1)));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putAssignment(new InstanceAssignment("orders-service", 1, "node-a"));
    store.putAssignment(new InstanceAssignment("orders-service", 2, "node-a"));
    // node-a never heartbeats at all -- every one of its assignments is unconfirmed from tick one.

    ReplicaCountReconciler reconciler = immediateReconciler(store, Duration.ofSeconds(15));

    reconciler.reconcileOnce();
    assertEquals(
        2,
        assignedIndices(store, "orders-service").size(),
        "maxUnavailable: 1 must release exactly one of the three stale assignments this tick, not"
            + " all three");

    // Converge across further ticks: each tick, simulate DeploymentReconciler re-placing whatever
    // index was just released onto a confirmed, healthy node (the only way an index actually stops
    // counting as unavailable), then reconcile again -- never observing more than one index missing
    // at once, and eventually clearing every replica off the dead node entirely.
    Set<Integer> allIndices = Set.of(0, 1, 2);
    Set<Integer> confirmedOnNodeB = new HashSet<>();
    for (int tick = 0; tick < 5; tick++) {
      long onDeadNode =
          store.listAssignmentsFor(Optional.empty(), "orders-service").stream()
              .filter(a -> a.nodeId().equals("node-a"))
              .count();
      if (onDeadNode == 0) {
        break;
      }
      long missing = allIndices.size() - assignedIndices(store, "orders-service").size();
      assertTrue(missing <= 1, "never more than maxUnavailable=1 replica missing at once");
      for (int index : allIndices) {
        if (!hasAssignment(store, "orders-service", index)) {
          confirmOnHealthyNode(store, "orders-service", "node-b", confirmedOnNodeB, index);
          confirmedOnNodeB.add(index);
        }
      }
      reconciler.reconcileOnce();
    }

    assertEquals(
        0,
        store.listAssignmentsFor(Optional.empty(), "orders-service").stream()
            .filter(a -> a.nodeId().equals("node-a"))
            .count(),
        "every replica must eventually migrate off the dead node -- throttling must still converge");
  }

  @Test
  void a_deferred_eviction_does_not_restart_its_own_grace_period() {
    StateStore store = new StateStore();
    store.putDeployment(deploymentWithDisruption("orders-service", 2, new DisruptionBudget(1)));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putAssignment(new InstanceAssignment("orders-service", 1, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat("node-a", new ResourceUsageSnapshot(1000, 0, 1000, 0), List.of()));

    TestClock clock = TestClock.startingNow();
    ReplicaCountReconciler reconciler =
        new ReplicaCountReconciler(store, NODE_DARK_TIMEOUT, GRACE_PERIOD, clock);
    reconciler.reconcileOnce(); // starts the grace period for both indices

    clock.advance(GRACE_PERIOD.plusSeconds(1));
    reconciler.reconcileOnce(); // both indices are now due; the budget releases only one
    assertEquals(1, assignedIndices(store, "orders-service").size());
    int deferred = assignedIndices(store, "orders-service").contains(0) ? 1 : 0;

    // Simulate DeploymentReconciler re-placing the released index and confirming it healthy,
    // freeing the budget -- crucially, with no further clock advance at all.
    int released = 1 - deferred;
    confirmOnHealthyNode(store, "orders-service", "node-b", Set.of(), released);

    reconciler.reconcileOnce();
    assertFalse(
        hasAssignment(store, "orders-service", deferred),
        "the deferred index's grace period had already elapsed before the budget deferred it --"
            + " freeing the budget must release it immediately rather than making it wait out a"
            + " fresh grace period");
  }

  @Test
  void a_single_affected_replica_is_not_delayed_by_the_default_disruption_budget() {
    // The common case this reconciler must leave unaffected: only one replica of a deployment is
    // ever on the node that died (e.g. anti-affinity is enabled, or the deployment simply has few
    // enough replicas), so even the default maxUnavailable: 1 budget must never introduce any
    // delay beyond the ordinary grace period.
    StateStore store = new StateStore();
    store.putDeployment(
        new DeploymentSpec("orders-service", ORDERS, "", 3, PlacementConstraints.NONE));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putAssignment(new InstanceAssignment("orders-service", 1, "node-b"));
    store.putAssignment(new InstanceAssignment("orders-service", 2, "node-b"));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-b",
            new ResourceUsageSnapshot(1000, 0, 1000, 0),
            List.of(
                InstanceObservation.builder("orders-service", 1, ORDERS, "ACTIVE", true, true)
                    .build(),
                InstanceObservation.builder("orders-service", 2, ORDERS, "ACTIVE", true, true)
                    .build())));
    // node-a alone goes dark, with only instance 0 assigned to it.

    immediateReconciler(store, Duration.ofSeconds(15)).reconcileOnce();

    assertFalse(
        hasAssignment(store, "orders-service", 0),
        "the single affected replica must still be released immediately");
    assertTrue(hasAssignment(store, "orders-service", 1));
    assertTrue(hasAssignment(store, "orders-service", 2));
  }

  @Test
  void a_disruption_budget_covering_every_replica_evicts_them_all_in_one_tick() {
    StateStore store = new StateStore();
    store.putDeployment(deploymentWithDisruption("orders-service", 3, new DisruptionBudget(3)));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putAssignment(new InstanceAssignment("orders-service", 1, "node-a"));
    store.putAssignment(new InstanceAssignment("orders-service", 2, "node-a"));

    immediateReconciler(store, Duration.ofSeconds(15)).reconcileOnce();

    assertEquals(
        0,
        assignedIndices(store, "orders-service").size(),
        "a budget covering every replica must not throttle eviction at all");
  }

  @Test
  void a_missing_deployment_spec_evicts_at_full_speed_with_no_throttling() {
    // No DeploymentSpec at all (e.g. already deleted by some other path) -- nothing to throttle
    // against, so this reconciler falls back to its original unthrottled behavior rather than
    // getting stuck refusing to ever release these assignments.
    StateStore store = new StateStore();
    store.putAssignment(new InstanceAssignment("orphaned-service", 0, "node-a"));
    store.putAssignment(new InstanceAssignment("orphaned-service", 1, "node-a"));
    store.putAssignment(new InstanceAssignment("orphaned-service", 2, "node-a"));

    immediateReconciler(store, Duration.ofSeconds(15)).reconcileOnce();

    assertEquals(
        0,
        assignedIndices(store, "orphaned-service").size(),
        "with no spec to read a budget from, every stale assignment must still be released");
  }
}
