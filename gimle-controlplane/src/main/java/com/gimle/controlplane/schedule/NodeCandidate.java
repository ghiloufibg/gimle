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
 *
 * <p>{@code labels} mirrors {@code capabilities().labels()} as its own top-level accessor (rather
 * than requiring every caller to reach through {@code capabilities()}) since the scheduler's
 * required-label filter reads it on every candidate, the same way {@code tenantsPresent} already
 * gets its own accessor instead of being read off the assignment set directly.
 *
 * <p>{@code cordoned} is an operator-set flag (unrelated to the latest-heartbeat capacity/labels
 * above) meaning "don't place anything new here" -- it never evicts what's already running, only
 * excludes the node from future placement, so {@code alreadyRunsThisDeployment}/{@code
 * tenantsPresent} stay meaningful even for a cordoned node.
 */
public record NodeCandidate(
    String nodeId,
    NodeCapabilities capabilities,
    ResourceUsageSnapshot capacity,
    boolean alreadyRunsThisDeployment,
    Set<String> tenantsPresent,
    boolean cordoned) {

  public NodeCandidate {
    tenantsPresent = Set.copyOf(tenantsPresent);
  }

  public NodeCandidate(
      String nodeId,
      NodeCapabilities capabilities,
      ResourceUsageSnapshot capacity,
      boolean alreadyRunsThisDeployment) {
    this(nodeId, capabilities, capacity, alreadyRunsThisDeployment, Set.of(), false);
  }

  public NodeCandidate(
      String nodeId,
      NodeCapabilities capabilities,
      ResourceUsageSnapshot capacity,
      boolean alreadyRunsThisDeployment,
      Set<String> tenantsPresent) {
    this(nodeId, capabilities, capacity, alreadyRunsThisDeployment, tenantsPresent, false);
  }

  Set<String> labels() {
    return capabilities.labels();
  }

  long freeMemoryBytes() {
    return capacity.totalMemoryBytes() - capacity.assignedMemoryBytes();
  }

  long freeCpuMillicores() {
    return capacity.totalCpuMillicores() - capacity.assignedCpuMillicores();
  }
}
