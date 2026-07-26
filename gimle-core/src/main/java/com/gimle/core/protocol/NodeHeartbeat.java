package com.gimle.core.protocol;

import java.util.List;

/** Periodic report from an agent to the control plane: its capacity, and what it's running. */
public record NodeHeartbeat(
    String nodeId, ResourceUsageSnapshot capacity, List<InstanceObservation> instances) {

  public NodeHeartbeat {
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    if (capacity == null) {
      throw new IllegalArgumentException("capacity must not be null");
    }
    instances = List.copyOf(instances);
  }
}
