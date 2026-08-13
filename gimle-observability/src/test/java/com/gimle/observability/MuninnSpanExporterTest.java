package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link MuninnSpanExporter} exercised against a real, SDK-produced {@link SpanData} rather than a
 * hand-built one (no test-only OpenTelemetry SDK-testing artifact is a dependency here) -- a real
 * {@link SdkTracerProvider} wired to a {@link SimpleSpanProcessor} invokes the exporter under test
 * synchronously on {@code span.end()}, the same shape {@code MuninnShipperTest}'s own stub-server
 * pattern uses for the HTTP side.
 */
class MuninnSpanExporterTest {

  private HttpServer stub;

  @AfterEach
  void tearDown() {
    if (stub != null) {
      stub.stop(0);
    }
  }

  private HttpServer startStub(List<String> receivedBodies) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ingest/traces/CONTROLPLANE/node-a",
        exchange -> {
          try (InputStream in = exchange.getRequestBody()) {
            receivedBodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
          }
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return server;
  }

  @Test
  @Timeout(10)
  void a_real_span_batch_reaches_the_stub_ingest_server_with_the_expected_shape() throws Exception {
    List<String> receivedBodies = new CopyOnWriteArrayList<>();
    stub = startStub(receivedBodies);
    String muninnEndpoint = "127.0.0.1:" + stub.getAddress().getPort();

    try (MuninnShipper shipper =
        new MuninnShipper(
            muninnEndpoint, "/ingest/traces/CONTROLPLANE/node-a", Duration.ofSeconds(30))) {
      MuninnSpanExporter exporter = new MuninnSpanExporter(shipper);
      SdkTracerProvider tracerProvider =
          SdkTracerProvider.builder()
              .addSpanProcessor(SimpleSpanProcessor.create(exporter))
              .build();
      Tracer tracer = tracerProvider.get("test");
      Span span = tracer.spanBuilder("do-something").setAttribute("http.method", "GET").startSpan();
      span.end();
      tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
      tracerProvider.close();

      awaitUntil(() -> !receivedBodies.isEmpty(), Duration.ofSeconds(5));
      Map<String, Object> line = Json.asObject(Json.parse(receivedBodies.get(0).trim()));
      assertEquals("do-something", line.get("name"));
      assertEquals("GET", line.get("http.method"));
      assertTrue(line.containsKey("traceId"));
      assertTrue(line.containsKey("spanId"));
      assertTrue(line.containsKey("timestamp"));
    }
  }

  @Test
  @Timeout(10)
  void export_never_throws_even_when_shipping_fails() {
    // A shipper pointed at a port nothing is listening on -- the ingest call itself must fail.
    MuninnShipper shipper =
        new MuninnShipper(
            "127.0.0.1:1", "/ingest/traces/CONTROLPLANE/node-a", Duration.ofSeconds(30));
    MuninnSpanExporter exporter = new MuninnSpanExporter(shipper);

    Collection<SpanData> spans = capturedSpans();
    CompletableResultCode result = exporter.export(spans);

    assertTrue(result.isSuccess(), "export must never surface a shipping failure to the SDK");
    shipper.close();
  }

  private static Collection<SpanData> capturedSpans() {
    CapturingSpanExporter capturingExporter = new CapturingSpanExporter();
    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(capturingExporter))
            .build();
    Tracer tracer = tracerProvider.get("test");
    tracer.spanBuilder("ephemeral").startSpan().end();
    tracerProvider.close();
    return capturingExporter.captured();
  }

  private static void awaitUntil(BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within " + timeout);
      }
      Thread.sleep(20);
    }
  }
}
