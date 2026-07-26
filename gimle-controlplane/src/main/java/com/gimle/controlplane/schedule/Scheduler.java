package com.gimle.controlplane.schedule;

import com.gimle.core.exception.GimleSchedulingException;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import java.util.Comparator;
import java.util.List;

/**
 * Bin-packing across registered nodes' latest heartbeat capacity (design §6): first-fit-decreasing
 * over free {@code (memory, cpu)}, filtered by isolation-tier support and, if requested,
 * anti-affinity against nodes already running another replica of the same deployment. A pure
 * function of its inputs -- it never reads the state store itself, so it's testable with synthetic
 * candidates and doesn't need a real module artifact to resolve a tier/resource request against.
 */
public final class Scheduler {

  /**
   * Chooses a node for one replica, returning its id. Anti-affinity is enforced by strict
   * exclusion, not a soft preference: if honoring it would leave no candidate, placement fails
   * outright rather than silently violating the constraint it was asked to honor.
   */
  public String place(
      String deploymentName,
      int instanceIndex,
      IsolationTier tier,
      ResourceSpec resourceRequest,
      boolean antiAffinityAcrossNodes,
      List<NodeCandidate> candidates) {
    List<NodeCandidate> tierEligible =
        candidates.stream().filter(c -> c.capabilities().supportedTiers().contains(tier)).toList();

    List<NodeCandidate> affinityEligible;
    if (antiAffinityAcrossNodes) {
      affinityEligible = tierEligible.stream().filter(c -> !c.alreadyRunsThisDeployment()).toList();
      if (affinityEligible.isEmpty() && !tierEligible.isEmpty()) {
        throw GimleSchedulingException.antiAffinityViolated(deploymentName, instanceIndex);
      }
    } else {
      affinityEligible = tierEligible;
    }

    long requiredMemory = resourceRequest.memoryBytes();
    long requiredCpu = resourceRequest.cpuMillicores();

    return affinityEligible.stream()
        .sorted(
            Comparator.comparingLong(NodeCandidate::freeMemoryBytes)
                .thenComparingLong(NodeCandidate::freeCpuMillicores)
                .reversed())
        .filter(c -> c.freeMemoryBytes() >= requiredMemory && c.freeCpuMillicores() >= requiredCpu)
        .findFirst()
        .map(NodeCandidate::nodeId)
        .orElseThrow(
            () ->
                GimleSchedulingException.noFeasiblePlacement(deploymentName, instanceIndex, tier));
  }
}
