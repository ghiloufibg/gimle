package com.gimle.holmgang.steps;

import com.gimle.holmgang.WorkDirs;
import com.gimle.holmgang.cluster.GimleCluster;
import com.gimle.testkit.heimdall.HeimdallConditionError;
import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import java.time.Duration;
import java.util.List;

/**
 * Scenario lifecycle around the step classes: reads the {@code @destructive} tag before a scenario,
 * and afterwards closes leftover invariant guards (a violation still fails the scenario, even when
 * the step forgot to check it), hands a pooled cluster back clean (uncordons, deletes created
 * deployments and tenants), or closes an owned destructive cluster outright. Cleanup of a pooled
 * cluster is best-effort by design -- a cleanup hiccup must never mask the scenario's own result.
 */
public final class Hooks {

  private final ScenarioWorld world;

  public Hooks(final ScenarioWorld world) {
    this.world = world;
  }

  @Before
  public void beforeScenario(final Scenario scenario) {
    world.scenarioName = scenario.getName();
    if (scenario.getSourceTagNames().contains("@destructive")) {
      world.markDestructive();
    }
  }

  @After
  public void afterScenario(final Scenario scenario) {
    if (scenario.isFailed()) {
      ClusterPool.markFailed();
    }
    if (world.workload != null) {
      bestEffort(world.workload::close);
    }
    for (final Process load : world.loadProcesses) {
      bestEffort(load::destroyForcibly);
    }
    while (!world.partitions.isEmpty()) {
      final var partition = world.partitions.pollLast();
      bestEffort(partition::heal);
    }
    HeimdallConditionError leftoverViolation = null;
    while (!world.guards.isEmpty()) {
      try {
        world.guards.pollLast().close();
      } catch (final HeimdallConditionError violation) {
        if (leftoverViolation == null) {
          leftoverViolation = violation;
        }
      }
    }
    if (world.hasCluster()) {
      if (world.ownsCluster()) {
        world.cluster().close();
        if (WorkDirs.shouldDelete(scenario.isFailed())) {
          WorkDirs.deleteRecursively(world.ownedWorkDir());
        }
      } else {
        restorePooledCluster();
      }
    }
    if (leftoverViolation != null) {
      throw leftoverViolation;
    }
  }

  @AfterAll
  public static void closeClusters() {
    ClusterPool.closeAll();
  }

  private void restorePooledCluster() {
    final GimleCluster cluster = world.cluster();
    for (final String nodeId : List.copyOf(world.cordonedNodes)) {
      bestEffort(() -> cluster.api().uncordonNode(nodeId));
    }
    for (final String deployment : List.copyOf(world.deployments.keySet())) {
      bestEffort(
          () -> {
            cluster.api().deleteDeployment(deployment);
            cluster.when().deployment(deployment).isAbsent().await(Duration.ofSeconds(60));
          });
    }
    for (final String tenant : List.copyOf(world.tenants)) {
      bestEffort(() -> cluster.api().deleteTenant(tenant));
    }
    for (final String statefulSet : List.copyOf(world.statefulSets)) {
      bestEffort(() -> cluster.api().deleteStatefulSet(statefulSet));
    }
  }

  private static void bestEffort(final Runnable cleanup) {
    try {
      cleanup.run();
    } catch (final RuntimeException | AssertionError e) {
      // Swallowed deliberately: the scenario's own verdict is already recorded, and a cleanup
      // failure on an already-broken cluster would only bury it.
    }
  }
}
