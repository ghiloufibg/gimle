package com.gimle.hilmir.launch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReadinessPollerTest {

  @Test
  void times_out_with_a_clear_message_when_nothing_ever_listens() {
    final int neverOpenedPort;
    try {
      neverOpenedPort = LaunchTestSupport.freePort();
    } catch (final IOException e) {
      throw new AssertionError(e);
    }

    final HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                ReadinessPoller.awaitPortOpen(
                    "127.0.0.1:" + neverOpenedPort, Duration.ofMillis(300), "a test process"));

    assertTrue(e.getMessage().contains("a test process"));
    assertTrue(e.getMessage().contains(String.valueOf(neverOpenedPort)));
  }

  @Test
  void returns_as_soon_as_the_port_is_already_listening() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      ReadinessPoller.awaitPortOpen(
          "127.0.0.1:" + socket.getLocalPort(), Duration.ofSeconds(5), "an already-open port");
    }
  }

  @Test
  void
      the_process_aware_overload_fails_fast_once_the_process_has_already_exited_instead_of_waiting_out_the_timeout()
          throws IOException, InterruptedException {
    final int neverOpenedPort = LaunchTestSupport.freePort();
    // A process that starts and exits almost immediately, portably (java -version runs on every
    // platform this codebase supports) -- the fixture stands in for a real spawn that crashed at
    // startup, e.g. a bad classpath or missing main class.
    final Process process =
        new ProcessBuilder(LaunchTestSupport.javaExecutable(), "-version").start();
    process.waitFor();
    final Path logFile = Path.of("store-0.log");

    final HilmirException e =
        assertThrows(
            HilmirException.class,
            () ->
                ReadinessPoller.awaitPortOpen(
                    "127.0.0.1:" + neverOpenedPort,
                    Duration.ofSeconds(30),
                    "a test process",
                    process,
                    logFile));

    assertTrue(e.getMessage().contains("exited"));
    assertTrue(e.getMessage().contains("store-0.log"));
  }

  @Test
  void the_process_aware_overload_returns_as_soon_as_the_port_is_already_listening()
      throws IOException, InterruptedException {
    // The port-open check runs before the process-liveness check on every iteration, so an
    // already-listening port returns immediately regardless of the process's own state -- even one
    // that has already exited, as this one deliberately has.
    final Process alreadyExitedProcess =
        new ProcessBuilder(LaunchTestSupport.javaExecutable(), "-version").start();
    alreadyExitedProcess.waitFor();

    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      ReadinessPoller.awaitPortOpen(
          "127.0.0.1:" + socket.getLocalPort(),
          Duration.ofSeconds(5),
          "an already-open port",
          alreadyExitedProcess,
          Path.of("unused.log"));
    }
  }

  @Test
  void is_port_open_reports_false_for_a_closed_port_without_waiting() throws IOException {
    final int closedPort = LaunchTestSupport.freePort();

    assertFalse(ReadinessPoller.isPortOpen("127.0.0.1:" + closedPort));
  }

  @Test
  void is_port_open_reports_true_for_a_listening_port() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      assertTrue(ReadinessPoller.isPortOpen("127.0.0.1:" + socket.getLocalPort()));
    }
  }
}
