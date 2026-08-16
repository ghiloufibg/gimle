package com.gimle.testkit;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Spin-polls a condition instead of a fixed sleep, so a real-cluster test fails fast the moment the
 * condition becomes true rather than always waiting out a fixed delay. The canonical copy of this
 * primitive: several modules (gimle-mimir, gimle-fabric, gimle-worker, gimle-controlplane's own
 * test sources) each still carry their own small, independent copy rather than depending on this
 * one -- deliberately left as-is rather than migrated, since none of them otherwise depends on
 * test-only infrastructure from another module.
 */
public final class Await {

  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(10);

  private Await() {}

  public static void until(final BooleanSupplier condition, final Duration timeout) {
    until(condition, timeout, DEFAULT_POLL_INTERVAL, null);
  }

  public static void until(
      final BooleanSupplier condition, final Duration timeout, final String description) {
    until(condition, timeout, DEFAULT_POLL_INTERVAL, description);
  }

  private static void until(
      final BooleanSupplier condition,
      final Duration timeout,
      final Duration pollInterval,
      final String description) {
    final long deadline = System.nanoTime() + timeout.toNanos();
    final String suffix = description == null ? "" : ": " + description;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within " + timeout + suffix);
      }
      try {
        Thread.sleep(pollInterval);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting for condition" + suffix, e);
      }
    }
  }
}
