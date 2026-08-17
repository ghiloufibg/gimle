package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.core.protocol.ControlMessage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the worker's half of the control channel against a real Unix domain socket -- the same
 * kind of listener {@code gimle-agent} will run, stood up directly here rather than via a full
 * agent process, since building and testing the listener side of that channel is {@code
 * gimle-agent}'s own concern, not this module's. This proves {@link ControlChannelClient} itself,
 * not {@link WorkerMain}'s full command loop, which needs a real subprocess pair to test
 * end-to-end.
 */
class ControlChannelClientTest {

  private Path socketPath;
  private ServerSocketChannel server;

  private Path freshSocketPath() throws IOException {
    // A short path, deliberately not JUnit's @TempDir (nested under the test class/method name),
    // to stay well under AF_UNIX's sun_path length limit on every platform this runs on.
    return Files.createTempDirectory("gimle-uds-").resolve("c.sock");
  }

  @AfterEach
  void tearDown() throws IOException {
    if (server != null) {
      server.close();
    }
    if (socketPath != null) {
      Files.deleteIfExists(socketPath);
      Files.deleteIfExists(socketPath.getParent());
    }
  }

  @Test
  void connect_with_retry_succeeds_once_the_listener_is_up() throws Exception {
    socketPath = freshSocketPath();
    Thread listenerThread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    Thread.sleep(200);
                    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                    server.bind(UnixDomainSocketAddress.of(socketPath));
                  } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                  }
                });

    try (ControlChannelClient client =
        ControlChannelClient.connectWithRetry(
            UnixDomainSocketAddress.of(socketPath), Duration.ofMillis(30), Duration.ofSeconds(5))) {
      assertNotNull(client);
    } finally {
      listenerThread.join(Duration.ofSeconds(5).toMillis());
    }
  }

  @Test
  void connect_with_retry_gives_up_after_its_timeout_if_nothing_ever_listens() throws Exception {
    socketPath = freshSocketPath();
    assertThrows(
        IOException.class,
        () ->
            ControlChannelClient.connectWithRetry(
                UnixDomainSocketAddress.of(socketPath),
                Duration.ofMillis(20),
                Duration.ofMillis(150)));
  }

  @Test
  void a_sent_message_is_received_intact_on_the_other_end() throws Exception {
    socketPath = freshSocketPath();
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socketPath));

    try (ControlChannelClient client =
            ControlChannelClient.connectWithRetry(
                UnixDomainSocketAddress.of(socketPath),
                Duration.ofMillis(20),
                Duration.ofSeconds(5));
        SocketChannel serverSide = server.accept()) {
      client.send(new ControlMessage.Hello("worker-1", 4242));

      String line = readLine(serverSide);
      assertEquals("HELLO worker-1 4242   0", line);
    }
  }

  @Test
  void receive_returns_empty_once_the_peer_closes_the_connection() throws Exception {
    socketPath = freshSocketPath();
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socketPath));

    try (ControlChannelClient client =
        ControlChannelClient.connectWithRetry(
            UnixDomainSocketAddress.of(socketPath), Duration.ofMillis(20), Duration.ofSeconds(5))) {
      try (SocketChannel serverSide = server.accept()) {
        writeLine(serverSide, "PING corr-1");
        Optional<ControlMessage> received = client.receive();
        assertEquals(Optional.of(new ControlMessage.Ping("corr-1")), received);
      }
      // serverSide closed by the try-with-resources above -> the next receive() sees EOF.
      assertEquals(Optional.empty(), client.receive());
    }
  }

  private static String readLine(SocketChannel channel) throws IOException {
    BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
    return reader.readLine();
  }

  private static void writeLine(SocketChannel channel, String line) throws IOException {
    Writer writer =
        new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8);
    writer.write(line);
    writer.write("\n");
    writer.flush();
  }
}
