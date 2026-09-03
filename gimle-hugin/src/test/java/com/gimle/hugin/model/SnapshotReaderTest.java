package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.protocol.Json;
import java.time.Instant;
import java.util.LinkedHashMap;
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

  @Test
  void an_observation_carrying_a_tier_and_a_limit_reads_both_back() {
    InstanceRow row = rowFrom(withIsolation("TIER_2", Map.of("memory", "512Mi", "cpu", "500m")));

    assertEquals(Optional.of(IsolationTier.TIER_2), row.isolationTier());
    assertEquals(Optional.of(new ResourceSpec("512Mi", "500m")), row.resourceLimit());
    assertEquals(512L * 1024L * 1024L, row.resourceLimit().orElseThrow().memoryBytes());
  }

  @Test
  void an_observation_carrying_neither_leaves_both_absent() {
    InstanceRow row = rowFrom(Fixtures.instance(0, "node-alpha", "ACTIVE", 0, 0));

    assertTrue(row.isolationTier().isEmpty());
    assertTrue(row.resourceLimit().isEmpty());
  }

  @Test
  void a_tier_or_a_quantity_this_build_cannot_read_is_dropped_rather_than_shown_raw() {
    InstanceRow row = rowFrom(withIsolation("TIER_9", Map.of("memory", "lots", "cpu", "500m")));

    assertTrue(row.isolationTier().isEmpty());
    assertTrue(row.resourceLimit().isEmpty());
  }

  @Test
  void a_workload_short_of_replicas_reads_back_its_shortfall_and_its_policy_verdicts() {
    Map<String, Object> deployment =
        new LinkedHashMap<>(
            Fixtures.deployment(
                "checkout-api",
                Optional.of("acme"),
                List.of(Fixtures.instance(0, "node-alpha", "ACTIVE", 0, 0))));
    Json.asObject(deployment.get("spec")).put("replicas", 4);
    deployment.put("unplacedCount", 3);
    deployment.put("quotaViolating", true);
    deployment.put("limitRangeViolating", true);
    deployment.put("limitRangeViolationReason", "memory limit 2Gi exceeds tenant maximum 1Gi");

    WorkloadRow row = workloadFrom(deployment);

    assertEquals("checkout-api", row.name());
    assertEquals(Optional.of("acme"), row.tenantId());
    assertEquals(4, row.desiredReplicas());
    assertEquals(1, row.placedCount());
    assertEquals(3, row.unplacedCount());
    assertFalse(row.settled());
    assertTrue(row.problem().contains("1 of 4 placed"), row.problem());
    assertTrue(row.problem().contains("quota exceeded"), row.problem());
    assertTrue(row.problem().contains("exceeds tenant maximum 1Gi"), row.problem());
  }

  @Test
  void a_fully_placed_unviolating_workload_reads_as_settled() {
    WorkloadRow row =
        workloadFrom(
            Fixtures.deployment(
                "greeter-provider",
                Optional.empty(),
                List.of(Fixtures.instance(0, "node-alpha", "ACTIVE", 0, 0))));

    assertTrue(row.settled());
    assertEquals("", row.problem());
  }

  @Test
  void a_violation_the_control_plane_gives_no_reason_for_still_reports_the_violation() {
    Map<String, Object> deployment =
        new LinkedHashMap<>(Fixtures.deployment("billing-api", Optional.empty(), List.of()));
    deployment.put("limitRangeViolating", true);

    WorkloadRow row = workloadFrom(deployment);

    assertFalse(row.settled());
    assertTrue(row.problem().contains("limit range"), row.problem());
  }

  @Test
  void daemon_set_and_stateful_set_instances_appear_alongside_deployment_ones() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/nodes", List.of(Fixtures.node("node-alpha", 0, 4000, 0, 8)))
            .withList(
                "/deployments",
                List.of(
                    Fixtures.deployment(
                        "greeter-provider",
                        Optional.empty(),
                        List.of(Fixtures.instance(0, "node-alpha", "ACTIVE", 0, 0)))))
            .withList(
                "/daemonsets",
                List.of(
                    Fixtures.deployment(
                        "log-shipper",
                        Optional.empty(),
                        List.of(Fixtures.instance(0, "node-alpha", "ACTIVE", 0, 0)))))
            .withList(
                "/statefulsets",
                List.of(
                    Fixtures.deployment(
                        "ledger",
                        Optional.empty(),
                        List.of(Fixtures.instance(0, "node-alpha", "ACTIVE", 0, 0)))));

    ClusterSnapshot snapshot = new SnapshotReader(reader).read();

    assertEquals(3, snapshot.instances().size());
    assertEquals(
        List.of(WorkloadKind.DEPLOYMENT, WorkloadKind.STATEFUL_SET, WorkloadKind.DAEMON_SET),
        snapshot.instances().stream().map(InstanceRow::kind).toList());
    // One flat name-ordered table across all three kinds, not three blocks grouped by kind.
    assertEquals(
        List.of("greeter-provider", "ledger", "log-shipper"),
        snapshot.instances().stream().map(InstanceRow::deploymentName).toList());
    assertEquals(3, snapshot.nodes().getFirst().instanceCount());
  }

  @Test
  void a_kind_the_control_plane_serves_nothing_for_costs_only_its_own_rows() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/nodes", List.of(Fixtures.node("node-alpha", 0, 4000, 0, 8)))
            .withList(
                "/deployments",
                List.of(
                    Fixtures.deployment(
                        "greeter-provider",
                        Optional.empty(),
                        List.of(Fixtures.instance(0, "node-alpha", "ACTIVE", 0, 0)))));

    ClusterSnapshot snapshot = new SnapshotReader(reader).read();

    assertEquals(1, snapshot.instances().size());
    assertTrue(snapshot.connected());
  }

  @Test
  void a_daemon_set_declares_no_replica_count_so_it_never_reads_as_short_of_one() {
    // A DaemonSet's desired count is "one per eligible node", which the control plane does not
    // serve -- so there is no shortfall to compute and claiming one would be invented.
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/nodes", List.of(Fixtures.node("node-alpha", 0, 4000, 0, 8)))
            .withList(
                "/daemonsets",
                List.of(
                    Map.of(
                        "spec", Map.of("name", "log-shipper"),
                        "instances", List.of(Fixtures.instance(0, "node-alpha", "ACTIVE", 0, 0)))));

    WorkloadRow row = new SnapshotReader(reader).read().workloads().getFirst();

    assertEquals(WorkloadKind.DAEMON_SET, row.kind());
    assertTrue(row.settled());
  }

  private static WorkloadRow workloadFrom(final Map<String, Object> deployment) {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/nodes", List.of(Fixtures.node("node-alpha", 0, 4000, 0, 8)))
            .withList("/deployments", List.of(deployment));
    return new SnapshotReader(reader).read().workloads().getFirst();
  }

  @Test
  void reported_ports_and_volume_usage_read_back_off_the_observation() {
    Map<String, Object> instance =
        new LinkedHashMap<>(Fixtures.instance(0, "node-alpha", "ACTIVE", 0, 0));
    Map<String, Object> observation =
        new LinkedHashMap<>(Json.asObject(instance.get("observation")));
    observation.put("ports", Map.of("http", 8080, "notAPort", "eighty-eighty"));
    observation.put("volumeUsageBytes", 512L * 1024L * 1024L);
    instance.put("observation", observation);

    InstanceRow row = rowFrom(instance);

    // The unusable entry is dropped rather than taking the whole map down with it.
    assertEquals(Map.of("http", 8080), row.ports());
    assertEquals(512L * 1024L * 1024L, row.volumeUsageBytes());
  }

  @Test
  void an_observation_reporting_no_ports_reads_back_an_empty_map_not_a_null() {
    InstanceRow row = rowFrom(Fixtures.instance(0, "node-alpha", "ACTIVE", 0, 0));

    assertTrue(row.ports().isEmpty());
    assertEquals(0L, row.volumeUsageBytes());
  }

  private static Map<String, Object> withIsolation(
      final String tier, final Map<String, Object> limit) {
    Map<String, Object> instance =
        new LinkedHashMap<>(Fixtures.instance(0, "node-alpha", "ACTIVE", 0, 0));
    Map<String, Object> observation =
        new LinkedHashMap<>(Json.asObject(instance.get("observation")));
    observation.put("isolationTier", tier);
    observation.put("resourceLimit", limit);
    instance.put("observation", observation);
    return instance;
  }

  private static InstanceRow rowFrom(final Map<String, Object> instance) {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/nodes", List.of(Fixtures.node("node-alpha", 0, 4000, 1, 8)))
            .withList(
                "/deployments",
                List.of(
                    Fixtures.deployment("greeter-provider", Optional.empty(), List.of(instance))));
    return new SnapshotReader(reader).read().instances().getFirst();
  }
}
