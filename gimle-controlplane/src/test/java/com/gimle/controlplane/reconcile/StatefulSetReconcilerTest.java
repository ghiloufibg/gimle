package com.gimle.controlplane.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.time.TestClock;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.mimir.store.WorkloadHealthState;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/** Mirrors {@link DaemonSetReconcilerTest}'s own shape closely -- see that class for why. */
class StatefulSetReconcilerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static int counter = 0;

  private Path buildFixtureJar() {
    String uniqueName = "com.gimle.fixture.statefulsetreconciler" + (counter++);
    return TestModuleBuilder.module("module " + uniqueName + " {\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
        .build(tempDir, uniqueName + ".jar");
  }

  private StatefulSetSpec statefulSet(String name, Path jar, int replicas) {
    return new StatefulSetSpec(
        name,
        new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
        jar.toAbsolutePath().toString(),
        replicas,
        PlacementConstraints.NONE,
        Optional.empty(),
        Optional.empty());
  }

  private static void registerNode(StateStore store, String nodeId) {
    store.putNodeRegistration(
        new NodeRegistration(
            nodeId, new NodeCapabilities(Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2))));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            nodeId, new ResourceUsageSnapshot(500L * 1024 * 1024, 0, 4000, 0), List.of()));
  }

  /**
   * Reports {@code assignment} ready on its own node's heartbeat, matching {@code isReady} --
   * merges into whatever that node's heartbeat already reports rather than replacing it wholesale,
   * since more than one StatefulSet index can legitimately share a node (nothing here decrements
   * capacity the way a real agent's own instance count would); a naive full-replacement would
   * silently un-ready every other index already reported on the same node.
   */
  private static void reportReady(StateStore store, StatefulSetAssignment assignment) {
    List<InstanceObservation> existing =
        store
            .getNodeHeartbeat(assignment.nodeId())
            .map(ObservedHeartbeat::heartbeat)
            .map(NodeHeartbeat::instances)
            .orElse(List.of());
    List<InstanceObservation> merged = new ArrayList<>();
    for (InstanceObservation obs : existing) {
      if (!(obs.deploymentName().equals(assignment.statefulSetName())
          && obs.instanceIndex() == assignment.instanceIndex())) {
        merged.add(obs);
      }
    }
    merged.add(
        new InstanceObservation(
            assignment.statefulSetName(),
            assignment.instanceIndex(),
            assignment.moduleId(),
            "ACTIVE",
            true,
            true,
            0,
            0,
            0,
            0,
            0));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            assignment.nodeId(),
            new ResourceUsageSnapshot(500L * 1024 * 1024, 0, 4000, 0),
            merged));
  }

  private Optional<StatefulSetAssignment> indexOf(
      List<StatefulSetAssignment> assignments, int index) {
    return assignments.stream().filter(a -> a.instanceIndex() == index).findFirst();
  }

  /**
   * Reports {@code assignment}'s own instance as {@code FAILED} rather than merely not-yet-ready --
   * mirrors {@code reportReady}'s merge shape exactly.
   */
  private static void reportFailed(StateStore store, StatefulSetAssignment assignment) {
    List<InstanceObservation> existing =
        store
            .getNodeHeartbeat(assignment.nodeId())
            .map(ObservedHeartbeat::heartbeat)
            .map(NodeHeartbeat::instances)
            .orElse(List.of());
    List<InstanceObservation> merged = new ArrayList<>();
    for (InstanceObservation obs : existing) {
      if (!(obs.deploymentName().equals(assignment.statefulSetName())
          && obs.instanceIndex() == assignment.instanceIndex())) {
        merged.add(obs);
      }
    }
    merged.add(
        new InstanceObservation(
            assignment.statefulSetName(),
            assignment.instanceIndex(),
            assignment.moduleId(),
            "FAILED",
            true,
            false,
            0,
            0,
            0,
            0,
            0));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            assignment.nodeId(),
            new ResourceUsageSnapshot(500L * 1024 * 1024, 0, 4000, 0),
            merged));
  }

  @Test
  void places_only_index_zero_when_nothing_is_ready_yet() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putStatefulSetSpec(statefulSet("orders", jar, 3));
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    registerNode(store, "node-c");

    new StatefulSetReconciler(store, scheduler).reconcileOnce();

    List<StatefulSetAssignment> assignments =
        store.listStatefulSetAssignmentsFor(Optional.empty(), "orders");
    assertEquals(1, assignments.size());
    assertTrue(indexOf(assignments, 0).isPresent());
  }

  @Test
  void does_not_place_index_one_until_index_zero_reports_ready() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putStatefulSetSpec(statefulSet("orders", jar, 3));
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    StatefulSetReconciler reconciler = new StatefulSetReconciler(store, scheduler);
    reconciler.reconcileOnce();

    reconciler.reconcileOnce(); // index 0 still not ready -- must not place index 1

    List<StatefulSetAssignment> assignments =
        store.listStatefulSetAssignmentsFor(Optional.empty(), "orders");
    assertEquals(1, assignments.size());
  }

  @Test
  void places_index_one_once_index_zero_becomes_ready() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putStatefulSetSpec(statefulSet("orders", jar, 2));
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    StatefulSetReconciler reconciler = new StatefulSetReconciler(store, scheduler);
    reconciler.reconcileOnce();
    StatefulSetAssignment index0 =
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 0).orElseThrow();

    reportReady(store, index0);
    reconciler.reconcileOnce();

    List<StatefulSetAssignment> assignments =
        store.listStatefulSetAssignmentsFor(Optional.empty(), "orders");
    assertEquals(2, assignments.size());
    assertTrue(indexOf(assignments, 1).isPresent());
  }

  @Test
  void an_index_stays_sticky_bound_to_its_node_across_a_rolling_update() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jarV1 = buildFixtureJar();
    registerNode(store, "node-a");
    StatefulSetSpec v1 = statefulSet("orders", jarV1, 1);
    store.putStatefulSetSpec(v1);
    StatefulSetReconciler reconciler = new StatefulSetReconciler(store, scheduler);
    reconciler.reconcileOnce();
    StatefulSetAssignment placed =
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 0).orElseThrow();
    String originalNode = placed.nodeId();
    reportReady(store, placed);

    Path jarV2 = buildFixtureJar();
    StatefulSetSpec v2 = statefulSet("orders", jarV2, 1);
    store.putStatefulSetSpec(v2);
    reconciler.reconcileOnce(); // removes the mismatched index 0, marks it rolling
    reconciler.reconcileOnce(); // re-places it -- must land back on the same node

    StatefulSetAssignment rolled =
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 0).orElseThrow();
    assertEquals(originalNode, rolled.nodeId());
    assertEquals(v2.moduleId(), rolled.moduleId());
  }

  @Test
  void scale_down_removes_the_highest_index_first_one_at_a_time() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    registerNode(store, "node-c");
    StatefulSetSpec spec = statefulSet("orders", jar, 3);
    store.putStatefulSetSpec(spec);
    StatefulSetReconciler reconciler = new StatefulSetReconciler(store, scheduler);
    // Drive all three indices up, reporting each ready before the next is attempted.
    for (int i = 0; i < 3; i++) {
      reconciler.reconcileOnce();
      List<StatefulSetAssignment> current =
          store.listStatefulSetAssignmentsFor(Optional.empty(), "orders");
      current.forEach(a -> reportReady(store, a));
    }
    assertEquals(3, store.listStatefulSetAssignmentsFor(Optional.empty(), "orders").size());

    store.putStatefulSetSpec(statefulSet("orders", jar, 1));
    reconciler.reconcileOnce();

    List<StatefulSetAssignment> afterOneTick =
        store.listStatefulSetAssignmentsFor(Optional.empty(), "orders");
    assertEquals(2, afterOneTick.size(), "only the highest index is removed per tick");
    assertTrue(indexOf(afterOneTick, 2).isEmpty());

    reconciler.reconcileOnce();
    List<StatefulSetAssignment> afterTwoTicks =
        store.listStatefulSetAssignmentsFor(Optional.empty(), "orders");
    assertEquals(1, afterTwoTicks.size());
    assertTrue(indexOf(afterTwoTicks, 0).isPresent());
  }

  @Test
  void scaling_back_up_after_scale_down_reuses_the_same_sticky_node() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    StatefulSetSpec spec = statefulSet("orders", jar, 2);
    store.putStatefulSetSpec(spec);
    StatefulSetReconciler reconciler = new StatefulSetReconciler(store, scheduler);
    reconciler.reconcileOnce();
    reportReady(
        store,
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 0).orElseThrow());
    reconciler.reconcileOnce();
    StatefulSetAssignment index1 =
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 1).orElseThrow();
    String index1Node = index1.nodeId();
    reportReady(store, index1);

    // Scale down to 1 (drops index 1), then immediately back up to 2.
    store.putStatefulSetSpec(statefulSet("orders", jar, 1));
    reconciler.reconcileOnce();
    assertTrue(
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 1).isEmpty());

    store.putStatefulSetSpec(statefulSet("orders", jar, 2));
    reconciler.reconcileOnce();

    StatefulSetAssignment respawned =
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 1).orElseThrow();
    assertEquals(
        index1Node,
        respawned.nodeId(),
        "the sticky binding must survive a scale-down/scale-up round trip");
  }

  @Test
  void deleting_a_statefulset_removes_its_orphaned_assignment_and_sticky_binding() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a");
    store.putStatefulSetSpec(statefulSet("orders", jar, 1));
    new StatefulSetReconciler(store, scheduler).reconcileOnce();
    assertEquals(1, store.listStatefulSetAssignmentsFor(Optional.empty(), "orders").size());
    assertTrue(store.getStatefulSetIndexNode(Optional.empty(), "orders", 0).isPresent());

    store.removeStatefulSetSpec(Optional.empty(), "orders");
    new StatefulSetReconciler(store, scheduler).reconcileOnce();

    assertTrue(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders").isEmpty());
    assertTrue(store.getStatefulSetIndexNode(Optional.empty(), "orders", 0).isEmpty());
  }

  @Test
  void refuses_to_place_when_the_jar_on_disk_no_longer_matches_the_recorded_hash() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a");
    StatefulSetSpec mismatched =
        new StatefulSetSpec(
            "orders",
            new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
            jar.toAbsolutePath().toString(),
            1,
            PlacementConstraints.NONE,
            Optional.empty(),
            Optional.of("f".repeat(64)));
    store.putStatefulSetSpec(mismatched);

    new StatefulSetReconciler(store, scheduler).reconcileOnce();

    assertTrue(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders").isEmpty());
  }

  @Test
  void leaves_the_statefulset_unplaced_without_throwing_when_no_node_has_capacity() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putStatefulSetSpec(statefulSet("orders", jar, 1));
    // no nodes registered at all

    StatefulSetReconciler reconciler = new StatefulSetReconciler(store, scheduler);
    reconciler.reconcileOnce();
    reconciler.reconcileOnce(); // idempotent: calling again doesn't error or duplicate

    assertTrue(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders").isEmpty());
  }

  @Test
  void a_sticky_index_stuck_on_a_now_cordoned_node_does_not_relocate_elsewhere() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    store.putStatefulSetSpec(statefulSet("orders", jar, 1));
    StatefulSetReconciler reconciler = new StatefulSetReconciler(store, scheduler);
    reconciler.reconcileOnce();
    StatefulSetAssignment placed =
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 0).orElseThrow();
    reportReady(store, placed);

    // Simulate the assignment disappearing (e.g. an operator-driven repair) while the node itself
    // is cordoned -- the sticky binding must still force placement back onto that exact node,
    // never onto node-b, even though node-b has free capacity.
    store.removeStatefulSetAssignment(Optional.empty(), "orders", 0);
    store.putNodeCordon(placed.nodeId(), true);
    reconciler.reconcileOnce();

    assertTrue(
        store.listStatefulSetAssignmentsFor(Optional.empty(), "orders").isEmpty(),
        "a sticky index must stay unplaced rather than relocate to a different node");
  }

  @Test
  void an_arbitrary_starting_snapshot_converges_by_dropping_excess_indices() {
    // Simulates a stale snapshot with an index beyond the current replica count already on
    // record. A from-scratch reconcile must remove it without any history beyond this snapshot.
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a");
    StatefulSetSpec spec = statefulSet("orders", jar, 1);
    store.putStatefulSetSpec(spec);
    store.putStatefulSetAssignment(
        new StatefulSetAssignment("orders", 5, "node-a", spec.moduleId(), spec.artifactPath()));

    new StatefulSetReconciler(store, scheduler).reconcileOnce();

    assertTrue(
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 5).isEmpty());
  }

  @Test
  void a_replica_on_a_dark_but_not_yet_timed_out_node_is_not_relocated(TestClock clock) {
    // ADD-5: isReady alone never checked the assigned node's own heartbeat freshness, only whether
    // the *last* heartbeat that node ever sent happened to report this index ready -- which stays
    // true forever once a node goes dark. Mirrors DaemonSetReconcilerTest's identically-named
    // test: within the grace window a merely-dark node's assignment survives untouched (sticky
    // placement -- see class javadoc).
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a");
    store.putStatefulSetSpec(statefulSet("orders", jar, 1));
    Duration nodeDarkTimeout = Duration.ofSeconds(15);
    Duration placementGracePeriod = Duration.ofSeconds(30);
    StatefulSetReconciler reconciler =
        new StatefulSetReconciler(
            store,
            scheduler,
            mutation -> mutation.applyTo(store),
            nodeDarkTimeout,
            placementGracePeriod,
            clock);
    reconciler.reconcileOnce();
    StatefulSetAssignment placed =
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 0).orElseThrow();
    reportReady(store, placed);
    reconciler.reconcileOnce(); // clears any in-flight rolling-update bookkeeping first

    // node-a stops heartbeating: past nodeDarkTimeout, but still well within the combined grace
    // window -- the assignment must survive untouched.
    clock.advance(nodeDarkTimeout.plus(Duration.ofSeconds(1)));
    reconciler.reconcileOnce();

    List<StatefulSetAssignment> stillWithinGrace =
        store.listStatefulSetAssignmentsFor(Optional.empty(), "orders");
    assertEquals(
        1,
        stillWithinGrace.size(),
        "a merely-dark node must keep its assignment during the placement grace period");
    assertEquals("node-a", stillWithinGrace.get(0).nodeId());
  }

  @Test
  void a_replica_on_a_node_dark_past_the_grace_period_is_released_and_lands_back_on_the_same_node(
      TestClock clock) {
    // ADD-5's actual failure mode: session-store's own StatefulSet instance stayed reported
    // ACTIVE/alive/ready for a full 4.5-minute observation window after its node was killed
    // outright, with zero eviction attempt. Once genuinely gone (past the combined grace window),
    // the assignment is released -- but the sticky binding survives, so once the node's heartbeat
    // is fresh again the very same index lands back on the very same node, never a different one.
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a");
    store.putStatefulSetSpec(statefulSet("orders", jar, 1));
    Duration nodeDarkTimeout = Duration.ofSeconds(15);
    Duration placementGracePeriod = Duration.ofSeconds(30);
    StatefulSetReconciler reconciler =
        new StatefulSetReconciler(
            store,
            scheduler,
            mutation -> mutation.applyTo(store),
            nodeDarkTimeout,
            placementGracePeriod,
            clock);
    reconciler.reconcileOnce();
    StatefulSetAssignment placed =
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 0).orElseThrow();
    reportReady(store, placed);
    reconciler.reconcileOnce();

    clock.advance(nodeDarkTimeout.plus(placementGracePeriod).plus(Duration.ofSeconds(1)));
    reconciler.reconcileOnce();

    assertTrue(
        store.listStatefulSetAssignmentsFor(Optional.empty(), "orders").isEmpty(),
        "a genuinely-gone node's assignment must be released");
    assertTrue(
        store.getStatefulSetIndexNode(Optional.empty(), "orders", 0).isPresent(),
        "the sticky node binding must survive the release");

    // node-a comes back (a real recovery, not just a fresh heartbeat on a different machine).
    registerNode(store, "node-a");
    reconciler.reconcileOnce();

    StatefulSetAssignment replaced =
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 0).orElseThrow();
    assertEquals("node-a", replaced.nodeId(), "the sticky index must land back on the same node");
  }

  @Test
  void a_crash_looping_index_is_released_for_reschedule_once_its_backoff_elapses(TestClock clock) {
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a");
    store.putStatefulSetSpec(statefulSet("orders", jar, 1));
    StatefulSetReconciler reconciler =
        new StatefulSetReconciler(
            store,
            scheduler,
            mutation -> mutation.applyTo(store),
            StatefulSetReconciler.DEFAULT_NODE_DARK_TIMEOUT,
            StatefulSetReconciler.DEFAULT_NODE_DARK_TIMEOUT,
            clock);
    reconciler.reconcileOnce();
    StatefulSetAssignment placed =
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 0).orElseThrow();
    reportFailed(store, placed);

    reconciler.reconcileOnce(); // first failure observed: starts the backoff, doesn't act yet
    assertTrue(
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 0).isPresent());

    // One nanosecond short of WorkloadCrashLoopBackoff's default 2-second initial delay.
    clock.advance(Duration.ofSeconds(2).minusNanos(1));
    reconciler.reconcileOnce();
    assertTrue(
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 0).isPresent(),
        "the backoff has not elapsed yet, so nothing should have been rescheduled");

    clock.advance(Duration.ofNanos(1));
    reconciler.reconcileOnce();
    assertTrue(
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 0).isEmpty(),
        "the crash-looping index should have been released once its backoff elapsed");
    assertTrue(
        store.getStatefulSetIndexNode(Optional.empty(), "orders", 0).isPresent(),
        "the sticky binding must survive a crash-loop release, same as any other release");

    // The very next tick re-places it -- on the same sticky node.
    reconciler.reconcileOnce();
    StatefulSetAssignment replaced =
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 0).orElseThrow();
    assertEquals("node-a", replaced.nodeId());
  }

  @Test
  void a_crash_looping_index_that_exhausts_its_budget_is_never_skipped_past(TestClock clock) {
    // OrderedReady must never place index 1 while index 0 sits permanently failed -- the same
    // ordering guarantee a merely-not-ready index 0 already gets. A generous, non-default
    // node-dark timeout keeps every attempt's backoff (up to a capped 60s) from ever being
    // mistaken for the node itself going dark -- this test is about the restart budget, not
    // node darkness.
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    store.putStatefulSetSpec(statefulSet("orders", jar, 2));
    StatefulSetReconciler reconciler =
        new StatefulSetReconciler(
            store,
            scheduler,
            mutation -> mutation.applyTo(store),
            Duration.ofMinutes(10),
            Duration.ofMinutes(10),
            clock);

    // Matches WorkloadCrashLoopBackoff's own default schedule (initialDelay=2s, x2, capped at 1m).
    Duration initialDelay = Duration.ofSeconds(2);
    int maxAttemptsPerWindow = 5;

    // Drive index 0 through repeated crash-loop-and-reschedule cycles, one per restart-budget
    // attempt, until the budget (5 attempts per window) is exhausted on the 6th failure.
    reconciler.reconcileOnce(); // places index 0
    for (int attempt = 1; attempt <= maxAttemptsPerWindow + 1; attempt++) {
      StatefulSetAssignment index0 =
          indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 0).orElseThrow();
      reportFailed(store, index0);
      reconciler.reconcileOnce(); // records the failure, starts this attempt's backoff
      Duration delay =
          initialDelay.multipliedBy(
              (long) Math.pow(2.0, Math.min(attempt, maxAttemptsPerWindow) - 1));
      clock.advance(delay.compareTo(Duration.ofMinutes(1)) > 0 ? Duration.ofMinutes(1) : delay);
      reconciler.reconcileOnce(); // releases it (attempts 1-5) or gives up (attempt 6)
      if (attempt <= maxAttemptsPerWindow) {
        reconciler.reconcileOnce(); // re-places it on the same sticky node for the next cycle
      }
    }

    // Gave up: the stale, still-FAILED assignment is left exactly where it is -- never removed,
    // never re-placed -- the same "leaves it FAILED forever" posture HealthReconciler takes.
    assertTrue(
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 0).isPresent(),
        "a permanently-failed index keeps its stale assignment in place");
    assertTrue(
        store
            .getWorkloadHealthState(Optional.empty(), "StatefulSet", "orders", "0")
            .orElseThrow()
            .permanentlyFailed());

    // index 1 must never have been placed -- OrderedReady never skips past a stuck index 0 -- and
    // one more tick changes nothing, proving the give-up is permanent, not merely this tick's.
    reconciler.reconcileOnce();
    assertTrue(
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 1).isEmpty());
  }

  @Test
  void converges_correctly_from_a_persisted_permanently_failed_workload_health_state(
      TestClock clock) {
    // A brand-new reconciler must respect a permanently-failed slot recorded under a previous
    // reconciler-leader term, with no history of its own -- the StatefulSet-keyed counterpart of
    // HealthReconcilerTest's own "converges from an arbitrary mix" case.
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a");
    store.putStatefulSetSpec(statefulSet("orders", jar, 1));
    store.putStatefulSetAssignment(
        new StatefulSetAssignment(
            "orders",
            0,
            "node-a",
            new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
            jar.toAbsolutePath().toString()));
    store.putWorkloadHealthState(
        new WorkloadHealthState(
            "StatefulSet", "orders", "0", 5, 0L, 0L, false, true, Optional.empty()));

    new StatefulSetReconciler(store, scheduler).reconcileOnce();

    assertTrue(
        indexOf(store.listStatefulSetAssignmentsFor(Optional.empty(), "orders"), 0).isPresent(),
        "a permanently-failed index's stale assignment is left in place, not torn down");
  }
}
