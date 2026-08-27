package com.gimle.ragnarok.fenrir;

/**
 * The palette of faults Fenrir can inject, each mapped to a primitive the cluster already exposes.
 * The kill/bounce distinction is deliberate: {@link #WORKER_KILL} is a true kill with no
 * harness-side restart, because the platform's own supervisor is what must bring the worker back --
 * if it doesn't, that is the finding. Every other kind is a bounce (kill, dwell, harness {@code
 * restart()}) because nothing in the platform restarts a store, control plane, Fafnir, Muninn, or
 * Andvari replica, so the property being validated is tolerance during the outage and rejoin after
 * it, not a restart the platform never claimed to do. {@link #MUNINN_BOUNCE} and {@link
 * #ANDVARI_BOUNCE} only ever pick a victim when their own process is running with more than one
 * replica -- bouncing the last one would just be an ordinary kill-the-instance test, not the
 * failover/fan-out property multi-replica topologies actually exist to prove. {@link #LINK_CUT} and
 * {@link #STORE_PARTITION} are neither kill nor bounce -- the victim process stays alive the whole
 * time, only its network reachability changes. Bounce and network faults are only ever drawn on a
 * target with process control or boot-time interposition over its own topology; a target that lacks
 * either records the strike as skipped rather than firing it.
 */
public enum FaultKind {
  WORKER_KILL,
  STORE_BOUNCE,
  LEADER_BOUNCE,
  CONTROL_PLANE_BOUNCE,
  LINK_CUT,
  STORE_PARTITION,
  FAFNIR_BOUNCE,
  MUNINN_BOUNCE,
  ANDVARI_BOUNCE
}
