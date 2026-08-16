package com.gimle.testkit.heimdall;

import java.util.OptionalInt;

/**
 * The fluent entry point conditions are built from. An unscoped instance ({@code cluster.when()})
 * accepts a satisfying view from any control-plane replica; a pinned one ({@code cluster.when(1)})
 * only completes conditions on views fetched through that replica -- the deterministic way to
 * assert that state written through one replica is observable through another.
 */
public final class HeimdallScope {

  private final Heimdall heimdall;
  private final OptionalInt replicaFilter;

  HeimdallScope(final Heimdall heimdall, final OptionalInt replicaFilter) {
    this.heimdall = heimdall;
    this.replicaFilter = replicaFilter;
  }

  public DeploymentConditions deployment(final String name) {
    return new DeploymentConditions(heimdall, replicaFilter, name);
  }

  public NodeConditions node(final String nodeId) {
    return new NodeConditions(heimdall, replicaFilter, nodeId);
  }

  public LogConditions instanceLog(
      final String deploymentName, final int instanceIndex, final String category) {
    return new LogConditions(heimdall, replicaFilter, deploymentName, instanceIndex, category);
  }

  /**
   * A condition over cluster-external observable state (a worker process's pid, a file on disk)
   * that no control-plane view or log stream can express -- evaluated by the shared watcher loop,
   * never a poll loop at the call site. Replica pinning does not apply: probes observe the machine,
   * not a control plane.
   */
  public HeimdallCondition probe(
      final String description, final java.util.function.BooleanSupplier condition) {
    return heimdall.registerProbeCondition(description, condition);
  }

  String describeScope() {
    return replicaFilter.isPresent()
        ? " (observed via control-plane replica #" + replicaFilter.getAsInt() + ")"
        : "";
  }
}
