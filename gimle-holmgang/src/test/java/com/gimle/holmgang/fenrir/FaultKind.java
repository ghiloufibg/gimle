package com.gimle.holmgang.fenrir;

/**
 * The palette of faults Fenrir can inject, each mapped to a primitive the cluster already exposes.
 * The kill/bounce distinction is deliberate: {@link #WORKER_KILL} is a true kill with no
 * harness-side restart, because the platform's own supervisor is what must bring the worker back --
 * if it doesn't, that is the finding. Every other kind is a bounce (kill, dwell, harness {@code
 * restart()}) because nothing in the platform restarts a store, control plane, or Fafnir, so the
 * property being validated is tolerance during the outage and rejoin after it, not a restart the
 * platform never claimed to do. {@link #LINK_CUT} and {@link #STORE_PARTITION} are neither kill nor
 * bounce -- the victim process stays alive the whole time, only its network reachability changes.
 */
public enum FaultKind {
  WORKER_KILL,
  STORE_BOUNCE,
  LEADER_BOUNCE,
  CONTROL_PLANE_BOUNCE,
  LINK_CUT,
  STORE_PARTITION,
  FAFNIR_BOUNCE
}
