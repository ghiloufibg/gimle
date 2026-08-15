package com.gimle.hilmir.launch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
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
