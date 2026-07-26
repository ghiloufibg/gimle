package com.gimle.core.protocol;

import com.gimle.core.module.ModuleId;

/**
 * One node's current view of one deployment instance it's supervising: which module it's running,
 * its lifecycle state, and liveness/readiness. {@code lifecycleState} travels as a plain {@code
 * String} (a {@code ModuleState} name), not {@code gimle-module}'s own enum -- same choice {@link
 * ControlMessage.ModuleStateChanged} already made and for the identical reason: {@code gimle-core}
 * has no dependency on {@code gimle-module}, and the control plane only needs to track/relay/diff
 * the state, not interpret it.
 */
public record InstanceObservation(
    String deploymentName,
    int instanceIndex,
    ModuleId moduleId,
    String lifecycleState,
    boolean alive,
    boolean ready) {

  public InstanceObservation {
    if (deploymentName == null || deploymentName.isBlank()) {
      throw new IllegalArgumentException("deploymentName must not be blank");
    }
    if (instanceIndex < 0) {
      throw new IllegalArgumentException("instanceIndex must not be negative: " + instanceIndex);
    }
    if (moduleId == null) {
      throw new IllegalArgumentException("moduleId must not be null");
    }
    if (lifecycleState == null || lifecycleState.isBlank()) {
      throw new IllegalArgumentException("lifecycleState must not be blank");
    }
  }
}
