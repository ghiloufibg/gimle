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

  /** Bounces a Fafnir replica, validating secrets-path degradation and recovery. */
  public static Pool fafnirBounces() {
    return new Pool(FaultKind.FAFNIR_BOUNCE, 1, DEFAULT_DWELL);
  }

  /**
   * Bounces a Muninn replica, validating shipping's fan-out tolerates one endpoint going dark. Only
   * meaningful -- and only ever drawn -- on a topology with more than one replica; see {@link
   * FaultKind#MUNINN_BOUNCE}.
   */
  public static Pool muninnBounces() {
    return new Pool(FaultKind.MUNINN_BOUNCE, 1, DEFAULT_DWELL);
  }

  /**
   * Bounces an Andvari replica, validating the registry's peer-sync failover. Only meaningful --
   * and only ever drawn -- on a topology with more than one replica; see {@link
   * FaultKind#ANDVARI_BOUNCE}.
   */
  public static Pool andvariBounces() {
    return new Pool(FaultKind.ANDVARI_BOUNCE, 1, DEFAULT_DWELL);
  }
}
