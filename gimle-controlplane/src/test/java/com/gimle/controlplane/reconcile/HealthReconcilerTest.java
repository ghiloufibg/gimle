package com.gimle.controlplane.reconcile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ReconcilerInstanceState;
import com.gimle.mimir.store.StateStore;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

class HealthReconcilerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static final ModuleId ORDERS =
      new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));

  private static HealthReconciler fastReconciler(StateStore store) {
    // Tiny backoff parameters so tests don't have to wait out production-scale delays.
    return new HealthReconciler(
        store, Duration.ofMillis(10), 2.0, Duration.ofMillis(100), 3, Duration.ofSeconds(5));
  }

  private static boolean hasAssignment(StateStore store, String deploymentName, int index) {
    return store.listAssignmentsFor(deploymentName).stream()
        .anyMatch(a -> a.instanceIndex() == index);
  }

  private static void heartbeat(StateStore store, String nodeId, boolean alive, String state) {
    heartbeatFor(store, nodeId, "orders-service", alive, state);
  }

  private static void heartbeatFor(
      StateStore store, String nodeId, String deploymentName, boolean alive, String state) {
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            nodeId,
            new ResourceUsageSnapshot(1000, 0, 1000, 0),
            List.of(new InstanceObservation(deploymentName, 0, ORDERS, state, alive, true))));
  }

  @Test
  void a_healthy_instance_is_left_alone() {
    StateStore store = new StateStore(tempDir.resolve("healthy"));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    heartbeat(store, "node-a", true, "ACTIVE");

    fastReconciler(store).reconcileOnce();

    assertTrue(hasAssignment(store, "orders-service", 0));
  }

  @Test
  void an_unmentioned_instance_is_left_alone_by_this_reconciler() {
    // No observation at all is ReplicaCountReconciler's concern, not this one.
    StateStore store = new StateStore(tempDir.resolve("unmentioned"));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat("node-a", new ResourceUsageSnapshot(1000, 0, 1000, 0), List.of()));

    fastReconciler(store).reconcileOnce();

    assertTrue(hasAssignment(store, "orders-service", 0));
  }

  @Test
  void an_unhealthy_instance_is_rescheduled_once_its_backoff_elapses() throws InterruptedException {
    StateStore store = new StateStore(tempDir.resolve("unhealthy"));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    heartbeat(store, "node-a", false, "ACTIVE");

    HealthReconciler reconciler = fastReconciler(store);
    reconciler
        .reconcileOnce(); // first failure observed: starts the (10ms) backoff, doesn't act yet
    assertTrue(hasAssignment(store, "orders-service", 0));

    Thread.sleep(30);
    reconciler.reconcileOnce(); // backoff elapsed: now it removes the assignment

    assertFalse(hasAssignment(store, "orders-service", 0));
  }

  @Test
  void a_failed_lifecycle_state_is_treated_as_unhealthy_even_if_alive_is_true()
      throws InterruptedException {
    StateStore store = new StateStore(tempDir.resolve("failed-state"));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    heartbeat(store, "node-a", true, "FAILED");

    HealthReconciler reconciler = fastReconciler(store);
    reconciler.reconcileOnce();
    Thread.sleep(30);
    reconciler.reconcileOnce();

    assertFalse(hasAssignment(store, "orders-service", 0));
  }

  @Test
  void readiness_alone_never_triggers_a_reschedule() throws InterruptedException {
    StateStore store = new StateStore(tempDir.resolve("not-ready"));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1000, 0, 1000, 0),
            List.of(new InstanceObservation("orders-service", 0, ORDERS, "ACTIVE", true, false))));

    HealthReconciler reconciler = fastReconciler(store);
    reconciler.reconcileOnce();
    Thread.sleep(30);
    reconciler.reconcileOnce();

    assertTrue(hasAssignment(store, "orders-service", 0));
  }

  @Test
  void recovering_before_backoff_elapses_cancels_the_pending_reschedule() {
    StateStore store = new StateStore(tempDir.resolve("recovers"));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    heartbeat(store, "node-a", false, "ACTIVE");

    HealthReconciler reconciler = fastReconciler(store);
    reconciler.reconcileOnce(); // failure observed, backoff pending

    heartbeat(store, "node-a", true, "ACTIVE"); // recovers before the backoff elapses
    reconciler.reconcileOnce();

    assertTrue(hasAssignment(store, "orders-service", 0));
  }

  @Test
  void repeated_failures_across_reschedules_eventually_exhaust_the_budget_and_stop_retrying()
      throws InterruptedException {
    StateStore store = new StateStore(tempDir.resolve("exhausted"));
    HealthReconciler reconciler = fastReconciler(store); // maxAttemptsPerWindow = 3

    for (int attempt = 0; attempt < 3; attempt++) {
      store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
      heartbeat(store, "node-a", false, "ACTIVE");
      reconciler.reconcileOnce();
      Thread.sleep(150); // exceed the capped 100ms backoff comfortably
      reconciler.reconcileOnce();
      assertFalse(
          hasAssignment(store, "orders-service", 0),
          "attempt " + attempt + " should have rescheduled");
    }

    // A 4th failure of the same instance index is beyond the 3-attempt budget: it must not be
    // rescheduled again, matching the module/worker-level tiers' own "give up" framing.
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    heartbeat(store, "node-a", false, "ACTIVE");
    reconciler.reconcileOnce();
    Thread.sleep(150);
    reconciler.reconcileOnce();

    assertTrue(
        hasAssignment(store, "orders-service", 0),
        "exhausted budget must stop further rescheduling");
  }

  @Test
  void backoff_state_survives_a_reconciler_reconstruction_against_the_same_store()
      throws InterruptedException {
    // Simulates a reconciler-leader failover: a fresh HealthReconciler instance, backed by the
    // same on-disk store, must resume the in-progress backoff rather than re-granting a full
    // restart budget to an already-flapping instance.
    Path dir = tempDir.resolve("survives-reconstruction");
    StateStore store = new StateStore(dir);
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    heartbeat(store, "node-a", false, "ACTIVE");

    fastReconciler(store).reconcileOnce(); // first failure recorded, pending retry not yet elapsed
    assertTrue(hasAssignment(store, "orders-service", 0));

    // Reopen the store (a real process restart would do this too) and construct a brand-new
    // reconciler against it -- no in-memory state carries over except what was persisted.
    StateStore reopened = new StateStore(dir);
    HealthReconciler resumed = fastReconciler(reopened);

    Thread.sleep(30);
    resumed.reconcileOnce(); // if the pending backoff wasn't resumed, this would start a new one

    assertFalse(
        hasAssignment(reopened, "orders-service", 0),
        "the resumed reconciler should have completed the backoff it didn't start itself");
  }

  @Test
  void converges_correctly_from_an_arbitrary_mix_of_persisted_backoff_states() {
    // A brand-new reconciler must handle every one of these correctly on its very first tick, with
    // no history of its own -- exactly what a reconciler-leader failover leaves behind.
    StateStore store = new StateStore(tempDir.resolve("store-arbitrary"));
    long now = Instant.now().toEpochMilli();

    // 1. Healthy: no persisted state, nothing should happen to it.
    store.putAssignment(new InstanceAssignment("healthy-service", 0, "node-healthy"));
    heartbeatFor(store, "node-healthy", "healthy-service", true, "ACTIVE");

    // 2. Unhealthy, already pending retry whose delay elapsed before this reconciler even existed
    // -- must be rescheduled on this very first tick, not treated as a fresh failure.
    store.putAssignment(new InstanceAssignment("overdue-service", 0, "node-overdue"));
    heartbeatFor(store, "node-overdue", "overdue-service", false, "ACTIVE");
    store.putReconcilerInstanceState(
        new ReconcilerInstanceState(
            "overdue-service",
            0,
            1,
            now - 60_000,
            now - 30_000,
            true,
            false,
            ReconcilerInstanceState.ABSENT));

    // 3. Unhealthy, never tracked before -- starts a fresh backoff, must not reschedule yet.
    store.putAssignment(new InstanceAssignment("fresh-unhealthy-service", 0, "node-fresh"));
    heartbeatFor(store, "node-fresh", "fresh-unhealthy-service", false, "ACTIVE");

    // 4. Already permanently failed under a previous leader -- must stay untouched forever, not
    // re-evaluated as if it were a fresh failure.
    store.putAssignment(new InstanceAssignment("exhausted-service", 0, "node-exhausted"));
    heartbeatFor(store, "node-exhausted", "exhausted-service", false, "ACTIVE");
    store.putReconcilerInstanceState(
        new ReconcilerInstanceState(
            "exhausted-service",
            0,
            5,
            now - 600_000,
            now - 500_000,
            false,
            true,
            ReconcilerInstanceState.ABSENT));

    // Production-scale backoff parameters (not fastReconciler's tiny ones): the point of case 3
    // is that its freshly-started backoff has *not* elapsed within this single tick.
    new HealthReconciler(store).reconcileOnce();

    assertTrue(hasAssignment(store, "healthy-service", 0));
    assertFalse(hasAssignment(store, "overdue-service", 0));
    assertTrue(hasAssignment(store, "fresh-unhealthy-service", 0));
    assertTrue(hasAssignment(store, "exhausted-service", 0));
    assertTrue(
        store.getReconcilerInstanceState("exhausted-service", 0).orElseThrow().permanentlyFailed());
  }
}
