package com.gimle.controlplane.schedule;

import com.gimle.core.module.ResourceSpec;
import java.util.Optional;

/**
 * One instance currently assigned to a node, described only in the terms preemption needs: what it
 * would give back if evicted, and how strongly it is entitled not to be.
 *
 * <p>{@code request} is the workload's own declared resource request, the same figure that was
 * charged against the node's capacity when this instance was placed -- so reclaiming it is exactly
 * the inverse of the bin-packing decision that consumed it, not an estimate from live usage. Using
 * observed usage instead would let a quiet instance look free to evict while still holding a
 * reservation the scheduler must honour for everything else.
 */
public record ResidentInstance(
    String deploymentName,
    int instanceIndex,
    Optional<String> tenantId,
    int priority,
    ResourceSpec request) {

  public ResidentInstance {
    if (deploymentName == null || deploymentName.isBlank()) {
      throw new IllegalArgumentException("deploymentName must not be blank");
    }
    if (instanceIndex < 0) {
      throw new IllegalArgumentException("instanceIndex must not be negative: " + instanceIndex);
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be Optional.empty(), not null");
    }
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
  }
}
