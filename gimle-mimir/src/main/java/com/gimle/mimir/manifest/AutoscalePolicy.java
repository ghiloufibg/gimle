package com.gimle.mimir.manifest;

import java.time.Duration;
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
 *
 * <p>{@link #scaleUpCooldown}/{@link #scaleDownCooldown} are the stabilization windows: the minimum
 * time that must have elapsed since this deployment's last recorded scale event before the
 * autoscaler may move its replica count again in that direction. Without them a metric oscillating
 * around its own target scales up, then down, then up again on consecutive ticks indefinitely. The
 * two directions get separate windows because operators want them asymmetric: {@link
 * #DEFAULT_SCALE_UP_COOLDOWN} is zero, so a genuine load spike is answered on the very next tick,
 * while {@link #DEFAULT_SCALE_DOWN_COOLDOWN} is five minutes, so shedding capacity waits for the
 * load to actually stay down. {@code Duration.ZERO} in either direction disables that window
 * entirely; a negative one is rejected.
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
    OptionalDouble queueDepthWeight,
    Duration scaleUpCooldown,
    Duration scaleDownCooldown) {

  /** How {@link #targetCpuUtilizationPercent} et al. combine into one ideal replica count. */
  public enum CombinationMode {
    WORST_SIGNAL,
    WEIGHTED
  }

  /** Scaling up is the direction that answers a real load spike, so it waits for nothing. */
  public static final Duration DEFAULT_SCALE_UP_COOLDOWN = Duration.ZERO;

  /** Shedding capacity waits for the load to stay down, not merely dip for one tick. */
  public static final Duration DEFAULT_SCALE_DOWN_COOLDOWN = Duration.ofMinutes(5);

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
    if (scaleUpCooldown == null) {
      scaleUpCooldown = DEFAULT_SCALE_UP_COOLDOWN;
    }
    if (scaleDownCooldown == null) {
      scaleDownCooldown = DEFAULT_SCALE_DOWN_COOLDOWN;
    }
    requireNonNegative(scaleUpCooldown, "scaleUpCooldown");
    requireNonNegative(scaleDownCooldown, "scaleDownCooldown");
  }

  private static void requireNonNegative(Duration cooldown, String fieldName) {
    if (cooldown.isNegative()) {
      throw new IllegalArgumentException(fieldName + " must not be negative: " + cooldown);
    }
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
   * CombinationMode#WORST_SIGNAL} with no weights.
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

  /**
   * Pre-cooldown canonical shape, preserved for every call site that predates the two stabilization
   * windows -- both take their documented defaults.
   */
  public AutoscalePolicy(
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
    this(
        minReplicas,
        maxReplicas,
        targetCpuUtilizationPercent,
        targetRequestRatePerSecond,
        targetErrorRatePercent,
        targetQueueDepth,
        combinationMode,
        cpuWeight,
        requestRateWeight,
        errorRateWeight,
        queueDepthWeight,
        DEFAULT_SCALE_UP_COOLDOWN,
        DEFAULT_SCALE_DOWN_COOLDOWN);
  }
}
