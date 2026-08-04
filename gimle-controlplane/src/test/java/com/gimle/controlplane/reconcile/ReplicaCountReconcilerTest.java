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

  /** How one assignment's node heartbeat, if any, relates to a fixed {@code nodeDarkTimeout}. */
  private enum HeartbeatKind {
    NONE,
    FRESH,
    STALE
  }

  // Wide margins, deliberately: a tighter dark timeout (originally 20ms/60ms) proved genuinely
  // flaky under concurrent Surefire test execution -- the gap between writing a FRESH heartbeat
  // and calling reconcileOnce() (after a loop over the rest of the batch) occasionally exceeded a
  // 20ms timeout under scheduling contention, intermittently making a FRESH entry look stale.
  private static final Duration FIXED_DARK_TIMEOUT = Duration.ofMillis(200);
  private static final long STALE_SLEEP_MILLIS = 500;

  /**
   * One arbitrary assignment's scenario: whether its own node has heartbeated at all, whether that
   * heartbeat (if any) is fresh or has gone stale past the fixed dark timeout, and whether it
   * mentions the assignment. {@code expectRemoved} mirrors {@code isConfirmedByItsNode}'s own
   * decision order directly: a dark node overrides mention entirely (checked first, per {@code
   * isConfirmedByItsNode}'s {@code .filter(observed -> !nodeIsDark(...))} short-circuit), so a
   * stale-but-mentioning heartbeat still counts as removed -- the same case {@code
   * an_assignment_whose_nodes_heartbeat_has_gone_stale_is_removed_even_if_it_mentions_the_instance}
   * already covers as a hand-picked example.
   */
  private record ReplicaScenario(HeartbeatKind heartbeatKind, boolean mentions) {
    boolean expectRemoved() {
      return switch (heartbeatKind) {
        case NONE, STALE -> true;
        case FRESH -> !mentions;
      };
    }
  }

  private static final Generator<ReplicaScenario> SINGLE_SCENARIO =
      Generator.from(
          env ->
              new ReplicaScenario(
                  env.generate(Generator.sampledFrom(HeartbeatKind.values())),
                  env.generate(Generator.booleans())));

  // A single-assignment domain has only 6 distinct combinations (3 HeartbeatKind x 2 booleans) --
  // too few for jetCheck's default 100-iteration "sufficiently different values" requirement
  // (verified empirically: it throws CannotSatisfyCondition past ~6 iterations). A list of 1-3
  // independent assignments, each on its own node, both supplies real entropy (6+36+216 raw
  // combinations before even counting list-length choice) and exercises something the single-
  // assignment version couldn't: that one reconcile pass gets every assignment's decision right
  // independently in the same tick, not just one at a time.
  private static final Generator<List<ReplicaScenario>> REPLICA_SCENARIOS =
      Generator.listsOf(IntDistribution.uniform(1, 3), SINGLE_SCENARIO);

  private static NodeHeartbeat heartbeatFor(
      String nodeId, String deploymentName, boolean mentions) {
    List<InstanceObservation> instances =
        mentions
            ? List.of(new InstanceObservation(deploymentName, 0, ORDERS, "ACTIVE", true, true))
            : List.of();
    return new NodeHeartbeat(nodeId, new ResourceUsageSnapshot(1000, 0, 1000, 0), instances);
  }

  /**
   * CLAUDE.md's own "convergence tests from arbitrary starting states" requirement, scoped to what
   * a fresh reconciler's first tick can actually decide (design doc §1.2): {@code
   * firstSeenMissingAt} is reconciler-internal, invisible to and unsettable via {@code StateStore},
   * so a freshly-constructed instance always starts it empty regardless of what's generated here --
   * this property covers the mention/dark-timeout decision, not the grace-period bookkeeping, which
   * the existing hand-picked {@code Thread.sleep}-driven examples above already cover. Uses the
   * 3-arg constructor with an explicit {@code Duration.ZERO} grace period (immediateReconciler),
   * not the 2-arg one -- that one defaults the grace period to {@code nodeDarkTimeout} itself,
   * which would make first-tick removal never decidable at all.
   *
   * <p>Iteration count deliberately capped well below jetCheck's default 100: any {@code STALE}
   * entry costs a real {@link #STALE_SLEEP_MILLIS} sleep, and the default count made this single
   * test take 30+ seconds. 25 iterations against this scenario's combinatorial space (up to 5^3 raw
   * combinations before list-length choice) still covers far more than the ~8 hand-picked examples
   * above, at a fraction of the runtime.
   */
  @Test
  void a_fresh_reconciler_never_removes_an_assignment_confirmed_by_a_current_heartbeat() {
    AtomicInteger trial = new AtomicInteger();
    PropertyChecker.customized()
        .withIterationCount(25)
        .forAll(
            REPLICA_SCENARIOS,
            scenarios -> {
              StateStore store =
                  new StateStore(tempDir.resolve("prop-replica-" + trial.incrementAndGet()));
              for (int i = 0; i < scenarios.size(); i++) {
                store.putAssignment(new InstanceAssignment("deployment-" + i, 0, "node-" + i));
              }
              // STALE heartbeats must be written and aged *before* FRESH ones are written, so a
              // shared sleep for the STALE entries in this batch can't also stale-ify a FRESH entry
              // that happens to land in the same trial.
              boolean anyStale =
                  scenarios.stream().anyMatch(s -> s.heartbeatKind() == HeartbeatKind.STALE);
              for (int i = 0; i < scenarios.size(); i++) {
                if (scenarios.get(i).heartbeatKind() == HeartbeatKind.STALE) {
                  store.putNodeHeartbeat(
                      heartbeatFor("node-" + i, "deployment-" + i, scenarios.get(i).mentions()));
                }
              }
              if (anyStale) {
                try {
                  Thread.sleep(STALE_SLEEP_MILLIS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new RuntimeException(e);
                }
              }
              for (int i = 0; i < scenarios.size(); i++) {
                if (scenarios.get(i).heartbeatKind() == HeartbeatKind.FRESH) {
                  store.putNodeHeartbeat(
                      heartbeatFor("node-" + i, "deployment-" + i, scenarios.get(i).mentions()));
                }
              }

              immediateReconciler(store, FIXED_DARK_TIMEOUT).reconcileOnce();

              for (int i = 0; i < scenarios.size(); i++) {
                boolean removed = !hasAssignment(store, "deployment-" + i, 0);
                if (removed != scenarios.get(i).expectRemoved()) {
                  return false;
                }
              }
              return true;
            });
  }
}
