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
import java.util.concurrent.atomic.AtomicBoolean;
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
                InstanceObservation.builder(
                        assignment.daemonSetName(), 0, assignment.moduleId(), "ACTIVE", true, true)
                    .build())));
  }

  /** Reports {@code assignment}'s own instance as {@code FAILED} rather than merely not ready. */
  private static void reportFailed(StateStore store, DaemonSetAssignment assignment) {
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            assignment.nodeId(),
            new ResourceUsageSnapshot(500L * 1024 * 1024, 0, 4000, 0),
            List.of(
                InstanceObservation.builder(
                        assignment.daemonSetName(), 0, assignment.moduleId(), "FAILED", true, false)
                    .build())));
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

  /**
   * Regression test for the staleness check comparing only {@code moduleId}, not {@code
   * artifactPath} (unlike {@code DeploymentReconciler.isStale}): a re-applied manifest with the
   * same {@code moduleId} but a patched jar at a new path must still trigger a rollout.
   */
  @Test
  void an_artifact_path_only_change_still_triggers_a_rolling_update() throws Exception {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jarV1 = buildFixtureJar();
    Path jarV2 = tempDir.resolve("patched-" + jarV1.getFileName());
    java.nio.file.Files.copy(jarV1, jarV2); // same moduleId, new path -- a hotfix rebuild
    registerNode(store, "node-a");
    DaemonSetSpec v1 = daemonSet("node-exporter", jarV1, PlacementConstraints.NONE);
    store.putDaemonSetSpec(v1);
    DaemonSetReconciler reconciler = new DaemonSetReconciler(store, scheduler);
    reconciler.reconcileOnce();
    for (DaemonSetAssignment a :
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter")) {
      reportReady(store, a);
    }

    DaemonSetSpec v2 =
        new DaemonSetSpec(
            "node-exporter",
            v1.moduleId(),
            jarV2.toAbsolutePath().toString(),
            PlacementConstraints.NONE,
            Optional.empty(),
            Optional.empty());
    store.putDaemonSetSpec(v2);
    reconciler.reconcileOnce();

    DaemonSetAssignment rolled =
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").stream()
            .filter(a -> a.nodeId().equals("node-a"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        jarV2.toAbsolutePath().toString(),
        rolled.artifactPath(),
        "an artifact-path-only change must still roll the assignment forward");
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

  // ---- desired-count publication (GIMLE-15 sub-item 2) ----

  @Test
  void desired_count_is_zero_when_no_node_is_eligible() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(daemonSet("node-exporter", jar, PlacementConstraints.NONE));

    new DaemonSetReconciler(store, scheduler).reconcileOnce();

    assertEquals(Optional.of(0), store.getDaemonSetDesiredCount(Optional.empty(), "node-exporter"));
  }

  @Test
  void desired_count_equals_the_full_eligible_node_set_when_every_node_qualifies() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(daemonSet("node-exporter", jar, PlacementConstraints.NONE));
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    registerNode(store, "node-c");

    new DaemonSetReconciler(store, scheduler).reconcileOnce();

    assertEquals(Optional.of(3), store.getDaemonSetDesiredCount(Optional.empty(), "node-exporter"));
  }

  @Test
  void desired_count_tracks_required_labels_the_same_way_placement_does() {
    // The desired count is derived from the identical eligibility filter that already restricts
    // placement -- required labels must narrow both the same way.
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(
        daemonSet("gpu-agent", jar, new PlacementConstraints(Optional.of(Set.of("gpu")), false)));
    registerNode(store, "node-gpu", Set.of("gpu"));
    registerNode(store, "node-plain", Set.of());

    new DaemonSetReconciler(store, scheduler).reconcileOnce();

    assertEquals(Optional.of(1), store.getDaemonSetDesiredCount(Optional.empty(), "gpu-agent"));
  }

  @Test
  void desired_count_drops_when_a_node_becomes_ineligible_and_recovers_once_it_is_eligible_again() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(daemonSet("node-exporter", jar, PlacementConstraints.NONE));
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    DaemonSetReconciler reconciler = new DaemonSetReconciler(store, scheduler);
    reconciler.reconcileOnce();
    assertEquals(Optional.of(2), store.getDaemonSetDesiredCount(Optional.empty(), "node-exporter"));

    store.putNodeCordon("node-a", true);
    reconciler.reconcileOnce();
    assertEquals(
        Optional.of(1),
        store.getDaemonSetDesiredCount(Optional.empty(), "node-exporter"),
        "a cordoned node must drop out of the desired count on the very next tick");

    store.putNodeCordon("node-a", false);
    reconciler.reconcileOnce();
    assertEquals(
        Optional.of(2),
        store.getDaemonSetDesiredCount(Optional.empty(), "node-exporter"),
        "an uncordoned node must count toward desired again once it's eligible");
  }

  @Test
  void an_arbitrary_starting_snapshot_still_converges_the_desired_count_with_no_prior_history() {
    // Level-triggered convergence: a reconciler with no history of its own, started from a
    // snapshot that never recorded a desired count at all, must still compute and publish the
    // correct value on its very first tick.
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
    assertTrue(store.getDaemonSetDesiredCount(Optional.empty(), "node-exporter").isEmpty());

    new DaemonSetReconciler(store, scheduler).reconcileOnce();

    assertEquals(
        Optional.of(1),
        store.getDaemonSetDesiredCount(Optional.empty(), "node-exporter"),
        "only node-fresh is eligible; the cordoned node-stale must not count toward desired");
  }

  // ---- an unreadable node is not a node that is gone ----

  private static DaemonSetReconciler reconciler(
      com.gimle.mimir.store.StoreReader reads,
      StateStore store,
      Duration nodeDarkTimeout,
      Duration placementGracePeriod,
      TestClock clock) {
    return new DaemonSetReconciler(
        reads,
        new Scheduler(),
        mutation -> mutation.applyTo(store),
        nodeDarkTimeout,
        placementGracePeriod,
        clock);
  }

  @Test
  void a_store_leader_election_does_not_tear_down_a_healthy_daemonset(TestClock clock) {
    // Heartbeats are leader-local and never replicated, so a new leader holds nothing for any
    // node -- an empty read, not a report that the nodes are gone. Acting on it would evict every
    // assignment in the cluster off machines whose agents never stopped supervising them.
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(daemonSet("node-exporter", jar, PlacementConstraints.NONE));
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    registerNode(store, "node-c");
    Duration nodeDarkTimeout = Duration.ofSeconds(15);
    DaemonSetReconciler reconciler =
        reconciler(store, store, nodeDarkTimeout, nodeDarkTimeout, clock);
    reconciler.reconcileOnce();
    assertEquals(3, store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").size());

    store.beginNodeObservationWindow();
    clock.advance(Duration.ofSeconds(1));
    reconciler.reconcileOnce();

    assertEquals(
        3,
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").size(),
        "an election must not evict a single assignment");
    assertEquals(
        Optional.of(3),
        store.getDaemonSetDesiredCount(Optional.empty(), "node-exporter"),
        "nor may it publish a desired count that disowns those same assignments");
  }

  @Test
  void the_desired_count_never_falls_below_what_the_same_tick_keeps_placed(TestClock clock) {
    // Every node goes dark but stays inside the placement grace period, so this tick deliberately
    // keeps all three assignments. A desired count drawn from eligibility alone would read 0 here,
    // and any reader subtracting placed from desired would see -3.
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(daemonSet("node-exporter", jar, PlacementConstraints.NONE));
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    registerNode(store, "node-c");
    Duration nodeDarkTimeout = Duration.ofSeconds(15);
    Duration placementGracePeriod = Duration.ofSeconds(30);
    DaemonSetReconciler reconciler =
        reconciler(store, store, nodeDarkTimeout, placementGracePeriod, clock);
    reconciler.reconcileOnce();

    clock.advance(nodeDarkTimeout.plus(Duration.ofSeconds(1)));
    reconciler.reconcileOnce();

    int placed = store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").size();
    int desired = store.getDaemonSetDesiredCount(Optional.empty(), "node-exporter").orElseThrow();
    assertEquals(3, placed);
    assertEquals(3, desired);
    assertTrue(desired - placed >= 0, "desired minus placed must never be negative");
  }

  @Test
  void a_node_that_stays_unheard_from_past_the_grace_window_is_still_evicted(TestClock clock) {
    // The other half of the same property: holding an unconfirmed absence must not turn into never
    // acting on a real one.
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(daemonSet("node-exporter", jar, PlacementConstraints.NONE));
    registerNode(store, "node-a");
    Duration nodeDarkTimeout = Duration.ofSeconds(15);
    DaemonSetReconciler reconciler =
        reconciler(store, store, nodeDarkTimeout, nodeDarkTimeout, clock);
    reconciler.reconcileOnce();
    store.beginNodeObservationWindow();

    clock.advance(Duration.ofMinutes(5));
    reconciler.reconcileOnce();

    assertTrue(
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").isEmpty(),
        "a node the store has genuinely never heard from must still lose its assignment");
    assertEquals(Optional.of(0), store.getDaemonSetDesiredCount(Optional.empty(), "node-exporter"));
  }

  @Test
  void a_store_read_that_throws_mid_tick_publishes_nothing_and_converges_on_a_later_tick(
      TestClock clock) {
    // Level-triggered convergence from an arbitrary starting state: this tick reads the specs and
    // the assignments fine and only then loses the store. Nothing it half-computed may be
    // published -- the next tick recomputes the whole thing from the same full snapshot.
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(daemonSet("node-exporter", jar, PlacementConstraints.NONE));
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    AtomicBoolean storeUnreachable = new AtomicBoolean(true);
    DaemonSetReconciler reconciler =
        reconciler(
            UnreachableStoreReads.over(store, storeUnreachable, Set.of("getRollingDaemonSetNodes")),
            store,
            Duration.ofSeconds(15),
            Duration.ofSeconds(15),
            clock);

    reconciler.reconcileOnce();

    assertTrue(
        store.getDaemonSetDesiredCount(Optional.empty(), "node-exporter").isEmpty(),
        "a tick that could not finish must publish no count at all");
    assertTrue(
        store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").isEmpty(),
        "nor place anything against a snapshot it could not finish reading");

    storeUnreachable.set(false);
    reconciler.reconcileOnce();

    assertEquals(2, store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").size());
    assertEquals(Optional.of(2), store.getDaemonSetDesiredCount(Optional.empty(), "node-exporter"));
  }

  @Test
  void a_store_read_that_throws_mid_tick_leaves_an_already_published_count_alone(TestClock clock) {
    // The same property stated over an existing count rather than a missing one: a broken tick
    // must not overwrite what the last complete tick established.
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(daemonSet("node-exporter", jar, PlacementConstraints.NONE));
    registerNode(store, "node-a");
    registerNode(store, "node-b");
    AtomicBoolean storeUnreachable = new AtomicBoolean(false);
    DaemonSetReconciler reconciler =
        reconciler(
            UnreachableStoreReads.over(store, storeUnreachable, Set.of("getRollingDaemonSetNodes")),
            store,
            Duration.ofSeconds(15),
            Duration.ofSeconds(15),
            clock);
    reconciler.reconcileOnce();
    assertEquals(Optional.of(2), store.getDaemonSetDesiredCount(Optional.empty(), "node-exporter"));

    store.putNodeCordon("node-a", true);
    store.putNodeCordon("node-b", true);
    storeUnreachable.set(true);
    reconciler.reconcileOnce();

    assertEquals(
        Optional.of(2),
        store.getDaemonSetDesiredCount(Optional.empty(), "node-exporter"),
        "a tick that aborted must leave the last complete tick's count in place");

    storeUnreachable.set(false);
    reconciler.reconcileOnce();

    assertEquals(Optional.of(0), store.getDaemonSetDesiredCount(Optional.empty(), "node-exporter"));
    assertTrue(store.listDaemonSetAssignmentsFor(Optional.empty(), "node-exporter").isEmpty());
  }

  @Test
  void deleting_a_daemonset_clears_its_stale_desired_count() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putDaemonSetSpec(daemonSet("node-exporter", jar, PlacementConstraints.NONE));
    registerNode(store, "node-a");
    new DaemonSetReconciler(store, scheduler).reconcileOnce();
    assertEquals(Optional.of(1), store.getDaemonSetDesiredCount(Optional.empty(), "node-exporter"));

    store.removeDaemonSetSpec(Optional.empty(), "node-exporter");

    assertTrue(store.getDaemonSetDesiredCount(Optional.empty(), "node-exporter").isEmpty());
  }
}
