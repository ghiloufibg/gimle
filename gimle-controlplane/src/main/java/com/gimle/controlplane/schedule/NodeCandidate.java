package com.gimle.controlplane.schedule;

import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.ResourceUsageSnapshot;

/**
 * One registered node's placement-relevant state, as of its latest heartbeat: what it can run, how
 * much room it has, and whether it's already running another replica of the deployment being placed
 * (the caller -- {@code DeploymentReconciler} -- derives this last flag from the state store's
 * current assignments; the scheduler itself never reads the store directly, keeping it a pure
 * function of its inputs and testable without one).
 */
public record NodeCandidate(
    String nodeId,
    NodeCapabilities capabilities,
    ResourceUsageSnapshot capacity,
    boolean alreadyRunsThisDeployment) {

  long free_memory_bytes() {
    return capacity.totalMemoryBytes() - capacity.assignedMemoryBytes();
  }

  long free_cpu_millicores() {
    return capacity.totalCpuMillicores() - capacity.assignedCpuMillicores();
  }
}
