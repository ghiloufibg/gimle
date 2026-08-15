package com.gimle.mimir.manifest;

import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.ModuleId;
import com.gimle.core.vessel.VesselSpec;
import java.time.Duration;
import java.util.Optional;

/**
 * The per-firing shape of a {@link CronJobSpec} -- {@link JobSpec}'s own fields minus {@code name}
 * (generated fresh per firing as {@code {cronJobName}-{epochSeconds}}), {@code tenantId} (carried
 * on {@link CronJobSpec} itself instead, since every firing of one CronJob shares the same tenant),
 * and {@code artifactSha256} (never trusted from a manifest, always recomputed at firing time the
 * same way a directly-submitted {@link JobSpec} is at admission -- see {@code CronJobReconciler}).
 * {@code vessel} carries straight through into each fired {@link JobSpec} unchanged -- a nightly
 * batch jar is a CronJob whose {@code jobTemplate} declares a {@code vessel:} block exactly the
 * same shape every other workload kind's own {@code vessel:} block uses.
 */
public record JobTemplate(
    ModuleId moduleId,
    String artifactPath,
    PlacementConstraints placement,
    Optional<Duration> activeDeadline,
    int backoffLimit,
    Optional<VesselSpec> vessel) {

  public JobTemplate {
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
    if (vessel == null) {
      throw new IllegalArgumentException("vessel must be Optional.empty(), not null");
    }
  }

  /** Back-compat: defaults {@code vessel} to {@code Optional.empty()}. */
  public JobTemplate(
      ModuleId moduleId,
      String artifactPath,
      PlacementConstraints placement,
      Optional<Duration> activeDeadline,
      int backoffLimit) {
    this(moduleId, artifactPath, placement, activeDeadline, backoffLimit, Optional.empty());
  }
}
