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
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.WorkloadHealthState;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/** Mirrors {@link JobReconcilerTest}'s own shape closely -- see that class for why. */
class DaemonSetReconcilerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static int counter = 0;

  private Path buildFixtureJar() {
    String uniqueName = "com.gimle.fixture.daemonsetreconciler" + (counter++);
    return TestModuleBuilder.module("module " + uniqueName + " {\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
        .build(tempDir, uniqueName + ".jar");
  }

  private DaemonSetSpec daemonSet(String name, Path jar, PlacementConstraints placement) {
    return new DaemonSetSpec(
        name,
        new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
        jar.toAbsolutePath().toString(),
        placement,
        Optional.empty(),
        Optional.empty());
  }

  private DaemonSetSpec daemonSetTolerantOfAllTaints(String name, Path jar) {
    return new DaemonSetSpec(
        name,
        new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
        jar.toAbsolutePath().toString(),
        PlacementConstraints.NONE,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        true);
  }

  private static void registerNode(StateStore store, String nodeId, Set<String> labels) {
    store.putNodeRegistration(
        new NodeRegistration(
            nodeId,
            new NodeCapabilities(Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2), labels)));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            nodeId, new ResourceUsageSnapshot(500L * 1024 * 1024, 0, 4000, 0), List.of()));
  }

  private static void registerNode(StateStore store, String nodeId) {
    registerNode(store, nodeId, Set.of());
  }

  /** Reports {@code assignment} ready on its own node's heartbeat, matching {@code isReady}. */
  private static void reportReady(StateStore store, DaemonSetAssignment assignment) {
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            assignment.nodeId(),
            new ResourceUsageSnapshot(500L * 1024 * 1024, 0, 4000, 0),
            List.of(
                new InstanceObservation(
                    assignment.daemonSetName(),
                    0,
                    assignment.moduleId(),
                    "ACTIVE",
                    true,
                    true,
                    0,
                    0,
                    0,
                    0,
                    0))));
  }

  /** Reports {@code assignment}'s own instance as {@code FAILED} rather than merely not ready. */
  private static void reportFailed(StateStore store, DaemonSetAssignment assignment) {
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            assignment.nodeId(),
            new ResourceUsageSnapshot(500L * 1024 * 1024, 0, 4000, 0),
            List.of(
                new InstanceObservation(
                    assignment.daemonSetName(),
                    0,
                    assignment.moduleId(),
                    "FAILED",
                    true,
                    false,
                    0,
                    0,
                    0,
                    0,
                    0))));
  }

  @Test
  void places_an_assignment_on_every_registered_node() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(daemonSet("node-exporter", jar, PlacementConstraints.NONE));
    registerNode(store, "node-a");
    registerNode(store, "node-b");

    new DaemonSetReconciler(store, scheduler).reconcileOnce();

    List<DaemonSetAssignment> assignments =
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter");
    assertEquals(2, assignments.size());
    assertEquals(
        Set.of("node-a", "node-b"),
        assignments.stream().map(DaemonSetAssignment::nodeId).collect(Collectors.toSet()));
  }

  @Test
  void required_labels_restrict_placement_to_matching_nodes_only() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(
        daemonSet("gpu-agent", jar, new PlacementConstraints(Optional.of(Set.of("gpu")), false)));
    registerNode(store, "node-gpu", Set.of("gpu"));
    registerNode(store, "node-plain", Set.of());

    new DaemonSetReconciler(store, scheduler).reconcileOnce();

    List<DaemonSetAssignment> assignments =
        store.listDaemonSetAssignmentsFor(Optional.empty(), "gpu-agent");
    assertEquals(1, assignments.size());
    assertEquals("node-gpu", assignments.get(0).nodeId());
  }

  @Test
  void an_untenanted_daemonset_is_excluded_from_a_tainted_node_by_default() {
    // The strict, pre-existing behavior: without tolerateAllTaints, a DaemonSet is filtered by
    // node taints exactly like a Deployment or StatefulSet would be.
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(daemonSet("node-exporter", jar, PlacementConstraints.NONE));
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    store.putNodeTaint("node-b", "some-other-tenant", true);

    new DaemonSetReconciler(store, scheduler).reconcileOnce();

    List<DaemonSetAssignment> assignments =
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter");
    assertEquals(1, assignments.size());
    assertEquals("node-a", assignments.get(0).nodeId());
  }

  @Test
  void a_daemonset_with_tolerate_all_taints_covers_a_tainted_node_too() {
    // FUNC-55: a genuinely cluster-wide DaemonSet (e.g. a log shipper) that opts into
    // tolerateAllTaints must reach every node, including one reserved for a different tenant.
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(daemonSetTolerantOfAllTaints("cluster-log-shipper", jar));
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    store.putNodeTaint("node-b", "some-other-tenant", true);

    new DaemonSetReconciler(store, scheduler).reconcileOnce();

    List<DaemonSetAssignment> assignments =
        store.listDaemonSetAssignmentsFor(Optional.empty(), "cluster-log-shipper");
    assertEquals(2, assignments.size());
    assertEquals(
        Set.of("node-a", "node-b"),
        assignments.stream().map(DaemonSetAssignment::nodeId).collect(Collectors.toSet()));
  }

  @Test
  void leaves_the_daemonset_unassigned_without_throwing_when_no_node_is_registered() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(daemonSet("node-exporter", jar, PlacementConstraints.NONE));

    DaemonSetReconciler reconciler = new DaemonSetReconciler(store, scheduler);
    reconciler.reconcileOnce();
    reconciler.reconcileOnce(); // idempotent

    assertTrue(store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").isEmpty());
  }

  @Test
  void cordoning_a_node_removes_its_assignment_on_the_next_tick() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(daemonSet("node-exporter", jar, PlacementConstraints.NONE));
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    DaemonSetReconciler reconciler = new DaemonSetReconciler(store, scheduler);
    reconciler.reconcileOnce();
    assertEquals(2, store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").size());

    store.putNodeCordon("node-a", true);
    reconciler.reconcileOnce();

    List<DaemonSetAssignment> assignments =
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter");
    assertEquals(1, assignments.size());
    assertEquals("node-b", assignments.get(0).nodeId());
  }

  @Test
  void deleting_a_daemonset_removes_its_orphaned_assignments() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(daemonSet("node-exporter", jar, PlacementConstraints.NONE));
    registerNode(store, "node-a");
    new DaemonSetReconciler(store, scheduler).reconcileOnce();
    assertEquals(1, store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").size());

    store.removeDaemonSetSpec(Optional.empty(), "node-exporter");
    new DaemonSetReconciler(store, scheduler).reconcileOnce();

    assertTrue(store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").isEmpty());
    assertTrue(store.listDaemonSetAssignments().isEmpty());
  }

  @Test
  void refuses_to_place_when_the_jar_on_disk_no_longer_matches_the_recorded_hash() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a");
    DaemonSetSpec mismatched =
        new DaemonSetSpec(
            "node-exporter",
            new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
            jar.toAbsolutePath().toString(),
            PlacementConstraints.NONE,
            Optional.empty(),
            Optional.of("f".repeat(64)));
    store.putDaemonSetSpec(mismatched);

    new DaemonSetReconciler(store, scheduler).reconcileOnce();

    assertTrue(store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").isEmpty());
  }

  @Test
  void
      an_arbitrary_starting_snapshot_converges_by_dropping_ineligible_and_filling_eligible_nodes() {
    // Simulates a stale snapshot: an assignment lingering on a now-cordoned node, and an eligible
    // node with no assignment at all yet. A from-scratch reconcile must fix both without any
    // history beyond this snapshot -- the level-triggered convergence property every reconciler
    // in this codebase is held to.
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    DaemonSetSpec spec = daemonSet("node-exporter", jar, PlacementConstraints.NONE);
    store.putDaemonSetSpec(spec);
    registerNode(store, "node-stale");
    registerNode(store, "node-fresh");
    store.putNodeCordon("node-stale", true);
    store.putDaemonSetAssignment(
        new DaemonSetAssignment(
            "node-exporter", "node-stale", spec.moduleId(), spec.artifactPath()));

    new DaemonSetReconciler(store, scheduler).reconcileOnce();

    List<DaemonSetAssignment> assignments =
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter");
    assertEquals(1, assignments.size());
    assertEquals("node-fresh", assignments.get(0).nodeId());
  }

  @Test
  void rolling_update_replaces_one_node_at_a_time_and_waits_for_readiness() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jarV1 = buildFixtureJar();
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    DaemonSetSpec v1 = daemonSet("node-exporter", jarV1, PlacementConstraints.NONE);
    store.putDaemonSetSpec(v1);
    DaemonSetReconciler reconciler = new DaemonSetReconciler(store, scheduler);
    reconciler.reconcileOnce();
    for (DaemonSetAssignment a :
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter")) {
      reportReady(store, a);
    }

    Path jarV2 = buildFixtureJar();
    DaemonSetSpec v2 = daemonSet("node-exporter", jarV2, PlacementConstraints.NONE);
    store.putDaemonSetSpec(v2);
    reconciler.reconcileOnce();

    // Exactly one node is mid-rollout at a time: still 2 total assignments (the old one on the
    // not-yet-rolled node, plus the freshly re-placed one on the rolled node), never both nodes
    // torn down simultaneously.
    List<DaemonSetAssignment> midRollout =
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter");
    assertEquals(2, midRollout.size());
    long onNewVersion = midRollout.stream().filter(a -> a.moduleId().equals(v2.moduleId())).count();
    assertEquals(1, onNewVersion, "exactly one node should have rolled forward so far");

    // The rolled node hasn't reported ready yet -- another tick must not start a second rollout.
    reconciler.reconcileOnce();
    long stillOnNewVersion =
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").stream()
            .filter(a -> a.moduleId().equals(v2.moduleId()))
            .count();
    assertEquals(
        1, stillOnNewVersion, "a second node must not start rolling before the first is ready");

    for (DaemonSetAssignment a :
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter")) {
      reportReady(store, a);
    }
    reconciler.reconcileOnce();
    reconciler.reconcileOnce();

    List<DaemonSetAssignment> done =
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter");
    assertEquals(2, done.size());
    assertTrue(done.stream().allMatch(a -> a.moduleId().equals(v2.moduleId())));
  }

  @Test
  void a_replica_on_a_dark_but_not_yet_timed_out_node_is_not_relocated(TestClock clock) {
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    DaemonSetSpec spec = daemonSet("node-exporter", jar, PlacementConstraints.NONE);
    store.putDaemonSetSpec(spec);
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    Duration nodeDarkTimeout = Duration.ofSeconds(15);
    Duration placementGracePeriod = Duration.ofSeconds(30);
    DaemonSetReconciler reconciler =
        new DaemonSetReconciler(
            store,
            scheduler,
            mutation -> mutation.applyTo(store),
            nodeDarkTimeout,
            placementGracePeriod,
            clock);
    reconciler.reconcileOnce();
    assertEquals(2, store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").size());

    // node-a stops heartbeating (a partition, not a real failure): past nodeDarkTimeout, so it's
    // no longer a placement candidate, but still well within the combined grace window.
    clock.advance(nodeDarkTimeout.plus(Duration.ofSeconds(1)));
    reconciler.reconcileOnce();

    List<DaemonSetAssignment> stillWithinGrace =
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter");
    assertEquals(
        2,
        stillWithinGrace.size(),
        "a merely-dark node must keep its assignment during the placement grace period");
    assertTrue(stillWithinGrace.stream().anyMatch(a -> a.nodeId().equals("node-a")));

    // Now past nodeDarkTimeout + placementGracePeriod: node-a counts as genuinely gone. node-b's
    // own heartbeat is refreshed right before this tick so it stays eligible throughout -- the
    // point of this assertion is specifically that node-a's assignment is the one reclaimed, not
    // that every node happens to go dark at once.
    clock.advance(placementGracePeriod);
    registerNode(store, "node-b");
    reconciler.reconcileOnce();

    List<DaemonSetAssignment> afterGracePeriod =
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter");
    assertEquals(1, afterGracePeriod.size());
    assertEquals("node-b", afterGracePeriod.get(0).nodeId());
  }

  @Test
  void cordoning_a_dark_node_still_removes_its_assignment_immediately(TestClock clock) {
    // A cordon is a deliberate operator action, not an ambiguous "is the node still there"
    // signal -- it must not wait out the darkness grace period even when the node also happens to
    // be unreachable.
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(daemonSet("node-exporter", jar, PlacementConstraints.NONE));
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    Duration nodeDarkTimeout = Duration.ofSeconds(15);
    Duration placementGracePeriod = Duration.ofSeconds(30);
    DaemonSetReconciler reconciler =
        new DaemonSetReconciler(
            store,
            scheduler,
            mutation -> mutation.applyTo(store),
            nodeDarkTimeout,
            placementGracePeriod,
            clock);
    reconciler.reconcileOnce();
    assertEquals(2, store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").size());

    store.putNodeCordon("node-a", true);
    reconciler.reconcileOnce();

    List<DaemonSetAssignment> assignments =
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter");
    assertEquals(1, assignments.size());
    assertEquals("node-b", assignments.get(0).nodeId());
  }

  @Test
  void a_crash_looping_node_is_released_for_reschedule_once_its_backoff_elapses(TestClock clock) {
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a");
    store.putDaemonSetSpec(daemonSet("node-exporter", jar, PlacementConstraints.NONE));
    DaemonSetReconciler reconciler =
        new DaemonSetReconciler(
            store,
            scheduler,
            mutation -> mutation.applyTo(store),
            DaemonSetReconciler.DEFAULT_NODE_DARK_TIMEOUT,
            DaemonSetReconciler.DEFAULT_NODE_DARK_TIMEOUT,
            clock);
    reconciler.reconcileOnce();
    DaemonSetAssignment placed =
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").get(0);
    reportFailed(store, placed);

    reconciler.reconcileOnce(); // first failure observed: starts the backoff, doesn't act yet
    assertEquals(1, store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").size());

    // One nanosecond short of WorkloadCrashLoopBackoff's default 2-second initial delay.
    clock.advance(Duration.ofSeconds(2).minusNanos(1));
    reconciler.reconcileOnce();
    assertEquals(
        1,
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").size(),
        "the backoff has not elapsed yet, so nothing should have been rescheduled");

    clock.advance(Duration.ofNanos(1));
    reconciler.reconcileOnce();

    // The stale assignment is released, and the same tick's placement pass re-adds a fresh one
    // to the very same (still-eligible) node -- there is nowhere else for it to go.
    List<DaemonSetAssignment> after =
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter");
    assertEquals(1, after.size());
    assertEquals("node-a", after.get(0).nodeId());
  }

  @Test
  void a_crash_looping_node_that_exhausts_its_budget_is_left_permanently_unassigned(
      TestClock clock) {
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a");
    store.putDaemonSetSpec(daemonSet("node-exporter", jar, PlacementConstraints.NONE));
    // A generous, non-default node-dark timeout keeps every attempt's backoff (up to a capped
    // 60s) from ever being mistaken for the node itself going dark -- this test is about the
    // restart budget, not node darkness.
    DaemonSetReconciler reconciler =
        new DaemonSetReconciler(
            store,
            scheduler,
            mutation -> mutation.applyTo(store),
            Duration.ofMinutes(10),
            Duration.ofMinutes(10),
            clock);

    Duration initialDelay = Duration.ofSeconds(2);
    int maxAttemptsPerWindow = 5;

    reconciler.reconcileOnce(); // places the assignment
    for (int attempt = 1; attempt <= maxAttemptsPerWindow + 1; attempt++) {
      DaemonSetAssignment current =
          store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").stream()
              .filter(a -> a.nodeId().equals("node-a"))
              .findFirst()
              .orElseThrow();
      reportFailed(store, current);
      reconciler.reconcileOnce(); // records the failure, starts this attempt's backoff
      Duration delay =
          initialDelay.multipliedBy(
              (long) Math.pow(2.0, Math.min(attempt, maxAttemptsPerWindow) - 1));
      clock.advance(delay.compareTo(Duration.ofMinutes(1)) > 0 ? Duration.ofMinutes(1) : delay);
      reconciler.reconcileOnce(); // releases and re-places it (attempts 1-5), or gives up (6th)
    }

    // Gave up: the stale, still-FAILED assignment is left exactly where it is -- never removed --
    // the same "leaves it FAILED forever" posture HealthReconciler takes. One more tick changes
    // nothing, proving the give-up is permanent, not merely this tick's.
    reconciler.reconcileOnce();
    List<DaemonSetAssignment> afterGivingUp =
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter");
    assertEquals(
        1, afterGivingUp.size(), "a permanently-failed node keeps its stale assignment in place");
    assertEquals("node-a", afterGivingUp.get(0).nodeId());
    assertTrue(
        store
            .getWorkloadHealthState(Optional.empty(), "DaemonSet", "node-exporter", "node-a")
            .orElseThrow()
            .permanentlyFailed());
  }

  @Test
  void converges_correctly_from_a_persisted_permanently_failed_workload_health_state(
      TestClock clock) {
    // A brand-new reconciler must respect a permanently-failed node recorded under a previous
    // reconciler-leader term, with no history of its own.
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a");
    DaemonSetSpec spec = daemonSet("node-exporter", jar, PlacementConstraints.NONE);
    store.putDaemonSetSpec(spec);
    store.putDaemonSetAssignment(
        new DaemonSetAssignment("node-exporter", "node-a", spec.moduleId(), spec.artifactPath()));
    store.putWorkloadHealthState(
        new WorkloadHealthState(
            "DaemonSet",
            "node-exporter",
            "node-a",
            5,
            0L,
            0L,
            false,
            true,
            WorkloadHealthState.ABSENT,
            Optional.empty()));

    // Threads the same TestClock through the reconciler as the store -- a reconciler defaulted to
    // Clock.systemUTC() would see the store's TestClock-stamped heartbeat as impossibly stale and
    // evict it as a dark node, unrelated to the permanently-failed check this test is about.
    new DaemonSetReconciler(
            store,
            scheduler,
            mutation -> mutation.applyTo(store),
            DaemonSetReconciler.DEFAULT_NODE_DARK_TIMEOUT,
            DaemonSetReconciler.DEFAULT_NODE_DARK_TIMEOUT,
            clock)
        .reconcileOnce();

    assertEquals(
        1,
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").size(),
        "a permanently-failed node's stale assignment is left in place, not torn down");
  }
}
