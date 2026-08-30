package com.gimle.controlplane.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleSchedulingException;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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

  // ---- node taints / tenant tolerations ----

  private static NodeCandidate nodeWithTaints(
      String id, NodeCapabilities capabilities, long freeMemoryBytes, Set<String> taints) {
    return new NodeCandidate(
        id, capabilities, new ResourceUsageSnapshot(freeMemoryBytes, 0, 1000, 0), false, taints);
  }

  @Test
  void taint_excludes_a_node_tainted_for_a_different_tenant() {
    List<NodeCandidate> candidates =
        List.of(
            nodeWithTaints("node-tainted", TIER_1_AND_2, 800L * 1024 * 1024, Set.of("tenant-b")),
            nodeWithTaints("node-open", TIER_1_AND_2, 200L * 1024 * 1024, Set.of()));

    String chosen =
        scheduler.place(
            "orders", 0, IsolationTier.TIER_2, REQUEST, false, Optional.of("tenant-a"), candidates);

    assertEquals("node-open", chosen);
  }

  @Test
  void taint_permits_a_node_tainted_for_the_same_tenant() {
    List<NodeCandidate> candidates =
        List.of(
            nodeWithTaints(
                "node-tainted-same", TIER_1_AND_2, 800L * 1024 * 1024, Set.of("tenant-a")));

    String chosen =
        scheduler.place(
            "orders", 0, IsolationTier.TIER_2, REQUEST, false, Optional.of("tenant-a"), candidates);

    assertEquals("node-tainted-same", chosen);
  }

  @Test
  void taint_fails_outright_when_every_capable_node_is_tainted_for_a_different_tenant() {
    List<NodeCandidate> candidates =
        List.of(
            nodeWithTaints("node-tainted", TIER_1_AND_2, 800L * 1024 * 1024, Set.of("tenant-b")));

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

  /**
   * An operator hitting this needs to know which node(s) and which tenant(s) they're tainted for --
   * an event that just says "node taints exclude tenant" with no specifics is indistinguishable
   * from any other unexplained scheduling failure.
   */
  @Test
  void taint_failure_names_the_specific_blocking_node_and_tenant() {
    List<NodeCandidate> candidates =
        List.of(
            nodeWithTaints("node-tainted", TIER_1_AND_2, 800L * 1024 * 1024, Set.of("tenant-b")));

    GimleSchedulingException failure =
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

    assertTrue(failure.getMessage().contains("node-tainted"), failure.getMessage());
    assertTrue(failure.getMessage().contains("tenant-b"), failure.getMessage());
  }

  @Test
  void taint_failure_names_every_blocking_node_when_more_than_one_conflicts() {
    List<NodeCandidate> candidates =
        List.of(
            nodeWithTaints("node-b", TIER_1_AND_2, 800L * 1024 * 1024, Set.of("tenant-b")),
            nodeWithTaints("node-c", TIER_1_AND_2, 800L * 1024 * 1024, Set.of("tenant-c")));

    GimleSchedulingException failure =
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

    assertTrue(failure.getMessage().contains("node-b"), failure.getMessage());
    assertTrue(failure.getMessage().contains("tenant-b"), failure.getMessage());
    assertTrue(failure.getMessage().contains("node-c"), failure.getMessage());
    assertTrue(failure.getMessage().contains("tenant-c"), failure.getMessage());
  }

  @Test
  void taint_is_enforced_at_tier1_too_unlike_the_co_residency_check_it_replaced() {
    List<NodeCandidate> candidates =
        List.of(
            nodeWithTaints("node-tainted", TIER_1_AND_2, 800L * 1024 * 1024, Set.of("tenant-b")),
            nodeWithTaints("node-open", TIER_1_AND_2, 200L * 1024 * 1024, Set.of()));

    String chosen =
        scheduler.place(
            "orders", 0, IsolationTier.TIER_1, REQUEST, false, Optional.of("tenant-a"), candidates);

    assertEquals("node-open", chosen);
  }

  @Test
  void an_untenanted_deployment_never_tolerates_a_taint() {
    List<NodeCandidate> candidates =
        List.of(
            nodeWithTaints("node-tainted", TIER_1_AND_2, 800L * 1024 * 1024, Set.of("tenant-b")));

    assertThrows(
        GimleSchedulingException.class,
        () -> scheduler.place("orders", 0, IsolationTier.TIER_2, REQUEST, false, candidates));
  }

  @Test
  void an_untainted_node_admits_any_tenant() {
    List<NodeCandidate> candidates =
        List.of(nodeWithTaints("node-open", TIER_1_AND_2, 800L * 1024 * 1024, Set.of()));

    String chosen =
        scheduler.place(
            "orders", 0, IsolationTier.TIER_2, REQUEST, false, Optional.of("tenant-a"), candidates);

    assertEquals("node-open", chosen);
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

  // ---- node cordon ----

  private static NodeCandidate nodeWithCordon(
      String id, long freeMemoryBytes, long freeCpuMillicores, boolean cordoned) {
    return new NodeCandidate(
        id,
        TIER_1_AND_2,
        new ResourceUsageSnapshot(freeMemoryBytes, 0, freeCpuMillicores, 0),
        false,
        Set.of(),
        cordoned);
  }

  @Test
  void cordon_excludes_a_cordoned_node_from_placement() {
    List<NodeCandidate> candidates =
        List.of(
            nodeWithCordon("node-cordoned", 800L * 1024 * 1024, 1000, true),
            nodeWithCordon("node-free", 200L * 1024 * 1024, 1000, false));

    String chosen = scheduler.place("orders", 0, IsolationTier.TIER_1, REQUEST, false, candidates);

    assertEquals("node-free", chosen);
  }

  @Test
  void cordon_permits_placement_on_an_uncordoned_node() {
    List<NodeCandidate> candidates =
        List.of(nodeWithCordon("node-free", 800L * 1024 * 1024, 1000, false));

    String chosen = scheduler.place("orders", 0, IsolationTier.TIER_1, REQUEST, false, candidates);

    assertEquals("node-free", chosen);
  }

  @Test
  void cordon_fails_outright_when_every_capable_node_is_cordoned() {
    List<NodeCandidate> candidates =
        List.of(nodeWithCordon("node-cordoned", 800L * 1024 * 1024, 1000, true));

    assertThrows(
        GimleSchedulingException.class,
        () -> scheduler.place("orders", 0, IsolationTier.TIER_1, REQUEST, false, candidates));
  }

  @Test
  void cordon_is_not_enforced_when_no_node_is_cordoned() {
    List<NodeCandidate> candidates =
        List.of(nodeWithCordon("node-free", 800L * 1024 * 1024, 1000, false));

    String chosen = scheduler.place("orders", 0, IsolationTier.TIER_1, REQUEST, false, candidates);

    assertEquals("node-free", chosen);
  }

  // ---- eligibleNodes -- same five-step filter chain `place` applies, minus the final
  // bin-packing pick: every survivor comes back, never just one, and it never throws on an empty
  // result.

  @Test
  void eligible_nodes_returns_every_node_that_passes_every_filter() {
    List<NodeCandidate> candidates =
        List.of(
            node("node-a", TIER_1_AND_2, 500L * 1024 * 1024, 1000, false),
            node("node-b", TIER_1_AND_2, 10L * 1024 * 1024, 1000, false));

    List<NodeCandidate> eligible =
        scheduler.eligibleNodes(
            IsolationTier.TIER_1, false, Optional.empty(), Set.of(), false, candidates);

    // Unlike place(), resource sizing (freeMemoryBytes/freeCpuMillicores) is never consulted --
    // node-b's tiny free memory would fail place()'s own bin-packing filter but survives here.
    assertEquals(2, eligible.size());
  }

  @Test
  void eligible_nodes_excludes_nodes_that_do_not_support_the_requested_tier() {
    List<NodeCandidate> candidates =
        List.of(
            node("node-tier1-only", TIER_1_ONLY, 800L * 1024 * 1024, 1000, false),
            node("node-both", TIER_1_AND_2, 200L * 1024 * 1024, 1000, false));

    List<NodeCandidate> eligible =
        scheduler.eligibleNodes(
            IsolationTier.TIER_2, false, Optional.empty(), Set.of(), false, candidates);

    assertEquals(List.of("node-both"), eligible.stream().map(NodeCandidate::nodeId).toList());
  }

  @Test
  void eligible_nodes_excludes_cordoned_nodes() {
    List<NodeCandidate> candidates =
        List.of(
            nodeWithCordon("node-cordoned", 800L * 1024 * 1024, 1000, true),
            nodeWithCordon("node-free", 200L * 1024 * 1024, 1000, false));

    List<NodeCandidate> eligible =
        scheduler.eligibleNodes(
            IsolationTier.TIER_1, false, Optional.empty(), Set.of(), false, candidates);

    assertEquals(List.of("node-free"), eligible.stream().map(NodeCandidate::nodeId).toList());
  }

  @Test
  void eligible_nodes_applies_required_labels() {
    List<NodeCandidate> candidates =
        List.of(
            nodeWithLabels("node-no-labels", 800L * 1024 * 1024, Set.of()),
            nodeWithLabels("node-gpu", 200L * 1024 * 1024, Set.of("gpu")));

    List<NodeCandidate> eligible =
        scheduler.eligibleNodes(
            IsolationTier.TIER_1, false, Optional.empty(), Set.of("gpu"), false, candidates);

    assertEquals(List.of("node-gpu"), eligible.stream().map(NodeCandidate::nodeId).toList());
  }

  @Test
  void eligible_nodes_applies_node_taints() {
    List<NodeCandidate> candidates =
        List.of(
            nodeWithTaints("node-tainted", TIER_1_AND_2, 800L * 1024 * 1024, Set.of("tenant-b")),
            nodeWithTaints("node-open", TIER_1_AND_2, 200L * 1024 * 1024, Set.of()));

    List<NodeCandidate> eligible =
        scheduler.eligibleNodes(
            IsolationTier.TIER_2, false, Optional.of("tenant-a"), Set.of(), false, candidates);

    assertEquals(List.of("node-open"), eligible.stream().map(NodeCandidate::nodeId).toList());
  }

  @Test
  void eligible_nodes_tolerate_all_taints_bypasses_the_taint_filter_entirely() {
    // A DaemonSet whose manifest set tolerateAllTaints=true must cover every node -- including a
    // node tainted for a different tenant, and one tainted with no tenantId of its own at all.
    List<NodeCandidate> candidates =
        List.of(
            nodeWithTaints("node-tainted", TIER_1_AND_2, 800L * 1024 * 1024, Set.of("tenant-b")),
            nodeWithTaints("node-open", TIER_1_AND_2, 200L * 1024 * 1024, Set.of()));

    List<NodeCandidate> eligible =
        scheduler.eligibleNodes(
            IsolationTier.TIER_2, false, Optional.empty(), Set.of(), true, candidates);

    assertEquals(
        Set.of("node-tainted", "node-open"),
        eligible.stream().map(NodeCandidate::nodeId).collect(Collectors.toSet()));
  }

  @Test
  void eligible_nodes_returns_an_empty_list_rather_than_throwing_when_nothing_qualifies() {
    List<NodeCandidate> candidates =
        List.of(node("node-a", TIER_1_ONLY, 800L * 1024 * 1024, 1000, false));

    List<NodeCandidate> eligible =
        scheduler.eligibleNodes(
            IsolationTier.TIER_2, false, Optional.empty(), Set.of(), false, candidates);

    assertEquals(List.of(), eligible);
  }

  @Test
  void eligible_nodes_returns_an_empty_list_for_no_candidates_at_all() {
    List<NodeCandidate> eligible =
        scheduler.eligibleNodes(
            IsolationTier.TIER_1, false, Optional.empty(), Set.of(), false, List.of());

    assertEquals(List.of(), eligible);
  }

  // ---- sticky placement ----

  @Test
  void sticky_placement_returns_the_sticky_node_even_when_a_roomier_node_exists() {
    List<NodeCandidate> candidates =
        List.of(
            node("node-sticky", TIER_1_AND_2, 200L * 1024 * 1024, 1000, false),
            node("node-roomier", TIER_1_AND_2, 800L * 1024 * 1024, 1000, false));

    String chosen =
        scheduler.place(
            "orders-statefulset",
            0,
            IsolationTier.TIER_1,
            REQUEST,
            false,
            Optional.empty(),
            Set.of(),
            Optional.of("node-sticky"),
            candidates);

    assertEquals("node-sticky", chosen);
  }

  @Test
  void sticky_placement_fails_outright_rather_than_choosing_a_different_node_when_sticky_is_gone() {
    List<NodeCandidate> candidates =
        List.of(node("node-other", TIER_1_AND_2, 800L * 1024 * 1024, 1000, false));

    assertThrows(
        GimleSchedulingException.class,
        () ->
            scheduler.place(
                "orders-statefulset",
                0,
                IsolationTier.TIER_1,
                REQUEST,
                false,
                Optional.empty(),
                Set.of(),
                Optional.of("node-sticky"),
                candidates));
  }

  @Test
  void sticky_placement_fails_when_the_sticky_node_is_cordoned() {
    List<NodeCandidate> candidates =
        List.of(nodeWithCordon("node-sticky", 800L * 1024 * 1024, 1000, true));

    assertThrows(
        GimleSchedulingException.class,
        () ->
            scheduler.place(
                "orders-statefulset",
                0,
                IsolationTier.TIER_1,
                REQUEST,
                false,
                Optional.empty(),
                Set.of(),
                Optional.of("node-sticky"),
                candidates));
  }

  @Test
  void sticky_placement_fails_when_the_sticky_node_no_longer_has_enough_free_capacity() {
    List<NodeCandidate> candidates =
        List.of(node("node-sticky", TIER_1_AND_2, 10L * 1024 * 1024, 1000, false));

    assertThrows(
        GimleSchedulingException.class,
        () ->
            scheduler.place(
                "orders-statefulset",
                0,
                IsolationTier.TIER_1,
                REQUEST,
                false,
                Optional.empty(),
                Set.of(),
                Optional.of("node-sticky"),
                candidates));
  }

  @Test
  void sticky_placement_ignores_anti_affinity_since_there_is_only_ever_one_candidate() {
    // node-sticky is flagged alreadyRunsThisDeployment=true (as it would be for an index revisiting
    // its own prior node) -- anti-affinity must not exclude a node from being sticky-placed onto
    // itself, unlike ordinary (non-sticky) placement where that same flag would disqualify it.
    List<NodeCandidate> candidates =
        List.of(node("node-sticky", TIER_1_AND_2, 800L * 1024 * 1024, 1000, true));

    String chosen =
        scheduler.place(
            "orders-statefulset",
            0,
            IsolationTier.TIER_1,
            REQUEST,
            true,
            Optional.empty(),
            Set.of(),
            Optional.of("node-sticky"),
            candidates);

    assertEquals("node-sticky", chosen);
  }

  @Test
  void sticky_placement_still_enforces_required_labels_on_the_sticky_node() {
    List<NodeCandidate> candidates =
        List.of(nodeWithLabels("node-sticky", 800L * 1024 * 1024, Set.of()));

    assertThrows(
        GimleSchedulingException.class,
        () ->
            scheduler.place(
                "orders-statefulset",
                0,
                IsolationTier.TIER_1,
                REQUEST,
                false,
                Optional.empty(),
                Set.of("gpu"),
                Optional.of("node-sticky"),
                candidates));
  }

  @Test
  void sticky_placement_still_enforces_node_taints_on_the_sticky_node() {
    List<NodeCandidate> candidates =
        List.of(
            nodeWithTaints("node-sticky", TIER_1_AND_2, 800L * 1024 * 1024, Set.of("tenant-b")));

    assertThrows(
        GimleSchedulingException.class,
        () ->
            scheduler.place(
                "orders-statefulset",
                0,
                IsolationTier.TIER_2,
                REQUEST,
                false,
                Optional.of("tenant-a"),
                Set.of(),
                Optional.of("node-sticky"),
                candidates));
  }

  @Test
  void absent_sticky_node_id_behaves_exactly_like_the_non_sticky_overload() {
    List<NodeCandidate> candidates =
        List.of(
            node("node-small", TIER_1_AND_2, 200L * 1024 * 1024, 1000, false),
            node("node-big", TIER_1_AND_2, 800L * 1024 * 1024, 1000, false));

    String chosen =
        scheduler.place(
            "orders",
            0,
            IsolationTier.TIER_1,
            REQUEST,
            false,
            Optional.empty(),
            Set.of(),
            Optional.empty(),
            candidates);

    assertEquals("node-big", chosen);
  }
}
