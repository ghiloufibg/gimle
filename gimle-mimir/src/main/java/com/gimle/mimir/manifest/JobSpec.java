package com.gimle.mimir.manifest;

import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.ModuleId;
import java.time.Duration;
import java.util.Optional;

/**
 * Desired state for one run-to-completion Job: exactly one logical unit of work, retried up to
 * {@code backoffLimit} times -- deliberately not Kubernetes Job's own {@code parallelism}/{@code
 * completions} multi-pod fan-out, real added complexity not needed to teach the run-to-completion
 * mechanic itself.
 *
 * <p>Mirrors {@link DeploymentSpec}'s own shape closely -- same {@code moduleId}/{@code
 * artifactPath}/{@code placement}/{@code tenantId}/{@code artifactSha256} fields, same reasoning
 * for each (artifact path travels as a plain string for the scheduler to read the descriptor from
 * before any node has resolved anything; {@code artifactSha256} is never trusted from an
 * operator-submitted manifest, always recomputed server-side at admission) -- but with no {@code
 * replicas}/{@code autoscale} (a Job is never scaled, only retried) and two fields no Deployment
 * has: {@code activeDeadline} (a wall-clock ceiling across every attempt combined; once exceeded
 * the job is marked permanently {@code FAILED} regardless of remaining {@code backoffLimit}
 * headroom) and {@code backoffLimit} itself.
 */
public record JobSpec(
    String name,
    ModuleId moduleId,
    String artifactPath,
    PlacementConstraints placement,
    Optional<Duration> activeDeadline,
    int backoffLimit,
    Optional<String> tenantId,
    Optional<String> artifactSha256)
    implements WorkloadSpec {

  public JobSpec {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("job name must not be blank");
    }
    if (moduleId == null) {
      throw new IllegalArgumentException("moduleId must not be null");
    }
    ArtifactReference.requireValid(artifactPath);
    if (placement == null) {
      throw new IllegalArgumentException("placement must not be null");
    }
    if (activeDeadline == null) {
      throw new IllegalArgumentException("activeDeadline must be Optional.empty(), not null");
    }
    if (backoffLimit < 0) {
      throw new IllegalArgumentException("backoffLimit must not be negative: " + backoffLimit);
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be Optional.empty(), not null");
    }
    if (artifactSha256 == null) {
      throw new IllegalArgumentException("artifactSha256 must be Optional.empty(), not null");
    }
  }
}
