package com.gimle.hilmir.launch;

import com.gimle.hilmir.HilmirException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * A plain TCP-connect readiness signal: every process kind this launcher spawns exposes some
 * listening port (a store's client port, a control plane's HTTP port, an agent's gossip port, ...),
 * and a successful connect to it is a sufficient, uniform "this process is up enough for a
 * dependent to talk to it" signal without needing a role-specific health check.
 */
final class ReadinessPoller {

  private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
  private static final int CONNECT_TIMEOUT_MILLIS = 500;

  private ReadinessPoller() {}

  /** Retries a connect to {@code hostPort} until it succeeds or {@code timeout} elapses. */
  static void awaitPortOpen(
      final String hostPort, final Duration timeout, final String description) {
    final long deadlineNanos = System.nanoTime() + timeout.toNanos();
    while (!isPortOpen(hostPort)) {
      if (System.nanoTime() > deadlineNanos) {
        throw new HilmirException(
            "timed out after " + timeout + " waiting for " + description + " at " + hostPort);
      }
      try {
        Thread.sleep(POLL_INTERVAL);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new HilmirException("interrupted while waiting for " + description, e);
      }
    }
  }

  /** A single, instantaneous connect attempt -- no retry, for a point-in-time status check. */
  static boolean isPortOpen(final String hostPort) {
    final HostAndPort parsed = HostAndPort.parse(hostPort);
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(parsed.host(), parsed.port()), CONNECT_TIMEOUT_MILLIS);
      return true;
    } catch (final IOException e) {
      return false;
    }
  }

  /** Splits on the last {@code :} so an IPv6 literal host (itself colon-separated) still works. */
  private record HostAndPort(String host, int port) {
    static HostAndPort parse(final String hostPort) {
      final int lastColon = hostPort.lastIndexOf(':');
      if (lastColon < 0) {
        throw new HilmirException("not a host:port address: " + hostPort);
      }
      final String host = hostPort.substring(0, lastColon);
      try {
        final int port = Integer.parseInt(hostPort.substring(lastColon + 1));
        return new HostAndPort(host, port);
      } catch (final NumberFormatException e) {
        throw new HilmirException("not a host:port address: " + hostPort, e);
      }
    }
  }
}
