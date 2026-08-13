package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.protocol.ControlMessageCodec;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RelayingSpanExporter} exercised against a real Unix domain socket, the same {@link
 * ControlChannelClientTest}-style pattern -- proving the exporter relays a real span batch as one
 * {@code ControlMessage.TracesSnapshot} the peer can decode with the ordinary {@link
 * ControlMessageCodec}, not a private serialization only this class understands.
 */
class RelayingSpanExporterTest {

  private Path socketPath;
  private ServerSocketChannel server;

  @AfterEach
  void tearDown() throws IOException {
    if (server != null) {
      server.close();
    }
    if (socketPath != null) {
      Files.deleteIfExists(socketPath);
    }
  }

  @Test
  void a_real_span_batch_is_relayed_as_one_traces_snapshot_message() throws Exception {
    socketPath = Files.createTempDirectory("gimle-uds-").resolve("c.sock");
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socketPath));

    try (ControlChannelClient client =
            ControlChannelClient.connectWithRetry(
                UnixDomainSocketAddress.of(socketPath),
                Duration.ofMillis(20),
                Duration.ofSeconds(5));
        SocketChannel serverSide = server.accept()) {
      RelayingSpanExporter exporter = new RelayingSpanExporter(client, "worker-4242");
      SdkTracerProvider tracerProvider =
          SdkTracerProvider.builder()
              .addSpanProcessor(SimpleSpanProcessor.create(exporter))
              .build();
      Tracer tracer = tracerProvider.get("test");
      tracer.spanBuilder("do-something").startSpan().end();
      tracerProvider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
      tracerProvider.close();

      String line = readLine(serverSide);
      ControlMessage decoded = ControlMessageCodec.decode(line);
      assertTrue(decoded instanceof ControlMessage.TracesSnapshot);
      ControlMessage.TracesSnapshot snapshot = (ControlMessage.TracesSnapshot) decoded;
      assertEquals("worker-4242", snapshot.workerId());
      assertTrue(snapshot.ndjsonPayload().contains("do-something"));
    }
  }

  @Test
  void export_never_throws_even_when_the_channel_is_closed() throws IOException {
    // A channel whose underlying socket is already closed -- send() fails with IOException, the
    // realistic failure shape a worker mid-teardown could hit; the exporter must swallow it rather
    // than surface it to the OpenTelemetry SDK (matches MuninnSpanExporter's own contract).
    Path path = Files.createTempDirectory("gimle-uds-closed-").resolve("c.sock");
    try (ServerSocketChannel srv = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      srv.bind(UnixDomainSocketAddress.of(path));
      ControlChannelClient client =
          ControlChannelClient.connectWithRetry(
              UnixDomainSocketAddress.of(path), Duration.ofMillis(20), Duration.ofSeconds(5));
      try (SocketChannel ignoredServerSide = srv.accept()) {
        client.close();
        RelayingSpanExporter exporter = new RelayingSpanExporter(client, "worker-1");
        SdkTracerProvider tracerProvider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = tracerProvider.get("test");
        tracer.spanBuilder("ephemeral").startSpan().end();
        CompletableResultCode result =
            tracerProvider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(result.isSuccess(), "export must never surface a relay failure to the SDK");
        tracerProvider.close();
      }
    }
  }

  private static String readLine(SocketChannel channel) throws IOException {
    BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
    return reader.readLine();
  }
}
