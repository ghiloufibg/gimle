package com.gimle.controlplane.reconcile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.StateStore;
import java.nio.file.Path;
import java.time.Duration;
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
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            nodeId,
            new ResourceUsageSnapshot(1000, 0, 1000, 0),
            List.of(new InstanceObservation("orders-service", 0, ORDERS, state, alive, true))));
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
}
