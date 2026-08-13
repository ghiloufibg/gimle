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
 *
 * <p>{@code maxUnavailable} and {@code maxSurge} may not both be {@code 0} -- that combination
 * really would mean "never replace anything," a stuck rollout. Either one alone at {@code 0} is
 * fine: {@code DeploymentReconciler#handleRollingUpdate} and {@code #handleSurge} run as fully
 * independent passes, each already a no-op at budget {@code 0} on its own ({@code inFlight.size()
 * >= 0} is immediately true), so a Deployment with {@code maxUnavailable: 0, maxSurge: N} rolls out
 * entirely through surge-then-promote, never removing an index before its replacement lands --
 * literal Kubernetes {@code RollingUpdateDeployment} semantics. A DaemonSet's own single-arg
 * constructor below always pairs with {@code maxSurge == 0}, so this joint check alone still
 * correctly rejects {@code maxUnavailable: 0} there -- no extra DaemonSet-specific check needed.
 */
public record DisruptionBudget(int maxUnavailable, int maxSurge) {

  /** The behavior every deployment/daemonset had before this type existed. */
  public static final DisruptionBudget DEFAULT = new DisruptionBudget(1, 0);

  public DisruptionBudget {
    if (maxUnavailable < 0) {
      throw new IllegalArgumentException("maxUnavailable must not be negative: " + maxUnavailable);
    }
    if (maxSurge < 0) {
      throw new IllegalArgumentException("maxSurge must not be negative: " + maxSurge);
    }
    if (maxUnavailable == 0 && maxSurge == 0) {
      throw new IllegalArgumentException(
          "maxUnavailable and maxSurge must not both be 0 -- nothing would ever be replaced");
    }
  }

  /** DaemonSet shape: no surge concept, see this record's own javadoc. */
  public DisruptionBudget(int maxUnavailable) {
    this(maxUnavailable, 0);
  }
}
