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
import com.gimle.core.protocol.InstanceObservation;
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

/**
 * Phase 4 §9's rolling-update behavior, layered onto {@link DeploymentReconciler}'s existing
 * convergence loop: one in-flight index replacement at a time, state that survives a reconciler
 * restart because it's read back from disk rather than kept only in memory, and a rollout that
 * never sees its new index turn ready stalling in place without touching other indices.
 */
class DeploymentReconcilerRollingUpdateTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static int counter = 0;

  private Path buildFixtureJar() {
    String uniqueName = "com.gimle.fixture.rollingupdate" + (counter++);
    return TestModuleBuilder.module("module " + uniqueName + " {\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
        .build(tempDir, uniqueName + ".jar");
  }

  private static DeploymentSpec deployment(String name, int replicas, Path jar) {
    return new DeploymentSpec(
        name,
        new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
        jar.toAbsolutePath().toString(),
        replicas,
        PlacementConstraints.NONE);
  }

  private static void registerNode(StateStore store, String nodeId) {
    store.putNodeRegistration(
        new NodeRegistration(
            nodeId, new NodeCapabilities(Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2))));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            nodeId, new ResourceUsageSnapshot(500L * 1024 * 1024, 0, 4000, 0), List.of()));
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
                new InstanceObservation(
                    deploymentName, instanceIndex, moduleId, "ACTIVE", true, true))));
  }

  private static InstanceAssignment assignmentAt(StateStore store, String name, int index) {
    return store.listAssignmentsFor(name).stream()
        .filter(a -> a.instanceIndex() == index)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void a_module_id_change_migrates_exactly_one_index_at_a_time() {
    StateStore store = new StateStore(tempDir.resolve("store"));
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler reconciler = new DeploymentReconciler(store, scheduler);
    registerNode(store, "node-a");

    Path jarV1 = buildFixtureJar();
    DeploymentSpec v1 = deployment("orders-service", 2, jarV1);
    store.putDeployment(v1);
    reconciler.reconcileOnce();
    markReady(store, "node-a", "orders-service", 0, v1.moduleId());
    markReady(store, "node-a", "orders-service", 1, v1.moduleId());
    reconciler.reconcileOnce(); // no-op: both indices already match the current spec

    Path jarV2 = buildFixtureJar();
    DeploymentSpec v2 = deployment("orders-service", 2, jarV2);
    store.putDeployment(v2);

    reconciler.reconcileOnce();
    assertEquals(v2.moduleId(), assignmentAt(store, "orders-service", 0).moduleId());
    assertEquals(v1.moduleId(), assignmentAt(store, "orders-service", 1).moduleId());
    assertEquals(Optional.of(0), store.getRollingIndex("orders-service"));

    // Not yet ready: a second reconcile must not touch index 1 or start a second migration.
    reconciler.reconcileOnce();
    assertEquals(v1.moduleId(), assignmentAt(store, "orders-service", 1).moduleId());
    assertEquals(Optional.of(0), store.getRollingIndex("orders-service"));

    markReady(store, "node-a", "orders-service", 0, v2.moduleId());
    reconciler.reconcileOnce(); // clears rollingIndex now that index 0 is confirmed ready
    reconciler.reconcileOnce(); // a fresh tick then picks up index 1's migration
    assertEquals(v2.moduleId(), assignmentAt(store, "orders-service", 0).moduleId());
    assertEquals(v2.moduleId(), assignmentAt(store, "orders-service", 1).moduleId());
    assertEquals(Optional.of(1), store.getRollingIndex("orders-service"));
  }

  @Test
  void mid_rollout_state_survives_a_simulated_reconciler_restart() {
    Path storeDir = tempDir.resolve("store-restart");
    StateStore store = new StateStore(storeDir);
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler reconciler = new DeploymentReconciler(store, scheduler);
    registerNode(store, "node-a");

    Path jarV1 = buildFixtureJar();
    DeploymentSpec v1 = deployment("orders-service", 2, jarV1);
    store.putDeployment(v1);
    reconciler.reconcileOnce();
    markReady(store, "node-a", "orders-service", 0, v1.moduleId());
    markReady(store, "node-a", "orders-service", 1, v1.moduleId());

    Path jarV2 = buildFixtureJar();
    DeploymentSpec v2 = deployment("orders-service", 2, jarV2);
    store.putDeployment(v2);
    reconciler.reconcileOnce();
    assertEquals(Optional.of(0), store.getRollingIndex("orders-service"));
    assertEquals(v1.moduleId(), assignmentAt(store, "orders-service", 1).moduleId());

    // Simulate a control-plane restart: fresh StateStore/DeploymentReconciler instances reloading
    // from the same on-disk directory, with no in-memory history at all.
    StateStore restarted = new StateStore(storeDir);
    DeploymentReconciler restartedReconciler = new DeploymentReconciler(restarted, scheduler);

    assertEquals(Optional.of(0), restarted.getRollingIndex("orders-service"));
    restartedReconciler.reconcileOnce();
    // Still not ready (heartbeat state is also reloaded from disk, unresolved): index 1 must
    // remain untouched even after the restart.
    assertEquals(v1.moduleId(), assignmentAt(restarted, "orders-service", 1).moduleId());
    assertEquals(Optional.of(0), restarted.getRollingIndex("orders-service"));

    markReady(restarted, "node-a", "orders-service", 0, v2.moduleId());
    restartedReconciler.reconcileOnce(); // clears rollingIndex now that index 0 is ready
    restartedReconciler.reconcileOnce(); // a fresh tick then picks up index 1's migration
    assertEquals(Optional.of(1), restarted.getRollingIndex("orders-service"));
    assertEquals(v2.moduleId(), assignmentAt(restarted, "orders-service", 1).moduleId());
  }

  @Test
  void a_rollout_that_never_turns_ready_stalls_without_touching_other_indices() {
    StateStore store = new StateStore(tempDir.resolve("store-stalled"));
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler reconciler = new DeploymentReconciler(store, scheduler);
    registerNode(store, "node-a");

    Path jarV1 = buildFixtureJar();
    DeploymentSpec v1 = deployment("orders-service", 2, jarV1);
    store.putDeployment(v1);
    reconciler.reconcileOnce();
    markReady(store, "node-a", "orders-service", 0, v1.moduleId());
    markReady(store, "node-a", "orders-service", 1, v1.moduleId());

    Path jarV2 = buildFixtureJar();
    DeploymentSpec v2 = deployment("orders-service", 2, jarV2);
    store.putDeployment(v2);
    reconciler.reconcileOnce();

    InstanceAssignment index1Before = assignmentAt(store, "orders-service", 1);
    for (int i = 0; i < 5; i++) {
      reconciler.reconcileOnce(); // index 0's readiness is never reported
    }

    assertEquals(Optional.of(0), store.getRollingIndex("orders-service"));
    assertEquals(index1Before, assignmentAt(store, "orders-service", 1));
    assertTrue(
        store.listAssignmentsFor("orders-service").stream()
            .anyMatch(a -> a.instanceIndex() == 0 && a.moduleId().equals(v2.moduleId())));
  }
}
