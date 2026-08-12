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
 * additional, independently-optional scaling signals alongside the original CPU target. {@link
 * #combinationMode} picks how {@link com.gimle.controlplane.autoscale.AutoscaleReconciler} combines
 * whichever of those signals are actually configured into one ideal replica count: {@link
 * CombinationMode#WORST_SIGNAL} (the default) computes an ideal replica count per configured signal
 * and takes the highest one, the same "max wins" approach Kubernetes' own HPA uses across multiple
 * metrics rather than blending units together; {@link CombinationMode#WEIGHTED} instead blends
 * every configured signal's own observed/target ratio into one weighted average, using {@link
 * #cpuWeight}/{@link #requestRateWeight}/{@link #errorRateWeight}/{@link #queueDepthWeight} (each
 * defaulting to {@code 1.0} when its own signal is configured but its weight is not). Every
 * optional field here defaults to "not evaluated"/"unweighted" when absent, so an existing CPU-only
 * or worst-signal policy behaves identically to before weighting was added.
 */
public record AutoscalePolicy(
    int minReplicas,
    int maxReplicas,
    int targetCpuUtilizationPercent,
    OptionalDouble targetRequestRatePerSecond,
    OptionalDouble targetErrorRatePercent,
    OptionalInt targetQueueDepth,
    CombinationMode combinationMode,
    OptionalDouble cpuWeight,
    OptionalDouble requestRateWeight,
    OptionalDouble errorRateWeight,
    OptionalDouble queueDepthWeight) {

  /** How {@link #targetCpuUtilizationPercent} et al. combine into one ideal replica count. */
  public enum CombinationMode {
    WORST_SIGNAL,
    WEIGHTED
  }

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
    if (combinationMode == null) {
      combinationMode = CombinationMode.WORST_SIGNAL;
    }
    requirePositiveIfPresent(cpuWeight, "cpuWeight");
    requirePositiveIfPresent(requestRateWeight, "requestRateWeight");
    requirePositiveIfPresent(errorRateWeight, "errorRateWeight");
    requirePositiveIfPresent(queueDepthWeight, "queueDepthWeight");
  }

  private static void requirePositiveIfPresent(OptionalDouble weight, String fieldName) {
    if (weight.isPresent() && weight.getAsDouble() <= 0) {
      throw new IllegalArgumentException(
          fieldName + " must be positive if present: " + weight.getAsDouble());
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

  /**
   * Pre-weighting canonical shape, preserved for every call site that predates {@link
   * #combinationMode} and the four per-signal weights -- defaults to {@link
   * CombinationMode#WORST_SIGNAL} with no weights, i.e. exactly today's behavior.
   */
  public AutoscalePolicy(
      int minReplicas,
      int maxReplicas,
      int targetCpuUtilizationPercent,
      OptionalDouble targetRequestRatePerSecond,
      OptionalDouble targetErrorRatePercent,
      OptionalInt targetQueueDepth) {
    this(
        minReplicas,
        maxReplicas,
        targetCpuUtilizationPercent,
        targetRequestRatePerSecond,
        targetErrorRatePercent,
        targetQueueDepth,
        CombinationMode.WORST_SIGNAL,
        OptionalDouble.empty(),
        OptionalDouble.empty(),
        OptionalDouble.empty(),
        OptionalDouble.empty());
  }
}
