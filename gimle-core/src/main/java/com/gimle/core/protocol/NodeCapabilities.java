package com.gimle.core.protocol;

import com.gimle.core.module.IsolationTier;
import java.util.Set;

/**
 * Which isolation tiers a node's active resource limiter supports, reported once at registration
 * rather than assumed uniform across every node. The scheduler uses this to reject (never
 * downgrade) a replica whose tier a given node can't honor. Modeling this per-node from the start
 * avoids a breaking wire change once tier support varies across nodes.
 *
 * <p>{@code labels} are opaque, operator-assigned tokens (e.g. {@code "gpu"}, {@code "ssd"}) a
 * deployment manifest's {@code placement.requiredLabels} matches against by exact set membership --
 * no key/value structure, no expressions, matching {@code PlacementConstraints}'s own deliberately
 * minimal design. Empty for a node with no labels, which simply can't satisfy any deployment that
 * requires one.
 */
public record NodeCapabilities(Set<IsolationTier> supportedTiers, Set<String> labels) {

  public NodeCapabilities {
    supportedTiers = Set.copyOf(supportedTiers);
    labels = Set.copyOf(labels);
  }

  public NodeCapabilities(Set<IsolationTier> supportedTiers) {
    this(supportedTiers, Set.of());
  }
}
