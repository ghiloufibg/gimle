package com.gimle.controlplane.manifest;

import java.util.Optional;
import java.util.Set;

/**
 * Placement-time constraints layered on top of a module's own descriptor (which says nothing about
 * placement). Phase 3 keeps this deliberately minimal: a flat label-set match, no label expressions
 * or operators, matching the spec's own scope for this phase.
 */
public record PlacementConstraints(
    Optional<Set<String>> requiredNodeLabels, boolean antiAffinityAcrossNodes) {

  public static final PlacementConstraints NONE = new PlacementConstraints(Optional.empty(), false);

  public PlacementConstraints {
    if (requiredNodeLabels == null) {
      throw new IllegalArgumentException("requiredNodeLabels must be Optional.empty(), not null");
    }
    requiredNodeLabels = requiredNodeLabels.map(Set::copyOf);
  }
}
