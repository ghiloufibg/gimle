package com.gimle.mimir.manifest;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Horizontal autoscaling policy for one deployment -- Kubernetes' own separation between a {@code
 * Deployment} and its {@code HorizontalPodAutoscaler} collapsed into one optional field, since
 * {@code DeploymentSpec} is already the single per-deployment resource here; no reason to invent a
 * second top-level resource type for what one optional field covers.
 *
 * <p>{@code targetRequestRatePerSecond}/{@code targetErrorRatePercent}/{@code targetQueueDepth} are
 * additional, independently-optional scaling signals alongside the original CPU target -- {@link
 * com.gimle.controlplane.autoscale.AutoscaleReconciler} computes an ideal replica count per
 * configured signal and takes the worst (highest) one, the same "max wins" approach Kubernetes' own
 * HPA uses across multiple metrics rather than blending units together. Each defaults to "not
 * evaluated" when absent, so an existing CPU-only policy behaves identically to before these three
 * were added.
 */
public record AutoscalePolicy(
    int minReplicas,
    int maxReplicas,
    int targetCpuUtilizationPercent,
    OptionalDouble targetRequestRatePerSecond,
    OptionalDouble targetErrorRatePercent,
    OptionalInt targetQueueDepth) {

  public AutoscalePolicy {
    if (minReplicas < 0) {
      throw new IllegalArgumentException("minReplicas must not be negative: " + minReplicas);
    }
    if (maxReplicas < minReplicas) {
      throw new IllegalArgumentException(
          "maxReplicas (" + maxReplicas + ") must be >= minReplicas (" + minReplicas + ")");
    }
    if (targetCpuUtilizationPercent <= 0) {
      throw new IllegalArgumentException(
          "targetCpuUtilizationPercent must be positive: " + targetCpuUtilizationPercent);
    }
    if (targetRequestRatePerSecond.isPresent() && targetRequestRatePerSecond.getAsDouble() <= 0) {
      throw new IllegalArgumentException(
          "targetRequestRatePerSecond must be positive if present: "
              + targetRequestRatePerSecond.getAsDouble());
    }
    if (targetErrorRatePercent.isPresent() && targetErrorRatePercent.getAsDouble() <= 0) {
      throw new IllegalArgumentException(
          "targetErrorRatePercent must be positive if present: "
              + targetErrorRatePercent.getAsDouble());
    }
    if (targetQueueDepth.isPresent() && targetQueueDepth.getAsInt() <= 0) {
      throw new IllegalArgumentException(
          "targetQueueDepth must be positive if present: " + targetQueueDepth.getAsInt());
    }
  }

  /** CPU-only shape, preserved for every call site that predates the three optional signals. */
  public AutoscalePolicy(int minReplicas, int maxReplicas, int targetCpuUtilizationPercent) {
    this(
        minReplicas,
        maxReplicas,
        targetCpuUtilizationPercent,
        OptionalDouble.empty(),
        OptionalDouble.empty(),
        OptionalInt.empty());
  }
}
