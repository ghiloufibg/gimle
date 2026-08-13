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
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.DisruptionBudget;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.StateStore;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rolling-update behavior, layered onto {@link DeploymentReconciler}'s existing convergence
 * loop: up to the deployment's effective {@link DisruptionBudget#maxUnavailable} index replacements
 * in flight at once (default {@code 1}, absent a {@code disruption:} block), state that survives a
 * reconciler restart because it's read back from disk rather than kept only in memory, and a
 * rollout that never sees its new index turn ready stalling in place without touching other
 * indices.
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

  /**
   * Unlike {@link #markReady}, reports every given index as ready on {@code moduleId} in a single
   * combined heartbeat -- {@code putNodeHeartbeat} replaces a node's entire observation list each
   * call, so marking several indices ready one {@link #markReady} call at a time on the same node
   * would have each call silently erase the previous one's observation instead of accumulating.
   */
  private static void markManyReady(
      StateStore store,
      String nodeId,
      String deploymentName,
      ModuleId moduleId,
      Set<Integer> indices) {
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            nodeId,
            new ResourceUsageSnapshot(500L * 1024 * 1024, 0, 4000, 0),
            indices.stream()
                .map(
                    index ->
                        new InstanceObservation(
                            deploymentName, index, moduleId, "ACTIVE", true, true))
                .toList()));
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
    assertEquals(Set.of(0), store.getRollingIndices("orders-service"));

    // Not yet ready: a second reconcile must not touch index 1 or start a second migration --
    // the default disruption budget (no disruption: block) is maxUnavailable: 1.
    reconciler.reconcileOnce();
    assertEquals(v1.moduleId(), assignmentAt(store, "orders-service", 1).moduleId());
    assertEquals(Set.of(0), store.getRollingIndices("orders-service"));

    markReady(store, "node-a", "orders-service", 0, v2.moduleId());
    // A single tick both clears index 0 (now confirmed ready) and, since budget is freed in the
    // very same tick, immediately starts index 1's migration -- DeploymentReconciler's continuous
    // top-up model, not a settle-then-scan one.
    reconciler.reconcileOnce();
    assertEquals(v2.moduleId(), assignmentAt(store, "orders-service", 0).moduleId());
    assertEquals(Set.of(1), store.getRollingIndices("orders-service"));
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
    assertEquals(Set.of(0), store.getRollingIndices("orders-service"));
    assertEquals(v1.moduleId(), assignmentAt(store, "orders-service", 1).moduleId());

    // Simulate a control-plane restart: fresh StateStore/DeploymentReconciler instances reloading
    // from the same on-disk directory, with no in-memory history at all.
    StateStore restarted = new StateStore(storeDir);
    DeploymentReconciler restartedReconciler = new DeploymentReconciler(restarted, scheduler);

    assertEquals(Set.of(0), restarted.getRollingIndices("orders-service"));
    restartedReconciler.reconcileOnce();
    // Still not ready (heartbeat state is also reloaded from disk, unresolved): index 1 must
    // remain untouched even after the restart.
    assertEquals(v1.moduleId(), assignmentAt(restarted, "orders-service", 1).moduleId());
    assertEquals(Set.of(0), restarted.getRollingIndices("orders-service"));

    markReady(restarted, "node-a", "orders-service", 0, v2.moduleId());
    restartedReconciler.reconcileOnce(); // clears index 0, immediately tops up with index 1
    assertEquals(Set.of(1), restarted.getRollingIndices("orders-service"));
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

    assertEquals(Set.of(0), store.getRollingIndices("orders-service"));
    assertEquals(index1Before, assignmentAt(store, "orders-service", 1));
    assertTrue(
        store.listAssignmentsFor("orders-service").stream()
            .anyMatch(a -> a.instanceIndex() == 0 && a.moduleId().equals(v2.moduleId())));
  }

  /**
   * A regression this test guards against: a real node agent that has fetched a rolled-forward
   * assignment but hasn't actually replaced the worker yet (or -- the real bug this reproduces --
   * never will, see {@code AgentMain#requiresReplacement}) can still be mid-heartbeat-cycle
   * reporting the OLD instance as {@code ready} for that exact index. {@code isReady} must reject
   * that stale-but-present observation by its {@code moduleId}, not just its {@code ready} flag, or
   * {@link DeploymentReconciler#handleRollingUpdate} would clear that index's in-flight marker and
   * move on to migrating the next index while the first one is still silently running old code.
   */
  @Test
  void a_ready_observation_still_reporting_the_old_module_id_does_not_clear_the_rollout() {
    StateStore store = new StateStore(tempDir.resolve("store-stale-heartbeat"));
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
    assertEquals(Set.of(0), store.getRollingIndices("orders-service"));

    // The stale heartbeat: still reports index 0 as ready, but on the OLD moduleId -- exactly what
    // a node agent that silently kept the old worker running would keep sending forever.
    markReady(store, "node-a", "orders-service", 0, v1.moduleId());
    reconciler.reconcileOnce();

    assertEquals(
        Set.of(0),
        store.getRollingIndices("orders-service"),
        "a ready-but-wrong-moduleId observation must not be mistaken for the rollout completing");
    assertEquals(v1.moduleId(), assignmentAt(store, "orders-service", 1).moduleId());
  }

  /**
   * QA hardening real-cluster finding, confirmed against a real cluster via {@code
   * RedeployStabilityIT}: {@code handleRollingUpdate}'s clear condition used to also require the
   * in-flight index's assignment to match the *live* {@code spec.moduleId()} -- but {@code spec} is
   * read fresh on every tick, so a third version submitted before the second version's readiness
   * was observed raced ahead of that equality check. Once it could never pass again for that index,
   * the index stayed marked in-flight forever and every future tick left it untouched, permanently
   * blocking this deployment from ever rolling forward again -- not just stalling the in-flight
   * migration (already covered above), but deadlocking the *next* one too, indefinitely.
   */
  @Test
  void
      a_version_submitted_while_the_prior_rollout_is_still_confirming_does_not_deadlock_future_rollouts() {
    StateStore store = new StateStore(tempDir.resolve("store-chained-rollout"));
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler reconciler = new DeploymentReconciler(store, scheduler);
    registerNode(store, "node-a");

    Path jarV1 = buildFixtureJar();
    DeploymentSpec v1 = deployment("orders-service", 1, jarV1);
    store.putDeployment(v1);
    reconciler.reconcileOnce();
    markReady(store, "node-a", "orders-service", 0, v1.moduleId());

    Path jarV2 = buildFixtureJar();
    DeploymentSpec v2 = deployment("orders-service", 1, jarV2);
    store.putDeployment(v2);
    reconciler.reconcileOnce(); // starts rolling index 0 forward to v2
    assertEquals(Set.of(0), store.getRollingIndices("orders-service"));

    // Index 0 becomes ready on v2 -- but a THIRD version is submitted before the next reconcile
    // tick runs, racing ahead of the check that would otherwise have cleared the in-flight marker.
    markReady(store, "node-a", "orders-service", 0, v2.moduleId());
    Path jarV3 = buildFixtureJar();
    DeploymentSpec v3 = deployment("orders-service", 1, jarV3);
    store.putDeployment(v3);

    // Must still clear: index 0 IS ready, just not yet on v3 -- and because budget frees up in the
    // very same tick (continuous top-up), the v2->v3 mismatch is detected and re-placed immediately
    // too, rather than deadlocking on the completed-but-now-stale v2 migration.
    reconciler.reconcileOnce();
    assertEquals(
        Set.of(0),
        store.getRollingIndices("orders-service"),
        "the v1->v2 migration must clear, and the v2->v3 migration it immediately uncovers must"
            + " start in its place -- otherwise this deployment can never roll forward again");
    assertEquals(
        v3.moduleId(),
        assignmentAt(store, "orders-service", 0).moduleId(),
        "the freed budget must be spent on the newly-uncovered v2->v3 mismatch in the same tick");

    markReady(store, "node-a", "orders-service", 0, v3.moduleId());
    reconciler.reconcileOnce();
    assertEquals(
        Set.of(),
        store.getRollingIndices("orders-service"),
        "the chained rollout should also complete cleanly, proving rollouts chain instead of"
            + " deadlocking on the first one a newer submission races ahead of");
  }

  @Test
  void disruption_max_unavailable_caps_concurrent_migrations_and_tops_up_as_each_completes() {
    StateStore store = new StateStore(tempDir.resolve("store-budget"));
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler reconciler = new DeploymentReconciler(store, scheduler);
    registerNode(store, "node-a");

    Path jarV1 = buildFixtureJar();
    DeploymentSpec v1 =
        deploymentWithDisruption("orders-service", 5, jarV1, new DisruptionBudget(2));
    store.putDeployment(v1);
    reconciler.reconcileOnce();
    for (int i = 0; i < 5; i++) {
      markReady(store, "node-a", "orders-service", i, v1.moduleId());
    }
    reconciler.reconcileOnce(); // no-op: every index already matches v1

    Path jarV2 = buildFixtureJar();
    DeploymentSpec v2 =
        deploymentWithDisruption("orders-service", 5, jarV2, new DisruptionBudget(2));
    store.putDeployment(v2);

    reconciler.reconcileOnce();
    assertEquals(
        2,
        store.getRollingIndices("orders-service").size(),
        "maxUnavailable: 2 must start exactly 2 migrations, not all 5 and not just 1");

    // Neither of the two in-flight indices is ready yet: a second tick must not exceed the budget.
    reconciler.reconcileOnce();
    assertEquals(2, store.getRollingIndices("orders-service").size());

    // The lower of the two in-flight indices becomes ready -- the freed slot is topped up with a
    // third migration in the same tick, keeping exactly 2 in flight rather than dropping to 1.
    int firstInFlight =
        store.getRollingIndices("orders-service").stream().min(Integer::compare).orElseThrow();
    markReady(store, "node-a", "orders-service", firstInFlight, v2.moduleId());
    reconciler.reconcileOnce();
    assertEquals(
        2,
        store.getRollingIndices("orders-service").size(),
        "a freed slot must be topped up immediately, keeping the in-flight count at the budget"
            + " rather than draining to 1 before starting the next migration");
    assertTrue(
        store.listAssignmentsFor("orders-service").stream()
                .filter(a -> a.moduleId().equals(v2.moduleId()))
                .count()
            >= 1);

    // Drain the remaining migrations to completion, one tick at a time, marking whichever indices
    // are currently in flight as ready -- the deployment must fully converge to v2 without ever
    // exceeding the budget along the way (already asserted above) and without ever stalling.
    // Completion is "no index still in flight," not "every assignment already shows v2's
    // moduleId" -- placement applies v2 immediately regardless of readiness, so the latter can
    // turn true a tick before the in-flight set itself actually empties.
    for (int tick = 0; tick < 10 && !store.getRollingIndices("orders-service").isEmpty(); tick++) {
      markManyReady(
          store,
          "node-a",
          "orders-service",
          v2.moduleId(),
          store.getRollingIndices("orders-service"));
      reconciler.reconcileOnce();
    }
    assertTrue(
        store.listAssignmentsFor("orders-service").stream()
            .allMatch(a -> a.moduleId().equals(v2.moduleId())),
        "the rollout must fully converge to v2 across every index");
    assertEquals(Set.of(), store.getRollingIndices("orders-service"));
  }
}
