package com.gimle.controlplane.autoscale;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.controlplane.manifest.DeploymentSpec;
import com.gimle.controlplane.manifest.PlacementConstraints;
import com.gimle.controlplane.raft.MutationSink;
import com.gimle.controlplane.store.InstanceAssignment;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.jetbrains.jetCheck.PropertyChecker;
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

  @Test
  void does_not_repropose_an_unchanged_effective_replica_count_from_the_no_signal_branch() {
    StateStore store = new StateStore(tempDir.resolve("store-no-op-writes-no-signal"));
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
          mutation.applyTo(store);
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
    StateStore store = new StateStore(tempDir.resolve("store-no-op-writes-converged"));
    Path jar = buildFixtureJar();
    AutoscalePolicy policy = new AutoscalePolicy(1, 5, 50);
    DeploymentSpec spec = deployment("orders-service", 2, jar, policy);
    store.putDeployment(spec);
    twoReadyInstancesAt(store, "orders-service", spec.moduleId(), 10L);
    AtomicInteger proposals = new AtomicInteger();
    MutationSink countingSink =
        mutation -> {
          proposals.incrementAndGet();
          mutation.applyTo(store);
        };
    AutoscaleReconciler reconciler = new AutoscaleReconciler(store, countingSink);

    for (int i = 0; i < 5; i++) {
      reconciler.reconcileOnce(); // 2 -> 3 -> 4 -> 5, then clamped at 5 for the remaining ticks
    }
    int proposalsToReachCeiling = proposals.get();
    for (int i = 0; i < 5; i++) {
      reconciler.reconcileOnce(); // steady state: already at the 5-replica ceiling
    }

    assertEquals(5, store.getEffectiveReplicas("orders-service").orElseThrow());
    assertEquals(3, proposalsToReachCeiling, "expected exactly the 3 real transitions 2->3->4->5");
    assertEquals(
        proposalsToReachCeiling,
        proposals.get(),
        "ticks after convergence must not re-propose the already-correct ceiling value");
  }

  /**
   * One arbitrary scenario for the property below: a policy, a starting effective replica count
   * already within that policy's bounds (the realistic case -- this reconciler is the only writer
   * of {@code effectiveReplicas}, and always clamps before writing, so a value outside the current
   * policy's bounds would only arise from an external edit or a since-narrowed policy; out of scope
   * for this property, not a gap in it), and a variable number of ready instances each reporting
   * their own CPU usage.
   */
  private record AutoscaleScenario(
      int minReplicas,
      int maxReplicas,
      int targetCpuUtilizationPercent,
      int currentEffective,
      List<Long> readyCpuMillicoresUsed) {}

  private static final Generator<AutoscaleScenario> AUTOSCALE_SCENARIOS =
      Generator.from(
          env -> {
            int minReplicas = env.generate(Generator.integers(0, 5));
            int maxReplicas = minReplicas + env.generate(Generator.integers(0, 10));
            int targetCpuUtilizationPercent = env.generate(Generator.integers(1, 200));
            int currentEffective = env.generate(Generator.integers(minReplicas, maxReplicas));
            List<Long> readyCpuMillicoresUsed =
                env
                    .generate(
                        Generator.listsOf(IntDistribution.uniform(1, 4), Generator.integers(0, 50)))
                    .stream()
                    .map(Long::valueOf)
                    .toList();
            return new AutoscaleScenario(
                minReplicas,
                maxReplicas,
                targetCpuUtilizationPercent,
                currentEffective,
                readyCpuMillicoresUsed);
          });

  /**
   * A variable-length generalization of {@link #twoReadyInstancesAt}: one ready instance per entry
   * in {@code cpuMillicoresUsedPerInstance}, all on {@code node-a}.
   */
  private static void readyInstancesAt(
      StateStore store,
      String deploymentName,
      ModuleId moduleId,
      List<Long> cpuMillicoresUsedPerInstance) {
    List<InstanceObservation> observations = new ArrayList<>();
    for (int i = 0; i < cpuMillicoresUsedPerInstance.size(); i++) {
      store.putAssignment(new InstanceAssignment(deploymentName, i, "node-a", moduleId, ""));
      observations.add(
          new InstanceObservation(
              deploymentName,
              i,
              moduleId,
              "ACTIVE",
              true,
              true,
              0.0,
              0,
              cpuMillicoresUsedPerInstance.get(i),
              0L));
    }
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a", new ResourceUsageSnapshot(500L * 1024 * 1024, 0, 4000, 0), observations));
  }

  /**
   * CLAUDE.md's own "convergence tests from arbitrary starting states" requirement, for the one of
   * the three remaining reconcilers that's genuinely stateless (design doc §1.1): rather than
   * re-deriving the reconciler's own ceil/ratio formula as the "expected" value (which would just
   * be testing that the code agrees with itself), this asserts the two invariants the class's own
   * javadoc actually promises -- clamped to policy bounds, moved by at most one replica per tick --
   * hold across hundreds of arbitrary policy/utilization/replica-count combinations, not just the
   * handful of hand-picked examples above.
   */
  @Test
  void a_tick_never_leaves_the_configured_bounds_or_moves_more_than_one_replica() {
    Path jar = buildFixtureJar();
    AtomicInteger trial = new AtomicInteger();
    PropertyChecker.forAll(
        AUTOSCALE_SCENARIOS,
        scenario -> {
          StateStore store =
              new StateStore(tempDir.resolve("prop-autoscale-" + trial.incrementAndGet()));
          AutoscalePolicy policy =
              new AutoscalePolicy(
                  scenario.minReplicas(),
                  scenario.maxReplicas(),
                  scenario.targetCpuUtilizationPercent());
          DeploymentSpec spec =
              deployment("orders-service", scenario.currentEffective(), jar, policy);
          store.putDeployment(spec);
          store.putEffectiveReplicas("orders-service", scenario.currentEffective());
          readyInstancesAt(
              store, "orders-service", spec.moduleId(), scenario.readyCpuMillicoresUsed());

          new AutoscaleReconciler(store).reconcileOnce();
          int next = store.getEffectiveReplicas("orders-service").orElseThrow();

          boolean withinBounds = next >= scenario.minReplicas() && next <= scenario.maxReplicas();
          boolean atMostOneStep = Math.abs(next - scenario.currentEffective()) <= 1;
          return withinBounds && atMostOneStep;
        });
  }
}
