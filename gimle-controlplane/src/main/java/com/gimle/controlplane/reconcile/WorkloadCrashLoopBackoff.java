package com.gimle.controlplane.reconcile;

import com.gimle.core.restart.RestartTracker;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.StoreReader;
import com.gimle.mimir.store.WorkloadHealthState;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The {@link StatefulSetReconciler}/{@link DaemonSetReconciler} equivalent of {@link
 * HealthReconciler}'s own restart-budget bookkeeping, extracted here rather than duplicated twice
 * (unlike {@code DaemonSetReconciler#handleRollingUpdate}'s own deliberate duplication of {@link
 * DeploymentReconciler#handleRollingUpdate} -- that pair differs only in key type, while this one
 * would otherwise be byte-for-byte identical across both callers). Deliberately not {@link
 * HealthReconciler} itself, nor built on its {@code ReconcilerInstanceState}: a StatefulSet and a
 * Deployment can share the same name, and a DaemonSet's slot is a node id, not an {@code int}
 * instance index -- see {@link WorkloadHealthState}'s own javadoc. {@code workloadKind} is always
 * the literal {@code "StatefulSet"} or {@code "DaemonSet"}, {@code slot} is the instance index (as
 * a string) for a StatefulSet or the raw node id for a DaemonSet.
 *
 * <p>Callers own detecting a crash (a {@code FAILED} observation, distinct from "not yet ready")
 * and applying the {@link StateMutation} this returns; this class only ever decides *when*,
 * mirroring {@link HealthReconciler#handleUnhealthy}'s own three outcomes: wait out the backoff,
 * remove the stale assignment now that the backoff has elapsed, or give up permanently once the
 * restart budget is exhausted.
 */
final class WorkloadCrashLoopBackoff {

  private final StoreReader store;
  private final Duration initialDelay;
  private final double multiplier;
  private final Duration cap;
  private final int maxAttemptsPerWindow;
  private final Duration window;

  /** Result of evaluating one crash observation for one slot at one tick. */
  record Evaluation(
      boolean shouldRemoveAssignmentNow, boolean permanentlyFailed, StateMutation stateMutation) {}

  WorkloadCrashLoopBackoff(StoreReader store) {
    // Matches HealthReconciler's own defaults exactly -- the same "rescheduling is heavier than a
    // module/worker restart" reasoning applies identically to a StatefulSet index or DaemonSet
    // node.
    this(store, Duration.ofSeconds(2), 2.0, Duration.ofMinutes(1), 5, Duration.ofMinutes(15));
  }

  WorkloadCrashLoopBackoff(
      StoreReader store,
      Duration initialDelay,
      double multiplier,
      Duration cap,
      int maxAttemptsPerWindow,
      Duration window) {
    this.store = store;
    this.initialDelay = initialDelay;
    this.multiplier = multiplier;
    this.cap = cap;
    this.maxAttemptsPerWindow = maxAttemptsPerWindow;
    this.window = window;
  }

  boolean isPermanentlyFailed(
      String workloadKind, String workloadName, String slot, Optional<String> tenantId) {
    return currentState(workloadKind, workloadName, slot, tenantId).permanentlyFailed();
  }

  /**
   * Call once a heartbeat observation for {@code slot} reports {@code FAILED}. Never called for a
   * slot {@link #isPermanentlyFailed} already -- callers check that first and stop, the same way
   * {@link HealthReconciler#reconcileAssignment} does.
   */
  Evaluation handleFailureObserved(
      String workloadKind,
      String workloadName,
      String slot,
      Optional<String> tenantId,
      Instant now) {
    WorkloadHealthState persisted = currentState(workloadKind, workloadName, slot, tenantId);
    RestartTracker tracker = restoreTracker(persisted);
    boolean pendingRetry = persisted.pendingRetry();
    if (!pendingRetry) {
      if (!tracker.recordFailureAndCheckShouldRetry(now)) {
        return new Evaluation(
            false,
            true,
            saveMutation(
                state(
                    workloadKind, workloadName, slot, tenantId, tracker, false, true, persisted)));
      }
      pendingRetry = true;
    }

    Duration delay = tracker.delayUntilNextAttempt(now);
    if (delay.compareTo(Duration.ZERO) <= 0) {
      return new Evaluation(
          true,
          false,
          saveMutation(
              state(workloadKind, workloadName, slot, tenantId, tracker, false, false, persisted)));
    }
    return new Evaluation(
        false,
        false,
        saveMutation(
            state(workloadKind, workloadName, slot, tenantId, tracker, true, false, persisted)));
  }

  /**
   * Call once a slot is confirmed ready. Empty when there's nothing persisted to reset -- avoids an
   * unnecessary write on every healthy tick, matching {@link HealthReconciler#recordHealthy}.
   */
  Optional<StateMutation> handleHealthyObserved(
      String workloadKind, String workloadName, String slot, Optional<String> tenantId) {
    WorkloadHealthState persisted = currentState(workloadKind, workloadName, slot, tenantId);
    if (persisted.attemptsInWindow() == 0
        && persisted.windowStartEpochMilli() == WorkloadHealthState.ABSENT
        && !persisted.pendingRetry()) {
      return Optional.empty();
    }
    return Optional.of(
        saveMutation(
            new WorkloadHealthState(
                workloadKind,
                workloadName,
                slot,
                0,
                WorkloadHealthState.ABSENT,
                WorkloadHealthState.ABSENT,
                false,
                persisted.permanentlyFailed(),
                persisted.firstContinuousReadyAtEpochMilli(),
                tenantId)));
  }

  private RestartTracker restoreTracker(WorkloadHealthState persisted) {
    if (persisted.windowStartEpochMilli() == WorkloadHealthState.ABSENT) {
      return new RestartTracker(initialDelay, multiplier, cap, maxAttemptsPerWindow, window);
    }
    Instant nextAllowedAttempt =
        persisted.nextAllowedAttemptEpochMilli() == WorkloadHealthState.ABSENT
            ? Instant.EPOCH
            : Instant.ofEpochMilli(persisted.nextAllowedAttemptEpochMilli());
    return RestartTracker.restore(
        initialDelay,
        multiplier,
        cap,
        maxAttemptsPerWindow,
        window,
        persisted.attemptsInWindow(),
        Instant.ofEpochMilli(persisted.windowStartEpochMilli()),
        nextAllowedAttempt);
  }

  private static WorkloadHealthState state(
      String workloadKind,
      String workloadName,
      String slot,
      Optional<String> tenantId,
      RestartTracker tracker,
      boolean pendingRetry,
      boolean permanentlyFailed,
      WorkloadHealthState previous) {
    return new WorkloadHealthState(
        workloadKind,
        workloadName,
        slot,
        tracker.attemptsInWindow(),
        tracker.windowStart().map(Instant::toEpochMilli).orElse(WorkloadHealthState.ABSENT),
        tracker.nextAllowedAttempt().equals(Instant.EPOCH)
            ? WorkloadHealthState.ABSENT
            : tracker.nextAllowedAttempt().toEpochMilli(),
        pendingRetry,
        permanentlyFailed,
        previous.firstContinuousReadyAtEpochMilli(),
        tenantId);
  }

  private static StateMutation saveMutation(WorkloadHealthState state) {
    if (state.isEmpty()) {
      return new StateMutation.RemoveWorkloadHealthState(
          state.tenantId(), state.workloadKind(), state.workloadName(), state.slot());
    }
    return new StateMutation.PutWorkloadHealthState(state);
  }

  private WorkloadHealthState currentState(
      String workloadKind, String workloadName, String slot, Optional<String> tenantId) {
    return store
        .getWorkloadHealthState(tenantId, workloadKind, workloadName, slot)
        .orElseGet(
            () ->
                new WorkloadHealthState(
                    workloadKind,
                    workloadName,
                    slot,
                    0,
                    WorkloadHealthState.ABSENT,
                    WorkloadHealthState.ABSENT,
                    false,
                    false,
                    WorkloadHealthState.ABSENT,
                    tenantId));
  }
}
