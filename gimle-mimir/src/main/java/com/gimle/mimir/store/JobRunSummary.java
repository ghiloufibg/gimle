package com.gimle.mimir.store;

import java.util.Optional;

/**
 * The last attempt's own detail, retained once a {@link com.gimle.mimir.manifest.JobSpec} reaches a
 * terminal {@link JobPhase} -- {@link JobRun} itself is removed at that same transition ({@code
 * JobReconciler}'s own terminal-transition mutations always pair a {@link JobPhase} write with a
 * {@link JobRun} removal), since a live {@link JobRun} also doubles as scheduler placement/
 * tenant-isolation bookkeeping ({@code JobReconciler#buildCandidates}) that must not linger forever
 * for a job that will never run again. This is deliberately a separate, smaller record rather than
 * just keeping the {@link JobRun} around: node/attempt/reason is exactly what {@code get jobs -o
 * json}'s own {@code currentRun} field needs to report back once a job is terminal, with none of
 * the scheduling-bookkeeping baggage a lingering {@link JobRun} would carry.
 */
public record JobRunSummary(
    String jobName, int attempt, String nodeId, String reason, Optional<String> tenantId) {

  public JobRunSummary {
    if (jobName == null || jobName.isBlank()) {
      throw new IllegalArgumentException("jobName must not be blank");
    }
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must not be negative: " + attempt);
    }
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be Optional.empty(), not null");
    }
  }

  /** Back-compat: defaults {@code tenantId} to {@code Optional.empty()} (untenanted). */
  public JobRunSummary(String jobName, int attempt, String nodeId, String reason) {
    this(jobName, attempt, nodeId, reason, Optional.empty());
  }
}
