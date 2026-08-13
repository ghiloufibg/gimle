package com.gimle.mimir.manifest;

import com.gimle.core.module.ModuleId;
import java.time.Duration;
import java.util.Optional;

/**
 * The per-firing shape of a {@link CronJobSpec} -- {@link JobSpec}'s own fields minus {@code name}
 * (generated fresh per firing as {@code {cronJobName}-{epochSeconds}}), {@code tenantId} (carried
 * on {@link CronJobSpec} itself instead, since every firing of one CronJob shares the same tenant),
 * and {@code artifactSha256} (never trusted from a manifest, always recomputed at firing time the
 * same way a directly-submitted {@link JobSpec} is at admission -- see {@code CronJobReconciler}).
 */
public record JobTemplate(
    ModuleId moduleId,
    String artifactPath,
    PlacementConstraints placement,
    Optional<Duration> activeDeadline,
    int backoffLimit) {

  public JobTemplate {
    if (moduleId == null) {
      throw new IllegalArgumentException("moduleId must not be null");
    }
    if (artifactPath == null || artifactPath.isBlank()) {
      throw new IllegalArgumentException("artifactPath must not be blank");
    }
    if (placement == null) {
      throw new IllegalArgumentException("placement must not be null");
    }
    if (activeDeadline == null) {
      throw new IllegalArgumentException("activeDeadline must be Optional.empty(), not null");
    }
    if (backoffLimit < 0) {
      throw new IllegalArgumentException("backoffLimit must not be negative: " + backoffLimit);
    }
  }
}
