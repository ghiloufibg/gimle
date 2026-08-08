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

class ReplicaCountReconcilerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static final ModuleId ORDERS =
      new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));

  private static boolean hasAssignment(StateStore store, String deploymentName, int index) {
    return store.listAssignmentsFor(deploymentName).stream()
        .anyMatch(a -> a.instanceIndex() == index);
  }

  /**
   * Zero grace period: "not confirmed" is acted on the very first tick, for tests that don't care
   * about the grace-period mechanic itself.
   */
  private static ReplicaCountReconciler immediateReconciler(
      StateStore store, Duration nodeDarkTimeout) {
    return new ReplicaCountReconciler(store, nodeDarkTimeout, Duration.ZERO);
  }

  @Test
  void an_assignment_confirmed_by_a_fresh_heartbeat_survives() {
    StateStore store = new StateStore(tempDir.resolve("confirmed"));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1000, 0, 1000, 0),
            List.of(new InstanceObservation("orders-service", 0, ORDERS, "ACTIVE", true, true))));

    immediateReconciler(store, Duration.ofSeconds(15)).reconcileOnce();

    assertTrue(hasAssignment(store, "orders-service", 0));
  }

  @Test
  void an_assignment_the_node_never_mentions_is_removed_once_the_grace_period_elapses() {
    StateStore store = new StateStore(tempDir.resolve("unmentioned"));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    // node-a heartbeats, but reports running nothing at all for this deployment
    store.putNodeHeartbeat(
        new NodeHeartbeat("node-a", new ResourceUsageSnapshot(1000, 0, 1000, 0), List.of()));

    immediateReconciler(store, Duration.ofSeconds(15)).reconcileOnce();

    assertFalse(hasAssignment(store, "orders-service", 0));
  }

  @Test
  void a_brand_new_assignment_not_yet_mentioned_survives_within_the_grace_period()
      throws InterruptedException {
    // The bug this grace period fixes: a freshly-placed assignment is, by construction, never in
    // any heartbeat sent before the owning agent has fetched and started it. Removing on the very
    // first "not mentioned yet" tick would undo the placement before the agent had a chance to act
    // on it -- this reproduces exactly that timing and asserts it survives.
    StateStore store = new StateStore(tempDir.resolve("grace-period-survives"));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat("node-a", new ResourceUsageSnapshot(1000, 0, 1000, 0), List.of()));

    ReplicaCountReconciler reconciler =
        new ReplicaCountReconciler(store, Duration.ofSeconds(15), Duration.ofMillis(200));
    reconciler.reconcileOnce();
    assertTrue(
        hasAssignment(store, "orders-service", 0),
        "should survive the first tick, within the grace period");

    Thread.sleep(50);
    reconciler.reconcileOnce();
    assertTrue(hasAssignment(store, "orders-service", 0), "still within the grace period");
  }

  @Test
  void an_unmentioned_assignment_is_removed_once_the_grace_period_elapses()
      throws InterruptedException {
    StateStore store = new StateStore(tempDir.resolve("grace-period-expires"));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat("node-a", new ResourceUsageSnapshot(1000, 0, 1000, 0), List.of()));

    ReplicaCountReconciler reconciler =
        new ReplicaCountReconciler(store, Duration.ofSeconds(15), Duration.ofMillis(50));
    reconciler.reconcileOnce(); // starts the grace period
    assertTrue(hasAssignment(store, "orders-service", 0));

    Thread.sleep(80);
    reconciler.reconcileOnce(); // grace period elapsed: now it removes it

    assertFalse(hasAssignment(store, "orders-service", 0));
  }

  @Test
  void becoming_confirmed_within_the_grace_period_cancels_the_pending_removal()
      throws InterruptedException {
    StateStore store = new StateStore(tempDir.resolve("recovers-within-grace"));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat("node-a", new ResourceUsageSnapshot(1000, 0, 1000, 0), List.of()));

    ReplicaCountReconciler reconciler =
        new ReplicaCountReconciler(store, Duration.ofSeconds(15), Duration.ofMillis(50));
    reconciler.reconcileOnce(); // starts the grace period

    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1000, 0, 1000, 0),
            List.of(new InstanceObservation("orders-service", 0, ORDERS, "ACTIVE", true, true))));

    Thread.sleep(80); // past what would have been the grace-period deadline
    reconciler.reconcileOnce();

    assertTrue(
        hasAssignment(store, "orders-service", 0),
        "the agent caught up before the grace period expired");
  }

  @Test
  void an_assignment_to_a_node_that_never_heartbeated_at_all_is_removed() {
    StateStore store = new StateStore(tempDir.resolve("never-heartbeated"));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-ghost"));

    immediateReconciler(store, Duration.ofSeconds(15)).reconcileOnce();

    assertFalse(hasAssignment(store, "orders-service", 0));
  }

  @Test
  void
      an_assignment_whose_nodes_heartbeat_has_gone_stale_is_removed_even_if_it_mentions_the_instance()
          throws InterruptedException {
    StateStore store = new StateStore(tempDir.resolve("stale-heartbeat"));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1000, 0, 1000, 0),
            List.of(new InstanceObservation("orders-service", 0, ORDERS, "ACTIVE", true, true))));

    Thread.sleep(60); // let the heartbeat age past a deliberately tiny dark timeout

    immediateReconciler(store, Duration.ofMillis(20)).reconcileOnce();

    assertFalse(hasAssignment(store, "orders-service", 0));
  }

  @Test
  void unrelated_assignments_on_a_healthy_node_are_left_alone() {
    StateStore store = new StateStore(tempDir.resolve("unrelated"));
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putAssignment(new InstanceAssignment("catalog-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1000, 0, 1000, 0),
            List.of(
                new InstanceObservation("orders-service", 0, ORDERS, "ACTIVE", true, true),
                new InstanceObservation("catalog-service", 0, ORDERS, "ACTIVE", true, true))));

    immediateReconciler(store, Duration.ofSeconds(15)).reconcileOnce();

    assertTrue(hasAssignment(store, "orders-service", 0));
    assertTrue(hasAssignment(store, "catalog-service", 0));
  }

  @Test
  void grace_period_state_survives_a_reconciler_reconstruction_against_the_same_store()
      throws InterruptedException {
    // Simulates a reconciler-leader failover: a fresh ReplicaCountReconciler instance, backed by
    // the same on-disk store, must resume the already-elapsing grace-period timer rather than
    // restarting it, which would delay a legitimate reschedule.
    Path dir = tempDir.resolve("survives-reconstruction");
    StateStore store = new StateStore(dir);
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat("node-a", new ResourceUsageSnapshot(1000, 0, 1000, 0), List.of()));

    ReplicaCountReconciler original =
        new ReplicaCountReconciler(store, Duration.ofSeconds(15), Duration.ofMillis(80));
    original.reconcileOnce(); // starts the grace period
    assertTrue(hasAssignment(store, "orders-service", 0));

    // Reopen the store (a real process restart would do this too) and construct a brand-new
    // reconciler against it -- no in-memory state carries over except what was persisted.
    StateStore reopened = new StateStore(dir);
    ReplicaCountReconciler resumed =
        new ReplicaCountReconciler(reopened, Duration.ofSeconds(15), Duration.ofMillis(80));

    Thread.sleep(100); // past the original grace-period deadline, not a fresh one
    resumed.reconcileOnce();

    assertFalse(
        hasAssignment(reopened, "orders-service", 0),
        "the resumed reconciler should have completed the grace period it didn't start itself");
  }

  @Test
  void converges_correctly_from_an_arbitrary_mix_of_persisted_grace_period_state() {
    // A brand-new reconciler must handle every one of these correctly on its very first tick, with
    // no history of its own -- exactly what a reconciler-leader failover leaves behind.
    StateStore store = new StateStore(tempDir.resolve("store-arbitrary"));
    long now = Instant.now().toEpochMilli();

    // 1. Confirmed by a fresh heartbeat -- must survive untouched.
    store.putAssignment(new InstanceAssignment("confirmed-service", 0, "node-a"));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1000, 0, 1000, 0),
            List.of(
                new InstanceObservation("confirmed-service", 0, ORDERS, "ACTIVE", true, true))));

    // 2. Not confirmed, grace-period timer already elapsed before this reconciler even existed --
    // must be removed on this very first tick, not treated as freshly missing.
    store.putAssignment(new InstanceAssignment("overdue-service", 0, "node-b"));
    store.putNodeHeartbeat(
        new NodeHeartbeat("node-b", new ResourceUsageSnapshot(1000, 0, 1000, 0), List.of()));
    store.putReconcilerInstanceState(
        new ReconcilerInstanceState(
            "overdue-service",
            0,
            0,
            ReconcilerInstanceState.ABSENT,
            ReconcilerInstanceState.ABSENT,
            false,
            false,
            now - 60_000));

    // 3. Not confirmed, no persisted timer yet -- the grace period starts now, so it must not be
    // removed on this same first tick.
    store.putAssignment(new InstanceAssignment("fresh-missing-service", 0, "node-c"));
    store.putNodeHeartbeat(
        new NodeHeartbeat("node-c", new ResourceUsageSnapshot(1000, 0, 1000, 0), List.of()));

    new ReplicaCountReconciler(store, Duration.ofSeconds(15), Duration.ofSeconds(30))
        .reconcileOnce();

    assertTrue(hasAssignment(store, "confirmed-service", 0));
    assertFalse(hasAssignment(store, "overdue-service", 0));
    assertTrue(hasAssignment(store, "fresh-missing-service", 0));
  }
}
