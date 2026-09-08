package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link StatefulSetSpec#maxCommittedInstances()} -- everything else on this record is covered by
 * {@code StatefulSetManifestParserTest} instead, since it's purely parsing/defaulting.
 */
class StatefulSetSpecTest {

  @Test
  void with_no_autoscale_max_committed_instances_equals_replicas() {
    StatefulSetSpec spec = statefulSet(3, Optional.empty());

    assertEquals(3, spec.maxCommittedInstances());
  }

  @Test
  void with_autoscale_max_replicas_above_replicas_max_committed_instances_uses_max_replicas() {
    StatefulSetSpec spec = statefulSet(1, Optional.of(new AutoscalePolicy(1, 7, 80)));

    assertEquals(7, spec.maxCommittedInstances());
  }

  @Test
  void with_autoscale_max_replicas_below_replicas_max_committed_instances_uses_replicas() {
    // replicas is the user-submitted floor -- a maxReplicas ceiling lower than it must never
    // shrink the committed count below replicas.
    StatefulSetSpec spec = statefulSet(5, Optional.of(new AutoscalePolicy(1, 3, 80)));

    assertEquals(5, spec.maxCommittedInstances());
  }

  private static StatefulSetSpec statefulSet(int replicas, Optional<AutoscalePolicy> autoscale) {
    return new StatefulSetSpec(
        "orders-db",
        new ModuleId("orders-db", Version.parse("1.0.0")),
        "/tmp/orders-db.jar",
        replicas,
        PlacementConstraints.NONE,
        autoscale,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }
}
