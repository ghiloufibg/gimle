package com.gimle.core.protocol;

/**
 * The kind of entry an {@link InstanceEvent} records: one value per {@code gimle-module}'s own
 * {@code LifecycleEvent} sealed-interface variant, plus {@link #LIVENESS_FAILED}, which records a
 * cause rather than a transition. Deliberately its own copy of the transition names rather than a
 * shared reference to that type: {@code gimle-core} has no dependency on {@code gimle-module}, the
 * same reason {@link ControlMessage.ModuleStateChanged#state} already travels as a plain {@code
 * String} instead of {@code gimle-module}'s own state enum.
 */
public enum InstanceEventKind {
  INSTALLED,
  RESOLVED,
  STARTING,
  ACTIVE,
  STOPPING,
  UNINSTALLED,
  TRANSITION_FAILED,
  COMPLETED,

  /**
   * A liveness probe failed enough consecutive times that the worker restarted the module. Not a
   * transition of its own -- the restart's ordinary STOPPING/UNINSTALLED/INSTALLED/ACTIVE run
   * follows and is recorded the usual way -- but without this entry that run is indistinguishable
   * on the timeline from an operator stopping and redeploying the instance by hand.
   */
  LIVENESS_FAILED
}
