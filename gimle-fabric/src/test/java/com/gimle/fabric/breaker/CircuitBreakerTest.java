package com.gimle.fabric.breaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

  @Test
  void starts_closed_and_allows_requests() {
    CircuitBreaker breaker = new CircuitBreaker(4, 0.5, Duration.ofMillis(50));
    assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    assertTrue(breaker.allowRequest());
  }

  @Test
  void opens_once_error_rate_crosses_threshold_over_the_window() {
    CircuitBreaker breaker = new CircuitBreaker(4, 0.5, Duration.ofMillis(50));
    breaker.recordFailure();
    breaker.recordFailure();
    breaker.recordSuccess();
    breaker.recordSuccess();
    assertEquals(CircuitBreaker.State.CLOSED, breaker.state(), "exactly at 50% should stay closed");

    breaker.recordFailure();
    assertEquals(CircuitBreaker.State.OPEN, breaker.state());
    assertFalse(breaker.allowRequest());
  }

  @Test
  void does_not_open_before_the_window_fills() {
    CircuitBreaker breaker = new CircuitBreaker(4, 0.5, Duration.ofMillis(50));
    breaker.recordFailure();
    breaker.recordFailure();
    breaker.recordFailure();
    assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
  }

  @Test
  void half_opens_after_cooldown_and_allows_exactly_one_trial() throws InterruptedException {
    CircuitBreaker breaker = new CircuitBreaker(2, 0.5, Duration.ofMillis(20));
    breaker.recordFailure();
    breaker.recordFailure();
    assertEquals(CircuitBreaker.State.OPEN, breaker.state());

    Thread.sleep(30);
    assertTrue(breaker.allowRequest(), "the cooldown elapsed; one trial should be allowed");
    assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
    assertFalse(breaker.allowRequest(), "only one trial may be in flight at a time");
  }

  @Test
  void half_open_success_closes_the_breaker() throws InterruptedException {
    CircuitBreaker breaker = new CircuitBreaker(2, 0.5, Duration.ofMillis(20));
    breaker.recordFailure();
    breaker.recordFailure();
    Thread.sleep(30);
    assertTrue(breaker.allowRequest());

    breaker.recordSuccess();
    assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    assertTrue(breaker.allowRequest());
  }

  @Test
  void half_open_failure_reopens_the_breaker() throws InterruptedException {
    CircuitBreaker breaker = new CircuitBreaker(2, 0.5, Duration.ofMillis(20));
    breaker.recordFailure();
    breaker.recordFailure();
    Thread.sleep(30);
    assertTrue(breaker.allowRequest());

    breaker.recordFailure();
    assertEquals(CircuitBreaker.State.OPEN, breaker.state());
    assertFalse(breaker.allowRequest());
  }

  @Test
  void is_excluded_does_not_consume_the_half_open_trial_slot() throws InterruptedException {
    CircuitBreaker breaker = new CircuitBreaker(2, 0.5, Duration.ofMillis(20));
    breaker.recordFailure();
    breaker.recordFailure();
    Thread.sleep(30);

    assertFalse(breaker.isExcluded(), "cooldown elapsed; no longer excluded from candidacy");
    assertFalse(breaker.isExcluded(), "checking again must not itself claim the trial slot");
    assertTrue(breaker.allowRequest(), "the real attempt still gets to claim the trial slot");
    assertFalse(breaker.allowRequest(), "a second concurrent attempt is correctly rejected");
  }

  @Test
  void rejects_invalid_construction_arguments() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> new CircuitBreaker(0, 0.5, Duration.ofMillis(1)));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> new CircuitBreaker(4, 0.0, Duration.ofMillis(1)));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> new CircuitBreaker(4, 1.5, Duration.ofMillis(1)));
  }
}
