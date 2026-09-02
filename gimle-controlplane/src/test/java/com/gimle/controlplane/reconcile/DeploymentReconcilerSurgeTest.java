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
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.DisruptionBudget;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.StateStore;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code maxSurge} half of disruption budgets, layered onto {@link DeploymentReconciler}'s
 * existing convergence loop the same way {@link DeploymentReconcilerRollingUpdateTest} covers the
 * {@code maxUnavailable} half -- a separate file rather than added to that one, matching this
 * codebase's own convention of one file per workload/budget dimension rather than one growing file
 * covering both. {@code DisruptionBudget}'s own {@code maxUnavailable} floor of {@code 1} means a
 * pure {@code maxSurge}-only scenario (no removal budget at all) isn't expressible -- every test
 * here uses a small {@code maxUnavailable} too, so both budgets are genuinely active at once, the
 * same as any real multi-replica rollout would see.
 */
class DeploymentReconcilerSurgeTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static int counter = 0;

  private Path buildFixtureJar() {
    String uniqueName = "com.gimle.fixture.surge" + (counter++);
    return TestModuleBuilder.module("module " + uniqueName + " {\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
        .build(tempDir, uniqueName + ".jar");
  }

  private static DeploymentSpec deploymentWithDisruption(
      String name, int replicas, Path jar, DisruptionBudget disruption) {
    return new DeploymentSpec(
        name,
        new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
        jar.toAbsolutePath().toString(),
        replicas,
        PlacementConstraints.NONE,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(disruption));
  }

  /**
   * A {@link DeploymentReconciler} threaded with a {@link TestClock} so a test can advance past
   * {@link DeploymentReconciler#READINESS_STABILIZATION_WINDOW} without any real waiting -- every
   * test in this file that expects a surge to actually promote needs this rather than the plain
   * two-arg (real-clock) constructor.
   */
  private static DeploymentReconciler deploymentReconciler(
      StateStore store, Scheduler scheduler, TestClock clock) {
    return new DeploymentReconciler(
        store,
        scheduler,
        mutation -> mutation.applyTo(store),
        DeploymentReconciler.DEFAULT_NODE_DARK_TIMEOUT,
        clock);
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
                InstanceObservation.builder(
                        deploymentName, instanceIndex, moduleId, "ACTIVE", true, true)
                    .build())));
  }

  /**
   * Reports every currently-assigned index on {@code nodeId} as ready, each on its own current
   * {@code moduleId}, in one combined heartbeat -- {@code putNodeHeartbeat} replaces a node's
   * entire observation list each call, so marking several indices ready one {@link #markReady} call
   * at a time on the same node would have each call silently erase the previous one's observation
   * instead of accumulating (the same pitfall {@code
   * DeploymentReconcilerRollingUpdateTest#markManyReady} exists to avoid).
   */
  private static void markAllCurrentlyAssignedReady(
      StateStore store, String nodeId, String deploymentName) {
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            nodeId,
            new ResourceUsageSnapshot(500L * 1024 * 1024, 0, 4000, 0),
            store.listAssignmentsFor(Optional.empty(), deploymentName).stream()
                .map(
                    a ->
                        InstanceObservation.builder(
                                deploymentName,
                                a.instanceIndex(),
                                a.moduleId(),
                                "ACTIVE",
                                true,
                                true)
                            .build())
                .toList()));
  }

  private static Optional<InstanceAssignment> assignmentAtIfPresent(
      StateStore store, String name, int index) {
    return store.listAssignmentsFor(Optional.empty(), name).stream()
        .filter(a -> a.instanceIndex() == index)
        .findFirst();
  }

  private static InstanceAssignment assignmentAt(StateStore store, String name, int index) {
    return assignmentAtIfPresent(store, name, index).orElseThrow();
  }

  @Test
  void a_surge_replacement_is_provisioned_before_the_original_is_removed() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler reconciler = new DeploymentReconciler(store, scheduler);
    registerNode(store, "node-a");

    Path jarV1 = buildFixtureJar();
    DeploymentSpec v1 =
        deploymentWithDisruption("orders-service", 2, jarV1, new DisruptionBudget(1, 1));
    store.putDeployment(v1);
    reconciler.reconcileOnce();
    markReady(store, "node-a", "orders-service", 0, v1.moduleId());
    markReady(store, "node-a", "orders-service", 1, v1.moduleId());
    reconciler.reconcileOnce(); // no-op: both indices already match v1

    Path jarV2 = buildFixtureJar();
    DeploymentSpec v2 =
        deploymentWithDisruption("orders-service", 2, jarV2, new DisruptionBudget(1, 1));
    store.putDeployment(v2);
    reconciler.reconcileOnce();

    // maxUnavailable (budget 1) runs first and claims the lowest mismatched index (0); maxSurge
    // (budget 1) then claims the remaining mismatch (1) -- the two budgets never double-claim the
    // same index (see the dedicated independence test below for the general case).
    Map<Integer, Integer> surging = store.getSurgeIndices(Optional.empty(), "orders-service");
    assertEquals(1, surging.size());
    int surgeIndex = surging.keySet().iterator().next();
    assertEquals(1, surging.get(surgeIndex));
    assertTrue(surgeIndex >= 2, "a surge index must be synthetic, >= replicas");

    // The surge target's ORIGINAL assignment must still be present and still on v1 -- provisioning
    // the replacement must never remove the original ahead of the replacement proving healthy.
    assertEquals(v1.moduleId(), assignmentAt(store, "orders-service", 1).moduleId());
    // The surge instance itself is already up, on v2, at its own synthetic index.
    assertEquals(v2.moduleId(), assignmentAt(store, "orders-service", surgeIndex).moduleId());
    // Running count is replicas + 1 (the surge slot) -- capacity never drops below replicas.
    assertEquals(3, store.listAssignmentsFor(Optional.empty(), "orders-service").size());
  }

  @Test
  void promotion_retargets_the_surge_instance_onto_the_target_in_the_same_tick(TestClock clock) {
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler reconciler = deploymentReconciler(store, scheduler, clock);
    registerNode(store, "node-a");

    Path jarV1 = buildFixtureJar();
    DeploymentSpec v1 =
        deploymentWithDisruption("orders-service", 2, jarV1, new DisruptionBudget(1, 1));
    store.putDeployment(v1);
    reconciler.reconcileOnce();
    markReady(store, "node-a", "orders-service", 0, v1.moduleId());
    markReady(store, "node-a", "orders-service", 1, v1.moduleId());

    Path jarV2 = buildFixtureJar();
    DeploymentSpec v2 =
        deploymentWithDisruption("orders-service", 2, jarV2, new DisruptionBudget(1, 1));
    store.putDeployment(v2);
    reconciler.reconcileOnce();

    int surgeIndex =
        store.getSurgeIndices(Optional.empty(), "orders-service").keySet().iterator().next();
    int targetIndex = store.getSurgeIndices(Optional.empty(), "orders-service").get(surgeIndex);
    String surgeNodeId = assignmentAt(store, "orders-service", surgeIndex).nodeId();
    markReady(store, "node-a", "orders-service", surgeIndex, v2.moduleId());
    reconciler.reconcileOnce(); // first observation: starts the stabilization timer
    clock.advance(DeploymentReconciler.READINESS_STABILIZATION_WINDOW);
    reconciler.reconcileOnce(); // stabilized: promotes, retargets in place, no restart

    assertTrue(
        store.getSurgeIndices(Optional.empty(), "orders-service").isEmpty(),
        "promotion must clear the surge tracking entry");
    // No later-tick scale-down sweep needed: the surge slot's own assignment is gone in this same
    // tick, retargeted rather than left orphaned for later reclaiming.
    assertTrue(
        assignmentAtIfPresent(store, "orders-service", surgeIndex).isEmpty(),
        "the surge slot must be gone in the very same tick promotion happens");
    InstanceAssignment promoted = assignmentAt(store, "orders-service", targetIndex);
    assertEquals(v2.moduleId(), promoted.moduleId());
    assertEquals(
        surgeNodeId,
        promoted.nodeId(),
        "the retargeted instance keeps the exact node the surge instance already proved healthy"
            + " on, never a fresh scheduling decision");
    assertEquals(
        OptionalInt.of(surgeIndex),
        promoted.renamedFromInstanceIndex(),
        "the retargeted assignment must carry the rename hint AgentMain relies on to retarget its"
            + " own already-running worker instead of restarting it");
    assertEquals(
        2,
        store.listAssignmentsFor(Optional.empty(), "orders-service").size(),
        "no extra tick needed to converge back to exactly replicas instances");
  }

  @Test
  void max_surge_caps_concurrent_surges_and_tops_up_as_each_promotes(TestClock clock) {
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler reconciler = deploymentReconciler(store, scheduler, clock);
    registerNode(store, "node-a");

    Path jarV1 = buildFixtureJar();
    DeploymentSpec v1 =
        deploymentWithDisruption("orders-service", 5, jarV1, new DisruptionBudget(1, 2));
    store.putDeployment(v1);
    reconciler.reconcileOnce();
    for (int i = 0; i < 5; i++) {
      markReady(store, "node-a", "orders-service", i, v1.moduleId());
    }
    reconciler.reconcileOnce(); // no-op: every index already matches v1

    Path jarV2 = buildFixtureJar();
    DeploymentSpec v2 =
        deploymentWithDisruption("orders-service", 5, jarV2, new DisruptionBudget(1, 2));
    store.putDeployment(v2);
    reconciler.reconcileOnce();

    assertEquals(
        2,
        store.getSurgeIndices(Optional.empty(), "orders-service").size(),
        "maxSurge: 2 must start exactly 2 surges, not fewer and not the remaining mismatches too");

    // Neither surge is ready yet: a second tick must not exceed the budget.
    reconciler.reconcileOnce();
    assertEquals(2, store.getSurgeIndices(Optional.empty(), "orders-service").size());

    // One surge becomes ready -- the freed slot is topped up with a new surge in the same tick.
    int firstSurgeIndex =
        store.getSurgeIndices(Optional.empty(), "orders-service").keySet().stream()
            .min(Integer::compare)
            .get();
    markReady(store, "node-a", "orders-service", firstSurgeIndex, v2.moduleId());
    reconciler.reconcileOnce(); // first observation: starts the stabilization timer
    clock.advance(DeploymentReconciler.READINESS_STABILIZATION_WINDOW);
    reconciler.reconcileOnce(); // stabilized: promotes and tops up in the same tick
    assertEquals(
        2,
        store.getSurgeIndices(Optional.empty(), "orders-service").size(),
        "a freed surge slot must be topped up immediately, keeping in-flight count at the budget");

    // Drain everything to completion, marking whichever indices are currently in flight (rolling
    // or surge) as ready and advancing the clock by a full stabilization window before every
    // reconcile, the same convergence-loop shape the maxUnavailable-only test uses.
    // 60 is a generous safety margin, not a tight bound: readiness now needs to hold across two
    // ticks per index (one to record when it was first observed ready, one more after the window
    // elapses to confirm it stayed ready) -- the loop exists to guard against a stalled/non-
    // converging bug turning into an infinite loop, not because this scenario is expected to need
    // anywhere near 60.
    for (int tick = 0;
        tick < 60
            && (!store.getRollingIndices(Optional.empty(), "orders-service").isEmpty()
                || !store.getSurgeIndices(Optional.empty(), "orders-service").isEmpty()
                || store.listAssignmentsFor(Optional.empty(), "orders-service").size() != 5
                || store.listAssignmentsFor(Optional.empty(), "orders-service").stream()
                    .anyMatch(a -> !a.moduleId().equals(v2.moduleId())));
        tick++) {
      markAllCurrentlyAssignedReady(store, "node-a", "orders-service");
      clock.advance(DeploymentReconciler.READINESS_STABILIZATION_WINDOW);
      reconciler.reconcileOnce();
    }

    assertEquals(Set.of(), store.getRollingIndices(Optional.empty(), "orders-service"));
    assertTrue(store.getSurgeIndices(Optional.empty(), "orders-service").isEmpty());
    assertEquals(5, store.listAssignmentsFor(Optional.empty(), "orders-service").size());
    assertTrue(
        store.listAssignmentsFor(Optional.empty(), "orders-service").stream()
            .allMatch(a -> a.moduleId().equals(v2.moduleId())),
        "the rollout must fully converge to v2 across every index, with no leftover surge slots");
  }

  @Test
  void max_unavailable_and_max_surge_never_claim_the_same_mismatched_index() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler reconciler = new DeploymentReconciler(store, scheduler);
    registerNode(store, "node-a");

    Path jarV1 = buildFixtureJar();
    DeploymentSpec v1 =
        deploymentWithDisruption("orders-service", 2, jarV1, new DisruptionBudget(1, 1));
    store.putDeployment(v1);
    reconciler.reconcileOnce();
    markReady(store, "node-a", "orders-service", 0, v1.moduleId());
    markReady(store, "node-a", "orders-service", 1, v1.moduleId());

    Path jarV2 = buildFixtureJar();
    DeploymentSpec v2 =
        deploymentWithDisruption("orders-service", 2, jarV2, new DisruptionBudget(1, 1));
    store.putDeployment(v2);
    reconciler.reconcileOnce();

    Set<Integer> rollingTargets = store.getRollingIndices(Optional.empty(), "orders-service");
    Set<Integer> surgeTargets =
        Set.copyOf(store.getSurgeIndices(Optional.empty(), "orders-service").values());
    assertEquals(1, rollingTargets.size());
    assertEquals(1, surgeTargets.size());
    assertTrue(
        rollingTargets.stream().noneMatch(surgeTargets::contains),
        "the same mismatched index must never be claimed by both budgets at once");
    assertEquals(Set.of(0, 1), union(rollingTargets, surgeTargets));
  }

  private static Set<Integer> union(Set<Integer> a, Set<Integer> b) {
    Set<Integer> merged = new HashSet<>(a);
    merged.addAll(b);
    return merged;
  }

  @Test
  void a_replica_count_drop_mid_surge_abandons_the_promotion_instead_of_completing_it() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler reconciler = new DeploymentReconciler(store, scheduler);
    registerNode(store, "node-a");

    Path jarV1 = buildFixtureJar();
    DeploymentSpec v1 =
        deploymentWithDisruption("orders-service", 2, jarV1, new DisruptionBudget(1, 1));
    store.putDeployment(v1);
    reconciler.reconcileOnce();
    markReady(store, "node-a", "orders-service", 0, v1.moduleId());
    markReady(store, "node-a", "orders-service", 1, v1.moduleId());

    Path jarV2 = buildFixtureJar();
    DeploymentSpec v2 =
        deploymentWithDisruption("orders-service", 2, jarV2, new DisruptionBudget(1, 1));
    store.putDeployment(v2);
    reconciler.reconcileOnce();

    int surgeIndex =
        store.getSurgeIndices(Optional.empty(), "orders-service").keySet().iterator().next();
    int targetIndex = store.getSurgeIndices(Optional.empty(), "orders-service").get(surgeIndex);

    // Scale down before the surge ever reports ready -- the target index falls out of range.
    DeploymentSpec v2Scaled =
        deploymentWithDisruption("orders-service", 1, jarV2, new DisruptionBudget(1, 1));
    store.putDeployment(v2Scaled);
    reconciler.reconcileOnce();

    assertTrue(
        store.getSurgeIndices(Optional.empty(), "orders-service").isEmpty(),
        "a surge whose target has scaled out of range must be abandoned, not promoted into a"
            + " nonexistent index");
    if (targetIndex >= 1) {
      assertTrue(assignmentAtIfPresent(store, "orders-service", targetIndex).isEmpty());
    }

    reconciler.reconcileOnce(); // the now-untracked, out-of-range surge assignment is reclaimed

    assertTrue(assignmentAtIfPresent(store, "orders-service", surgeIndex).isEmpty());
    assertEquals(1, store.listAssignmentsFor(Optional.empty(), "orders-service").size());
  }

  @Test
  void mid_surge_state_survives_a_simulated_reconciler_restart(TestClock clock) {
    Path storeDir = tempDir.resolve("store-restart");
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler reconciler = deploymentReconciler(store, scheduler, clock);
    registerNode(store, "node-a");

    Path jarV1 = buildFixtureJar();
    DeploymentSpec v1 =
        deploymentWithDisruption("orders-service", 2, jarV1, new DisruptionBudget(1, 1));
    store.putDeployment(v1);
    reconciler.reconcileOnce();
    markReady(store, "node-a", "orders-service", 0, v1.moduleId());
    markReady(store, "node-a", "orders-service", 1, v1.moduleId());

    Path jarV2 = buildFixtureJar();
    DeploymentSpec v2 =
        deploymentWithDisruption("orders-service", 2, jarV2, new DisruptionBudget(1, 1));
    store.putDeployment(v2);
    reconciler.reconcileOnce();

    Map<Integer, Integer> before = store.getSurgeIndices(Optional.empty(), "orders-service");
    assertEquals(1, before.size());

    // Simulate a control-plane restart: a fresh DeploymentReconciler with no in-memory history at
    // all, against the same store -- the store (gimle-mimir) is its own process and doesn't
    // restart with a control-plane replica.
    DeploymentReconciler restartedReconciler = deploymentReconciler(store, scheduler, clock);

    restartedReconciler.reconcileOnce(); // must not start a second, duplicate surge
    assertEquals(before, store.getSurgeIndices(Optional.empty(), "orders-service"));

    int surgeIndex = before.keySet().iterator().next();
    int targetIndex = before.get(surgeIndex);
    markReady(store, "node-a", "orders-service", surgeIndex, v2.moduleId());
    restartedReconciler.reconcileOnce(); // records the stabilization timer, doesn't promote yet
    clock.advance(DeploymentReconciler.READINESS_STABILIZATION_WINDOW);
    restartedReconciler.reconcileOnce();
    assertTrue(store.getSurgeIndices(Optional.empty(), "orders-service").isEmpty());
    assertEquals(v2.moduleId(), assignmentAt(store, "orders-service", targetIndex).moduleId());
  }
}
