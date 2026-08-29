package com.gimle.controlplane.autoscale;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.mimir.manifest.AutoscalePolicy;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.StateStore;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * The autoscaling formula: average observed CPU utilization against the module's own {@code
 * resourceRequest.cpuMillicores()} (10m in {@code TestModuleBuilder.minimalDescriptor}), adjusted
 * by exactly one replica per tick toward the computed ideal and clamped to {@code [minReplicas,
 * maxReplicas]}. It's further extended with three additional, independently-optional signals
 * (request rate, error rate, queue depth) -- whichever configured signal proposes the highest ideal
 * replica count wins, matching Kubernetes' own multi-metric HPA behavior.
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
   * of the fixture's 10m request, with every non-CPU scaling signal at zero.
   */
  private static void twoReadyInstancesAt(
      StateStore store, String deploymentName, ModuleId moduleId, long cpuMillicoresUsed) {
    twoReadyInstancesAt(store, deploymentName, moduleId, cpuMillicoresUsed, 0.0, 0.0, 0);
  }

  /**
   * Two ready instances (indices 0/1) on {@code node-a}, each reporting the given CPU usage plus
   * request rate, error rate, and queue depth -- lets a test drive a non-CPU signal independently
   * of the CPU one.
   */
  private static void twoReadyInstancesAt(
      StateStore store,
      String deploymentName,
      ModuleId moduleId,
      long cpuMillicoresUsed,
      double requestRatePerSecond,
      double errorRatePerSecond,
      int queueDepth) {
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
                    requestRatePerSecond,
                    queueDepth,
                    cpuMillicoresUsed,
                    0L,
                    errorRatePerSecond),
                new InstanceObservation(
                    deploymentName,
                    1,
                    moduleId,
                    "ACTIVE",
                    true,
                    true,
                    requestRatePerSecond,
                    queueDepth,
                    cpuMillicoresUsed,
                    0L,
                    errorRatePerSecond))));
  }

  @Test
  void scales_up_by_one_replica_per_tick_under_sustained_high_utilization() {
    StateStore store = new StateStore();
    Path jar = buildFixtureJar();
    AutoscalePolicy policy = new AutoscalePolicy(1, 5, 50);
    DeploymentSpec spec = deployment("orders-service", 2, jar, policy);
    store.putDeployment(spec);
    // 10m used out of a 10m request: 100% utilization, double the 50% target.
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 10L);

    AutoscaleReconciler reconciler = new AutoscaleReconciler(store);
    reconciler.reconcileOnce();
    assertEquals(3, store.getEffectiveReplicas(Optional.empty(), "orders-service").orElseThrow());

    reconciler.reconcileOnce();
    assertEquals(4, store.getEffectiveReplicas(Optional.empty(), "orders-service").orElseThrow());

    reconciler.reconcileOnce();
    assertEquals(5, store.getEffectiveReplicas(Optional.empty(), "orders-service").orElseThrow());
  }

  @Test
  void never_exceeds_max_replicas() {
    StateStore store = new StateStore();
    Path jar = buildFixtureJar();
    AutoscalePolicy policy = new AutoscalePolicy(1, 5, 50);
    DeploymentSpec spec = deployment("orders-service", 2, jar, policy);
    store.putDeployment(spec);
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 10L);

    AutoscaleReconciler reconciler = new AutoscaleReconciler(store);
    for (int i = 0; i < 10; i++) {
      reconciler.reconcileOnce();
    }

    assertEquals(5, store.getEffectiveReplicas(Optional.empty(), "orders-service").orElseThrow());
  }

  @Test
  void scales_down_by_one_replica_per_tick_under_sustained_low_utilization() {
    StateStore store = new StateStore();
    Path jar = buildFixtureJar();
    AutoscalePolicy policy = new AutoscalePolicy(1, 5, 50);
    DeploymentSpec spec = deployment("orders-service", 5, jar, policy);
    store.putDeployment(spec);
    store.putEffectiveReplicas(Optional.empty(), "orders-service", 5);
    // 1m used out of a 10m request: 10% utilization, a fifth of the 50% target.
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 1L);

    AutoscaleReconciler reconciler = new AutoscaleReconciler(store);
    reconciler.reconcileOnce();
    assertEquals(4, store.getEffectiveReplicas(Optional.empty(), "orders-service").orElseThrow());

    reconciler.reconcileOnce();
    assertEquals(3, store.getEffectiveReplicas(Optional.empty(), "orders-service").orElseThrow());
  }

  @Test
  void never_goes_below_min_replicas() {
    StateStore store = new StateStore();
    Path jar = buildFixtureJar();
    AutoscalePolicy policy = new AutoscalePolicy(2, 5, 50);
    DeploymentSpec spec = deployment("orders-service", 5, jar, policy);
    store.putDeployment(spec);
    store.putEffectiveReplicas(Optional.empty(), "orders-service", 5);
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 1L);

    AutoscaleReconciler reconciler = new AutoscaleReconciler(store);
    for (int i = 0; i < 10; i++) {
      reconciler.reconcileOnce();
    }

    assertEquals(2, store.getEffectiveReplicas(Optional.empty(), "orders-service").orElseThrow());
  }

  @Test
  void holds_the_current_count_when_nothing_ready_reports_yet() {
    StateStore store = new StateStore();
    Path jar = buildFixtureJar();
    AutoscalePolicy policy = new AutoscalePolicy(1, 5, 50);
    DeploymentSpec spec = deployment("orders-service", 3, jar, policy);
    store.putDeployment(spec);
    // no assignments, no heartbeats at all

    AutoscaleReconciler reconciler = new AutoscaleReconciler(store);
    reconciler.reconcileOnce();
    reconciler.reconcileOnce();

    assertEquals(3, store.getEffectiveReplicas(Optional.empty(), "orders-service").orElseThrow());
  }

  @Test
  void deployments_without_a_policy_are_left_untouched() {
    StateStore store = new StateStore();
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

    assertEquals(Optional.empty(), store.getEffectiveReplicas(Optional.empty(), "orders-service"));
  }

  @Test
  void does_not_repropose_an_unchanged_effective_replica_count_from_the_no_signal_branch() {
    StateStore store = new StateStore();
    Path jar = buildFixtureJar();
    AutoscalePolicy policy = new AutoscalePolicy(1, 5, 50);
    DeploymentSpec spec = deployment("orders-service", 3, jar, policy);
    store.putDeployment(spec);
    // No assignments/heartbeats: the "no ready observations, hold the current count" early-return
    // branch -- one of the three that used to re-propose an unchanged value every single tick.
    AtomicInteger proposals = new AtomicInteger();
    MutationSink countingSink =
        mutation -> {
          proposals.incrementAndGet();
          return mutation.applyTo(store);
        };
    AutoscaleReconciler reconciler = new AutoscaleReconciler(store, countingSink);

    reconciler.reconcileOnce();
    int afterFirstTick = proposals.get();
    reconciler.reconcileOnce();
    reconciler.reconcileOnce();

    assertEquals(1, afterFirstTick, "expected exactly one proposal to seed the store on tick one");
    assertEquals(
        afterFirstTick,
        proposals.get(),
        "repeated ticks against an unchanged store must not re-propose");
  }

  @Test
  void does_not_repropose_once_scaling_has_converged_and_clamped_at_the_ceiling() {
    StateStore store = new StateStore();
    Path jar = buildFixtureJar();
    AutoscalePolicy policy = new AutoscalePolicy(1, 5, 50);
    DeploymentSpec spec = deployment("orders-service", 2, jar, policy);
    store.putDeployment(spec);
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 10L);
    AtomicInteger proposals = new AtomicInteger();
    MutationSink countingSink =
        mutation -> {
          proposals.incrementAndGet();
          return mutation.applyTo(store);
        };
    AutoscaleReconciler reconciler = new AutoscaleReconciler(store, countingSink);

    for (int i = 0; i < 5; i++) {
      reconciler.reconcileOnce(); // 2 -> 3 -> 4 -> 5, then clamped at 5 for the remaining ticks
    }
    int proposalsToReachCeiling = proposals.get();
    for (int i = 0; i < 5; i++) {
      reconciler.reconcileOnce(); // steady state: already at the 5-replica ceiling
    }

    assertEquals(5, store.getEffectiveReplicas(Optional.empty(), "orders-service").orElseThrow());
    assertEquals(3, proposalsToReachCeiling, "expected exactly the 3 real transitions 2->3->4->5");
    assertEquals(
        proposalsToReachCeiling,
        proposals.get(),
        "ticks after convergence must not re-propose the already-correct ceiling value");
  }

  @Test
  void converges_correctly_from_an_arbitrary_out_of_range_persisted_replica_count() {
    // Simulates effectiveReplicas left behind by a policy edit (a lower maxReplicas, or a higher
    // minReplicas) applied after the value was last written -- a fresh reconciler has no history
    // of how it got there, only what's persisted now, and must still converge to the current
    // policy's bounds on this very first tick.
    StateStore store = new StateStore();
    AutoscalePolicy policy = new AutoscalePolicy(1, 5, 50);

    Path jarHigh = buildFixtureJar();
    DeploymentSpec highSpec = deployment("above-max-service", 2, jarHigh, policy);
    store.putDeployment(highSpec);
    // already above maxReplicas=5
    store.putEffectiveReplicas(Optional.empty(), "above-max-service", 9);
    twoReadyInstancesAt(store, "above-max-service", highSpec.moduleId(), 10L); // 100% util

    Path jarLow = buildFixtureJar();
    DeploymentSpec lowSpec = deployment("below-min-service", 2, jarLow, policy);
    store.putDeployment(lowSpec);
    // already below minReplicas=1
    store.putEffectiveReplicas(Optional.empty(), "below-min-service", -3);
    twoReadyInstancesAt(store, "below-min-service", lowSpec.moduleId(), 1L); // 10% util

    new AutoscaleReconciler(store).reconcileOnce();

    assertEquals(
        5, store.getEffectiveReplicas(Optional.empty(), "above-max-service").orElseThrow());
    assertEquals(
        1, store.getEffectiveReplicas(Optional.empty(), "below-min-service").orElseThrow());
  }

  @Test
  void queue_depth_alone_can_drive_scale_up_when_cpu_is_under_target() {
    StateStore store = new StateStore();
    Path jar = buildFixtureJar();
    // CPU target 50%, queue-depth target 2: CPU alone (10% util) would want to hold/scale down,
    // but every ready instance reports a queue depth of 10 -- 5x its target.
    AutoscalePolicy policy =
        new AutoscalePolicy(
            1, 5, 50, OptionalDouble.empty(), OptionalDouble.empty(), OptionalInt.of(2));
    DeploymentSpec spec = deployment("orders-service", 2, jar, policy);
    store.putDeployment(spec);
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 1L, 0.0, 0.0, 10);

    new AutoscaleReconciler(store).reconcileOnce();

    assertEquals(
        3,
        store.getEffectiveReplicas(Optional.empty(), "orders-service").orElseThrow(),
        "queue depth alone should drive a scale-up even though CPU utilization is well under"
            + " target");
  }

  @Test
  void request_rate_alone_can_drive_scale_up_when_cpu_is_under_target() {
    StateStore store = new StateStore();
    Path jar = buildFixtureJar();
    AutoscalePolicy policy =
        new AutoscalePolicy(
            1, 5, 50, OptionalDouble.of(10.0), OptionalDouble.empty(), OptionalInt.empty());
    DeploymentSpec spec = deployment("orders-service", 2, jar, policy);
    store.putDeployment(spec);
    // 10% CPU util (well under the 50% target) but 50 req/s per instance against a 10 req/s
    // target -- request rate alone should drive the scale-up.
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 1L, 50.0, 0.0, 0);

    new AutoscaleReconciler(store).reconcileOnce();

    assertEquals(3, store.getEffectiveReplicas(Optional.empty(), "orders-service").orElseThrow());
  }

  @Test
  void error_rate_alone_can_drive_scale_up_when_cpu_is_under_target() {
    StateStore store = new StateStore();
    Path jar = buildFixtureJar();
    AutoscalePolicy policy =
        new AutoscalePolicy(
            1, 5, 50, OptionalDouble.empty(), OptionalDouble.of(5.0), OptionalInt.empty());
    DeploymentSpec spec = deployment("orders-service", 2, jar, policy);
    store.putDeployment(spec);
    // 10% CPU util (well under target) but a 50% error rate (50 errors/s out of 100 req/s)
    // against a 5% target -- error rate alone should drive the scale-up.
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 1L, 100.0, 50.0, 0);

    new AutoscaleReconciler(store).reconcileOnce();

    assertEquals(3, store.getEffectiveReplicas(Optional.empty(), "orders-service").orElseThrow());
  }

  @Test
  void a_cpu_only_policy_ignores_non_cpu_signals_even_when_present_in_observations() {
    StateStore store = new StateStore();
    Path jar = buildFixtureJar();
    // The pre-Part-C 3-arg constructor: no request-rate/error-rate/queue-depth targets configured.
    AutoscalePolicy policy = new AutoscalePolicy(1, 5, 50);
    DeploymentSpec spec = deployment("orders-service", 2, jar, policy);
    store.putDeployment(spec);
    // Wildly high non-CPU signals that would obviously drive scale-up if evaluated, alongside a
    // low CPU utilization that wants to scale down.
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 1L, 1000.0, 1000.0, 1000);

    new AutoscaleReconciler(store).reconcileOnce();

    assertEquals(
        1,
        store.getEffectiveReplicas(Optional.empty(), "orders-service").orElseThrow(),
        "an unconfigured signal must never influence the scaling decision");
  }

  @Test
  void weighted_mode_blends_two_signals_instead_of_taking_the_max() {
    StateStore store = new StateStore();
    Path jar = buildFixtureJar();
    // CPU well under target (10% vs 50%, ratio 0.2) but request rate just over its own target (11
    // vs 10 req/s, ratio 1.1). Under WORST_SIGNAL, max(0.2, 1.1) wins and drives a scale-up (see
    // request_rate_alone_can_drive_scale_up_when_cpu_is_under_target above) -- but weighting CPU
    // three times as heavily as request rate blends the two into (0.2*3 + 1.1*1)/4 = 0.425, well
    // under 1.0, which drives a scale-*down* instead. Proves WEIGHTED genuinely combines signals
    // rather than aliasing max().
    AutoscalePolicy policy =
        new AutoscalePolicy(
            1,
            5,
            50,
            OptionalDouble.of(10.0),
            OptionalDouble.empty(),
            OptionalInt.empty(),
            AutoscalePolicy.CombinationMode.WEIGHTED,
            OptionalDouble.of(3.0),
            OptionalDouble.of(1.0),
            OptionalDouble.empty(),
            OptionalDouble.empty());
    DeploymentSpec spec = deployment("orders-service", 2, jar, policy);
    store.putDeployment(spec);
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 1L, 11.0, 0.0, 0);

    new AutoscaleReconciler(store).reconcileOnce();

    assertEquals(
        1,
        store.getEffectiveReplicas(Optional.empty(), "orders-service").orElseThrow(),
        "heavily weighting CPU should pull the blended decision below what request rate alone"
            + " would have driven under worst-signal-wins");
  }

  @Test
  void weighted_mode_with_no_weights_configured_behaves_like_an_unweighted_average() {
    StateStore store = new StateStore();
    Path jar = buildFixtureJar();
    // CPU ratio 0.2 (10% vs 50% target), request-rate ratio 1.4 (14 vs 10 req/s target) -- neither
    // weight configured, so both default to 1.0 and the blended ratio is a plain average: (0.2 +
    // 1.4) / 2 = 0.8, under 1.0, which holds the current count. WORST_SIGNAL on these same
    // observations would take max(0.2, 1.4) = 1.4 and scale up instead -- proving the default
    // (unweighted) WEIGHTED mode is a genuinely distinct code path from WORST_SIGNAL, not an alias
    // that happens to produce the same answer when no explicit weights are given.
    AutoscalePolicy policy =
        new AutoscalePolicy(
            1,
            5,
            50,
            OptionalDouble.of(10.0),
            OptionalDouble.empty(),
            OptionalInt.empty(),
            AutoscalePolicy.CombinationMode.WEIGHTED,
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty());
    DeploymentSpec spec = deployment("orders-service", 2, jar, policy);
    store.putDeployment(spec);
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 1L, 14.0, 0.0, 0);

    new AutoscaleReconciler(store).reconcileOnce();

    assertEquals(2, store.getEffectiveReplicas(Optional.empty(), "orders-service").orElseThrow());
  }

  @Test
  void weighted_mode_converges_to_the_clamped_ceiling_from_an_arbitrary_starting_replica_count() {
    // Same "no history, only what's persisted now" scenario as
    // converges_correctly_from_an_arbitrary_out_of_range_persisted_replica_count above, but for
    // WEIGHTED mode blending two signals (not WORST_SIGNAL, and not a single trivial signal) --
    // CLAUDE.md's reconciler-convergence requirement applies to the new combination mode too.
    StateStore store = new StateStore();
    Path jar = buildFixtureJar();
    // CPU ratio 0.2 (10% vs 50% target, weight 1.0 default), queue-depth ratio 3.0 (15 vs target 5,
    // weight 2.0) -- blended ratio (0.2*1 + 3.0*2)/(1+2) ~= 2.07, comfortably above 1.0, so this
    // should climb to the maxReplicas ceiling regardless of where it started.
    AutoscalePolicy policy =
        new AutoscalePolicy(
            1,
            5,
            50,
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalInt.of(5),
            AutoscalePolicy.CombinationMode.WEIGHTED,
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.of(2.0));
    DeploymentSpec spec = deployment("orders-service", 2, jar, policy);
    store.putDeployment(spec);
    // already below minReplicas=1
    store.putEffectiveReplicas(Optional.empty(), "orders-service", -3);
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 1L, 0.0, 0.0, 15);

    AutoscaleReconciler reconciler = new AutoscaleReconciler(store);
    for (int i = 0; i < 15; i++) {
      reconciler.reconcileOnce();
    }

    assertEquals(5, store.getEffectiveReplicas(Optional.empty(), "orders-service").orElseThrow());
  }
}
