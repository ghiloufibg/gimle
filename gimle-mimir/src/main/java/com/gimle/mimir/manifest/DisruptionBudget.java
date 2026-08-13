package com.gimle.mimir.manifest;

/**
 * How many replacements a rolling update may have in flight at once for a {@link DeploymentSpec} or
 * {@link DaemonSetSpec} -- generalizes {@code DeploymentReconciler}/{@code DaemonSetReconciler}'s
 * previously hardcoded "one index/node at a time" into an operator-tunable count, the same way
 * {@link AutoscalePolicy} generalized a previously-fixed replica count.
 *
 * <p>{@code maxSurge} is not implemented yet -- provisioning a replacement before removing the
 * original needs its own synthetic-index bookkeeping and interacts with tenant quota admission,
 * deliberately scoped out of this first pass. The field exists on this record purely so the shape
 * is already forward-compatible with a later change that does implement it; both {@link
 * DeploymentManifestParser} and {@link DaemonSetManifestParser} reject a nonzero {@code maxSurge}
 * outright today, rather than silently accepting and then ignoring it -- the same "reject outright
 * rather than look like it did something" posture {@link DaemonSetManifestParser} already takes for
 * {@code placement.antiAffinity}. On a {@link DaemonSetSpec} specifically, {@code maxSurge} would
 * additionally be meaningless even once implemented elsewhere: a DaemonSet is already exactly one
 * instance per eligible node by definition, so there is no "extra" instance a rollout could ever
 * provision ahead of removing the old one.
 */
public record DisruptionBudget(int maxUnavailable, int maxSurge) {

  /** The behavior every deployment/daemonset had before this type existed. */
  public static final DisruptionBudget DEFAULT = new DisruptionBudget(1, 0);

  public DisruptionBudget {
    if (maxUnavailable < 1) {
      throw new IllegalArgumentException("maxUnavailable must be at least 1: " + maxUnavailable);
    }
    if (maxSurge < 0) {
      throw new IllegalArgumentException("maxSurge must not be negative: " + maxSurge);
    }
  }

  /** DaemonSet shape: no surge concept, see this record's own javadoc. */
  public DisruptionBudget(int maxUnavailable) {
    this(maxUnavailable, 0);
  }
}
