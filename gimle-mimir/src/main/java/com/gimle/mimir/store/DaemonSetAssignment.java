package com.gimle.mimir.store;

import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.ModuleId;
import java.util.Optional;

/**
 * A {@link com.gimle.mimir.manifest.DaemonSetSpec}'s placement decision for one node -- the
 * DaemonSet-kind counterpart to {@link InstanceAssignment}, with {@code nodeId} playing {@code
 * instanceIndex}'s own role as the natural key (store key: tenant-scoped {@code daemonSetName + "#"
 * + nodeId}): there is no separate integer index to assign, the node itself already uniquely
 * identifies which "slot" this is. {@code moduleId}/{@code artifactPath} serve the identical
 * rolling-update purpose {@link InstanceAssignment}'s own fields do -- what {@code
 * DaemonSetReconciler} compares against the spec's current {@code moduleId} to detect a version
 * mismatch mid-rollout. {@code tenantId} mirrors {@code DaemonSetSpec#tenantId()}: carried
 * separately here (rather than re-derived from the spec at lookup time) so the store can key this
 * assignment correctly even after the owning spec is gone.
 */
public record DaemonSetAssignment(
    String daemonSetName,
    String nodeId,
    ModuleId moduleId,
    String artifactPath,
    Optional<String> tenantId) {

  public DaemonSetAssignment {
    if (daemonSetName == null || daemonSetName.isBlank()) {
      throw new IllegalArgumentException("daemonSetName must not be blank");
    }
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    if (moduleId == null) {
      throw new IllegalArgumentException("moduleId must not be null");
    }
    ArtifactReference.requireValid(artifactPath);
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be Optional.empty(), not null");
    }
  }

  /** Back-compat: defaults {@code tenantId} to {@code Optional.empty()} (untenanted). */
  public DaemonSetAssignment(
      String daemonSetName, String nodeId, ModuleId moduleId, String artifactPath) {
    this(daemonSetName, nodeId, moduleId, artifactPath, Optional.empty());
  }
}
