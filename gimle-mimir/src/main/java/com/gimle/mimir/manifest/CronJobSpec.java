package com.gimle.mimir.manifest;

import com.gimle.mimir.cron.CronSchedule;
import java.time.Duration;
import java.util.Optional;

/**
 * Desired state for a scheduled, repeating {@link JobSpec} generator (priority-3 design doc §3d): a
 * thin policy layer over {@link JobSpec}/{@code JobReconciler}, never a second execution engine --
 * {@code CronJobReconciler} only ever writes a {@link JobSpec} via a normal {@code PutJobSpec}
 * mutation once a firing is due, the same way {@code AutoscaleReconciler} only ever writes {@code
 * effectiveReplicas} rather than touching {@code InstanceAssignment} directly.
 *
 * <p>{@code schedule} is a standard 5-field cron expression (see {@link CronSchedule}), validated
 * eagerly here so a malformed schedule is rejected at submission, not discovered on the first
 * reconcile tick. {@code startingDeadline}, if present, is how late (after the computed due
 * instant) a firing may still be honored before it's logged as missed instead -- matching
 * Kubernetes CronJob's own missed-schedule handling.
 */
public record CronJobSpec(
    String name,
    String schedule,
    JobTemplate jobTemplate,
    Optional<Duration> startingDeadline,
    ConcurrencyPolicy concurrencyPolicy,
    Optional<String> tenantId)
    implements WorkloadSpec {

  public CronJobSpec {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("cronjob name must not be blank");
    }
    if (schedule == null || schedule.isBlank()) {
      throw new IllegalArgumentException("schedule must not be blank");
    }
    try {
      CronSchedule.parse(schedule);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "invalid cron schedule '" + schedule + "': " + e.getMessage(), e);
    }
    if (jobTemplate == null) {
      throw new IllegalArgumentException("jobTemplate must not be null");
    }
    if (startingDeadline == null) {
      throw new IllegalArgumentException("startingDeadline must be Optional.empty(), not null");
    }
    if (concurrencyPolicy == null) {
      throw new IllegalArgumentException("concurrencyPolicy must not be null");
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be Optional.empty(), not null");
    }
  }
}
