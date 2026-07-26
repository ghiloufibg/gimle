package com.gimle.controlplane.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.manifest.DeploymentSpec;
import com.gimle.controlplane.manifest.PlacementConstraints;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.controlplane.store.InstanceAssignment;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
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
}
