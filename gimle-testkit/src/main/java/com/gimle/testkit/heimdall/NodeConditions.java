package com.gimle.testkit.heimdall;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;

/** Conditions over one node's control-plane-visible state. */
public final class NodeConditions {

  private final Heimdall heimdall;
  private final OptionalInt replicaFilter;
  private final String nodeId;

  NodeConditions(final Heimdall heimdall, final OptionalInt replicaFilter, final String nodeId) {
    this.heimdall = heimdall;
    this.replicaFilter = replicaFilter;
    this.nodeId = nodeId;
  }

  /**
   * The node appears in {@code GET /nodes} at all -- the agent registers only after its gossip join
   * has completed, so this is a real proxy for "the agent is up and joined", not just "the process
   * started".
   */
  public HeimdallCondition isRegistered() {
    return register("node " + nodeId + " is registered", view -> view.nodes().containsKey(nodeId));
  }

  public HeimdallCondition isCordoned(final boolean expected) {
    return register(
        "node " + nodeId + " has cordoned=" + expected,
        view ->
            view.nodes().containsKey(nodeId) && view.nodes().get(nodeId).cordoned() == expected);
  }

  private HeimdallCondition register(
      final String description, final Predicate<ClusterView> predicate) {
    return heimdall.registerViewCondition(description, replicaFilter, Optional.empty(), predicate);
  }
}
