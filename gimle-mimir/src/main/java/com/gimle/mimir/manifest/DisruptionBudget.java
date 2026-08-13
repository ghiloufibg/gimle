package com.gimle.mimir.manifest;

/**
 * How many replacements a rolling update may have in flight at once for a {@link DeploymentSpec} or
 * {@link DaemonSetSpec} -- generalizes {@code DeploymentReconciler}/{@code DaemonSetReconciler}'s
 * previously hardcoded "one index/node at a time" into an operator-tunable count, the same way
 * {@link AutoscalePolicy} generalized a previously-fixed replica count.
 *
 * <p>{@code maxSurge} (provisioning a replacement before removing the original) is implemented for
 * {@link DeploymentSpec} only, via {@code DeploymentReconciler#handleSurge}'s synthetic index range
 * above {@code replicas} -- {@link DeploymentManifestParser} accepts a nonzero value.
 * Admission-time tenant quota accounting is surge-aware too: {@link
 * DeploymentSpec#maxCommittedInstances()} charges a tenant's quota for the peak {@code replicas +
 * maxSurge} a rollout could transiently reach, not just the steady-state {@code replicas}. {@link
 * DaemonSetManifestParser} still rejects a nonzero {@code maxSurge} outright, the same "reject
 * outright rather than look like it did something" posture it already takes for {@code
 * placement.antiAffinity} -- deliberately permanent, not a scoped-out first pass: a {@link
 * DaemonSetSpec} is already exactly one instance per eligible node by definition, so there is no
 * "extra" instance a rollout could ever provision ahead of removing the old one, on any
 * implementation.
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
