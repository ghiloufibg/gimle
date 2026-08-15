package com.gimle.holmgang.utgard;

import com.gimle.holmgang.HolmgangException;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * A small bounded poll loop for state this suite can only observe through a real HTTP call over the
 * container network, rather than the in-process {@code GimleCluster} fixture's own event-driven
 * Heimdall conditions -- Heimdall was built to attach to processes this harness itself spawned and
 * can tail the logs of directly, neither of which holds for a process running inside a container. A
 * condition that throws (a connection refused while a control plane is still coming up) is treated
 * as "not yet", exactly like the single-shot {@code is*} readers on {@code ClusterApi} do, and only
 * surfaces as the eventual timeout's own cause.
 */
final class UtgardPoll {

  private static final Duration INTERVAL = Duration.ofSeconds(2);

  private UtgardPoll() {}

  static void await(
      final BooleanSupplier condition, final Duration timeout, final String description) {
    final Instant deadline = Instant.now().plus(timeout);
    RuntimeException lastFailure = null;
    while (Instant.now().isBefore(deadline)) {
      try {
        if (condition.getAsBoolean()) {
          return;
        }
      } catch (final RuntimeException e) {
        lastFailure = e;
      }
      sleep();
    }
    throw new HolmgangException(
        description + " did not become true within " + timeout, lastFailure);
  }

  private static void sleep() {
    try {
      Thread.sleep(INTERVAL);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HolmgangException("interrupted while polling", e);
    }
  }
}
