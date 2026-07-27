package com.gimle.core.protocol;

import com.gimle.core.module.IsolationTier;
import java.util.Set;

/**
 * Which isolation tiers a node's active resource limiter supports, reported once at registration
 * rather than assumed uniform across every node. The scheduler uses this to reject (never
 * downgrade) a replica whose tier a given node can't honor. Modeling this per-node from the start
 * avoids a breaking wire change once tier support varies across nodes.
 */
public record NodeCapabilities(Set<IsolationTier> supportedTiers) {

  public NodeCapabilities {
    supportedTiers = Set.copyOf(supportedTiers);
  }
}
