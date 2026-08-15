package com.gimle.holmgang.utgard;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.HolmgangException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** No Docker, no network -- a fast unit test of the bounded poll loop's own two outcomes. */
class UtgardPollTest {

  @Test
  void returns_immediately_once_the_condition_is_already_true() {
    UtgardPoll.await(() -> true, Duration.ofSeconds(5), "always true");
  }

  @Test
  void retries_a_failing_condition_before_eventually_succeeding() {
    final AtomicInteger calls = new AtomicInteger();
    UtgardPoll.await(
        () -> calls.incrementAndGet() >= 2, Duration.ofSeconds(10), "second call true");
  }

  @Test
  void throws_a_forensic_message_once_the_timeout_elapses() {
    final HolmgangException error =
        assertThrows(
            HolmgangException.class,
            () -> UtgardPoll.await(() -> false, Duration.ofMillis(300), "never true"));
    assertTrue(error.getMessage().contains("never true"));
    assertTrue(error.getMessage().contains("did not become true within"));
  }

  @Test
  void a_condition_that_throws_is_treated_as_not_yet_until_the_timeout() {
    final HolmgangException error =
        assertThrows(
            HolmgangException.class,
            () ->
                UtgardPoll.await(
                    () -> {
                      throw new RuntimeException("connection refused");
                    },
                    Duration.ofMillis(300),
                    "server reachable"));
    assertTrue(error.getMessage().contains("server reachable"));
    assertTrue(error.getCause().getMessage().contains("connection refused"));
  }
}
