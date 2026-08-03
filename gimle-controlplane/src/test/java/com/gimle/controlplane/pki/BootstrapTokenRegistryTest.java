package com.gimle.controlplane.pki;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void expired_token_cannot_be_consumed() throws InterruptedException {
    BootstrapTokenRegistry registry = new BootstrapTokenRegistry();
    String token = registry.issue(Duration.ofMillis(1));

    Thread.sleep(20);

    assertFalse(registry.tryConsume(token));
  }

  @Test
  void unknown_token_cannot_be_consumed() {
    BootstrapTokenRegistry registry = new BootstrapTokenRegistry();

    assertFalse(registry.tryConsume("never-issued"));
  }
}
