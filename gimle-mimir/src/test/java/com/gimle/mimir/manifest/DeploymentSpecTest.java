package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link DeploymentSpec#maxCommittedInstances()} -- everything else on this record is covered by
 * {@code DeploymentManifestParserTest} instead, since it's purely parsing/defaulting.
 */
class DeploymentSpecTest {

  @Test
  void with_no_disruption_block_max_committed_instances_equals_replicas() {
    DeploymentSpec spec = deployment(3, Optional.empty());

    assertEquals(3, spec.maxCommittedInstances());
  }

  @Test
  void with_an_explicit_zero_surge_budget_max_committed_instances_equals_replicas() {
    DeploymentSpec spec = deployment(3, Optional.of(new DisruptionBudget(1, 0)));

    assertEquals(3, spec.maxCommittedInstances());
  }

  @Test
  void with_a_nonzero_surge_budget_max_committed_instances_adds_the_surge() {
    // The record itself doesn't reject a nonzero maxSurge (see DisruptionBudgetTest) -- only the
    // manifest parsers do -- so this is constructible directly even though no real manifest can
    // produce it yet.
    DeploymentSpec spec = deployment(3, Optional.of(new DisruptionBudget(1, 2)));

    assertEquals(5, spec.maxCommittedInstances());
  }

  private static DeploymentSpec deployment(int replicas, Optional<DisruptionBudget> disruption) {
    return new DeploymentSpec(
        "orders-service",
        new ModuleId("orders", Version.parse("1.0.0")),
        "/tmp/orders.jar",
        replicas,
        PlacementConstraints.NONE,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        disruption);
  }
}
