package com.gimle.core.restart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RestartTrackerTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private static RestartTracker tracker() {
    return new RestartTracker(
        Duration.ofMillis(100), 2.0, Duration.ofSeconds(5), 3, Duration.ofSeconds(60));
  }

  @Test
  void allows_retry_within_budget() {
    RestartTracker tracker = tracker();
    assertTrue(tracker.recordFailureAndCheckShouldRetry(T0));
    assertTrue(tracker.recordFailureAndCheckShouldRetry(T0.plusSeconds(1)));
    assertTrue(tracker.recordFailureAndCheckShouldRetry(T0.plusSeconds(2)));
  }

  @Test
  void exhausts_after_max_attempts_within_the_window() {
    RestartTracker tracker = tracker();
    assertTrue(tracker.recordFailureAndCheckShouldRetry(T0));
    assertTrue(tracker.recordFailureAndCheckShouldRetry(T0.plusSeconds(1)));
    assertTrue(tracker.recordFailureAndCheckShouldRetry(T0.plusSeconds(2)));
    assertFalse(
        tracker.recordFailureAndCheckShouldRetry(T0.plusSeconds(3)),
        "4th attempt within the 60s window should exceed the budget of 3");
  }

  @Test
  void delay_grows_exponentially_and_is_capped() {
    RestartTracker tracker = tracker();
    tracker.recordFailureAndCheckShouldRetry(T0);
    assertEquals(Duration.ofMillis(100), tracker.delayUntilNextAttempt(T0));

    tracker.recordFailureAndCheckShouldRetry(T0);
    assertEquals(Duration.ofMillis(200), tracker.delayUntilNextAttempt(T0));

    tracker.recordFailureAndCheckShouldRetry(T0);
    assertEquals(Duration.ofMillis(400), tracker.delayUntilNextAttempt(T0));
  }

  @Test
  void delay_never_exceeds_the_cap() {
    // multiplier 2.0, initial 1s, cap 5s -> uncapped would be 1,2,4,8,16s; capped stays at 5s.
    RestartTracker tracker =
        new RestartTracker(
            Duration.ofSeconds(1), 2.0, Duration.ofSeconds(5), 10, Duration.ofSeconds(60));
    Duration lastDelay = Duration.ZERO;
    for (int i = 0; i < 6; i++) {
      tracker.recordFailureAndCheckShouldRetry(T0);
      lastDelay = tracker.delayUntilNextAttempt(T0);
      assertTrue(
          lastDelay.compareTo(Duration.ofSeconds(5)) <= 0,
          "delay must never exceed the cap: " + lastDelay);
    }
    assertEquals(
        Duration.ofSeconds(5), lastDelay, "delay should have reached the cap by the 6th attempt");
  }

  @Test
  void window_resets_the_attempt_budget_once_it_elapses() {
    RestartTracker tracker = tracker();
    tracker.recordFailureAndCheckShouldRetry(T0);
    tracker.recordFailureAndCheckShouldRetry(T0);
    tracker.recordFailureAndCheckShouldRetry(T0);
    assertFalse(tracker.recordFailureAndCheckShouldRetry(T0)); // exhausted within this window

    // A failure well past the 60s window should start a fresh window with a fresh budget.
    assertTrue(tracker.recordFailureAndCheckShouldRetry(T0.plusSeconds(120)));
  }

  @Test
  void record_success_resets_the_tracker() {
    RestartTracker tracker = tracker();
    tracker.recordFailureAndCheckShouldRetry(T0);
    tracker.recordFailureAndCheckShouldRetry(T0);
    tracker.recordSuccess();

    // After a reset, a fresh failure should behave like the very first one (100ms delay), not
    // continue the exponential curve from before the success.
    tracker.recordFailureAndCheckShouldRetry(T0);
    assertEquals(Duration.ofMillis(100), tracker.delayUntilNextAttempt(T0));
  }

  @Test
  void rejects_invalid_construction_parameters() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RestartTracker(
                Duration.ZERO, 2.0, Duration.ofSeconds(5), 3, Duration.ofSeconds(60)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RestartTracker(
                Duration.ofMillis(100), 0.5, Duration.ofSeconds(5), 3, Duration.ofSeconds(60)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RestartTracker(
                Duration.ofSeconds(10), 2.0, Duration.ofSeconds(5), 3, Duration.ofSeconds(60)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RestartTracker(
                Duration.ofMillis(100), 2.0, Duration.ofSeconds(5), 0, Duration.ofSeconds(60)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RestartTracker(
                Duration.ofMillis(100), 2.0, Duration.ofSeconds(5), 3, Duration.ZERO));
  }
}
