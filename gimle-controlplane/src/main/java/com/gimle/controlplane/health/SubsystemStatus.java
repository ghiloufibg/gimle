package com.gimle.controlplane.health;

/** How a control-plane subsystem answered its most recently completed check. */
public enum SubsystemStatus {

  /** Its most recent tick or reachability probe completed successfully. */
  UP,

  /** Its most recent tick or reachability probe failed. */
  DOWN,

  /**
   * This replica does not currently hold the reconciler-leader lease, so a tick-driven subsystem
   * (scheduler, quota enforcer, heartbeat worker) never runs here at all -- a real, expected state
   * on every non-leader replica of a multi-replica control plane, not a fault.
   */
  STANDBY,

  /** This replica is the reconciler leader, but the subsystem hasn't completed a run yet. */
  UNKNOWN
}
