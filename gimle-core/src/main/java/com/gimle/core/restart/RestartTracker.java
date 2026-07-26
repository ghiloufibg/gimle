package com.gimle.core.restart;

import java.time.Duration;
import java.time.Instant;

/**
 * Bounded-retry-with-backoff: exponential delay between attempts, capped, giving up after a
 * configured number of attempts within a rolling window — the spec's "{@code
 * CrashLoopBackOff}-equivalent" language. Shared shape for both module-level restart
 * (dispose-and-reinstantiate, inside {@code gimle-worker}) and worker-level restart
 * (destroy-and-respawn, inside {@code gimle-agent}); the two differ only in their numeric
 * parameters, so the policy lives once, here, rather than being duplicated per caller. Not
 * thread-safe by itself — callers own one instance per module/worker and serialize their own access
 * to it (both current callers already do, driven by a single probe-loop/process-exit thread per
 * tracked entity).
 */
public final class RestartTracker {

  private final Duration initialDelay;
  private final double multiplier;
  private final Duration cap;
  private final int maxAttemptsPerWindow;
  private final Duration window;

  private int attemptsInWindow;
  private Instant windowStart;
  private Instant nextAllowedAttempt = Instant.EPOCH;

  public RestartTracker(
      Duration initialDelay,
      double multiplier,
      Duration cap,
      int maxAttemptsPerWindow,
      Duration window) {
    if (initialDelay.isNegative() || initialDelay.isZero()) {
      throw new IllegalArgumentException("initialDelay must be positive: " + initialDelay);
    }
    if (multiplier < 1.0) {
      throw new IllegalArgumentException("multiplier must be at least 1.0: " + multiplier);
    }
    if (cap.compareTo(initialDelay) < 0) {
      throw new IllegalArgumentException(
          "cap must not be smaller than initialDelay: " + cap + " < " + initialDelay);
    }
    if (maxAttemptsPerWindow < 1) {
      throw new IllegalArgumentException(
          "maxAttemptsPerWindow must be at least 1: " + maxAttemptsPerWindow);
    }
    if (window.isNegative() || window.isZero()) {
      throw new IllegalArgumentException("window must be positive: " + window);
    }
    this.initialDelay = initialDelay;
    this.multiplier = multiplier;
    this.cap = cap;
    this.maxAttemptsPerWindow = maxAttemptsPerWindow;
    this.window = window;
  }

  /**
   * Records a failure and reports whether another attempt is still within budget. When {@code
   * true}, {@link #delay_until_next_attempt} tells the caller how long to wait first. When {@code
   * false}, the budget for this rolling window is exhausted — the caller should escalate (module
   * giving up -&gt; worker restart; worker giving up -&gt; terminal status) rather than retry again
   * itself.
   */
  public boolean record_failure_and_check_should_retry(Instant now) {
    if (windowStart == null || Duration.between(windowStart, now).compareTo(window) > 0) {
      windowStart = now;
      attemptsInWindow = 0;
    }
    attemptsInWindow++;
    if (attemptsInWindow > maxAttemptsPerWindow) {
      return false;
    }
    long delayMillis =
        (long)
            Math.min(
                cap.toMillis(),
                initialDelay.toMillis() * Math.pow(multiplier, attemptsInWindow - 1));
    nextAllowedAttempt = now.plusMillis(delayMillis);
    return true;
  }

  /**
   * How long to wait before the retry {@link #record_failure_and_check_should_retry} just approved.
   */
  public Duration delay_until_next_attempt(Instant now) {
    Duration remaining = Duration.between(now, nextAllowedAttempt);
    return remaining.isNegative() ? Duration.ZERO : remaining;
  }

  /** Resets the window entirely — call after an attempt actually succeeds. */
  public void record_success() {
    attemptsInWindow = 0;
    windowStart = null;
    nextAllowedAttempt = Instant.EPOCH;
  }
}
