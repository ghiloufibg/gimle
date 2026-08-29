package com.gimle.mimir.store;

import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.ModuleId;
import java.time.Instant;
import java.util.Optional;

/**
 * The scheduler's placement decision for one attempt of a {@link com.gimle.mimir.manifest.JobSpec}
 * -- the run-to-completion counterpart to {@link InstanceAssignment}, {@code attempt} playing the
 * role {@code instanceIndex} does there (0-based, but "attempt N," not "replica N": a Job never has
 * more than one non-terminal run at a time, so this is a retry counter, not a placement fan-out).
 * {@code moduleId}/{@code artifactPath} are carried the same way {@link InstanceAssignment}'s are,
 * for the same reason -- the actual version placed, not implicitly whatever the spec's current
 * fields say.
 *
 * <p>{@code startedAt} is deliberately <em>not</em> reset on each retry: {@code
 * JobReconciler.placeAttempt} carries the same instant forward from attempt 0 through every
 * subsequent attempt of the same job, rather than stamping {@code clock.instant()} fresh each time.
 * That's what lets {@code JobSpec#activeDeadline}'s "wall-clock ceiling across all attempts
 * combined" be checked with a single field read (the current attempt's own {@code startedAt}) -- no
 * separate persisted "job first became active at" value needed. The tradeoff: this field can't also
 * answer "how long has this specific attempt been running," only "how long has this job been active
 * in total" -- nothing in this codebase needs the former yet.
 */
public record JobRun(
    String jobName,
    int attempt,
    String nodeId,
    ModuleId moduleId,
    String artifactPath,
    Instant startedAt,
    Optional<String> tenantId) {

  public JobRun {
    if (jobName == null || jobName.isBlank()) {
      throw new IllegalArgumentException("jobName must not be blank");
    }
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must not be negative: " + attempt);
    }
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    if (moduleId == null) {
      throw new IllegalArgumentException("moduleId must not be null");
    }
    ArtifactReference.requireValid(artifactPath);
    if (startedAt == null) {
      throw new IllegalArgumentException("startedAt must not be null");
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be Optional.empty(), not null");
    }
  }

  /** Back-compat: defaults {@code tenantId} to {@code Optional.empty()} (untenanted). */
  public JobRun(
      String jobName,
      int attempt,
      String nodeId,
      ModuleId moduleId,
      String artifactPath,
      Instant startedAt) {
    this(jobName, attempt, nodeId, moduleId, artifactPath, startedAt, Optional.empty());
  }
}
