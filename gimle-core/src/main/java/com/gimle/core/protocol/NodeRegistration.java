package com.gimle.core.protocol;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * An agent's one-time introduction of itself to the control plane: who it is, what it can run.
 *
 * <p>{@code apiAddress} is the agent's own reachable {@code host:port} (self-reported, same pattern
 * as {@code WorkerMain.resolveAdvertisedHost()} -- not captured from the registration request's raw
 * socket, which would be wrong behind NAT/a proxy) for its {@code AgentLogServer}, letting the
 * control plane proxy a log-read request to whichever node actually hosts the target instance.
 * {@code Optional.empty()} for an agent that hasn't started a log server (or an older agent build)
 * -- degrade, don't fail, matching this codebase's existing instrumentation-optionality posture.
 *
 * <p>Labels come from two places that must not overwrite each other. {@code
 * capabilities().labels()} is what the node itself reported at startup, from its own launch
 * configuration; {@code operatorLabels} is what an operator applied afterwards against a running
 * cluster. Keeping them apart is what lets a node re-register -- which replaces its whole
 * self-reported half -- without silently discarding labels the operator applied, and lets an
 * operator label a node whose launch configuration they cannot reach or change. Placement matches
 * against {@link #effectiveLabels()}, the union: a label means the same thing to a manifest
 * whichever half it arrived through.
 */
public record NodeRegistration(
    String nodeId,
    NodeCapabilities capabilities,
    Optional<String> apiAddress,
    Set<String> operatorLabels) {

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
    if (operatorLabels == null) {
      throw new IllegalArgumentException("operatorLabels must not be null (use Set.of())");
    }
    operatorLabels = Set.copyOf(operatorLabels);
  }

  public NodeRegistration(String nodeId, NodeCapabilities capabilities) {
    this(nodeId, capabilities, Optional.empty(), Set.of());
  }

  public NodeRegistration(
      String nodeId, NodeCapabilities capabilities, Optional<String> apiAddress) {
    this(nodeId, capabilities, apiAddress, Set.of());
  }

  /** Every label this node satisfies, however it was applied. */
  public Set<String> effectiveLabels() {
    Set<String> all = new LinkedHashSet<>(capabilities.labels());
    all.addAll(operatorLabels);
    return Set.copyOf(all);
  }

  public NodeRegistration withOperatorLabels(Set<String> labels) {
    return new NodeRegistration(nodeId, capabilities, apiAddress, labels);
  }
}
