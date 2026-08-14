package com.gimle.mimir.store;

import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.ModuleId;

/**
 * The scheduler's placement decision for one {@code StatefulSet} index -- mirrors {@link
 * InstanceAssignment} exactly (a real {@code instanceIndex}, unlike {@link DaemonSetAssignment}'s
 * always-{@code 0}), with one added guarantee {@code InstanceAssignment} doesn't carry: once an
 * index is first placed on {@code nodeId}, {@code StatefulSetReconciler} sticky-binds every future
 * placement attempt for that same index back to that same node (see {@code
 * StateStore#putStatefulSetIndexNode}) -- {@code nodeId} here is always a snapshot of "where it is
 * right now," but the sticky binding itself outlives any single assignment record, surviving a
 * rolling-update remove-then-replace within the same node.
 */
public record StatefulSetAssignment(
    String statefulSetName,
    int instanceIndex,
    String nodeId,
    ModuleId moduleId,
    String artifactPath) {

  public StatefulSetAssignment {
    if (statefulSetName == null || statefulSetName.isBlank()) {
      throw new IllegalArgumentException("statefulSetName must not be blank");
    }
    if (instanceIndex < 0) {
      throw new IllegalArgumentException("instanceIndex must not be negative: " + instanceIndex);
    }
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    if (moduleId == null) {
      throw new IllegalArgumentException("moduleId must not be null");
    }
    ArtifactReference.requireValid(artifactPath);
  }
}
