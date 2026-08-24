package com.gimle.controlplane.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.time.TestClock;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
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
    StateStore store = new StateStore(tempDir.resolve("store-dark-node"), clock);
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
    assertEquals(1, store.listAssignmentsFor("orders-service").size());

    // Past it, with the assignment released the way ReplicaCountReconciler would release it, the
    // gap must stay open rather than being refilled on the same silent node.
    store.removeAssignment("orders-service", 0);
    clock.advance(Duration.ofSeconds(1));
    reconciler.reconcileOnce();

    assertTrue(
        store.listAssignmentsFor("orders-service").isEmpty(),
        "a node that has stopped heartbeating must not be re-selected for placement");
  }

  @Test
  void creates_assignments_for_every_missing_index_when_capacity_exists() {
    StateStore store = new StateStore(tempDir.resolve("store-basic"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    DeploymentSpec spec = deployment("orders-service", 2, jar, PlacementConstraints.NONE);
    store.putDeployment(spec);
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);

    new DeploymentReconciler(store, scheduler).reconcileOnce();

    List<InstanceAssignment> assignments = store.listAssignmentsFor("orders-service");
    assertEquals(2, assignments.size());
    assertEquals(
        Set.of(0, 1),
        Set.copyOf(assignments.stream().map(InstanceAssignment::instanceIndex).toList()));
  }

  @Test
  void leaves_indices_unplaced_without_throwing_when_no_node_has_capacity() {
    StateStore store = new StateStore(tempDir.resolve("store-no-capacity"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putDeployment(deployment("orders-service", 2, jar, PlacementConstraints.NONE));
    // no nodes registered at all

    DeploymentReconciler reconciler = new DeploymentReconciler(store, scheduler);
    reconciler.reconcileOnce();
    reconciler.reconcileOnce(); // idempotent: calling again doesn't error or duplicate

    assertTrue(store.listAssignmentsFor("orders-service").isEmpty());
  }

  @Test
  void scale_down_removes_assignments_at_or_beyond_the_new_replica_count() {
    StateStore store = new StateStore(tempDir.resolve("store-scale-down"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    store.putDeployment(deployment("orders-service", 3, jar, PlacementConstraints.NONE));
    new DeploymentReconciler(store, scheduler).reconcileOnce();
    assertEquals(3, store.listAssignmentsFor("orders-service").size());

    store.putDeployment(deployment("orders-service", 1, jar, PlacementConstraints.NONE));
    new DeploymentReconciler(store, scheduler).reconcileOnce();

    List<InstanceAssignment> remaining = store.listAssignmentsFor("orders-service");
    assertEquals(1, remaining.size());
    assertEquals(0, remaining.get(0).instanceIndex());
  }

  @Test
  void deleting_a_deployment_removes_all_of_its_assignments() {
    StateStore store = new StateStore(tempDir.resolve("store-delete"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    store.putDeployment(deployment("orders-service", 2, jar, PlacementConstraints.NONE));
    new DeploymentReconciler(store, scheduler).reconcileOnce();
    assertEquals(2, store.listAssignmentsFor("orders-service").size());

    store.removeDeployment("orders-service");
    new DeploymentReconciler(store, scheduler).reconcileOnce();

    assertTrue(store.listAssignmentsFor("orders-service").isEmpty());
    assertTrue(store.listAssignments().isEmpty());
  }

  @Test
  void anti_affinity_spreads_replicas_across_distinct_nodes() {
    StateStore store = new StateStore(tempDir.resolve("store-anti-affinity"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    registerNode(store, "node-b", 500L * 1024 * 1024, 4000);
    store.putDeployment(
        deployment("orders-service", 2, jar, new PlacementConstraints(Optional.empty(), true)));

    new DeploymentReconciler(store, scheduler).reconcileOnce();

    List<InstanceAssignment> assignments = store.listAssignmentsFor("orders-service");
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
    StateStore store = new StateStore(tempDir.resolve("store-anti-affinity-deferred"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    registerNode(store, "node-b", 500L * 1024 * 1024, 4000);
    registerNode(store, "node-c", 500L * 1024 * 1024, 4000);
    store.putDeployment(
        deployment("orders-service", 3, jar, new PlacementConstraints(Optional.empty(), true)));

    List<StateMutation> deferred = new ArrayList<>();
    new DeploymentReconciler(
            store,
            scheduler,
            deferred::add,
            DeploymentReconciler.DEFAULT_NODE_DARK_TIMEOUT,
            Clock.systemUTC())
        .reconcileOnce();
    deferred.forEach(mutation -> mutation.applyTo(store));

    List<InstanceAssignment> assignments = store.listAssignmentsFor("orders-service");
    assertEquals(3, assignments.size());
    assertEquals(
        3, Set.copyOf(assignments.stream().map(InstanceAssignment::nodeId).toList()).size());
  }

  @Test
  void an_arbitrary_starting_snapshot_converges_the_same_as_a_fresh_reconcile() {
    // Mixed bag: index 0 already validly assigned, index 2 stale (>= the current replica count of
    // 2), plus an assignment for a deployment that no longer exists at all -- a from-scratch run
    // starting from this exact snapshot has no history to consult, only what's here right now.
    StateStore store = new StateStore(tempDir.resolve("store-arbitrary"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    store.putDeployment(deployment("orders-service", 2, jar, PlacementConstraints.NONE));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putAssignment(new InstanceAssignment("orders-service", 2, "node-a"));
    store.putAssignment(new InstanceAssignment("ghost-deployment", 0, "node-a"));

    new DeploymentReconciler(store, scheduler).reconcileOnce();

    List<InstanceAssignment> orders = store.listAssignmentsFor("orders-service");
    assertEquals(
        Set.of(0, 1), Set.copyOf(orders.stream().map(InstanceAssignment::instanceIndex).toList()));
    assertTrue(store.listAssignmentsFor("ghost-deployment").isEmpty());
  }

  @Test
  void places_new_instances_when_the_recorded_artifact_hash_still_matches_the_jar_on_disk() {
    StateStore store = new StateStore(tempDir.resolve("store-hash-match"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    store.putDeployment(
        deploymentWithArtifactSha256(
            "orders-service", 2, jar, PlacementConstraints.NONE, artifact.sha256()));

    new DeploymentReconciler(store, scheduler).reconcileOnce();

    assertEquals(2, store.listAssignmentsFor("orders-service").size());
  }

  @Test
  void refuses_to_place_new_instances_once_the_jar_on_disk_no_longer_matches_the_recorded_hash() {
    // Simulates the artifact having been silently swapped out from under a deployment name after
    // admission: the spec still names the recorded hash of the *original* jar, but the file at
    // artifactPath now has different bytes (and therefore a different real hash).
    StateStore store = new StateStore(tempDir.resolve("store-hash-mismatch"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    store.putDeployment(
        deploymentWithArtifactSha256(
            "orders-service", 2, jar, PlacementConstraints.NONE, "f".repeat(64)));

    new DeploymentReconciler(store, scheduler).reconcileOnce();

    assertTrue(store.listAssignmentsFor("orders-service").isEmpty());
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
    StateStore store = new StateStore(tempDir.resolve("store-unreadable-artifact-event"));
    Scheduler scheduler = new Scheduler();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    Path missingJar = tempDir.resolve("does-not-exist.jar");
    store.putDeployment(deployment("orders-service", 1, missingJar, PlacementConstraints.NONE));

    DeploymentReconciler reconciler = new DeploymentReconciler(store, scheduler);
    for (int i = 0; i < 5; i++) {
      reconciler.reconcileOnce();
    }

    assertTrue(store.listAssignmentsFor("orders-service").isEmpty());
    assertEquals(
        1,
        store.listInstanceEvents("orders-service", 0).size(),
        "five ticks of the exact same ongoing failure must record exactly one durable event, not"
            + " one per tick");
    assertEquals(
        InstanceEventKind.TRANSITION_FAILED,
        store.listInstanceEvents("orders-service", 0).get(0).kind());
  }
}
