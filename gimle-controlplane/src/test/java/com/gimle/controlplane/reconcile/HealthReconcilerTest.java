package com.gimle.controlplane.reconcile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.store.InstanceAssignment;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.jetbrains.jetCheck.PropertyChecker;
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

  /** One arbitrary assignment's observation shape, per {@link HealthReconciler#isHealthy}. */
  private enum ObservationKind {
    NONE,
    HEALTHY,
    UNHEALTHY_NOT_ALIVE,
    UNHEALTHY_FAILED_STATE,
    UNHEALTHY_BOTH
  }

  /**
   * One arbitrary assignment's scenario. {@code ready} is generated but must never affect the
   * outcome -- {@code readiness_alone_never_triggers_a_reschedule} already covers this as a single
   * hand-picked example; folding it into the property's own entropy generalizes that same guarantee
   * across every {@code ObservationKind} rather than just one.
   */
  private record HealthScenario(ObservationKind kind, boolean ready) {
    boolean isUnhealthy() {
      return kind != ObservationKind.NONE && kind != ObservationKind.HEALTHY;
    }
  }

  private static final Generator<HealthScenario> SINGLE_SCENARIO =
      Generator.from(
          env ->
              new HealthScenario(
                  env.generate(Generator.sampledFrom(ObservationKind.values())),
                  env.generate(Generator.booleans())));

  // Same reasoning as ReplicaCountReconcilerTest's own property: a single assignment's domain (5
  // kinds x 2 booleans = 10 combinations) is too small for jetCheck's default 100-iteration
  // diversity requirement. A list of 1-3 independent assignments supplies real entropy and tests
  // that one reconcile pass gets every assignment's decision right independently.
  private static final Generator<List<HealthScenario>> HEALTH_SCENARIOS =
      Generator.listsOf(IntDistribution.uniform(1, 3), SINGLE_SCENARIO);

  private static void putObservationIfAny(
      StateStore store, String nodeId, String deploymentName, HealthScenario scenario) {
    List<InstanceObservation> instances =
        switch (scenario.kind()) {
          case NONE -> List.of();
          case HEALTHY ->
              List.of(
                  new InstanceObservation(
                      deploymentName, 0, ORDERS, "ACTIVE", true, scenario.ready()));
          case UNHEALTHY_NOT_ALIVE ->
              List.of(
                  new InstanceObservation(
                      deploymentName, 0, ORDERS, "ACTIVE", false, scenario.ready()));
          case UNHEALTHY_FAILED_STATE ->
              List.of(
                  new InstanceObservation(
                      deploymentName, 0, ORDERS, "FAILED", true, scenario.ready()));
          case UNHEALTHY_BOTH ->
              List.of(
                  new InstanceObservation(
                      deploymentName, 0, ORDERS, "FAILED", false, scenario.ready()));
        };
    store.putNodeHeartbeat(
        new NodeHeartbeat(nodeId, new ResourceUsageSnapshot(1000, 0, 1000, 0), instances));
  }

  /**
   * CLAUDE.md's own "convergence tests from arbitrary starting states" requirement. Unlike {@link
   * ReplicaCountReconciler}'s property, this one genuinely needs two {@code reconcileOnce()} calls,
   * not one (design doc §1.2's own correction): {@code RestartTracker}'s constructor rejects a zero
   * {@code initialDelay} outright, so {@code delayUntilNextAttempt} can never be {@code <= 0} on
   * the very first recorded failure -- a single-call property here could only ever observe the
   * healthy-survives half, never a removal. Uses {@code fastReconciler}'s own tiny backoff
   * parameters (10ms initial delay) and the same sleep-past-it shape the existing hand-picked
   * examples above already use, just generalized across many arbitrary observation combinations at
   * once instead of one.
   */
  @Test
  void a_fresh_reconciler_matches_the_documented_health_decision_after_two_ticks() {
    AtomicInteger trial = new AtomicInteger();
    PropertyChecker.forAll(
        HEALTH_SCENARIOS,
        scenarios -> {
          StateStore store =
              new StateStore(tempDir.resolve("prop-health-" + trial.incrementAndGet()));
          for (int i = 0; i < scenarios.size(); i++) {
            store.putAssignment(new InstanceAssignment("deployment-" + i, 0, "node-" + i));
            putObservationIfAny(store, "node-" + i, "deployment-" + i, scenarios.get(i));
          }

          HealthReconciler reconciler = fastReconciler(store);
          reconciler.reconcileOnce(); // first failure observed: starts the (10ms) backoff
          try {
            Thread.sleep(30); // comfortably past the 10ms initial delay
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
          reconciler.reconcileOnce(); // backoff elapsed: now it removes unhealthy assignments

          for (int i = 0; i < scenarios.size(); i++) {
            boolean removed = !hasAssignment(store, "deployment-" + i, 0);
            if (removed != scenarios.get(i).isUnhealthy()) {
              return false;
            }
          }
          return true;
        });
  }
}
