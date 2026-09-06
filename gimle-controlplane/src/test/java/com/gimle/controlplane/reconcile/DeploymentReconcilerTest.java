package com.gimle.controlplane.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.time.TestClock;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.raft.MutationOutcome;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.StateStore;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

class DeploymentReconcilerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static int counter = 0;

  private Path buildFixtureJar() {
    String uniqueName = "com.gimle.fixture.reconciler" + (counter++);
    return TestModuleBuilder.module("module " + uniqueName + " {\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
        .build(tempDir, uniqueName + ".jar");
  }

  private DeploymentSpec deployment(
      String name, int replicas, Path jar, PlacementConstraints placement) {
    return new DeploymentSpec(
        name,
        new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
        jar.toAbsolutePath().toString(),
        replicas,
        placement);
  }

  private DeploymentSpec deploymentWithArtifactSha256(
      String name, int replicas, Path jar, PlacementConstraints placement, String artifactSha256) {
    return new DeploymentSpec(
        name,
        new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
        jar.toAbsolutePath().toString(),
        replicas,
        placement,
        Optional.empty(),
        Optional.empty(),
        Optional.of(artifactSha256));
  }

  private static void registerNode(
      StateStore store, String nodeId, long freeMemoryBytes, long freeCpuMillicores) {
    store.putNodeRegistration(
        new NodeRegistration(
            nodeId, new NodeCapabilities(Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2))));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            nodeId,
            new ResourceUsageSnapshot(freeMemoryBytes, 0, freeCpuMillicores, 0),
            List.of()));
  }

  /** A {@link DeploymentReconciler} threaded with {@code clock} rather than a real one. */
  private static DeploymentReconciler deploymentReconciler(
      StateStore store, Scheduler scheduler, TestClock clock) {
    return new DeploymentReconciler(
        store,
        scheduler,
        mutation -> mutation.applyTo(store),
        DeploymentReconciler.DEFAULT_NODE_DARK_TIMEOUT,
        clock);
  }

  private static void markReady(
      StateStore store,
      String nodeId,
      String deploymentName,
      int instanceIndex,
      ModuleId moduleId) {
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            nodeId,
            new ResourceUsageSnapshot(500L * 1024 * 1024, 0, 4000, 0),
            List.of(
                InstanceObservation.builder(
                        deploymentName, instanceIndex, moduleId, "ACTIVE", true, true)
                    .build())));
  }

  /**
   * Reports the same index as still present and alive but no longer {@code ready} -- a flap, not a
   * disappearance (that's {@code ReplicaCountReconciler}'s concern, not this reconciler's).
   */
  private static void markNotReady(
      StateStore store,
      String nodeId,
      String deploymentName,
      int instanceIndex,
      ModuleId moduleId) {
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            nodeId,
            new ResourceUsageSnapshot(500L * 1024 * 1024, 0, 4000, 0),
            List.of(
                InstanceObservation.builder(
                        deploymentName, instanceIndex, moduleId, "ACTIVE", true, false)
                    .build())));
  }

  private static InstanceAssignment assignmentAt(StateStore store, String name, int index) {
    return store.listAssignmentsFor(Optional.empty(), name).stream()
        .filter(a -> a.instanceIndex() == index)
        .findFirst()
        .orElseThrow();
  }

  /**
   * A node whose last heartbeat has aged past the dark timeout must drop out of placement entirely.
   *
   * <p>This is not a marginal filter. A dead node's heartbeat is frozen at whatever capacity it
   * reported while alive, and {@code ReplicaCountReconciler} has just released its assignments, so
   * it looks like the emptiest machine in the cluster -- an actively *preferred* candidate. Since a
   * placement there is never confirmed, the two reconcilers would then trade release and re-place
   * forever while the deployment stays down but looks scheduled.
   */
  @Test
  void a_node_whose_heartbeat_has_gone_stale_is_no_longer_a_placement_candidate(TestClock clock) {
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putDeployment(deployment("orders-service", 1, jar, PlacementConstraints.NONE));
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);

    DeploymentReconciler reconciler =
        new DeploymentReconciler(
            store,
            scheduler,
            mutation -> mutation.applyTo(store),
            DeploymentReconciler.DEFAULT_NODE_DARK_TIMEOUT,
            clock);

    // Right up to the timeout the node is still live and still takes placement.
    clock.advance(DeploymentReconciler.DEFAULT_NODE_DARK_TIMEOUT);
    reconciler.reconcileOnce();
    assertEquals(1, store.listAssignmentsFor(Optional.empty(), "orders-service").size());

    // Past it, with the assignment released the way ReplicaCountReconciler would release it, the
    // gap must stay open rather than being refilled on the same silent node.
    store.removeAssignment(Optional.empty(), "orders-service", 0);
    clock.advance(Duration.ofSeconds(1));
    reconciler.reconcileOnce();

    assertTrue(
        store.listAssignmentsFor(Optional.empty(), "orders-service").isEmpty(),
        "a node that has stopped heartbeating must not be re-selected for placement");
  }

  @Test
  void creates_assignments_for_every_missing_index_when_capacity_exists() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    DeploymentSpec spec = deployment("orders-service", 2, jar, PlacementConstraints.NONE);
    store.putDeployment(spec);
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);

    new DeploymentReconciler(store, scheduler).reconcileOnce();

    List<InstanceAssignment> assignments =
        store.listAssignmentsFor(Optional.empty(), "orders-service");
    assertEquals(2, assignments.size());
    assertEquals(
        Set.of(0, 1),
        Set.copyOf(assignments.stream().map(InstanceAssignment::instanceIndex).toList()));
  }

  @Test
  void leaves_indices_unplaced_without_throwing_when_no_node_has_capacity() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putDeployment(deployment("orders-service", 2, jar, PlacementConstraints.NONE));
    // no nodes registered at all

    DeploymentReconciler reconciler = new DeploymentReconciler(store, scheduler);
    reconciler.reconcileOnce();
    reconciler.reconcileOnce(); // idempotent: calling again doesn't error or duplicate

    assertTrue(store.listAssignmentsFor(Optional.empty(), "orders-service").isEmpty());
  }

  @Test
  void scale_down_removes_assignments_at_or_beyond_the_new_replica_count() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    store.putDeployment(deployment("orders-service", 3, jar, PlacementConstraints.NONE));
    new DeploymentReconciler(store, scheduler).reconcileOnce();
    assertEquals(3, store.listAssignmentsFor(Optional.empty(), "orders-service").size());

    store.putDeployment(deployment("orders-service", 1, jar, PlacementConstraints.NONE));
    new DeploymentReconciler(store, scheduler).reconcileOnce();

    List<InstanceAssignment> remaining =
        store.listAssignmentsFor(Optional.empty(), "orders-service");
    assertEquals(1, remaining.size());
    assertEquals(0, remaining.get(0).instanceIndex());
  }

  @Test
  void deleting_a_deployment_removes_all_of_its_assignments() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    store.putDeployment(deployment("orders-service", 2, jar, PlacementConstraints.NONE));
    new DeploymentReconciler(store, scheduler).reconcileOnce();
    assertEquals(2, store.listAssignmentsFor(Optional.empty(), "orders-service").size());

    store.removeDeployment(Optional.empty(), "orders-service");
    new DeploymentReconciler(store, scheduler).reconcileOnce();

    assertTrue(store.listAssignmentsFor(Optional.empty(), "orders-service").isEmpty());
    assertTrue(store.listAssignments().isEmpty());
  }

  @Test
  void anti_affinity_spreads_replicas_across_distinct_nodes() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    registerNode(store, "node-b", 500L * 1024 * 1024, 4000);
    store.putDeployment(
        deployment("orders-service", 2, jar, new PlacementConstraints(Optional.empty(), true)));

    new DeploymentReconciler(store, scheduler).reconcileOnce();

    List<InstanceAssignment> assignments =
        store.listAssignmentsFor(Optional.empty(), "orders-service");
    assertEquals(2, assignments.size());
    assertEquals(
        2, Set.copyOf(assignments.stream().map(InstanceAssignment::nodeId).toList()).size());
  }

  @Test
  void anti_affinity_spreads_a_fresh_multi_replica_placement_even_with_deferred_mutations() {
    // The other anti-affinity test's two-arg constructor applies each proposed mutation to the
    // store synchronously (mutation -> mutation.applyTo(store)), so its own second placement
    // already sees the first one's assignment when it re-reads the store -- masking exactly the
    // bug this test exists to catch. Production's real MutationSink (a RaftNode) does not offer
    // that guarantee: propose() during one reconciliation pass is not necessarily visible to a
    // read from within that same pass. This test reproduces that by collecting proposals and only
    // applying them once reconcileOnce() has fully returned, the same gap placeInstances's own
    // placedThisTick set now closes without relying on the store to reflect a same-tick sibling.
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    registerNode(store, "node-b", 500L * 1024 * 1024, 4000);
    registerNode(store, "node-c", 500L * 1024 * 1024, 4000);
    store.putDeployment(
        deployment("orders-service", 3, jar, new PlacementConstraints(Optional.empty(), true)));

    List<StateMutation> deferred = new ArrayList<>();
    MutationSink deferringSink =
        m -> {
          deferred.add(m);
          return MutationOutcome.accepted();
        };
    new DeploymentReconciler(
            store,
            scheduler,
            deferringSink,
            DeploymentReconciler.DEFAULT_NODE_DARK_TIMEOUT,
            Clock.systemUTC())
        .reconcileOnce();
    deferred.forEach(mutation -> mutation.applyTo(store));

    List<InstanceAssignment> assignments =
        store.listAssignmentsFor(Optional.empty(), "orders-service");
    assertEquals(3, assignments.size());
    assertEquals(
        3, Set.copyOf(assignments.stream().map(InstanceAssignment::nodeId).toList()).size());
  }

  @Test
  void an_arbitrary_starting_snapshot_converges_the_same_as_a_fresh_reconcile() {
    // Mixed bag: index 0 already validly assigned, index 2 stale (>= the current replica count of
    // 2), plus an assignment for a deployment that no longer exists at all -- a from-scratch run
    // starting from this exact snapshot has no history to consult, only what's here right now.
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    store.putDeployment(deployment("orders-service", 2, jar, PlacementConstraints.NONE));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putAssignment(new InstanceAssignment("orders-service", 2, "node-a"));
    store.putAssignment(new InstanceAssignment("ghost-deployment", 0, "node-a"));

    new DeploymentReconciler(store, scheduler).reconcileOnce();

    List<InstanceAssignment> orders = store.listAssignmentsFor(Optional.empty(), "orders-service");
    assertEquals(
        Set.of(0, 1), Set.copyOf(orders.stream().map(InstanceAssignment::instanceIndex).toList()));
    assertTrue(store.listAssignmentsFor(Optional.empty(), "ghost-deployment").isEmpty());
  }

  @Test
  void places_new_instances_when_the_recorded_artifact_hash_still_matches_the_jar_on_disk() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    store.putDeployment(
        deploymentWithArtifactSha256(
            "orders-service", 2, jar, PlacementConstraints.NONE, artifact.sha256()));

    new DeploymentReconciler(store, scheduler).reconcileOnce();

    assertEquals(2, store.listAssignmentsFor(Optional.empty(), "orders-service").size());
  }

  @Test
  void refuses_to_place_new_instances_once_the_jar_on_disk_no_longer_matches_the_recorded_hash() {
    // Simulates the artifact having been silently swapped out from under a deployment name after
    // admission: the spec still names the recorded hash of the *original* jar, but the file at
    // artifactPath now has different bytes (and therefore a different real hash).
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    store.putDeployment(
        deploymentWithArtifactSha256(
            "orders-service", 2, jar, PlacementConstraints.NONE, "f".repeat(64)));

    new DeploymentReconciler(store, scheduler).reconcileOnce();

    assertTrue(store.listAssignmentsFor(Optional.empty(), "orders-service").isEmpty());
  }

  /**
   * QA end-user-QA finding: an unplaceable deployment blocked by a bad artifact used to leave
   * nothing behind but a platform-log WARN, re-logged every tick -- {@code gimle events} showed
   * nothing at all, forever, with no way for an operator to discover why the deployment was stuck.
   * The durable event must exist, and must not be re-appended every tick for the exact same
   * still-ongoing failure.
   */
  @Test
  void an_unreadable_artifact_records_exactly_one_durable_event_despite_repeated_ticks() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    Path missingJar = tempDir.resolve("does-not-exist.jar");
    store.putDeployment(deployment("orders-service", 1, missingJar, PlacementConstraints.NONE));

    DeploymentReconciler reconciler = new DeploymentReconciler(store, scheduler);
    for (int i = 0; i < 5; i++) {
      reconciler.reconcileOnce();
    }

    assertTrue(store.listAssignmentsFor(Optional.empty(), "orders-service").isEmpty());
    assertEquals(
        1,
        store.listInstanceEvents(Optional.empty(), "orders-service", 0).size(),
        "five ticks of the exact same ongoing failure must record exactly one durable event, not"
            + " one per tick");
    assertEquals(
        InstanceEventKind.TRANSITION_FAILED,
        store.listInstanceEvents(Optional.empty(), "orders-service", 0).get(0).kind());
  }

  /**
   * A jar the platform refuses to read and a jar whose manifest the platform rejects are different
   * problems: the first needs a file fixed, the second needs the manifest fixed. The durable event
   * an operator reads must say which, and must carry the parser's own reason -- reporting a
   * rejected manifest as an unreadable artifact sends them looking at the wrong thing.
   */
  @Test
  void a_rejected_manifest_is_recorded_as_a_rejection_carrying_its_own_reason() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    // A valid jar whose descriptor names a module the parser refuses: a JPMS module name may not
    // contain a hyphen, and the manifest name is used verbatim as one.
    Path jar =
        TestModuleBuilder.module("module com.gimle.fixture.rejected {\n}\n")
            .withDescriptor(TestModuleBuilder.minimalDescriptor("orders-service", "1.0.0"))
            .build(tempDir, "rejected-manifest.jar");
    store.putDeployment(deployment("orders-service", 1, jar, PlacementConstraints.NONE));

    new DeploymentReconciler(store, scheduler).reconcileOnce();

    List<InstanceEvent> events = store.listInstanceEvents(Optional.empty(), "orders-service", 0);
    assertEquals(1, events.size());
    assertEquals(InstanceEventKind.TRANSITION_FAILED, events.get(0).kind());
    assertTrue(
        events.get(0).message().startsWith("artifact rejected: "),
        "a rejected manifest must not be reported as an unreadable artifact: "
            + events.get(0).message());
    assertTrue(
        events.get(0).message().contains("orders-service"),
        "the event must carry the parser's own reason: " + events.get(0).message());
  }

  /**
   * GIMLE-683 (FUNC-74): {@code isReady} used to be a pure point-in-time read of the latest
   * heartbeat's {@code ready} flag -- a freshly-placed replacement that happened to report ready on
   * exactly one heartbeat, then flapped straight back to not-ready, looked indistinguishable from a
   * genuinely stable one. A single lucky reading must never be mistaken for a completed migration.
   */
  @Test
  void
      an_instance_that_reports_ready_once_then_immediately_flaps_is_not_treated_as_a_completed_migration(
          TestClock clock) {
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler reconciler = deploymentReconciler(store, scheduler, clock);
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);

    Path jarV1 = buildFixtureJar();
    DeploymentSpec v1 = deployment("orders-service", 1, jarV1, PlacementConstraints.NONE);
    store.putDeployment(v1);
    reconciler.reconcileOnce();
    markReady(store, "node-a", "orders-service", 0, v1.moduleId());

    Path jarV2 = buildFixtureJar();
    DeploymentSpec v2 = deployment("orders-service", 1, jarV2, PlacementConstraints.NONE);
    store.putDeployment(v2);
    reconciler.reconcileOnce(); // starts rolling index 0 forward to v2
    assertEquals(Set.of(0), store.getRollingIndices(Optional.empty(), "orders-service"));

    // One lucky ready heartbeat, immediately followed by a flap back to not-ready.
    markReady(store, "node-a", "orders-service", 0, v2.moduleId());
    reconciler.reconcileOnce();
    markNotReady(store, "node-a", "orders-service", 0, v2.moduleId());
    reconciler.reconcileOnce();

    assertEquals(
        Set.of(0),
        store.getRollingIndices(Optional.empty(), "orders-service"),
        "a single ready heartbeat immediately followed by a flap back to not-ready must never be"
            + " mistaken for a genuinely completed migration");

    // Even once a full stabilization window's worth of time has passed, the flap must have reset
    // the timer -- there is no continuously-ready observation left to have stabilized.
    clock.advance(DeploymentReconciler.READINESS_STABILIZATION_WINDOW);
    reconciler.reconcileOnce();
    assertEquals(Set.of(0), store.getRollingIndices(Optional.empty(), "orders-service"));
  }

  /**
   * GIMLE-682 (FUNC-66): {@code handleRollingUpdate} throttles concurrent migrations to {@code
   * maxUnavailable}, but only because it trusts {@code isReady} to mean "genuinely stable" -- a
   * flapping replacement that clears its slot on a lucky reading would let the next migration start
   * even though {@code maxUnavailable} was configured as conservatively as possible (the default,
   * {@code 1}). This proves the throttle survives repeated flapping, not just a single false clear.
   */
  @Test
  void a_flapping_replacement_never_lets_a_second_migration_overlap_with_the_first(
      TestClock clock) {
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler reconciler = deploymentReconciler(store, scheduler, clock);
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);

    Path jarV1 = buildFixtureJar();
    DeploymentSpec v1 = deployment("orders-service", 2, jarV1, PlacementConstraints.NONE);
    store.putDeployment(v1);
    reconciler.reconcileOnce();
    markReady(store, "node-a", "orders-service", 0, v1.moduleId());
    markReady(store, "node-a", "orders-service", 1, v1.moduleId());

    Path jarV2 = buildFixtureJar();
    DeploymentSpec v2 = deployment("orders-service", 2, jarV2, PlacementConstraints.NONE);
    store.putDeployment(v2);
    reconciler.reconcileOnce(); // starts rolling index 0 forward; default maxUnavailable is 1
    assertEquals(Set.of(0), store.getRollingIndices(Optional.empty(), "orders-service"));
    assertEquals(v1.moduleId(), assignmentAt(store, "orders-service", 1).moduleId());

    // Several flap cycles: ready, then immediately not-ready again -- if a single ready heartbeat
    // could ever free the migration slot, index 1 would start migrating on one of these ticks even
    // though index 0 never genuinely stabilized.
    for (int flap = 0; flap < 5; flap++) {
      markReady(store, "node-a", "orders-service", 0, v2.moduleId());
      reconciler.reconcileOnce();
      markNotReady(store, "node-a", "orders-service", 0, v2.moduleId());
      reconciler.reconcileOnce();
    }

    assertEquals(
        Set.of(0),
        store.getRollingIndices(Optional.empty(), "orders-service"),
        "maxUnavailable: 1 must never be exceeded because of a flapping-but-never-stabilized"
            + " replacement");
    assertEquals(
        v1.moduleId(),
        assignmentAt(store, "orders-service", 1).moduleId(),
        "index 1 must never start migrating while index 0's replacement is still flapping");
  }

  /**
   * The happy path this fix must not regress: a replacement that is genuinely, continuously ready
   * for the full stabilization window is still correctly recognized as complete, freeing its budget
   * slot for the next migration.
   */
  @Test
  void a_genuinely_continuously_ready_replacement_completes_the_migration_and_frees_the_budget(
      TestClock clock) {
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler reconciler = deploymentReconciler(store, scheduler, clock);
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);

    Path jarV1 = buildFixtureJar();
    DeploymentSpec v1 = deployment("orders-service", 2, jarV1, PlacementConstraints.NONE);
    store.putDeployment(v1);
    reconciler.reconcileOnce();
    markReady(store, "node-a", "orders-service", 0, v1.moduleId());
    markReady(store, "node-a", "orders-service", 1, v1.moduleId());

    Path jarV2 = buildFixtureJar();
    DeploymentSpec v2 = deployment("orders-service", 2, jarV2, PlacementConstraints.NONE);
    store.putDeployment(v2);
    reconciler.reconcileOnce(); // starts rolling index 0 forward
    assertEquals(Set.of(0), store.getRollingIndices(Optional.empty(), "orders-service"));

    markReady(store, "node-a", "orders-service", 0, v2.moduleId());
    reconciler.reconcileOnce(); // first observation: records the stabilization timer
    assertEquals(
        Set.of(0),
        store.getRollingIndices(Optional.empty(), "orders-service"),
        "a single ready heartbeat is not yet proof of stability");

    clock.advance(DeploymentReconciler.READINESS_STABILIZATION_WINDOW);
    reconciler.reconcileOnce();
    assertEquals(
        Set.of(1),
        store.getRollingIndices(Optional.empty(), "orders-service"),
        "a genuinely, continuously ready replacement must free its migration slot once the"
            + " stabilization window elapses, immediately starting the next migration");
    assertEquals(v2.moduleId(), assignmentAt(store, "orders-service", 0).moduleId());
  }

  /**
   * The readiness-stabilization timer is persisted through {@link
   * com.gimle.mimir.store.ReconcilerInstanceState} precisely so a reconciler-leader failover
   * doesn't restart it from scratch -- a brand-new reconciler instance, with no in-memory history
   * at all, must still refuse to clear the migration before the window it didn't itself start has
   * elapsed, and must still clear it once that persisted window does elapse.
   */
  @Test
  void
      the_readiness_stabilization_timer_survives_a_reconciler_reconstruction_against_the_same_store(
          TestClock clock) {
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler reconciler = deploymentReconciler(store, scheduler, clock);
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);

    Path jarV1 = buildFixtureJar();
    DeploymentSpec v1 = deployment("orders-service", 1, jarV1, PlacementConstraints.NONE);
    store.putDeployment(v1);
    reconciler.reconcileOnce();
    markReady(store, "node-a", "orders-service", 0, v1.moduleId());

    Path jarV2 = buildFixtureJar();
    DeploymentSpec v2 = deployment("orders-service", 1, jarV2, PlacementConstraints.NONE);
    store.putDeployment(v2);
    reconciler.reconcileOnce(); // starts rolling index 0 forward
    assertEquals(Set.of(0), store.getRollingIndices(Optional.empty(), "orders-service"));

    markReady(store, "node-a", "orders-service", 0, v2.moduleId());
    reconciler.reconcileOnce(); // records the stabilization timer via ReconcilerInstanceState

    // Simulate a control-plane restart: a fresh DeploymentReconciler with no in-memory history at
    // all, against the same store -- the store (gimle-mimir) is its own process and doesn't
    // restart with a control-plane replica.
    DeploymentReconciler resumed = deploymentReconciler(store, scheduler, clock);

    resumed.reconcileOnce();
    assertEquals(
        Set.of(0),
        store.getRollingIndices(Optional.empty(), "orders-service"),
        "the resumed reconciler has no in-memory history of its own, but must not treat that as a"
            + " fresh start for a timer that already exists in the store");

    clock.advance(DeploymentReconciler.READINESS_STABILIZATION_WINDOW);
    resumed.reconcileOnce();
    assertEquals(
        Set.of(),
        store.getRollingIndices(Optional.empty(), "orders-service"),
        "the resumed reconciler must complete the migration once the persisted timer's window"
            + " elapses, proving the timer -- not just the ready flag -- survived the restart");
  }
}
