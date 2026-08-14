package com.gimle.holmgang.cluster;

import com.gimle.holmgang.HolmgangException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * The bootstrap runtime's internal readiness polling. Deliberately package-private: scenario code
 * gets condition helpers from {@link ClusterApi} instead of open-coding poll loops, and this whole
 * mechanism is an implementation detail scheduled to be subsumed by an event-driven harness rather
 * than grown into a public waiting API.
 */
final class Polls {

  private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

  private Polls() {}

  static void await(
      final BooleanSupplier condition, final Duration timeout, final String description) {
    final long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new HolmgangException("condition not met within " + timeout + ": " + description);
      }
      try {
        Thread.sleep(POLL_INTERVAL);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new HolmgangException("interrupted while waiting for: " + description, e);
      }
    }
  }

  static void awaitPortOpen(final String host, final int port, final Duration timeout) {
    await(
        () -> isPortOpen(host, port),
        timeout,
        "port " + host + ":" + port + " should start listening");
  }

  static boolean isPortOpen(final String host, final int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 500);
      return true;
    } catch (final IOException e) {
      return false;
    }
  }
}
