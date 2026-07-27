package com.gimle.controlplane.schedule;

import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import java.util.Set;

/**
 * One registered node's placement-relevant state, as of its latest heartbeat: what it can run, how
 * much room it has, and whether it's already running another replica of the deployment being placed
 * (the caller -- {@code DeploymentReconciler} -- derives this last flag from the state store's
 * current assignments; the scheduler itself never reads the store directly, keeping it a pure
 * function of its inputs and testable without one).
 *
 * <p>{@code tenantsPresent} is every distinct {@code tenantId} of a currently assigned deployment
 * on this node, across every deployment, not just the one being placed -- used to enforce
 * node-level tenant segregation for Tier 2/3 placements, which have a real process/kernel isolation
 * boundary regardless of node co-residency but may still need physical separation for compliance
 * reasons. Empty for an untenanted candidate set, which exempts every deployment from this filter.
 */
public record NodeCandidate(
    String nodeId,
    NodeCapabilities capabilities,
    ResourceUsageSnapshot capacity,
    boolean alreadyRunsThisDeployment,
    Set<String> tenantsPresent) {

  public NodeCandidate {
    tenantsPresent = Set.copyOf(tenantsPresent);
  }

  public NodeCandidate(
      String nodeId,
      NodeCapabilities capabilities,
      ResourceUsageSnapshot capacity,
      boolean alreadyRunsThisDeployment) {
    this(nodeId, capabilities, capacity, alreadyRunsThisDeployment, Set.of());
  }

  long freeMemoryBytes() {
    return capacity.totalMemoryBytes() - capacity.assignedMemoryBytes();
  }

  long freeCpuMillicores() {
    return capacity.totalCpuMillicores() - capacity.assignedCpuMillicores();
  }
}
