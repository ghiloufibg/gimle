package com.gimle.controlplane.health;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks the outcome of each reconcile-tick step this replica has actually run, plus whether this
 * replica currently holds the reconciler-leader lease -- the two facts {@code ApiServer}'s own
 * {@code /health} needs to answer "is the scheduler / quota enforcer / heartbeat worker running"
 * honestly rather than with a hardcoded badge. The reconcile tick itself only ever runs on
 * whichever replica holds the {@code reconciler-leader} lease (see {@code ControlPlaneMain}'s own
 * javadoc), so a step this replica has never run reports {@link SubsystemStatus#STANDBY} rather
 * than {@link SubsystemStatus#DOWN} -- a non-leader replica correctly never running these steps is
 * not a fault.
 *
 * <p>Step names are whatever the caller (a reconcile tick's own {@code runOne} wrapper) already
 * uses to identify each step -- this class has no opinion on what they mean, only on how recently
 * and successfully each one last ran.
 */
public final class ReconcilerHealth {

  private final Clock clock;
  private final Map<String, SubsystemHealth> lastOutcome = new ConcurrentHashMap<>();
  private final AtomicBoolean leader = new AtomicBoolean(false);

  public ReconcilerHealth(Clock clock) {
    this.clock = clock;
  }

  public void recordSuccess(String step) {
    lastOutcome.put(step, SubsystemHealth.up(clock.instant()));
  }

  public void recordFailure(String step, String reason) {
    lastOutcome.put(step, SubsystemHealth.down(clock.instant(), reason));
  }

  public void setLeader(boolean isLeader) {
    leader.set(isLeader);
  }

  public boolean isLeader() {
    return leader.get();
  }

  /**
   * One step's own last-run outcome, or {@link SubsystemStatus#STANDBY} while this replica isn't
   * the reconciler leader.
   */
  public SubsystemHealth step(String step) {
    if (!leader.get()) {
      return SubsystemHealth.standby();
    }
    return lastOutcome.getOrDefault(step, SubsystemHealth.unknown());
  }

  /**
   * Combines several steps into one badge -- UP only if every named step's own last run succeeded,
   * DOWN if any of them failed, STANDBY/UNKNOWN otherwise. Used where a single operator-facing
   * subsystem (e.g. "scheduler") is actually driven by more than one internal reconcile step
   * (deployment/job/daemonSet/statefulSet each place work through the same {@code Scheduler}). A
   * step's own last outcome is never overwritten by a different step's outcome -- this reads each
   * one's independently tracked state rather than sharing one bucket, so a later step's success in
   * the same tick can never mask an earlier step's failure.
   */
  public SubsystemHealth combined(String... steps) {
    if (!leader.get()) {
      return SubsystemHealth.standby();
    }
    boolean allUp = true;
    boolean anyReported = false;
    Instant latest = null;
    String firstError = null;
    for (String step : steps) {
      SubsystemHealth health = lastOutcome.get(step);
      if (health == null) {
        continue;
      }
      anyReported = true;
      if (health.status() == SubsystemStatus.DOWN) {
        allUp = false;
        if (firstError == null) {
          firstError = health.detail().orElse(null);
        }
      }
      Instant at = health.lastRunAt().orElse(null);
      if (at != null && (latest == null || at.isAfter(latest))) {
        latest = at;
      }
    }
    if (!anyReported) {
      return SubsystemHealth.unknown();
    }
    return allUp
        ? SubsystemHealth.up(latest)
        : SubsystemHealth.down(latest == null ? clock.instant() : latest, firstError);
  }
}
