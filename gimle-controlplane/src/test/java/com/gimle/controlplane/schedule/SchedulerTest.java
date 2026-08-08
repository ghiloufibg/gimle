package com.gimle.controlplane.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.core.exception.GimleSchedulingException;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SchedulerTest {

  private static final ResourceSpec REQUEST = new ResourceSpec("100Mi", "100m");
  private static final NodeCapabilities TIER_1_AND_2 =
      new NodeCapabilities(Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2));
  private static final NodeCapabilities TIER_1_ONLY =
      new NodeCapabilities(Set.of(IsolationTier.TIER_1));

  private final Scheduler scheduler = new Scheduler();

  private static NodeCandidate node(
      String id,
      NodeCapabilities capabilities,
      long freeMemoryBytes,
      long freeCpuMillicores,
      boolean alreadyRuns) {
    return new NodeCandidate(
        id,
        capabilities,
        new ResourceUsageSnapshot(freeMemoryBytes, 0, freeCpuMillicores, 0),
        alreadyRuns);
  }

  @Test
  void places_on_the_only_feasible_node() {
    List<NodeCandidate> candidates =
        List.of(node("node-a", TIER_1_AND_2, 500L * 1024 * 1024, 1000, false));

    String chosen = scheduler.place("orders", 0, IsolationTier.TIER_1, REQUEST, false, candidates);

    assertEquals("node-a", chosen);
  }

  @Test
  void prefers_the_node_with_more_free_capacity() {
    List<NodeCandidate> candidates =
        List.of(
            node("node-small", TIER_1_AND_2, 200L * 1024 * 1024, 1000, false),
            node("node-big", TIER_1_AND_2, 800L * 1024 * 1024, 1000, false));

    String chosen = scheduler.place("orders", 0, IsolationTier.TIER_1, REQUEST, false, candidates);

    assertEquals("node-big", chosen);
  }

  @Test
  void rejects_a_node_that_does_not_support_the_requested_tier() {
    List<NodeCandidate> candidates =
        List.of(
            node("node-tier1-only", TIER_1_ONLY, 800L * 1024 * 1024, 1000, false),
            node("node-both", TIER_1_AND_2, 200L * 1024 * 1024, 1000, false));

    String chosen = scheduler.place("orders", 0, IsolationTier.TIER_2, REQUEST, false, candidates);

    assertEquals("node-both", chosen);
  }

  @Test
  void throws_when_no_node_supports_the_requested_tier() {
    List<NodeCandidate> candidates =
        List.of(node("node-a", TIER_1_ONLY, 800L * 1024 * 1024, 1000, false));

    assertThrows(
        GimleSchedulingException.class,
        () -> scheduler.place("orders", 0, IsolationTier.TIER_2, REQUEST, false, candidates));
  }

  @Test
  void throws_when_no_node_has_enough_free_capacity() {
    List<NodeCandidate> candidates =
        List.of(node("node-a", TIER_1_AND_2, 10L * 1024 * 1024, 1000, false));

    assertThrows(
        GimleSchedulingException.class,
        () -> scheduler.place("orders", 0, IsolationTier.TIER_1, REQUEST, false, candidates));
  }

  @Test
  void anti_affinity_excludes_nodes_already_running_a_replica_of_the_same_deployment() {
    List<NodeCandidate> candidates =
        List.of(
            node("node-occupied", TIER_1_AND_2, 800L * 1024 * 1024, 1000, true),
            node("node-free", TIER_1_AND_2, 200L * 1024 * 1024, 1000, false));

    String chosen = scheduler.place("orders", 1, IsolationTier.TIER_1, REQUEST, true, candidates);

    assertEquals("node-free", chosen);
  }

  @Test
  void anti_affinity_fails_outright_rather_than_placing_on_an_occupied_node() {
    List<NodeCandidate> candidates =
        List.of(node("node-occupied", TIER_1_AND_2, 800L * 1024 * 1024, 1000, true));

    assertThrows(
        GimleSchedulingException.class,
        () -> scheduler.place("orders", 1, IsolationTier.TIER_1, REQUEST, true, candidates));
  }

  @Test
  void anti_affinity_is_not_enforced_when_not_requested() {
    List<NodeCandidate> candidates =
        List.of(node("node-occupied", TIER_1_AND_2, 800L * 1024 * 1024, 1000, true));

    String chosen = scheduler.place("orders", 1, IsolationTier.TIER_1, REQUEST, false, candidates);

    assertEquals("node-occupied", chosen);
  }

  @Test
  void no_candidates_at_all_throws() {
    assertThrows(
        GimleSchedulingException.class,
        () -> scheduler.place("orders", 0, IsolationTier.TIER_1, REQUEST, false, List.of()));
  }

  // ---- tenant node-level isolation, Phase 5 design §5.4 ----

  private static NodeCandidate nodeWithTenants(
      String id, NodeCapabilities capabilities, long freeMemoryBytes, Set<String> tenantsPresent) {
    return new NodeCandidate(
        id,
        capabilities,
        new ResourceUsageSnapshot(freeMemoryBytes, 0, 1000, 0),
        false,
        tenantsPresent);
  }

  @Test
  void tenant_isolation_excludes_a_tier2_node_already_running_a_different_tenant() {
    List<NodeCandidate> candidates =
        List.of(
            nodeWithTenants(
                "node-other-tenant", TIER_1_AND_2, 800L * 1024 * 1024, Set.of("tenant-b")),
            nodeWithTenants("node-free", TIER_1_AND_2, 200L * 1024 * 1024, Set.of()));

    String chosen =
        scheduler.place(
            "orders", 0, IsolationTier.TIER_2, REQUEST, false, Optional.of("tenant-a"), candidates);

    assertEquals("node-free", chosen);
  }

  @Test
  void tenant_isolation_permits_a_node_already_running_the_same_tenant() {
    List<NodeCandidate> candidates =
        List.of(
            nodeWithTenants(
                "node-same-tenant", TIER_1_AND_2, 800L * 1024 * 1024, Set.of("tenant-a")));

    String chosen =
        scheduler.place(
            "orders", 0, IsolationTier.TIER_2, REQUEST, false, Optional.of("tenant-a"), candidates);

    assertEquals("node-same-tenant", chosen);
  }

  @Test
  void tenant_isolation_fails_outright_when_every_capable_node_hosts_a_different_tenant() {
    List<NodeCandidate> candidates =
        List.of(
            nodeWithTenants(
                "node-other-tenant", TIER_1_AND_2, 800L * 1024 * 1024, Set.of("tenant-b")));

    assertThrows(
        GimleSchedulingException.class,
        () ->
            scheduler.place(
                "orders",
                0,
                IsolationTier.TIER_2,
                REQUEST,
                false,
                Optional.of("tenant-a"),
                candidates));
  }

  @Test
  void tenant_isolation_is_not_enforced_at_tier1() {
    // Tier 1 density packing across separate deployments isn't implemented anywhere in this
    // codebase (design §5.4's own correction) -- node-level exclusion is deliberately a no-op here.
    List<NodeCandidate> candidates =
        List.of(
            nodeWithTenants(
                "node-other-tenant", TIER_1_AND_2, 800L * 1024 * 1024, Set.of("tenant-b")));

    String chosen =
        scheduler.place(
            "orders", 0, IsolationTier.TIER_1, REQUEST, false, Optional.of("tenant-a"), candidates);

    assertEquals("node-other-tenant", chosen);
  }

  @Test
  void tenant_isolation_is_not_enforced_when_no_tenant_id_given() {
    List<NodeCandidate> candidates =
        List.of(
            nodeWithTenants(
                "node-other-tenant", TIER_1_AND_2, 800L * 1024 * 1024, Set.of("tenant-b")));

    String chosen = scheduler.place("orders", 0, IsolationTier.TIER_2, REQUEST, false, candidates);

    assertEquals("node-other-tenant", chosen);
  }

  // ---- placement.requiredLabels ----

  private static NodeCandidate nodeWithLabels(String id, long freeMemoryBytes, Set<String> labels) {
    return new NodeCandidate(
        id,
        new NodeCapabilities(Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2), labels),
        new ResourceUsageSnapshot(freeMemoryBytes, 0, 1000, 0),
        false);
  }

  @Test
  void required_labels_excludes_a_node_missing_one_of_them() {
    List<NodeCandidate> candidates =
        List.of(
            nodeWithLabels("node-no-labels", 800L * 1024 * 1024, Set.of()),
            nodeWithLabels("node-gpu", 200L * 1024 * 1024, Set.of("gpu")));

    String chosen =
        scheduler.place(
            "orders",
            0,
            IsolationTier.TIER_1,
            REQUEST,
            false,
            Optional.empty(),
            Set.of("gpu"),
            candidates);

    assertEquals("node-gpu", chosen);
  }

  @Test
  void required_labels_permits_a_node_carrying_extra_labels_beyond_what_is_required() {
    List<NodeCandidate> candidates =
        List.of(nodeWithLabels("node-gpu-ssd", 800L * 1024 * 1024, Set.of("gpu", "ssd")));

    String chosen =
        scheduler.place(
            "orders",
            0,
            IsolationTier.TIER_1,
            REQUEST,
            false,
            Optional.empty(),
            Set.of("gpu"),
            candidates);

    assertEquals("node-gpu-ssd", chosen);
  }

  @Test
  void required_labels_fails_outright_when_no_capable_node_carries_them() {
    List<NodeCandidate> candidates =
        List.of(nodeWithLabels("node-no-labels", 800L * 1024 * 1024, Set.of()));

    assertThrows(
        GimleSchedulingException.class,
        () ->
            scheduler.place(
                "orders",
                0,
                IsolationTier.TIER_1,
                REQUEST,
                false,
                Optional.empty(),
                Set.of("gpu"),
                candidates));
  }

  @Test
  void required_labels_is_not_enforced_when_the_manifest_does_not_request_any() {
    List<NodeCandidate> candidates =
        List.of(nodeWithLabels("node-no-labels", 800L * 1024 * 1024, Set.of()));

    String chosen = scheduler.place("orders", 0, IsolationTier.TIER_1, REQUEST, false, candidates);

    assertEquals("node-no-labels", chosen);
  }
}
