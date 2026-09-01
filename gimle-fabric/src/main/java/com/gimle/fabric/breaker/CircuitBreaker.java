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

  /**
   * Notified once per actual state change, and never for a call that leaves the state where it was.
   * Invoked outside this breaker's own lock, so an implementation is free to log or touch a meter
   * without serializing every other caller behind it; it must not call back into the breaker that
   * notified it.
   *
   * <p>This exists because a breaker opening is otherwise completely invisible: the endpoint simply
   * stops being selected, with nothing an operator can read to tell that apart from the endpoint
   * never having been in the catalog or its instance never having become ready.
   */
  @FunctionalInterface
  public interface TransitionListener {
    void onTransition(State from, State to);
  }

  public static final TransitionListener NO_LISTENER = (from, to) -> {};

  private static final int MAX_BACKOFF_SHIFT = 4; // caps effective cooldown at 16x the base

  private final int windowSize;
  private final double errorRateThreshold;
  private final Duration cooldown;
  private final Clock clock;
  private final TransitionListener listener;
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
    this(windowSize, errorRateThreshold, cooldown, clock, NO_LISTENER);
  }

  /**
   * {@code listener} observes every state change this breaker makes -- see {@link
   * TransitionListener}. The registry that owns a breaker uses it to attach the endpoint identity
   * this class deliberately knows nothing about, so a transition can be logged and metered against
   * the endpoint it actually concerns.
   */
  public CircuitBreaker(
      int windowSize,
      double errorRateThreshold,
      Duration cooldown,
      Clock clock,
      TransitionListener listener) {
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
    this.listener = listener;
    this.window = new boolean[windowSize];
  }

  /**
   * Whether a call may be attempted right now -- and, if {@code HALF_OPEN}, claims the single trial
   * slot as a side effect. Callers that actually intend to place a call must use this, not {@link
   * #isExcluded()}: claiming the trial slot for every filtered-but-not-chosen candidate would
   * strand those breakers in "trial in flight" forever, since nothing would ever call {@link
   * #recordSuccess()}/{@link #recordFailure()} on them to release it.
   */
  public boolean allowRequest() {
    final State before;
    final State after;
    final boolean allowed;
    synchronized (this) {
      before = state;
      transitionIfCooldownElapsed();
      allowed =
          switch (state) {
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
      after = state;
    }
    notifyTransition(before, after);
    return allowed;
  }

  /**
   * A side-effect-free "is this endpoint excluded from candidacy right now" check for building a
   * candidate list of endpoints whose circuit breaker is closed or half-open -- {@code OPEN} past
   * its cooldown still reads as excluded here (the transition to {@code HALF_OPEN} still happens,
   * since it's purely time-based, but no trial slot is claimed by merely checking).
   */
  public boolean isExcluded() {
    final State before;
    final State after;
    synchronized (this) {
      before = state;
      transitionIfCooldownElapsed();
      after = state;
    }
    notifyTransition(before, after);
    return after == State.OPEN;
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

  public void recordSuccess() {
    final State before;
    final State after;
    synchronized (this) {
      before = state;
      record(true);
      // OPEN is included, not just HALF_OPEN: the only way a call reaches an OPEN breaker at all is
      // FabricServiceRegistry's panic-mode bypass (allowRequest()'s own gate never admits one), so
      // a success recorded here is exactly the recovery evidence a HALF_OPEN trial would have
      // produced had cooldown already elapsed -- treating it any differently would keep the breaker
      // OPEN, and therefore still excluded from candidacy, until the unrelated, purely time-based
      // cooldown finally catches up.
      if (state == State.HALF_OPEN || state == State.OPEN) {
        close();
      }
      after = state;
    }
    notifyTransition(before, after);
  }

  public void recordFailure() {
    final State before;
    final State after;
    synchronized (this) {
      before = state;
      record(false);
      if (state == State.HALF_OPEN) {
        open();
      } else if (state == State.CLOSED
          && count >= windowSize
          && errorRate() >= errorRateThreshold) {
        open();
      }
      after = state;
    }
    notifyTransition(before, after);
  }

  public synchronized State state() {
    return state;
  }

  private void notifyTransition(State from, State to) {
    if (from != to) {
      listener.onTransition(from, to);
    }
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
