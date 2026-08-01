package com.gimle.core.protocol;

import java.util.Optional;

/**
 * An agent's one-time introduction of itself to the control plane: who it is, what it can run.
 *
 * <p>{@code apiAddress} is the agent's own reachable {@code host:port} (self-reported, same pattern
 * as {@code WorkerMain.resolveAdvertisedHost()} -- not captured from the registration request's raw
 * socket, which would be wrong behind NAT/a proxy) for its {@code AgentLogServer}, letting the
 * control plane proxy a log-read request to whichever node actually hosts the target instance.
 * {@code Optional.empty()} for an agent that hasn't started a log server (or an older agent build)
 * -- degrade, don't fail, matching this codebase's existing instrumentation-optionality posture.
 */
public record NodeRegistration(
    String nodeId, NodeCapabilities capabilities, Optional<String> apiAddress) {

  public NodeRegistration {
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    if (capabilities == null) {
      throw new IllegalArgumentException("capabilities must not be null");
    }
    if (apiAddress == null) {
      throw new IllegalArgumentException("apiAddress must not be null (use Optional.empty())");
    }
  }

  public NodeRegistration(String nodeId, NodeCapabilities capabilities) {
    this(nodeId, capabilities, Optional.empty());
  }
}
