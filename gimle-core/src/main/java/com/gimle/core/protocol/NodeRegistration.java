package com.gimle.core.protocol;

/** An agent's one-time introduction of itself to the control plane: who it is, what it can run. */
public record NodeRegistration(String nodeId, NodeCapabilities capabilities) {

  public NodeRegistration {
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    if (capabilities == null) {
      throw new IllegalArgumentException("capabilities must not be null");
    }
  }
}
