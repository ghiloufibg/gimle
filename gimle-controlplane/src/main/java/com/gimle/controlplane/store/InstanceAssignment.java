package com.gimle.controlplane.store;

/** The scheduler's placement decision for one deployment replica: which node should run it. */
public record InstanceAssignment(String deploymentName, int instanceIndex, String nodeId) {

  public InstanceAssignment {
    if (deploymentName == null || deploymentName.isBlank()) {
      throw new IllegalArgumentException("deploymentName must not be blank");
    }
    if (instanceIndex < 0) {
      throw new IllegalArgumentException("instanceIndex must not be negative: " + instanceIndex);
    }
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
  }
}
