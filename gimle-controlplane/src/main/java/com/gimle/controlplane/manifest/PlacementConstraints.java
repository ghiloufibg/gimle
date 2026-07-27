package com.gimle.controlplane.manifest;

import java.util.Optional;
import java.util.Set;

/**
 * Placement-time constraints layered on top of a module's own descriptor (which says nothing about
 * placement). Deliberately minimal: a flat label-set match, no label expressions or operators.
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
