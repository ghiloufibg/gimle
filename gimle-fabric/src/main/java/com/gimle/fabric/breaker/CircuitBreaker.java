package com.gimle.fabric.breaker;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/**
 * Per-endpoint sliding-window error-rate breaker: closed under normal operation, opens once the
 * error rate over the last {@code windowSize} calls crosses {@code errorRateThreshold}, half-opens
 * after {@code cooldown} to let exactly one trial call through, and closes again on that trial's
 * success or re-opens on its failure. An open breaker removes an endpoint from {@code
 * FabricServiceRegistry}'s remote-tier candidate lists -- this class implements outlier ejection at
 * the registry level, rather than as a separate component layered on top.
 */
public final class CircuitBreaker {

  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final int windowSize;
  private final double errorRateThreshold;
  private final Duration cooldown;
  private final boolean[] window;

  private int index;
  private int count;
  private State state = State.CLOSED;
  private Instant openedAt;
  private boolean halfOpenTrialInFlight;

  public CircuitBreaker(int windowSize, double errorRateThreshold, Duration cooldown) {
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
    if (state == State.OPEN && !Instant.now().isBefore(openedAt.plus(cooldown))) {
      state = State.HALF_OPEN;
      halfOpenTrialInFlight = false;
    }
  }

  public synchronized void recordSuccess() {
    record(true);
    if (state == State.HALF_OPEN) {
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
    openedAt = Instant.now();
    halfOpenTrialInFlight = false;
  }

  private void close() {
    state = State.CLOSED;
    Arrays.fill(window, true);
    index = 0;
    count = 0;
  }
}
