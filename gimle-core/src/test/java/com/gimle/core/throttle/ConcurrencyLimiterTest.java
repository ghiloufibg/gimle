package com.gimle.core.throttle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConcurrencyLimiterTest {

  @Test
  void admits_up_to_the_limit_then_refuses_the_next_caller() {
    ConcurrencyLimiter limiter = new ConcurrencyLimiter(3);

    assertTrue(limiter.tryAcquire());
    assertTrue(limiter.tryAcquire());
    assertTrue(limiter.tryAcquire());
    assertFalse(limiter.tryAcquire());
  }

  @Test
  void a_released_slot_can_be_reacquired() {
    ConcurrencyLimiter limiter = new ConcurrencyLimiter(1);
    assertTrue(limiter.tryAcquire());
    assertFalse(limiter.tryAcquire());

    limiter.release();

    assertTrue(limiter.tryAcquire());
  }

  @Test
  void in_flight_reflects_slots_currently_claimed() {
    ConcurrencyLimiter limiter = new ConcurrencyLimiter(5);
    limiter.tryAcquire();
    limiter.tryAcquire();

    assertEquals(2, limiter.inFlight());

    limiter.release();

    assertEquals(1, limiter.inFlight());
  }

  @Test
  void a_refusal_does_not_claim_a_slot() {
    ConcurrencyLimiter limiter = new ConcurrencyLimiter(1);
    limiter.tryAcquire();

    assertFalse(limiter.tryAcquire());
    assertEquals(1, limiter.inFlight(), "a refused attempt must not itself count as in flight");
  }

  @Test
  void a_non_positive_limit_is_rejected() {
    assertThrows(IllegalArgumentException.class, () -> new ConcurrencyLimiter(0));
    assertThrows(IllegalArgumentException.class, () -> new ConcurrencyLimiter(-1));
  }

  /**
   * The property an admission gate in front of a thread-per-request server actually needs: with a
   * budget of 10 and 100 racing callers holding whatever they claim (never releasing mid-race),
   * exactly 10 succeed -- no more, however many threads pile in at once.
   */
  @Test
  void concurrent_callers_racing_a_small_budget_admit_exactly_the_limit() throws Exception {
    ConcurrencyLimiter limiter = new ConcurrencyLimiter(10);
    Set<Integer> admitted = ConcurrentHashMap.newKeySet();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(100);

    for (int i = 0; i < 100; i++) {
      final int attempt = i;
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  start.await();
                  if (limiter.tryAcquire()) {
                    admitted.add(attempt);
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              });
    }
    start.countDown();
    assertTrue(done.await(30, TimeUnit.SECONDS));

    assertEquals(10, admitted.size());
    assertEquals(10, limiter.inFlight());
  }
}
