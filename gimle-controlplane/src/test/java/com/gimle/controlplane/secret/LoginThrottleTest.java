package com.gimle.controlplane.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

  @Test
  void a_key_that_has_never_failed_is_never_throttled() {
    LoginThrottle throttle = new LoginThrottle();
    assertEquals(Optional.empty(), throttle.throttledUntil("alice"));
  }

  @Test
  void failures_below_the_threshold_impose_no_delay() {
    LoginThrottle throttle = new LoginThrottle(3, Duration.ofSeconds(1), Duration.ofMinutes(5));
    throttle.recordFailure("alice");
    throttle.recordFailure("alice");

    assertEquals(Optional.empty(), throttle.throttledUntil("alice"));
  }

  @Test
  void the_threshold_th_failure_throttles_until_a_future_instant() {
    LoginThrottle throttle = new LoginThrottle(3, Duration.ofSeconds(1), Duration.ofMinutes(5));
    throttle.recordFailure("alice");
    throttle.recordFailure("alice");
    throttle.recordFailure("alice");

    Optional<Instant> until = throttle.throttledUntil("alice");
    assertTrue(until.isPresent());
    assertTrue(until.get().isAfter(Instant.now()));
  }

  @Test
  void each_additional_failure_past_the_threshold_doubles_the_delay_up_to_the_cap() {
    LoginThrottle throttle = new LoginThrottle(1, Duration.ofSeconds(1), Duration.ofSeconds(100));
    Instant before = Instant.now();

    throttle.recordFailure("alice"); // 1st failure at threshold 1 -> base delay (1s)
    long firstDelay =
        Duration.between(before, throttle.throttledUntil("alice").orElseThrow()).toSeconds();

    throttle.recordFailure("alice"); // 2nd failure -> 2x base delay (2s)
    long secondDelay =
        Duration.between(before, throttle.throttledUntil("alice").orElseThrow()).toSeconds();

    assertTrue(
        secondDelay > firstDelay,
        "expected the delay to grow: " + firstDelay + " -> " + secondDelay);
  }

  @Test
  void delay_growth_is_capped_at_max_delay() {
    LoginThrottle throttle = new LoginThrottle(1, Duration.ofSeconds(1), Duration.ofSeconds(5));
    for (int i = 0; i < 20; i++) {
      throttle.recordFailure("alice");
    }

    Instant until = throttle.throttledUntil("alice").orElseThrow();
    long delaySeconds = Duration.between(Instant.now(), until).toSeconds();
    assertTrue(delaySeconds <= 5, "expected the delay capped at 5s, was " + delaySeconds + "s");
  }

  @Test
  void a_success_clears_the_failure_history() {
    LoginThrottle throttle = new LoginThrottle(1, Duration.ofSeconds(1), Duration.ofMinutes(5));
    throttle.recordFailure("alice");
    assertTrue(throttle.throttledUntil("alice").isPresent());

    throttle.recordSuccess("alice");
    assertEquals(Optional.empty(), throttle.throttledUntil("alice"));
  }

  @Test
  void keys_are_tracked_independently() {
    LoginThrottle throttle = new LoginThrottle(1, Duration.ofSeconds(1), Duration.ofMinutes(5));
    throttle.recordFailure("alice");

    assertTrue(throttle.throttledUntil("alice").isPresent());
    assertEquals(Optional.empty(), throttle.throttledUntil("bob"));
  }

  @Test
  void a_non_positive_failure_threshold_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new LoginThrottle(0, Duration.ofSeconds(1), Duration.ofMinutes(5)));
  }

  @Test
  void a_max_delay_smaller_than_the_base_delay_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new LoginThrottle(1, Duration.ofMinutes(5), Duration.ofSeconds(1)));
  }
}
