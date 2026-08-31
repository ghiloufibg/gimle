package com.gimle.fabric.breaker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/**
 * Per-endpoint sliding-window error-rate breaker: closed under normal operation, opens once the
 * error rate over the last {@code windowSize} calls crosses {@code errorRateThreshold}, half-opens
 * after a cooldown to let exactly one trial call through, and closes again on that trial's success
 * or re-opens on its failure. An open breaker removes an endpoint from {@code
 * FabricServiceRegistry}'s remote-tier candidate lists -- this class implements outlier ejection at
 * the registry level, rather than as a separate component layered on top.
 *
 * <p>Each re-open doubles the cooldown actually applied (capped at {@code 2^MAX_BACKOFF_SHIFT}
 * times the base value), reset back to the base on a successful close. Without this, a caller whose
 * own request cadence happens to land on the same order as the base cooldown -- not a contrived
 * case; it's exactly what {@code gimle-examples}' own committed consumer/provider pair does with
 * production defaults, a fixed 5s cooldown against a fixed 5s call interval -- keeps re-admitting a
 * still-broken endpoint into the half-open trial on almost every call, so the observed failure rate
 * converges back toward the pre-breaker steady state instead of being suppressed. Envoy's own
 * outlier detection applies the identical {@code base_ejection_time * ejections_count} shape for
 * the same reason.
 */
public final class CircuitBreaker {

  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private static final int MAX_BACKOFF_SHIFT = 4; // caps effective cooldown at 16x the base

  private final int windowSize;
  private final double errorRateThreshold;
  private final Duration cooldown;
  private final Clock clock;
  private final boolean[] window;

  private int index;
  private int count;
  private State state = State.CLOSED;
  private Instant openedAt;
  private boolean halfOpenTrialInFlight;
  private int consecutiveOpens;

  public CircuitBreaker(int windowSize, double errorRateThreshold, Duration cooldown) {
    this(windowSize, errorRateThreshold, cooldown, Clock.systemUTC());
  }

  /**
   * Cooldown expiry is the one thing this class reads a clock for, so injecting one is all a test
   * needs to exercise the real production cooldown (seconds) without waiting for it -- see {@code
   * TestClock} in {@code gimle-core}'s test-jar. Production always uses the {@link
   * Clock#systemUTC()} default above.
   */
  public CircuitBreaker(int windowSize, double errorRateThreshold, Duration cooldown, Clock clock) {
    if (windowSize <= 0) {
      throw new IllegalArgumentException("windowSize must be positive: " + windowSize);
    }
    if (errorRateThreshold <= 0.0 || errorRateThreshold > 1.0) {
      throw new IllegalArgumentException(
          "errorRateThreshold must be in (0, 1]: " + errorRateThreshold);
    }
    this.windowSize = windowSize;
    this.errorRateThreshold = errorRateThreshold;
    this.cooldown = cooldown;
    this.clock = clock;
    this.window = new boolean[windowSize];
  }

  /**
   * Whether a call may be attempted right now -- and, if {@code HALF_OPEN}, claims the single trial
   * slot as a side effect. Callers that actually intend to place a call must use this, not {@link
   * #isExcluded()}: claiming the trial slot for every filtered-but-not-chosen candidate would
   * strand those breakers in "trial in flight" forever, since nothing would ever call {@link
   * #recordSuccess()}/{@link #recordFailure()} on them to release it.
   */
  public synchronized boolean allowRequest() {
    transitionIfCooldownElapsed();
    return switch (state) {
      case CLOSED -> true;
      case OPEN -> false;
      case HALF_OPEN -> {
        if (halfOpenTrialInFlight) {
          yield false;
        }
        halfOpenTrialInFlight = true;
        yield true;
      }
    };
  }

  /**
   * A side-effect-free "is this endpoint excluded from candidacy right now" check for building a
   * candidate list of endpoints whose circuit breaker is closed or half-open -- {@code OPEN} past
   * its cooldown still reads as excluded here (the transition to {@code HALF_OPEN} still happens,
   * since it's purely time-based, but no trial slot is claimed by merely checking).
   */
  public synchronized boolean isExcluded() {
    transitionIfCooldownElapsed();
    return state == State.OPEN;
  }

  private void transitionIfCooldownElapsed() {
    if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(effectiveCooldown()))) {
      state = State.HALF_OPEN;
      halfOpenTrialInFlight = false;
    }
  }

  /**
   * The base {@link #cooldown}, doubled per consecutive re-open, capped at {@code
   * 2^MAX_BACKOFF_SHIFT} times the base -- see this class's own javadoc.
   */
  private Duration effectiveCooldown() {
    int shift = Math.min(Math.max(0, consecutiveOpens - 1), MAX_BACKOFF_SHIFT);
    return cooldown.multipliedBy(1L << shift);
  }

  public synchronized void recordSuccess() {
    record(true);
    // OPEN is included, not just HALF_OPEN: the only way a call reaches an OPEN breaker at all is
    // FabricServiceRegistry's panic-mode bypass (allowRequest()'s own gate never admits one), so a
    // success recorded here is exactly the recovery evidence a HALF_OPEN trial would have produced
    // had cooldown already elapsed -- treating it any differently would keep the breaker OPEN, and
    // therefore still excluded from candidacy, until the unrelated, purely time-based cooldown
    // finally catches up.
    if (state == State.HALF_OPEN || state == State.OPEN) {
      close();
    }
  }

  public synchronized void recordFailure() {
    record(false);
    if (state == State.HALF_OPEN) {
      open();
      return;
    }
    if (state == State.CLOSED && count >= windowSize && errorRate() >= errorRateThreshold) {
      open();
    }
  }

  public synchronized State state() {
    return state;
  }

  private void record(boolean success) {
    window[index] = success;
    index = (index + 1) % windowSize;
    count = Math.min(count + 1, windowSize);
  }

  private double errorRate() {
    if (count == 0) {
      return 0.0;
    }
    int failures = 0;
    for (int i = 0; i < count; i++) {
      if (!window[i]) {
        failures++;
      }
    }
    return (double) failures / count;
  }

  private void open() {
    state = State.OPEN;
    openedAt = clock.instant();
    halfOpenTrialInFlight = false;
    consecutiveOpens = Math.min(consecutiveOpens + 1, MAX_BACKOFF_SHIFT + 1);
  }

  private void close() {
    state = State.CLOSED;
    consecutiveOpens = 0;
    Arrays.fill(window, true);
    index = 0;
    count = 0;
  }
}
