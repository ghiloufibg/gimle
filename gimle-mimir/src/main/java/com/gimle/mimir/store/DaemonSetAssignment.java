package com.gimle.mimir.store;

import com.gimle.core.module.ModuleId;

/**
 * A {@link com.gimle.mimir.manifest.DaemonSetSpec}'s placement decision for one node -- the
 * DaemonSet-kind counterpart to {@link InstanceAssignment}, with {@code nodeId} playing {@code
 * instanceIndex}'s own role as the natural key (store key: {@code daemonSetName + "#" + nodeId}):
 * there is no separate integer index to assign, the node itself already uniquely identifies which
 * "slot" this is. {@code moduleId}/{@code artifactPath} serve the identical rolling-update purpose
 * {@link InstanceAssignment}'s own fields do -- what {@code DaemonSetReconciler} compares against
 * the spec's current {@code moduleId} to detect a version mismatch mid-rollout.
 *
 * <p>Unlike {@link InstanceAssignment}, there is no placeholder/short constructor: every {@code
 * DaemonSetAssignment} is created fresh by {@code DaemonSetReconciler}, which always has a real,
 * just-resolved {@code moduleId}/{@code artifactPath} in hand at that point -- no legacy call site
 * predates this type the way some of {@code InstanceAssignment}'s did rolling updates.
 */
public record DaemonSetAssignment(
    String daemonSetName, String nodeId, ModuleId moduleId, String artifactPath) {

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
    if (artifactPath == null || artifactPath.isBlank()) {
      throw new IllegalArgumentException("artifactPath must not be blank");
    }
  }
}
