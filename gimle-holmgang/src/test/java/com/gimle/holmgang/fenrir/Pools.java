package com.gimle.holmgang.fenrir;

import java.time.Duration;

/**
 * Factory for the {@link Pool} kinds, each returning a sensible default the caller then refines.
 * Bounce dwells default to five seconds -- long enough that the outage is real, short enough that a
 * control-plane bounce stays well under the node-dark timeout (see {@link FenrirPlan}'s
 * validation).
 */
public final class Pools {

  private static final Duration DEFAULT_DWELL = Duration.ofSeconds(5);

  private Pools() {}

  /** Kills a worker JVM outright; the platform supervisor must respawn it. Dwell does not apply. */
  public static Pool workerKills() {
    return new Pool(FaultKind.WORKER_KILL, 1, Duration.ZERO);
  }

  /** Bounces a random non-leader store, validating quorum tolerance and Raft rejoin. */
  public static Pool storeBounces() {
    return new Pool(FaultKind.STORE_BOUNCE, 1, DEFAULT_DWELL);
  }

  /** Bounces whichever store currently leads, validating re-election under repeated leader loss. */
  public static Pool leaderBounces() {
    return new Pool(FaultKind.LEADER_BOUNCE, 1, DEFAULT_DWELL);
  }

  /** Bounces a control-plane replica, validating stateless failover and lease re-acquisition. */
  public static Pool controlPlaneBounces() {
    return new Pool(FaultKind.CONTROL_PLANE_BOUNCE, 1, DEFAULT_DWELL);
  }

  /** Cuts one replica's links to the stores, then heals -- proxied topologies only. */
  public static Pool linkCuts() {
    return new Pool(FaultKind.LINK_CUT, 1, DEFAULT_DWELL);
  }

  /**
   * Silently partitions one store replica from the rest of the raft cluster's peer traffic, then
   * heals -- proxied topologies only. Unlike {@link #linkCuts}'s immediate reset, this is a genuine
   * silent drop, validating check-quorum self-demotion and the store transport's connect/read
   * timeouts against a real gray failure rather than a clean refusal.
   */
  public static Pool storePartitions() {
    return new Pool(FaultKind.STORE_PARTITION, 1, DEFAULT_DWELL);
  }

  /** Bounces a Fafnir replica, validating secrets-path degradation and recovery. */
  public static Pool fafnirBounces() {
    return new Pool(FaultKind.FAFNIR_BOUNCE, 1, DEFAULT_DWELL);
  }
}
