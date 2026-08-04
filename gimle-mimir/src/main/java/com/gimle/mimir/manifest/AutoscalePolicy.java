package com.gimle.mimir.manifest;

/**
 * Horizontal autoscaling policy for one deployment -- Kubernetes' own separation between a {@code
 * Deployment} and its {@code HorizontalPodAutoscaler} collapsed into one optional field, since
 * {@code DeploymentSpec} is already the single per-deployment resource here; no reason to invent a
 * second top-level resource type for what one optional field covers.
 */
public record AutoscalePolicy(int minReplicas, int maxReplicas, int targetCpuUtilizationPercent) {

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
  }
}
