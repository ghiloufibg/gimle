package com.gimle.controlplane.node;

import com.gimle.mimir.store.ObservedHeartbeat;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Decides how much to trust what the store knows about a node, from two inputs: the node's last
 * recorded heartbeat, and since when the store has been in a position to record one at all.
 *
 * <p>The second input is what makes an absent heartbeat readable. Heartbeats are held only by
 * whichever store replica is currently leader and are never replicated, so leadership moving --
 * because a store process restarted, or merely because an election happened -- leaves the new
 * leader holding nothing for any node, however healthy the whole cluster is. Judged on the
 * heartbeat alone, every node in the cluster goes dark at that instant and stays dark until each
 * agent's next report lands, which is what made a node whose agent never stopped reporting flap
 * through STALE and UNKNOWN and back. Measuring absence from when the store started listening
 * instead answers the question that was actually being asked: has this node been silent for longer
 * than it has had the opportunity to speak?
 */
public final class NodeFreshness {

  /**
   * How long a node may be absent from a freshly-started observation window before absence starts
   * counting as silence. Derived from the staleness threshold rather than configured separately so
   * the two can never be set into a contradiction; twice is enough to cover an election plus
   * several of the agent's own heartbeat attempts, which is all this window has to outlast.
   */
  private static final int GRACE_MULTIPLE = 2;

  /**
   * The cluster-wide staleness threshold: how long a node's last heartbeat may be before the
   * platform stops trusting it. Held here rather than at each caller so the scheduler, the
   * reconcilers and the operator-facing node listing cannot drift apart on what "stale" means.
   */
  public static final Duration DEFAULT_STALE_AFTER = Duration.ofSeconds(15);

  private final Duration staleAfter;
  private final Duration observationGrace;

  /** A judge using the cluster-wide {@link #DEFAULT_STALE_AFTER}. */
  public static NodeFreshness standard() {
    return new NodeFreshness(DEFAULT_STALE_AFTER);
  }

  public NodeFreshness(Duration staleAfter) {
    if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()) {
      throw new IllegalArgumentException("staleAfter must be positive");
    }
    this.staleAfter = staleAfter;
    this.observationGrace = staleAfter.multipliedBy(GRACE_MULTIPLE);
  }

  /**
   * What an operator should be shown for this node. {@code registered} is whether the node has ever
   * introduced itself to the control plane, and it is what bounds the grace window to the case it
   * exists for: registrations are replicated durable state and survive a store election intact,
   * heartbeats are not and do not. So a registered node with no heartbeat really can be one the
   * store has simply not heard from yet, while an unregistered one is nothing the platform has any
   * record of at all -- there is no report in flight to wait for.
   */
  public Status statusOf(
      boolean registered,
      Optional<ObservedHeartbeat> observed,
      Instant observingSince,
      Instant now) {
    if (observed.isEmpty()) {
      return registered && withinObservationGrace(observingSince, now)
          ? Status.PENDING
          : Status.UNKNOWN;
    }
    return silentFor(observed.get(), now).compareTo(staleAfter) > 0 ? Status.STALE : Status.HEALTHY;
  }

  /**
   * Whether the platform should act on this node being gone -- release its assignments, stop
   * counting its replicas, place nothing new on it. Deliberately narrower than {@link #statusOf}:
   * {@link Status#PENDING} is displayed as its own thing but is never acted on, because acting on
   * it would mean rescheduling a whole cluster's workloads every time a store election happens.
   */
  public boolean hasGoneDark(
      boolean registered,
      Optional<ObservedHeartbeat> observed,
      Instant observingSince,
      Instant now) {
    return switch (statusOf(registered, observed, observingSince, now)) {
      case HEALTHY, PENDING -> false;
      case STALE, UNKNOWN -> true;
    };
  }

  private boolean withinObservationGrace(Instant observingSince, Instant now) {
    return Duration.between(observingSince, now).compareTo(observationGrace) <= 0;
  }

  private static Duration silentFor(ObservedHeartbeat observed, Instant now) {
    return Duration.between(observed.receivedAt(), now);
  }

  public enum Status {
    /** Heartbeating within the staleness threshold. */
    HEALTHY,
    /** Heartbeating, but its last report is older than the staleness threshold. */
    STALE,
    /**
     * Nothing recorded, and the store has been listening long enough that the silence is the node's
     * own rather than an artifact of the store having only just started listening.
     */
    UNKNOWN,
    /**
     * A registered node with nothing recorded yet, while the store has only just started listening
     * -- a node that registered moments ago, or every node in the cluster for the first moments
     * after a store election. Not an assertion that the node is healthy, and not grounds to act as
     * though it isn't.
     */
    PENDING
  }
}
