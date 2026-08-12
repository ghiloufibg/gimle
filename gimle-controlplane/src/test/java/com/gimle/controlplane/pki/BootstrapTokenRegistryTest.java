package com.gimle.controlplane.pki;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.time.TestClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage for the two properties {@link NodeBootstrapCsrTest}'s HTTP-level test can't
 * reliably assert (expiry timing) or would duplicate (single-use, tested here without needing a
 * real {@code ApiServer}).
 */
class BootstrapTokenRegistryTest {

  @Test
  void issued_token_can_be_consumed_exactly_once() {
    BootstrapTokenRegistry registry = new BootstrapTokenRegistry();
    String token = registry.issue(Duration.ofMinutes(5));

    assertTrue(registry.tryConsume(token));
    assertFalse(registry.tryConsume(token));
  }

  @Test
  void expired_token_cannot_be_consumed(TestClock clock) {
    // The real 5-minute TTL an operator would issue, rather than a 1ms one raced by a sleep.
    BootstrapTokenRegistry registry = new BootstrapTokenRegistry(clock);
    Duration ttl = Duration.ofMinutes(5);
    String token = registry.issue(ttl);

    clock.advance(ttl);

    assertFalse(
        registry.tryConsume(token),
        "a token is invalid from its expiry instant onward, not one tick later");
  }

  @Test
  void a_token_is_still_valid_right_up_to_its_expiry(TestClock clock) {
    BootstrapTokenRegistry registry = new BootstrapTokenRegistry(clock);
    Duration ttl = Duration.ofMinutes(5);
    String token = registry.issue(ttl);

    clock.advance(ttl.minusNanos(1));

    assertTrue(registry.tryConsume(token));
  }

  @Test
  void unknown_token_cannot_be_consumed() {
    BootstrapTokenRegistry registry = new BootstrapTokenRegistry();

    assertFalse(registry.tryConsume("never-issued"));
  }
}
