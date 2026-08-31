package com.gimle.fabric.breaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.time.TestClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Every cooldown here is {@link #COOLDOWN} -- the genuine production value {@code WorkerMain}
 * configures ({@code Duration.ofSeconds(5)}), not a compressed stand-in. These tests used to run a
 * 20ms cooldown against {@code Thread.sleep(30)}, which bought speed at two costs: the 10ms margin
 * between them was the whole flakiness budget (one GC pause and the sleep no longer clears the
 * cooldown, or clears one it shouldn't), and the doubling/reset behavior was only ever proven at
 * 20ms, never at the 5s value production actually runs. A {@link TestClock} removes both: time
 * advances exactly and instantly, so the real duration is as cheap to test as the fake one.
 *
 * <p>{@link TestClock} arrives by parameter injection via {@code TestClockExtension}, auto-
 * registered repo-wide -- no {@code @ExtendWith} needed. A fresh clock per test method.
 */
class CircuitBreakerTest {

  /** The real cooldown {@code WorkerMain} passes to {@code FabricServiceRegistry} in production. */
  private static final Duration COOLDOWN = Duration.ofSeconds(5);

  @Test
  void starts_closed_and_allows_requests(TestClock clock) {
    CircuitBreaker breaker = new CircuitBreaker(4, 0.5, COOLDOWN, clock);
    assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    assertTrue(breaker.allowRequest());
  }

  @Test
  void opens_once_error_rate_crosses_threshold_over_the_window(TestClock clock) {
    CircuitBreaker breaker = new CircuitBreaker(4, 0.5, COOLDOWN, clock);
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
  void does_not_open_before_the_window_fills(TestClock clock) {
    CircuitBreaker breaker = new CircuitBreaker(4, 0.5, COOLDOWN, clock);
    breaker.recordFailure();
    breaker.recordFailure();
    breaker.recordFailure();
    assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
  }

  /**
   * The exact-boundary pair a sleep-based test can't express at all: with real time you can only
   * sleep "comfortably past" a cooldown, never land on the instant before and the instant of.
   */
  @Test
  void stays_open_until_the_cooldown_has_fully_elapsed(TestClock clock) {
    CircuitBreaker breaker = new CircuitBreaker(2, 0.5, COOLDOWN, clock);
    breaker.recordFailure();
    breaker.recordFailure();
    assertEquals(CircuitBreaker.State.OPEN, breaker.state());

    clock.advance(COOLDOWN.minusNanos(1));
    assertFalse(breaker.allowRequest(), "one nanosecond short of the cooldown is still OPEN");

    clock.advance(Duration.ofNanos(1));
    assertTrue(breaker.allowRequest(), "at exactly the cooldown the breaker half-opens");
  }

  @Test
  void half_opens_after_cooldown_and_allows_exactly_one_trial(TestClock clock) {
    CircuitBreaker breaker = new CircuitBreaker(2, 0.5, COOLDOWN, clock);
    breaker.recordFailure();
    breaker.recordFailure();
    assertEquals(CircuitBreaker.State.OPEN, breaker.state());

    clock.advance(COOLDOWN);
    assertTrue(breaker.allowRequest(), "the cooldown elapsed; one trial should be allowed");
    assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
    assertFalse(breaker.allowRequest(), "only one trial may be in flight at a time");
  }

  @Test
  void half_open_success_closes_the_breaker(TestClock clock) {
    CircuitBreaker breaker = new CircuitBreaker(2, 0.5, COOLDOWN, clock);
    breaker.recordFailure();
    breaker.recordFailure();
    clock.advance(COOLDOWN);
    assertTrue(breaker.allowRequest());

    breaker.recordSuccess();
    assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    assertTrue(breaker.allowRequest());
  }

  /**
   * Models {@code FabricServiceRegistry}'s panic-mode bypass: a caller invokes the endpoint while
   * still {@code OPEN} (cooldown not elapsed, so {@link CircuitBreaker#allowRequest()} was never
   * consulted) and the call happens to succeed. That success must close the breaker exactly like a
   * real {@code HALF_OPEN} trial would -- otherwise the only evidence panic mode was designed to
   * surface (a call getting through and succeeding) is silently discarded and the endpoint stays
   * excluded from ordinary candidacy until the unrelated cooldown timer catches up.
   */
  @Test
  void a_success_recorded_while_still_open_closes_the_breaker(TestClock clock) {
    CircuitBreaker breaker = new CircuitBreaker(2, 0.5, COOLDOWN, clock);
    breaker.recordFailure();
    breaker.recordFailure();
    assertEquals(CircuitBreaker.State.OPEN, breaker.state());

    // Cooldown deliberately not advanced -- still OPEN, no HALF_OPEN trial slot ever claimed.
    breaker.recordSuccess();

    assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    assertTrue(breaker.allowRequest());
  }

  @Test
  void a_success_recorded_while_open_also_resets_the_backoff_to_the_base_cooldown(TestClock clock) {
    CircuitBreaker breaker = new CircuitBreaker(2, 0.5, COOLDOWN, clock);
    breaker.recordFailure();
    breaker.recordFailure();
    clock.advance(COOLDOWN);
    assertTrue(breaker.allowRequest());
    breaker.recordFailure(); // re-opens; backoff now doubled

    // A second, still-OPEN success (panic mode again, before the doubled cooldown elapses) closes
    // it just like the ordinary HALF_OPEN path, and must reset backoff the same way that path does.
    breaker.recordSuccess();
    assertEquals(CircuitBreaker.State.CLOSED, breaker.state());

    breaker.recordFailure();
    breaker.recordFailure();
    assertEquals(CircuitBreaker.State.OPEN, breaker.state());
    clock.advance(COOLDOWN);
    assertTrue(
        breaker.allowRequest(),
        "the OPEN-state close reset the backoff; the base cooldown applies again");
  }

  @Test
  void half_open_failure_reopens_the_breaker(TestClock clock) {
    CircuitBreaker breaker = new CircuitBreaker(2, 0.5, COOLDOWN, clock);
    breaker.recordFailure();
    breaker.recordFailure();
    clock.advance(COOLDOWN);
    assertTrue(breaker.allowRequest());

    breaker.recordFailure();
    assertEquals(CircuitBreaker.State.OPEN, breaker.state());
    assertFalse(breaker.allowRequest());
  }

  @Test
  void is_excluded_does_not_consume_the_half_open_trial_slot(TestClock clock) {
    CircuitBreaker breaker = new CircuitBreaker(2, 0.5, COOLDOWN, clock);
    breaker.recordFailure();
    breaker.recordFailure();
    clock.advance(COOLDOWN);

    assertFalse(breaker.isExcluded(), "cooldown elapsed; no longer excluded from candidacy");
    assertFalse(breaker.isExcluded(), "checking again must not itself claim the trial slot");
    assertTrue(breaker.allowRequest(), "the real attempt still gets to claim the trial slot");
    assertFalse(breaker.allowRequest(), "a second concurrent attempt is correctly rejected");
  }

  @Test
  void repeated_reopens_double_the_effective_cooldown(TestClock clock) {
    CircuitBreaker breaker = new CircuitBreaker(2, 0.5, COOLDOWN, clock);
    breaker.recordFailure();
    breaker.recordFailure();
    assertEquals(CircuitBreaker.State.OPEN, breaker.state());

    clock.advance(COOLDOWN);
    assertTrue(breaker.allowRequest(), "the base cooldown elapsed; the first trial is allowed");
    breaker.recordFailure(); // the trial fails -> re-opens, effective cooldown now 2x base

    clock.advance(COOLDOWN);
    assertFalse(
        breaker.allowRequest(),
        "only the base cooldown has elapsed since the re-open; the doubled one has not");

    clock.advance(COOLDOWN);
    assertTrue(breaker.allowRequest(), "the doubled cooldown has now elapsed");
  }

  /**
   * Reaches the {@code MAX_BACKOFF_SHIFT} ceiling, which the old sleep-based test could not have
   * afforded: five consecutive re-opens at the production cooldown is 5s+10s+20s+40s+80s of
   * modelled time, and the assertion is that the sixth is still 16x the base rather than 32x.
   */
  @Test
  void the_doubling_backoff_stops_at_its_documented_ceiling(TestClock clock) {
    CircuitBreaker breaker = new CircuitBreaker(2, 0.5, COOLDOWN, clock);
    breaker.recordFailure();
    breaker.recordFailure();
    assertEquals(CircuitBreaker.State.OPEN, breaker.state());

    // Drive the breaker through six consecutive re-opens: 1x, 2x, 4x, 8x, 16x, then the cap.
    for (int reopen = 0; reopen < 6; reopen++) {
      clock.advance(COOLDOWN.multipliedBy(16));
      assertTrue(breaker.allowRequest(), "16x the base always clears any capped cooldown");
      breaker.recordFailure();
      assertEquals(CircuitBreaker.State.OPEN, breaker.state());
    }

    clock.advance(COOLDOWN.multipliedBy(16).minusNanos(1));
    assertFalse(breaker.allowRequest(), "the capped cooldown is still 16x the base, not less");
    clock.advance(Duration.ofNanos(1));
    assertTrue(breaker.allowRequest(), "and never grows beyond 16x however many re-opens occur");
  }

  @Test
  void a_successful_half_open_trial_resets_the_backoff_to_the_base_cooldown(TestClock clock) {
    CircuitBreaker breaker = new CircuitBreaker(2, 0.5, COOLDOWN, clock);
    breaker.recordFailure();
    breaker.recordFailure();
    clock.advance(COOLDOWN);
    assertTrue(breaker.allowRequest());
    breaker.recordFailure(); // re-opens; backoff now doubled

    clock.advance(COOLDOWN.multipliedBy(2));
    assertTrue(breaker.allowRequest());
    breaker.recordSuccess(); // closes; backoff resets to the base

    breaker.recordFailure();
    breaker.recordFailure();
    assertEquals(CircuitBreaker.State.OPEN, breaker.state());
    clock.advance(COOLDOWN);
    assertTrue(
        breaker.allowRequest(),
        "the successful close reset the backoff; the base cooldown applies again, not a"
            + " continuation of the earlier doubling");
  }

  @Test
  void rejects_invalid_construction_arguments() {
    assertThrows(
        IllegalArgumentException.class, () -> new CircuitBreaker(0, 0.5, Duration.ofMillis(1)));
    assertThrows(
        IllegalArgumentException.class, () -> new CircuitBreaker(4, 0.0, Duration.ofMillis(1)));
    assertThrows(
        IllegalArgumentException.class, () -> new CircuitBreaker(4, 1.5, Duration.ofMillis(1)));
  }
}
