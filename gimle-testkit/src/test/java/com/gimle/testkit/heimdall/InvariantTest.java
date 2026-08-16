package com.gimle.testkit.heimdall;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InvariantTest {

  private static ClusterView viewWithActiveCount(final int active, final int total) {
    final List<ClusterView.InstanceView> instances = new java.util.ArrayList<>();
    for (int i = 0; i < total; i++) {
      instances.add(
          new ClusterView.InstanceView(
              i, "node-1", Optional.of(i < active ? "ACTIVE" : "STARTING"), Optional.of("1.0.0")));
    }
    return new ClusterView(
        Instant.now(),
        0,
        Map.of("greeter", new ClusterView.DeploymentView("greeter", total, false, instances)),
        Map.of());
  }

  @Test
  void min_active_replicas_holds_while_enough_instances_are_active() {
    final Invariant invariant = Invariants.deployment("greeter").minActiveReplicas(1);
    assertTrue(invariant.holds().test(viewWithActiveCount(1, 2)));
    assertTrue(invariant.holds().test(viewWithActiveCount(2, 2)));
    assertFalse(invariant.holds().test(viewWithActiveCount(0, 2)));
  }

  @Test
  void a_vanished_deployment_violates_min_active_replicas() {
    final Invariant invariant = Invariants.deployment("greeter").minActiveReplicas(1);
    final ClusterView empty = new ClusterView(Instant.now(), 0, Map.of(), Map.of());
    assertFalse(invariant.holds().test(empty));
  }
}
