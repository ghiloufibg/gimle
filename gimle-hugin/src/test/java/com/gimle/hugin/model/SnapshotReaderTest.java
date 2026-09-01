package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Turning the two API responses into rows, including the shapes that arrive incomplete. */
class SnapshotReaderTest {

  @Test
  void reads_nodes_and_instances_into_rows() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/nodes", List.of(Fixtures.node("node-alpha", 1240, 4000, 2, 8)))
            .withList(
                "/deployments",
                List.of(
                    Fixtures.deployment(
                        "greeter-provider",
                        Optional.empty(),
                        List.of(Fixtures.instance(0, "node-alpha", "ACTIVE", 24.1, 0.0)))));

    ClusterSnapshot snapshot = new SnapshotReader(reader).read();

    assertEquals(1, snapshot.nodes().size());
    NodeRow node = snapshot.nodes().getFirst();
    assertEquals("node-alpha", node.nodeId());
    assertEquals(1240, node.assignedCpuMillicores());
    assertEquals(4000, node.totalCpuMillicores());
    assertEquals(1, node.instanceCount());

    assertEquals(1, snapshot.instances().size());
    InstanceRow instance = snapshot.instances().getFirst();
    assertEquals("greeter-provider", instance.deploymentName());
    assertEquals("ACTIVE", instance.lifecycleState());
    assertTrue(instance.observed());
    assertTrue(instance.ready());
    assertEquals(24.1, instance.requestRatePerSecond(), 0.001);
    assertTrue(snapshot.connected());
  }

  @Test
  void an_instance_with_no_observation_yet_reads_as_pending_and_unobserved() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/nodes", List.of(Fixtures.node("node-alpha", 0, 4000, 0, 8)))
            .withList(
                "/deployments",
                List.of(
                    Fixtures.deployment(
                        "checkout-api",
                        Optional.empty(),
                        List.of(Map.of("instanceIndex", 0, "nodeId", "node-alpha")))));

    InstanceRow row = new SnapshotReader(reader).read().instances().getFirst();

    assertFalse(row.observed());
    assertEquals("PENDING", row.lifecycleState());
    assertEquals(0.0, row.requestRatePerSecond(), 0.0);
  }

  @Test
  void a_node_that_has_never_heartbeated_has_no_capacity_and_reads_unknown() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/nodes", List.of(Map.of("nodeId", "node-quiet", "cordoned", false)));

    NodeRow node = new SnapshotReader(reader).read().nodes().getFirst();

    assertFalse(node.hasCapacity());
    assertEquals(Optional.empty(), node.lastHeartbeatAt());
    assertEquals("UNKNOWN", node.state(Instant.now()));
  }

  @Test
  void an_empty_cluster_reads_as_connected_with_nothing_in_it() {
    ClusterSnapshot snapshot = new SnapshotReader(new FakeClusterReader()).read();

    assertTrue(snapshot.connected());
    assertTrue(snapshot.nodes().isEmpty());
    assertTrue(snapshot.instances().isEmpty());
  }

  @Test
  void a_deployments_tenant_is_carried_onto_every_one_of_its_instances() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList(
                "/deployments",
                List.of(
                    Fixtures.deployment(
                        "greeter-provider",
                        Optional.of("acme"),
                        List.of(Fixtures.instance(0, "node-alpha", "ACTIVE", 1.0, 0.0)))));

    InstanceRow row = new SnapshotReader(reader).read().instances().getFirst();

    assertEquals(Optional.of("acme"), row.tenantId());
  }

  @Test
  void an_unparseable_heartbeat_timestamp_degrades_that_field_rather_than_the_poll() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList(
                "/nodes",
                List.of(Map.of("nodeId", "node-alpha", "lastHeartbeatAt", "not-a-timestamp")));

    NodeRow node = new SnapshotReader(reader).read().nodes().getFirst();

    assertEquals(Optional.empty(), node.lastHeartbeatAt());
  }

  @Test
  void instances_are_ordered_by_deployment_then_index_regardless_of_response_order() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList(
                "/deployments",
                List.of(
                    Fixtures.deployment(
                        "zeta",
                        Optional.empty(),
                        List.of(Fixtures.instance(1, "node-a", "ACTIVE", 0, 0))),
                    Fixtures.deployment(
                        "alpha",
                        Optional.empty(),
                        List.of(
                            Fixtures.instance(1, "node-a", "ACTIVE", 0, 0),
                            Fixtures.instance(0, "node-a", "ACTIVE", 0, 0)))));

    List<InstanceRow> rows = new SnapshotReader(reader).read().instances();

    assertEquals(
        List.of("alpha/0", "alpha/1", "zeta/1"),
        rows.stream().map(row -> row.key().toString()).toList());
  }

  @Test
  void the_instance_count_shown_against_a_node_counts_only_that_nodes_instances() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList(
                "/nodes",
                List.of(
                    Fixtures.node("node-alpha", 0, 4000, 0, 8),
                    Fixtures.node("node-bravo", 0, 4000, 0, 8)))
            .withList(
                "/deployments",
                List.of(
                    Fixtures.deployment(
                        "greeter-provider",
                        Optional.empty(),
                        List.of(
                            Fixtures.instance(0, "node-alpha", "ACTIVE", 0, 0),
                            Fixtures.instance(1, "node-alpha", "ACTIVE", 0, 0),
                            Fixtures.instance(2, "node-bravo", "ACTIVE", 0, 0)))));

    ClusterSnapshot snapshot = new SnapshotReader(reader).read();

    assertEquals(2, snapshot.nodes().getFirst().instanceCount());
    assertEquals(1, snapshot.nodes().getLast().instanceCount());
  }
}
