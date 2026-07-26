package com.gimle.controlplane.autoscale;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.controlplane.manifest.DeploymentSpec;
import com.gimle.controlplane.manifest.PlacementConstraints;
import com.gimle.controlplane.store.InstanceAssignment;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 4 §10's autoscaling formula: average observed CPU utilization against the module's own
 * {@code resourceRequest.cpuMillicores()} (10m in {@code TestModuleBuilder.minimalDescriptor}),
 * adjusted by exactly one replica per tick toward the computed ideal and clamped to {@code
 * [minReplicas, maxReplicas]}.
 */
class AutoscaleReconcilerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static int counter = 0;

  private Path buildFixtureJar() {
    String uniqueName = "com.gimle.fixture.autoscale" + (counter++);
    return TestModuleBuilder.module("module " + uniqueName + " {\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
        .build(tempDir, uniqueName + ".jar");
  }

  private static DeploymentSpec deployment(
      String name, int replicas, Path jar, AutoscalePolicy policy) {
    return new DeploymentSpec(
        name,
        new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
        jar.toAbsolutePath().toString(),
        replicas,
        PlacementConstraints.NONE,
        Optional.of(policy));
  }

  /**
   * Two ready instances (indices 0/1) on {@code node-a}, each using {@code cpuMillicoresUsed} out
   * of the fixture's 10m request.
   */
  private static void twoReadyInstancesAt(
      StateStore store, String deploymentName, ModuleId moduleId, long cpuMillicoresUsed) {
    store.putAssignment(new InstanceAssignment(deploymentName, 0, "node-a", moduleId, ""));
    store.putAssignment(new InstanceAssignment(deploymentName, 1, "node-a", moduleId, ""));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(500L * 1024 * 1024, 0, 4000, 0),
            List.of(
                new InstanceObservation(
                    deploymentName,
                    0,
                    moduleId,
                    "ACTIVE",
                    true,
                    true,
                    0.0,
                    0,
                    cpuMillicoresUsed,
                    0L),
                new InstanceObservation(
                    deploymentName,
                    1,
                    moduleId,
                    "ACTIVE",
                    true,
                    true,
                    0.0,
                    0,
                    cpuMillicoresUsed,
                    0L))));
  }

  @Test
  void scales_up_by_one_replica_per_tick_under_sustained_high_utilization() {
    StateStore store = new StateStore(tempDir.resolve("store-scale-up"));
    Path jar = buildFixtureJar();
    AutoscalePolicy policy = new AutoscalePolicy(1, 5, 50);
    DeploymentSpec spec = deployment("orders-service", 2, jar, policy);
    store.putDeployment(spec);
    // 10m used out of a 10m request: 100% utilization, double the 50% target.
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 10L);

    AutoscaleReconciler reconciler = new AutoscaleReconciler(store);
    reconciler.reconcileOnce();
    assertEquals(3, store.getEffectiveReplicas("orders-service").orElseThrow());

    reconciler.reconcileOnce();
    assertEquals(4, store.getEffectiveReplicas("orders-service").orElseThrow());

    reconciler.reconcileOnce();
    assertEquals(5, store.getEffectiveReplicas("orders-service").orElseThrow());
  }

  @Test
  void never_exceeds_max_replicas() {
    StateStore store = new StateStore(tempDir.resolve("store-max-clamp"));
    Path jar = buildFixtureJar();
    AutoscalePolicy policy = new AutoscalePolicy(1, 5, 50);
    DeploymentSpec spec = deployment("orders-service", 2, jar, policy);
    store.putDeployment(spec);
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 10L);

    AutoscaleReconciler reconciler = new AutoscaleReconciler(store);
    for (int i = 0; i < 10; i++) {
      reconciler.reconcileOnce();
    }

    assertEquals(5, store.getEffectiveReplicas("orders-service").orElseThrow());
  }

  @Test
  void scales_down_by_one_replica_per_tick_under_sustained_low_utilization() {
    StateStore store = new StateStore(tempDir.resolve("store-scale-down"));
    Path jar = buildFixtureJar();
    AutoscalePolicy policy = new AutoscalePolicy(1, 5, 50);
    DeploymentSpec spec = deployment("orders-service", 5, jar, policy);
    store.putDeployment(spec);
    store.putEffectiveReplicas("orders-service", 5);
    // 1m used out of a 10m request: 10% utilization, a fifth of the 50% target.
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 1L);

    AutoscaleReconciler reconciler = new AutoscaleReconciler(store);
    reconciler.reconcileOnce();
    assertEquals(4, store.getEffectiveReplicas("orders-service").orElseThrow());

    reconciler.reconcileOnce();
    assertEquals(3, store.getEffectiveReplicas("orders-service").orElseThrow());
  }

  @Test
  void never_goes_below_min_replicas() {
    StateStore store = new StateStore(tempDir.resolve("store-min-clamp"));
    Path jar = buildFixtureJar();
    AutoscalePolicy policy = new AutoscalePolicy(2, 5, 50);
    DeploymentSpec spec = deployment("orders-service", 5, jar, policy);
    store.putDeployment(spec);
    store.putEffectiveReplicas("orders-service", 5);
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 1L);

    AutoscaleReconciler reconciler = new AutoscaleReconciler(store);
    for (int i = 0; i < 10; i++) {
      reconciler.reconcileOnce();
    }

    assertEquals(2, store.getEffectiveReplicas("orders-service").orElseThrow());
  }

  @Test
  void holds_the_current_count_when_nothing_ready_reports_yet() {
    StateStore store = new StateStore(tempDir.resolve("store-no-signal"));
    Path jar = buildFixtureJar();
    AutoscalePolicy policy = new AutoscalePolicy(1, 5, 50);
    DeploymentSpec spec = deployment("orders-service", 3, jar, policy);
    store.putDeployment(spec);
    // no assignments, no heartbeats at all

    AutoscaleReconciler reconciler = new AutoscaleReconciler(store);
    reconciler.reconcileOnce();
    reconciler.reconcileOnce();

    assertEquals(3, store.getEffectiveReplicas("orders-service").orElseThrow());
  }

  @Test
  void deployments_without_a_policy_are_left_untouched() {
    StateStore store = new StateStore(tempDir.resolve("store-no-policy"));
    Path jar = buildFixtureJar();
    DeploymentSpec spec =
        new DeploymentSpec(
            "orders-service",
            new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
            jar.toAbsolutePath().toString(),
            2,
            PlacementConstraints.NONE);
    store.putDeployment(spec);
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 10L);

    new AutoscaleReconciler(store).reconcileOnce();

    assertEquals(Optional.empty(), store.getEffectiveReplicas("orders-service"));
  }
}
