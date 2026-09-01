package com.gimle.controlplane.schedule;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleSchedulingException;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The wording of a placement failure is itself the deliverable here, not incidental: an operator
 * scaling a real cluster reads only this message, and "add capacity", "add a node supporting this
 * tier" and "remove a taint/cordon/label constraint" are three different actions. Each test asserts
 * the specific numbers and names a message must carry to tell them apart -- and, where it matters,
 * asserts a message does *not* claim a cause the scheduler didn't actually observe.
 */
class SchedulerDiagnosticsTest {

  private static final long MI = 1024L * 1024;
  private static final ResourceSpec REQUEST = new ResourceSpec("100Mi", "100m");
  private static final NodeCapabilities TIER_1_AND_2 =
      new NodeCapabilities(Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2));
  private static final NodeCapabilities TIER_1_ONLY =
      new NodeCapabilities(Set.of(IsolationTier.TIER_1));

  private final Scheduler scheduler = new Scheduler();

  private static NodeCandidate node(
      String id, NodeCapabilities capabilities, long freeMemoryBytes, long freeCpuMillicores) {
    return new NodeCandidate(
        id,
        capabilities,
        new ResourceUsageSnapshot(freeMemoryBytes, 0, freeCpuMillicores, 0),
        false);
  }

  private String placementFailure(List<NodeCandidate> candidates) {
    return placementFailure(IsolationTier.TIER_1, Optional.empty(), Set.of(), candidates);
  }

  private String placementFailure(
      IsolationTier tier,
      Optional<String> tenantId,
      Set<String> requiredLabels,
      List<NodeCandidate> candidates) {
    return assertThrows(
            GimleSchedulingException.class,
            () ->
                scheduler.place(
                    "orders", 3, tier, REQUEST, false, tenantId, requiredLabels, candidates))
        .getMessage();
  }

  // ---- capacity shortfall: which dimension, and by how much ----

  @Test
  void insufficient_memory_names_the_dimension_the_shortfall_and_the_roomiest_node() {
    String message = placementFailure(List.of(node("node-a", TIER_1_AND_2, 10 * MI, 1000)));

    assertTrue(message.contains("deployment orders instance 3"), message);
    assertTrue(message.contains("requests memory=100Mi cpu=100m"), message);
    assertTrue(message.contains("memory is short by 90Mi"), message);
    assertTrue(message.contains("the most any candidate has free is 10Mi, on node-a"), message);
    assertTrue(
        message.contains("free capacity per candidate node: node-a memory=10Mi cpu=1000m"),
        message);
    // The cpu dimension fits, so the message must not imply otherwise.
    assertFalse(message.contains("cpu is short"), message);
  }

  @Test
  void insufficient_cpu_names_the_dimension_the_shortfall_and_the_roomiest_node() {
    String message = placementFailure(List.of(node("node-a", TIER_1_AND_2, 800 * MI, 40)));

    assertTrue(message.contains("cpu is short by 60m"), message);
    assertTrue(message.contains("the most any candidate has free is 40m, on node-a"), message);
    assertFalse(message.contains("memory is short"), message);
  }

  @Test
  void a_shortfall_on_both_dimensions_names_both() {
    String message = placementFailure(List.of(node("node-a", TIER_1_AND_2, 10 * MI, 40)));

    assertTrue(message.contains("memory is short by 90Mi"), message);
    assertTrue(message.contains("cpu is short by 60m"), message);
  }

  /**
   * Both dimensions individually fit somewhere, yet no one node has both free -- reported as itself
   * rather than as a fabricated shortfall on a dimension that isn't actually short anywhere.
   */
  @Test
  void a_request_no_single_node_fits_though_each_dimension_fits_somewhere_says_exactly_that() {
    String message =
        placementFailure(
            List.of(
                node("node-a", TIER_1_AND_2, 800 * MI, 10),
                node("node-b", TIER_1_AND_2, 10 * MI, 1000)));

    assertTrue(message.contains("no single node has both memory and cpu free at once"), message);
    assertTrue(message.contains("the most memory free is 800Mi on node-a"), message);
    assertTrue(message.contains("the most cpu free is 1000m on node-b"), message);
    assertFalse(message.contains("short by"), message);
  }

  @Test
  void the_per_node_capacity_listing_is_capped_and_summarizes_the_remainder() {
    List<NodeCandidate> candidates = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      candidates.add(node("node-" + i, TIER_1_AND_2, 10 * MI, 1000));
    }

    String message = placementFailure(candidates);

    assertTrue(message.contains("none of the 8 candidate node(s)"), message);
    assertTrue(message.contains("node-0 memory=10Mi cpu=1000m"), message);
    assertTrue(message.contains("node-4 memory=10Mi cpu=1000m"), message);
    assertTrue(message.contains("and 3 more"), message);
    assertFalse(message.contains("node-5 memory"), message);
  }

  // ---- causes a capacity change cannot fix ----

  @Test
  void an_unsupported_tier_names_what_each_node_supports_and_says_capacity_will_not_help() {
    String message =
        placementFailure(
            IsolationTier.TIER_2,
            Optional.empty(),
            Set.of(),
            List.of(
                node("node-a", TIER_1_ONLY, 800 * MI, 1000),
                node("node-b", TIER_1_ONLY, 800 * MI, 1000)));

    assertTrue(message.contains("requires isolation tier TIER_2"), message);
    assertTrue(message.contains("none of the 2 registered node(s) supports"), message);
    assertTrue(message.contains("node-a supports [TIER_1]"), message);
    assertTrue(message.contains("node-b supports [TIER_1]"), message);
    assertTrue(message.contains("Adding capacity to these nodes cannot place it"), message);
    // A tier failure is not a capacity failure; claiming a shortfall would send the operator to
    // the wrong remedy entirely.
    assertFalse(message.contains("short by"), message);
  }

  @Test
  void an_empty_cluster_is_reported_as_itself_not_as_a_capacity_shortfall() {
    String message = placementFailure(List.of());

    assertTrue(message.contains("no nodes are currently registered"), message);
    assertFalse(message.contains("short by"), message);
  }

  @Test
  void a_taint_exclusion_names_the_blocking_nodes_tenants_and_the_remedy() {
    NodeCandidate tainted =
        new NodeCandidate(
            "node-reserved",
            TIER_1_AND_2,
            new ResourceUsageSnapshot(800 * MI, 0, 1000, 0),
            false,
            Set.of("tenant-b"));

    String message =
        placementFailure(IsolationTier.TIER_1, Optional.of("tenant-a"), Set.of(), List.of(tainted));

    assertTrue(message.contains("node-reserved is tainted for tenant(s) tenant-b"), message);
    assertTrue(message.contains("Untaint one of them"), message);
    // The node has ample room; a capacity claim here would be a lie.
    assertFalse(message.contains("short by"), message);
  }

  @Test
  void a_cordon_exclusion_names_every_cordoned_node_and_the_remedy() {
    NodeCandidate cordoned =
        new NodeCandidate(
            "node-drained",
            TIER_1_AND_2,
            new ResourceUsageSnapshot(800 * MI, 0, 1000, 0),
            false,
            Set.of(),
            true);

    String message = placementFailure(List.of(cordoned));

    assertTrue(
        message.contains("every node with tier support is cordoned -- node-drained"), message);
    assertTrue(message.contains("Uncordon one of them"), message);
    assertFalse(message.contains("short by"), message);
  }

  @Test
  void a_required_label_failure_names_the_required_labels_and_what_each_node_carries() {
    NodeCandidate plain =
        new NodeCandidate(
            "node-plain",
            new NodeCapabilities(Set.of(IsolationTier.TIER_1), Set.of("ssd")),
            new ResourceUsageSnapshot(800 * MI, 0, 1000, 0),
            false);

    String message =
        placementFailure(IsolationTier.TIER_1, Optional.empty(), Set.of("gpu"), List.of(plain));

    assertTrue(message.contains("requires node labels [gpu]"), message);
    assertTrue(message.contains("node-plain carries [ssd]"), message);
    assertTrue(message.contains("relax placement.requiredLabels"), message);
    assertFalse(message.contains("short by"), message);
  }

  // ---- sticky placement: one node, so the message names the one thing wrong with it ----

  private String stickyFailure(
      IsolationTier tier,
      Optional<String> tenantId,
      Set<String> requiredLabels,
      List<NodeCandidate> candidates) {
    return assertThrows(
            GimleSchedulingException.class,
            () ->
                scheduler.place(
                    "orders-set",
                    2,
                    tier,
                    REQUEST,
                    false,
                    tenantId,
                    requiredLabels,
                    Optional.of("node-sticky"),
                    candidates))
        .getMessage();
  }

  @Test
  void a_gone_sticky_node_says_it_is_not_registered_rather_than_listing_every_possible_cause() {
    String message =
        stickyFailure(
            IsolationTier.TIER_1,
            Optional.empty(),
            Set.of(),
            List.of(node("node-other", TIER_1_AND_2, 800 * MI, 1000)));

    assertTrue(message.contains("sticky-bound to node node-sticky"), message);
    assertTrue(message.contains("it is not a registered, live node"), message);
    assertTrue(message.contains("will not be rescheduled elsewhere"), message);
  }

  @Test
  void a_full_sticky_node_names_the_shortfall_against_its_own_free_capacity() {
    String message =
        stickyFailure(
            IsolationTier.TIER_1,
            Optional.empty(),
            Set.of(),
            List.of(node("node-sticky", TIER_1_AND_2, 10 * MI, 1000)));

    assertTrue(message.contains("it has memory=10Mi cpu=1000m free"), message);
    assertTrue(message.contains("against a request of memory=100Mi cpu=100m"), message);
    assertTrue(message.contains("short by 90Mi memory"), message);
  }

  @Test
  void a_cordoned_sticky_node_says_so_instead_of_blaming_capacity() {
    NodeCandidate cordoned =
        new NodeCandidate(
            "node-sticky",
            TIER_1_AND_2,
            new ResourceUsageSnapshot(800 * MI, 0, 1000, 0),
            false,
            Set.of(),
            true);

    String message =
        stickyFailure(IsolationTier.TIER_1, Optional.empty(), Set.of(), List.of(cordoned));

    assertTrue(message.contains("it is cordoned"), message);
    assertFalse(message.contains("short by"), message);
  }

  @Test
  void a_sticky_node_lacking_tier_support_names_the_tier_it_does_support() {
    String message =
        stickyFailure(
            IsolationTier.TIER_2,
            Optional.empty(),
            Set.of(),
            List.of(node("node-sticky", TIER_1_ONLY, 800 * MI, 1000)));

    assertTrue(message.contains("it does not support isolation tier TIER_2"), message);
    assertTrue(message.contains("it supports [TIER_1]"), message);
  }

  @Test
  void a_sticky_node_missing_a_required_label_names_the_missing_label() {
    NodeCandidate plain =
        new NodeCandidate(
            "node-sticky",
            new NodeCapabilities(Set.of(IsolationTier.TIER_1), Set.of("ssd")),
            new ResourceUsageSnapshot(800 * MI, 0, 1000, 0),
            false);

    String message =
        stickyFailure(IsolationTier.TIER_1, Optional.empty(), Set.of("gpu"), List.of(plain));

    assertTrue(message.contains("it is missing required node label(s) [gpu]"), message);
  }

  @Test
  void a_tainted_sticky_node_names_the_tenant_it_is_reserved_for() {
    NodeCandidate tainted =
        new NodeCandidate(
            "node-sticky",
            TIER_1_AND_2,
            new ResourceUsageSnapshot(800 * MI, 0, 1000, 0),
            false,
            Set.of("tenant-b"));

    String message =
        stickyFailure(IsolationTier.TIER_1, Optional.of("tenant-a"), Set.of(), List.of(tainted));

    assertTrue(message.contains("it is tainted for tenant(s) tenant-b"), message);
  }
}
