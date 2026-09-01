package com.gimle.core.throttle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RequestRateLimiterTest {

  @Test
  void a_full_burst_is_admitted_in_one_go() {
    RequestRateLimiter limiter = new RequestRateLimiter(50, Duration.ofSeconds(1));

    for (int i = 0; i < 50; i++) {
      assertEquals(Optional.empty(), limiter.acquire("10.0.0.1"), "request " + i);
    }
  }

  @Test
  void the_request_past_the_burst_is_refused_with_a_future_retry_instant() {
    RequestRateLimiter limiter = new RequestRateLimiter(3, Duration.ofSeconds(30));
    for (int i = 0; i < 3; i++) {
      limiter.acquire("10.0.0.1");
    }

    Optional<Instant> retryAt = limiter.acquire("10.0.0.1");

    assertTrue(retryAt.isPresent());
    assertTrue(retryAt.get().isAfter(Instant.now()));
    assertTrue(retryAt.get().isBefore(Instant.now().plusSeconds(31)));
  }

  @Test
  void a_refused_request_does_not_push_the_retry_instant_further_out() {
    RequestRateLimiter limiter = new RequestRateLimiter(1, Duration.ofSeconds(30));
    limiter.acquire("10.0.0.1");

    Instant firstRefusal = limiter.acquire("10.0.0.1").orElseThrow();
    Instant secondRefusal = limiter.acquire("10.0.0.1").orElseThrow();

    // The second refusal may only be earlier (time passed, the bucket partially refilled), never
    // later: repeated attempts must not extend the lockout the way a failure-backoff would.
    assertTrue(!secondRefusal.isAfter(firstRefusal));
  }

  @Test
  void a_key_that_exhausted_its_burst_is_admitted_again_once_a_token_refills() throws Exception {
    RequestRateLimiter limiter = new RequestRateLimiter(2, Duration.ofMillis(20));
    limiter.acquire("10.0.0.1");
    limiter.acquire("10.0.0.1");
    assertTrue(limiter.acquire("10.0.0.1").isPresent());

    Thread.sleep(60);

    assertEquals(Optional.empty(), limiter.acquire("10.0.0.1"));
  }

  @Test
  void a_key_never_refills_past_its_burst_capacity() throws Exception {
    RequestRateLimiter limiter = new RequestRateLimiter(2, Duration.ofMillis(1));
    limiter.acquire("10.0.0.1");

    Thread.sleep(50);

    assertEquals(Optional.empty(), limiter.acquire("10.0.0.1"));
    assertEquals(Optional.empty(), limiter.acquire("10.0.0.1"));
    assertTrue(limiter.acquire("10.0.0.1").isPresent());
  }

  /**
   * The case a real cluster bring-up depends on: many distinct callers each spending one request at
   * the same moment must all be admitted, no matter how small the per-key burst is.
   */
  @Test
  void one_exhausted_key_never_throttles_any_other_key() {
    RequestRateLimiter limiter = new RequestRateLimiter(1, Duration.ofSeconds(30));
    limiter.acquire("10.0.0.1");
    assertTrue(limiter.acquire("10.0.0.1").isPresent());

    for (int node = 0; node < 200; node++) {
      assertEquals(Optional.empty(), limiter.acquire("10.0.1." + node), "node " + node);
    }
  }

  @Test
  void concurrent_callers_on_one_key_are_admitted_exactly_burst_capacity_times() throws Exception {
    RequestRateLimiter limiter = new RequestRateLimiter(20, Duration.ofMinutes(10));
    Set<Integer> admitted = ConcurrentHashMap.newKeySet();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(64);

    for (int i = 0; i < 64; i++) {
      final int attempt = i;
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  start.await();
                  if (limiter.acquire("10.0.0.1").isEmpty()) {
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

    // A ten-minute refill interval makes any refill during the run impossible, so the count is
    // exact rather than a lower bound -- this is the property a token bucket must not lose under
    // concurrency.
    assertEquals(20, admitted.size());
  }

  @Test
  void a_non_positive_burst_or_refill_interval_is_rejected() {
    assertThrows(
        IllegalArgumentException.class, () -> new RequestRateLimiter(0, Duration.ofSeconds(1)));
    assertThrows(IllegalArgumentException.class, () -> new RequestRateLimiter(1, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> new RequestRateLimiter(1, Duration.ofSeconds(-1)));
    assertThrows(IllegalArgumentException.class, () -> new RequestRateLimiter(1, null));
  }
}
