package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.protocol.ControlMessageCodec;
import com.gimle.module.lifecycle.ModuleContext;
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
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link ControlPlaneRelay}'s correlation-keyed future registry: {@link ControlPlaneRelay#request}
 * correctly unblocks once a matching {@link ControlPlaneRelay#complete} arrives, and correctly
 * times out -- with its pending entry cleaned up either way -- when nothing ever answers. Driven
 * against a real Unix domain socket pair, the same infrastructure {@code ControlChannelClientTest}
 * already establishes for exercising {@link ControlChannelClient} itself.
 */
class ControlPlaneRelayTest {

  private Path socketPath;
  private ServerSocketChannel server;

  private Path freshSocketPath() throws IOException {
    return Files.createTempDirectory("gimle-relay-uds-").resolve("c.sock");
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
  @Timeout(10)
  void a_matching_response_completes_the_waiting_caller_and_leaves_no_pending_entry()
      throws Exception {
    socketPath = freshSocketPath();
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socketPath));

    try (ControlChannelClient channel =
            ControlChannelClient.connectWithRetry(
                UnixDomainSocketAddress.of(socketPath),
                Duration.ofMillis(20),
                Duration.ofSeconds(5));
        SocketChannel serverSide = server.accept()) {
      // A generous internal timeout, well beyond this test's own @Timeout(10): the point of this
      // test is proving a matching response wins the race, not exercising the timeout path (see
      // the sibling test below for that) -- a timeout short enough to plausibly race the
      // request/respond round trip below would make this test flaky for the wrong reason.
      ControlPlaneRelay relay = new ControlPlaneRelay(channel, Duration.ofSeconds(30));
      // ControlPlaneRelay.complete only ever gets called from a reader loop -- WorkerMain's own
      // receive loop in production -- never by request() itself, which only sends and blocks. A
      // real one is stood up here so relay.request has something to actually unblock it, the same
      // wiring #handle/#main provide in production.
      Thread relayReader = Thread.ofVirtual().start(() -> pumpRelayResults(channel, relay));

      try {
        CompletableFuture<ModuleContext.RelayResult> future =
            CompletableFuture.supplyAsync(() -> relay.request("/endpoints/greeter"));

        ControlMessage sent = readMessage(serverSide);
        assertTrue(sent instanceof ControlMessage.RelayControlPlaneRead);
        ControlMessage.RelayControlPlaneRead request = (ControlMessage.RelayControlPlaneRead) sent;
        assertEquals("/endpoints/greeter", request.path());

        writeMessage(
            serverSide,
            new ControlMessage.RelayControlPlaneResult(request.correlationId(), 200, "[]"));

        ModuleContext.RelayResult result = future.get(9, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(200, result.status());
        assertEquals("[]", result.body());
        assertEquals(0, relay.pendingRequestCount(), "the completed entry must not linger");
      } finally {
        relayReader.interrupt();
      }
    }
  }

  @Test
  @Timeout(10)
  void a_status_put_rides_the_same_pending_future_machinery_as_a_read() throws Exception {
    socketPath = freshSocketPath();
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socketPath));

    try (ControlChannelClient channel =
            ControlChannelClient.connectWithRetry(
                UnixDomainSocketAddress.of(socketPath),
                Duration.ofMillis(20),
                Duration.ofSeconds(5));
        SocketChannel serverSide = server.accept()) {
      ControlPlaneRelay relay = new ControlPlaneRelay(channel, Duration.ofSeconds(30));
      Thread relayReader = Thread.ofVirtual().start(() -> pumpRelayResults(channel, relay));

      try {
        CompletableFuture<ModuleContext.RelayResult> future =
            CompletableFuture.supplyAsync(
                () ->
                    relay.requestStatusPut(
                        "custom.Greeting",
                        java.util.Optional.of("acme"),
                        "hello",
                        "{\"timesSaid\":3}"));

        ControlMessage sent = readMessage(serverSide);
        assertTrue(sent instanceof ControlMessage.RelayResourceStatusPut);
        ControlMessage.RelayResourceStatusPut request =
            (ControlMessage.RelayResourceStatusPut) sent;
        assertEquals("custom.Greeting", request.kindName());
        assertEquals("acme", request.tenantId());
        assertEquals("hello", request.name());
        assertEquals("{\"timesSaid\":3}", request.statusJson());

        writeMessage(
            serverSide,
            new ControlMessage.RelayControlPlaneResult(request.correlationId(), 200, "{}"));

        ModuleContext.RelayResult result = future.get(9, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(200, result.status());
        assertEquals("{}", result.body());
        assertEquals(0, relay.pendingRequestCount(), "the completed entry must not linger");
      } finally {
        relayReader.interrupt();
      }
    }
  }

  @Test
  @Timeout(10)
  void a_cluster_scoped_status_put_carries_an_empty_tenant_field_on_the_wire() throws Exception {
    socketPath = freshSocketPath();
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socketPath));

    try (ControlChannelClient channel =
            ControlChannelClient.connectWithRetry(
                UnixDomainSocketAddress.of(socketPath),
                Duration.ofMillis(20),
                Duration.ofSeconds(5));
        SocketChannel serverSide = server.accept()) {
      ControlPlaneRelay relay = new ControlPlaneRelay(channel, Duration.ofMillis(200));

      // Nothing ever answers, so the caller times out -- this test only cares what went on the
      // wire for an empty tenant, and that the timeout path cleans up for a status put too.
      ModuleContext.RelayResult result =
          relay.requestStatusPut("custom.ClusterThing", java.util.Optional.empty(), "wide", "{}");

      assertEquals(504, result.status());
      assertEquals(0, relay.pendingRequestCount(), "a timed-out entry must not leak");
      ControlMessage sent = readMessage(serverSide);
      assertTrue(sent instanceof ControlMessage.RelayResourceStatusPut);
      assertEquals("", ((ControlMessage.RelayResourceStatusPut) sent).tenantId());
    }
  }

  @Test
  @Timeout(10)
  void no_response_times_out_and_still_leaves_no_pending_entry() throws Exception {
    socketPath = freshSocketPath();
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socketPath));

    try (ControlChannelClient channel =
            ControlChannelClient.connectWithRetry(
                UnixDomainSocketAddress.of(socketPath),
                Duration.ofMillis(20),
                Duration.ofSeconds(5));
        SocketChannel serverSide = server.accept()) {
      ControlPlaneRelay relay = new ControlPlaneRelay(channel, Duration.ofMillis(200));

      ModuleContext.RelayResult result = relay.request("/endpoints/greeter");

      assertEquals(504, result.status());
      assertEquals(0, relay.pendingRequestCount(), "a timed-out entry must not leak");
      // The request really was sent -- this is a genuine timeout, not a send failure disguised
      // as one.
      assertTrue(readMessage(serverSide) instanceof ControlMessage.RelayControlPlaneRead);
    }
  }

  @Test
  @Timeout(10)
  void a_late_response_after_the_caller_already_gave_up_is_dropped_without_error()
      throws Exception {
    socketPath = freshSocketPath();
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socketPath));

    try (ControlChannelClient channel =
            ControlChannelClient.connectWithRetry(
                UnixDomainSocketAddress.of(socketPath),
                Duration.ofMillis(20),
                Duration.ofSeconds(5));
        SocketChannel serverSide = server.accept()) {
      ControlPlaneRelay relay = new ControlPlaneRelay(channel, Duration.ofMillis(100));

      ModuleContext.RelayResult result = relay.request("/endpoints/greeter");
      assertEquals(504, result.status());

      // Arrives after the caller already timed out and moved on -- must be a silent no-op, not an
      // exception, matching this codebase's tolerance for a late/orphaned message elsewhere.
      relay.complete(new ControlMessage.RelayControlPlaneResult("some-other-id", 200, "late"));
      assertEquals(0, relay.pendingRequestCount());
      assertTrue(readMessage(serverSide) instanceof ControlMessage.RelayControlPlaneRead);
    }
  }

  /**
   * The worker-side counterpart to what {@code WorkerMain}'s real receive loop does with a {@code
   * RelayControlPlaneResult}: read it off the channel and hand it to {@link
   * ControlPlaneRelay#complete}. Runs until the channel closes or the thread is interrupted, which
   * {@link #tearDown} triggers via {@code server.close()}/{@code channel.close()} unblocking {@code
   * receive()} with an {@link IOException}.
   */
  private static void pumpRelayResults(ControlChannelClient channel, ControlPlaneRelay relay) {
    try {
      java.util.Optional<ControlMessage> received;
      while ((received = channel.receive()).isPresent()) {
        if (received.get() instanceof ControlMessage.RelayControlPlaneResult result) {
          relay.complete(result);
        }
      }
    } catch (IOException e) {
      // Channel closed underneath this reader -- the test tearing down, not a real failure.
    }
  }

  private static ControlMessage readMessage(SocketChannel channel) throws IOException {
    BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
    String line = reader.readLine();
    if (line == null) {
      throw new AssertionError("peer closed before sending a message");
    }
    return ControlMessageCodec.decode(line);
  }

  private static void writeMessage(SocketChannel channel, ControlMessage message)
      throws IOException {
    Writer writer =
        new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8);
    writer.write(ControlMessageCodec.encode(message));
    writer.write("\n");
    writer.flush();
  }
}
