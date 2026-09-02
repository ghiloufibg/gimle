package com.gimle.controlplane.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The PriorityClass preemption analogue: which instances the scheduler is willing to evict to seat
 * a workload the cluster has no room for, and — more importantly — which it refuses to.
 *
 * <p>A pure function over synthetic candidates, so every case here is exact rather than dependent
 * on a real cluster's timing: no store, no artifacts, no reconcile loop.
 */
class SchedulerPreemptionTest {

  private final Scheduler scheduler = new Scheduler();

  private static final ResourceSpec SMALL = new ResourceSpec("64Mi", "100m");
  private static final ResourceSpec LARGE = new ResourceSpec("512Mi", "1000m");

  /** A node with {@code freeMemory}/{@code freeCpu} left, running {@code residents}. */
  private static NodeCandidate node(
      String nodeId, long freeMemoryBytes, long freeCpuMillicores, ResidentInstance... residents) {
    long totalMemory = 8L * 1024 * 1024 * 1024;
    long totalCpu = 8_000;
    return new NodeCandidate(
        nodeId,
        new NodeCapabilities(Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2)),
        new ResourceUsageSnapshot(
            totalMemory, totalMemory - freeMemoryBytes, totalCpu, totalCpu - freeCpuMillicores),
        false,
        Set.of(),
        false,
        List.of(residents));
  }

  private static ResidentInstance resident(
      String name, int index, int priority, ResourceSpec spec) {
    return new ResidentInstance(name, index, Optional.of("acme"), priority, spec);
  }

  private Optional<Scheduler.Preemption> preemptFor(
      int priority, ResourceSpec request, List<NodeCandidate> candidates) {
    return scheduler.preemption(
        IsolationTier.TIER_1, request, priority, false, Optional.of("acme"), Set.of(), candidates);
  }

  @Test
  void a_higher_priority_workload_evicts_a_lower_priority_one_to_fit() {
    NodeCandidate full = node("node-a", 0, 0, resident("batch", 0, 0, LARGE));

    Scheduler.Preemption preemption = preemptFor(100, LARGE, List.of(full)).orElseThrow();

    assertEquals("node-a", preemption.nodeId());
    assertEquals(1, preemption.victims().size());
    assertEquals("batch", preemption.victims().get(0).deploymentName());
  }

  @Test
  void an_equal_priority_instance_is_never_a_victim() {
    // Two peers displacing each other forever is the failure this prevents: equal priority means
    // the cluster was given no basis to prefer either.
    NodeCandidate full = node("node-a", 0, 0, resident("peer", 0, 100, LARGE));

    assertTrue(preemptFor(100, LARGE, List.of(full)).isEmpty());
  }

  @Test
  void a_higher_priority_resident_is_never_a_victim() {
    NodeCandidate full = node("node-a", 0, 0, resident("critical", 0, 500, LARGE));

    assertTrue(preemptFor(100, LARGE, List.of(full)).isEmpty());
  }

  @Test
  void nothing_is_evicted_when_evicting_everything_still_would_not_fit() {
    // Under-evicting is the correct failure: tearing instances down and still not seating the
    // workload would cost the disruption and buy nothing.
    NodeCandidate full = node("node-a", 0, 0, resident("batch", 0, 0, SMALL));

    assertTrue(preemptFor(100, LARGE, List.of(full)).isEmpty());
  }

  @Test
  void the_lowest_priority_residents_are_taken_first() {
    NodeCandidate full =
        node(
            "node-a",
            0,
            0,
            resident("mid", 0, 50, SMALL),
            resident("lowest", 0, 1, SMALL),
            resident("low", 0, 10, SMALL));

    Scheduler.Preemption preemption = preemptFor(100, SMALL, List.of(full)).orElseThrow();

    assertEquals(1, preemption.victims().size());
    assertEquals("lowest", preemption.victims().get(0).deploymentName());
  }

  @Test
  void the_node_needing_the_fewest_evictions_wins() {
    NodeCandidate expensive =
        node("node-costly", 0, 0, resident("a", 0, 0, SMALL), resident("b", 1, 0, SMALL));
    NodeCandidate cheap = node("node-cheap", 0, 0, resident("c", 0, 0, LARGE));

    Scheduler.Preemption preemption =
        preemptFor(100, LARGE, List.of(expensive, cheap)).orElseThrow();

    assertEquals("node-cheap", preemption.nodeId());
    assertEquals(1, preemption.victims().size());
  }

  @Test
  void a_node_with_room_already_needs_no_victims() {
    NodeCandidate roomy = node("node-a", 8L * 1024 * 1024 * 1024, 8_000);

    Scheduler.Preemption preemption = preemptFor(100, SMALL, List.of(roomy)).orElseThrow();

    assertTrue(preemption.victims().isEmpty());
  }

  @Test
  void an_ineligible_node_is_never_preempted_however_low_its_residents_rank() {
    // The eligibility walk runs first, so a cordon, taint, tier or label mismatch means no
    // preemption -- evicting from a node the workload could not be placed on regardless would be
    // pure disruption. Asserted through a cordon, the simplest of the four to express.
    NodeCandidate cordoned =
        new NodeCandidate(
            "node-a",
            new NodeCapabilities(Set.of(IsolationTier.TIER_1)),
            new ResourceUsageSnapshot(1024, 1024, 1000, 1000),
            false,
            Set.of(),
            true,
            List.of(resident("batch", 0, 0, LARGE)));

    assertTrue(preemptFor(100, LARGE, List.of(cordoned)).isEmpty());
  }

  @Test
  void enough_victims_are_taken_when_one_does_not_free_enough() {
    NodeCandidate full =
        node("node-a", 0, 0, resident("a", 0, 0, SMALL), resident("b", 1, 0, SMALL));
    // Needs both SMALL reservations back to cover one request of their combined size.
    ResourceSpec combined = new ResourceSpec("128Mi", "200m");

    Scheduler.Preemption preemption = preemptFor(100, combined, List.of(full)).orElseThrow();

    assertEquals(2, preemption.victims().size());
  }

  @Test
  void cpu_pressure_alone_is_enough_to_preempt() {
    // Memory is plentiful, CPU is not -- a preemption keyed only on memory would find nothing.
    NodeCandidate full = node("node-a", 8L * 1024 * 1024 * 1024, 0, resident("batch", 0, 0, LARGE));

    Scheduler.Preemption preemption = preemptFor(100, LARGE, List.of(full)).orElseThrow();

    assertEquals(1, preemption.victims().size());
  }
}
