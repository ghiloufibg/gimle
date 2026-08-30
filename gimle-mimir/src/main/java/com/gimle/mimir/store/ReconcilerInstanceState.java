package com.gimle.mimir.store;

import java.util.Optional;

/**
 * Persisted per-instance backoff/grace-period bookkeeping, keyed by {@code deploymentName}/{@code
 * instanceIndex} -- consolidates what {@code HealthReconciler} (restart-budget tracking via {@code
 * RestartTracker}, plus its own {@code pendingRetry}/{@code permanentlyFailed} flags) and {@code
 * ReplicaCountReconciler} (missing-since grace-period timer) each used to hold only in a plain
 * {@code ConcurrentHashMap}, lost on every control-plane restart or reconciler-leader failover. One
 * record rather than two resource kinds: both are exactly the same shape of "this reconciler's
 * bookkeeping for this instance must survive a failover", and a rebuilt reconciler resumes both
 * together for a given instance anyway. Each reconciler only ever reads/writes its own fields --
 * {@code HealthReconciler} never touches {@code firstSeenMissingAtEpochMilli} and vice versa -- so
 * a write from one reconciler always starts from the other's already-persisted state to avoid
 * clobbering it.
 *
 * @param attemptsInWindow mirrors {@code RestartTracker#attemptsInWindow}; {@code 0} if no restart
 *     has been recorded in the current window.
 * @param windowStartEpochMilli mirrors {@code RestartTracker#windowStart}; {@link #ABSENT} if no
 *     window has been opened.
 * @param nextAllowedAttemptEpochMilli mirrors {@code RestartTracker#nextAllowedAttempt}; {@link
 *     #ABSENT} if no restart has been recorded yet.
 * @param pendingRetry {@code HealthReconciler}'s "backoff approved, waiting for the delay to
 *     elapse" flag.
 * @param permanentlyFailed {@code HealthReconciler}'s "restart budget exhausted, giving up" flag.
 * @param firstSeenMissingAtEpochMilli {@code ReplicaCountReconciler}'s grace-period timer start;
 *     {@link #ABSENT} if the instance is currently confirmed by its node's heartbeat.
 * @param firstContinuousReadyAtEpochMilli {@code DeploymentReconciler#isReady}'s own readiness-
 *     stabilization timer start -- when this instance was first observed continuously {@code ready}
 *     since the last time it was observed not-ready; {@link #ABSENT} if it is not currently
 *     observed ready at all. Owned exclusively by that readiness-stabilization logic, the same as
 *     {@code firstSeenMissingAtEpochMilli} is owned exclusively by {@code ReplicaCountReconciler}.
 */
public record ReconcilerInstanceState(
    String deploymentName,
    int instanceIndex,
    int attemptsInWindow,
    long windowStartEpochMilli,
    long nextAllowedAttemptEpochMilli,
    boolean pendingRetry,
    boolean permanentlyFailed,
    long firstSeenMissingAtEpochMilli,
    long firstContinuousReadyAtEpochMilli,
    Optional<String> tenantId) {

  /** Sentinel for an unset timestamp field -- {@code Optional} doesn't survive the wire codec. */
  public static final long ABSENT = -1L;

  public ReconcilerInstanceState {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be Optional.empty(), not null");
    }
  }

  /**
   * Back-compat: defaults {@code tenantId} to {@code Optional.empty()} (untenanted) and {@code
   * firstContinuousReadyAtEpochMilli} to {@link #ABSENT}, for the many existing callers that
   * predate both fields.
   */
  public ReconcilerInstanceState(
      String deploymentName,
      int instanceIndex,
      int attemptsInWindow,
      long windowStartEpochMilli,
      long nextAllowedAttemptEpochMilli,
      boolean pendingRetry,
      boolean permanentlyFailed,
      long firstSeenMissingAtEpochMilli) {
    this(
        deploymentName,
        instanceIndex,
        attemptsInWindow,
        windowStartEpochMilli,
        nextAllowedAttemptEpochMilli,
        pendingRetry,
        permanentlyFailed,
        firstSeenMissingAtEpochMilli,
        ABSENT,
        Optional.empty());
  }

  /** True once every field is back at its default -- signals the record can be deleted outright. */
  public boolean isEmpty() {
    return attemptsInWindow == 0
        && windowStartEpochMilli == ABSENT
        && nextAllowedAttemptEpochMilli == ABSENT
        && !pendingRetry
        && !permanentlyFailed
        && firstSeenMissingAtEpochMilli == ABSENT
        && firstContinuousReadyAtEpochMilli == ABSENT;
  }
}
