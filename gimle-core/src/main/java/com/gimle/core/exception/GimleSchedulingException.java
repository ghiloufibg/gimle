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

  public static GimleSchedulingException no_feasible_placement(
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

  public static GimleSchedulingException anti_affinity_violated(
      String deploymentName, int instanceIndex) {
    return new GimleSchedulingException(
        "deployment "
            + deploymentName
            + " instance "
            + instanceIndex
            + " cannot be placed without violating its anti-affinity constraint (every node with"
            + " capacity already runs another replica of this deployment)");
  }
}
