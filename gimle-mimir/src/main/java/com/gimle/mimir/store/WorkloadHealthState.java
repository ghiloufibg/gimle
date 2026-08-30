package com.gimle.mimir.store;

import java.util.Optional;

/**
 * The {@link ReconcilerInstanceState}-equivalent restart-budget bookkeeping for a StatefulSet index
 * or DaemonSet node-instance, kept as its own resource kind rather than folded into {@code
 * ReconcilerInstanceState} itself: that record's key is a Deployment-shaped {@code (deploymentName,
 * instanceIndex)} pair, and a DaemonSet's own natural slot is a {@code nodeId} (a {@code String}),
 * which doesn't fit the {@code int instanceIndex} field at all -- and even for StatefulSet, where
 * an integer index would fit, reusing the same flat map risks a genuine collision: a tenant's
 * Deployment and StatefulSet can share a name (they're different resource kinds, no cross-kind
 * uniqueness constraint), so a shared key without a kind discriminator could let one workload's
 * backoff bookkeeping silently overwrite the other's. {@code workloadKind} is that discriminator
 * ({@code "StatefulSet"} or {@code "DaemonSet"}); {@code slot} carries a StatefulSet's {@code
 * instanceIndex} as a string, or a DaemonSet's {@code nodeId} verbatim.
 *
 * @param attemptsInWindow mirrors {@code RestartTracker#attemptsInWindow}; {@code 0} if no restart
 *     has been recorded in the current window.
 * @param windowStartEpochMilli mirrors {@code RestartTracker#windowStart}; {@link #ABSENT} if no
 *     window has been opened.
 * @param nextAllowedAttemptEpochMilli mirrors {@code RestartTracker#nextAllowedAttempt}; {@link
 *     #ABSENT} if no restart has been recorded yet.
 * @param pendingRetry "backoff approved, waiting for the delay to elapse" flag.
 * @param permanentlyFailed "restart budget exhausted, giving up" flag -- the owning reconciler
 *     stops attempting a fresh placement for this slot once set, the same posture {@code
 *     HealthReconciler} already takes for a Deployment instance.
 */
public record WorkloadHealthState(
    String workloadKind,
    String workloadName,
    String slot,
    int attemptsInWindow,
    long windowStartEpochMilli,
    long nextAllowedAttemptEpochMilli,
    boolean pendingRetry,
    boolean permanentlyFailed,
    Optional<String> tenantId) {

  /** Sentinel for an unset timestamp field -- {@code Optional} doesn't survive the wire codec. */
  public static final long ABSENT = -1L;

  public WorkloadHealthState {
    if (workloadKind == null || workloadKind.isBlank()) {
      throw new IllegalArgumentException("workloadKind must not be blank");
    }
    if (workloadName == null || workloadName.isBlank()) {
      throw new IllegalArgumentException("workloadName must not be blank");
    }
    if (slot == null || slot.isBlank()) {
      throw new IllegalArgumentException("slot must not be blank");
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be Optional.empty(), not null");
    }
  }

  /** True once every field is back at its default -- signals the record can be deleted outright. */
  public boolean isEmpty() {
    return attemptsInWindow == 0
        && windowStartEpochMilli == ABSENT
        && nextAllowedAttemptEpochMilli == ABSENT
        && !pendingRetry
        && !permanentlyFailed;
  }
}
