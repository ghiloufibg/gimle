package com.gimle.hugin.model;

import java.time.Instant;
import java.util.Comparator;

/**
 * How the node table is ordered. The same rule the instance table follows: a utilization or an age
 * sorts worst-first, because the reason to sort by one is to put the node about to be a problem on
 * the first row; the id sorts alphabetically, which is a stable reading rather than a ranking.
 *
 * <p>Utilization is compared as a fraction of each node's own capacity rather than as an absolute
 * figure, so a small node running hot outranks a large one that is merely busy -- the first is
 * about to refuse placements and the second is doing its job.
 */
public enum NodeSortKey {
  ID("id"),
  CPU("cpu"),
  MEMORY("mem"),
  INSTANCES("inst"),
  HEARTBEAT("heartbeat");

  private final String label;

  NodeSortKey(final String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public NodeSortKey next() {
    NodeSortKey[] keys = values();
    return keys[(ordinal() + 1) % keys.length];
  }

  /**
   * Heartbeat age needs the current instant to mean anything, so the comparator is built per read
   * rather than held as a constant the way the instance table's are.
   */
  public Comparator<NodeRow> comparator(final Instant now) {
    Comparator<NodeRow> byId = Comparator.comparing(NodeRow::nodeId);
    return switch (this) {
      case ID -> byId;
      case CPU ->
          descending(node -> used(node.assignedCpuMillicores(), node.totalCpuMillicores()))
              .thenComparing(byId);
      case MEMORY ->
          descending(node -> used(node.assignedMemoryBytes(), node.totalMemoryBytes()))
              .thenComparing(byId);
      case INSTANCES -> descending(node -> (double) node.instanceCount()).thenComparing(byId);
      case HEARTBEAT ->
          descending(
                  node ->
                      node.heartbeatAge(now)
                          // A node that has never heartbeated is the oldest news there is.
                          .map(age -> (double) age.toMillis())
                          .orElse(Double.MAX_VALUE))
              .thenComparing(byId);
    };
  }

  /**
   * Kept here rather than borrowed from the renderer's own formatting helper: a model type that
   * imported one would put the layering the wrong way round.
   */
  private static double used(final long assigned, final long total) {
    return total <= 0 ? 0.0 : Math.clamp((double) assigned / total, 0.0, 1.0);
  }

  private static Comparator<NodeRow> descending(
      final java.util.function.ToDoubleFunction<NodeRow> measure) {
    return Comparator.comparingDouble(measure).reversed();
  }
}
