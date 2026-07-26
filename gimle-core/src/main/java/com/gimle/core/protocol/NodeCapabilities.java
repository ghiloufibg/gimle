package com.gimle.core.protocol;

import com.gimle.core.module.IsolationTier;
import java.util.Set;

/**
 * Which isolation tiers a node's active {@code ResourceLimiter} supports -- reported once at
 * registration rather than assumed uniform across every node, so the scheduler can reject (never
 * downgrade) a replica whose tier a specific node can't honor, even though every Phase 2 node
 * reports the same thing today ({@code PortableJvmFlagsResourceLimiter} supports TIER_1/ TIER_2
 * everywhere). Modeling it per-node from the start avoids a breaking wire change once Tier 3
 * support varies by node.
 */
public record NodeCapabilities(Set<IsolationTier> supportedTiers) {

  public NodeCapabilities {
    supportedTiers = Set.copyOf(supportedTiers);
  }
}
