package com.gimle.mimir.manifest;

import java.util.Optional;
import java.util.Set;

/**
 * Placement-time constraints layered on top of a module's own descriptor (which says nothing about
 * placement). Deliberately minimal: a flat label-set match, no label expressions or operators.
 *
 * <p>{@code priority} is the {@code PriorityClass} analogue, collapsed to the resolved integer a
 * PriorityClass exists to name rather than introduced as a separate cluster-scoped kind -- the same
 * simplification {@code ServiceSpec} makes by matching deployments by name instead of by label
 * selector. Higher wins; {@code 0} is the default an ordinary workload gets, and negative values
 * are allowed so a batch workload can be marked explicitly more evictable than the default.
 *
 * <p>Priority is <em>only</em> consulted when the cluster is out of room. It is not a scheduling
 * preference: a workload that fits somewhere is placed there regardless of what else is running, so
 * raising a priority never changes where a workload lands while capacity is available. What it buys
 * is the ability to make room -- see {@code Scheduler#preemption}.
 */
public record PlacementConstraints(
    Optional<Set<String>> requiredNodeLabels, boolean antiAffinityAcrossNodes, int priority) {

  public static final PlacementConstraints NONE =
      new PlacementConstraints(Optional.empty(), false, 0);

  public PlacementConstraints {
    if (requiredNodeLabels == null) {
      throw new IllegalArgumentException("requiredNodeLabels must be Optional.empty(), not null");
    }
    requiredNodeLabels = requiredNodeLabels.map(Set::copyOf);
  }

  /** Defaults {@code priority} to 0, what a workload declaring none gets. */
  public PlacementConstraints(
      Optional<Set<String>> requiredNodeLabels, boolean antiAffinityAcrossNodes) {
    this(requiredNodeLabels, antiAffinityAcrossNodes, 0);
  }
}
