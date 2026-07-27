package com.gimle.core.exception;

import com.gimle.core.module.IsolationTier;

/**
 * The scheduler cannot place a deployment's replica anywhere: no registered node has both the
 * capacity and the isolation-tier support the replica's module descriptor requires, or honoring an
 * anti-affinity constraint would leave it unplaceable. Never silently downgraded or placed in
 * violation of a constraint it was asked to honor — same "reject, don't downgrade" framing as
 * {@link GimleIsolationException}, evaluated control-plane-side against a specific node's reported
 * capabilities instead of worker-side against the one active limiter.
 */
public class GimleSchedulingException extends RuntimeException {

  private GimleSchedulingException(String message) {
    super(message);
  }

  public static GimleSchedulingException noFeasiblePlacement(
      String deploymentName, int instanceIndex, IsolationTier tier) {
    return new GimleSchedulingException(
        "deployment "
            + deploymentName
            + " instance "
            + instanceIndex
            + " requires isolation tier "
            + tier
            + " but no registered node has both the capacity and tier support to place it");
  }

  public static GimleSchedulingException antiAffinityViolated(
      String deploymentName, int instanceIndex) {
    return new GimleSchedulingException(
        "deployment "
            + deploymentName
            + " instance "
            + instanceIndex
            + " cannot be placed without violating its anti-affinity constraint (every node with"
            + " capacity already runs another replica of this deployment)");
  }

  /**
   * Phase 5 design §5.4: a Tier 2/3 replica for a tenant couldn't be placed without co-residing on
   * a node already running a different tenant's instance -- the node-level segregation this design
   * enforces for tiers with a real process/kernel isolation boundary.
   */
  public static GimleSchedulingException tenantIsolationViolated(
      String deploymentName, int instanceIndex) {
    return new GimleSchedulingException(
        "deployment "
            + deploymentName
            + " instance "
            + instanceIndex
            + " cannot be placed without co-residing with a different tenant's instance on every"
            + " node with capacity and tier support");
  }
}
