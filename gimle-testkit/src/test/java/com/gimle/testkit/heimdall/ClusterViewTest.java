package com.gimle.testkit.heimdall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClusterViewTest {

  private static ClusterView parse(final String deploymentsJson, final String nodesJson) {
    return ClusterView.fromJson(
        Instant.now(),
        0,
        Json.asArray(Json.parse(deploymentsJson)),
        Json.asArray(Json.parse(nodesJson)));
  }

  @Test
  void parses_the_deployments_and_nodes_list_shapes() {
    final ClusterView view =
        parse(
            """
            [{"spec": {"name": "greeter", "replicas": 2},
              "quotaViolating": false,
              "instances": [
                {"instanceIndex": 0, "nodeId": "node-1",
                 "observation": {"lifecycleState": "ACTIVE",
                                 "moduleId": {"name": "m", "version": "1.0.0"}}},
                {"instanceIndex": 1, "nodeId": "node-2"}]}]
            """,
            """
            [{"nodeId": "node-1",
              "cordoned": true,
              "capabilities": {"labels": ["gpu"]},
              "lastHeartbeatAt": "2026-08-14T05:00:00Z"},
             {"nodeId": "node-2", "cordoned": false, "capabilities": {"labels": []}}]
            """);
    final ClusterView.DeploymentView greeter = view.deployments().get("greeter");
    assertEquals(2, greeter.desiredReplicas());
    assertEquals(1, greeter.countInState("ACTIVE"));
    assertEquals(Optional.of("1.0.0"), greeter.instances().get(0).version());
    assertEquals(Optional.empty(), greeter.instances().get(1).lifecycleState());
    assertTrue(view.nodes().get("node-1").cordoned());
    assertEquals(List.of("gpu"), view.nodes().get("node-1").labels());
    assertTrue(view.nodes().get("node-1").lastHeartbeatAt().isPresent());
    assertTrue(view.nodes().get("node-2").lastHeartbeatAt().isEmpty());
  }

  @Test
  void all_active_on_version_requires_state_version_and_count_together() {
    final ClusterView view =
        parse(
            """
            [{"spec": {"name": "greeter", "replicas": 2},
              "quotaViolating": false,
              "instances": [
                {"instanceIndex": 0, "nodeId": "n1",
                 "observation": {"lifecycleState": "ACTIVE", "moduleId": {"version": "1.1.0"}}},
                {"instanceIndex": 1, "nodeId": "n2",
                 "observation": {"lifecycleState": "ACTIVE", "moduleId": {"version": "1.0.0"}}}]}]
            """,
            "[]");
    final ClusterView.DeploymentView greeter = view.deployments().get("greeter");
    assertFalse(greeter.allActiveOnVersion("1.1.0", 2), "mixed versions must not satisfy");
    assertFalse(greeter.allActiveOnVersion("1.1.0", 1), "wrong count must not satisfy");
    assertTrue(greeter.allActive());
  }

  @Test
  void an_unobserved_instance_never_counts_as_active() {
    final ClusterView view =
        parse(
            """
            [{"spec": {"name": "greeter", "replicas": 1},
              "quotaViolating": false,
              "instances": [{"instanceIndex": 0, "nodeId": "n1"}]}]
            """,
            "[]");
    final ClusterView.DeploymentView greeter = view.deployments().get("greeter");
    assertEquals(0, greeter.countInState("ACTIVE"));
    assertFalse(greeter.allActive());
  }
}
