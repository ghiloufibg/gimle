package com.gimle.fabric.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.fabric.registry.Greeter;
import com.gimle.fabric.trace.TraceContext;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * {@code FabricServer#startChildSpanContext} reads the process-wide {@link GlobalOpenTelemetry}
 * singleton, which every other test that dispatches a real inbound fabric call also touches (via
 * its default lazy no-op init) -- mutating it concurrently with those, the way root pom.xml's
 * class-level parallelism normally runs this module's tests, throws {@code GlobalOpenTelemetry.set
 * has already been called}. {@code @Isolated} (same tool {@code RaftClusterTest} already uses for
 * its own unrelated reason) pauses the rest of the suite around this one class instead, matching
 * how little else could safely coexist with a real SDK installed as the global instance.
 */
@Isolated
// Reads gimle.transport.protocol (through FabricClient/FabricServer) without ever setting it: a
// READ lock lets these plaintext classes run concurrently with each other while excluding any
// class that mutates the JVM-global TLS properties mid-run (see FabricTransportTlsTest).
@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ)
class FabricServerGlobalTracingTest {

  private static final ModuleId OWNER =
      new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0"));

  private FabricServer server;
  private ExecutorService clientThreads;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
    if (clientThreads != null) {
      clientThreads.shutdownNow();
    }
    GlobalOpenTelemetry.resetForTest();
  }

  @Test
  @Timeout(10)
  void a_call_with_no_active_caller_span_starts_a_fresh_valid_trace_not_the_all_zero_marker()
      throws Exception {
    // The actual P3 finding this guards: constructing a SpanContext straight from the all-zero
    // "no active span at capture time" marker and feeding it into a real backend must never
    // surface those literal zeros as a real span's trace id -- harmless under the platform's
    // default no-op tracer (nothing ever inspects it), so only a real SDK can prove the fix.
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    GlobalOpenTelemetry.resetForTest();
    GlobalOpenTelemetry.set(OpenTelemetrySdk.builder().setTracerProvider(provider).build());

    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(OWNER, Greeter.class, name -> "hello:" + name);
    server = new FabricServer(registry, Greeter.class.getClassLoader());
    InetSocketAddress address =
        (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));

    TraceContext noActiveSpan = new TraceContext(0L, 0L, 0L, (byte) 0);
    FabricFrame.InvokeRequest request =
        new FabricFrame.InvokeRequest(
            1L,
            noActiveSpan,
            Greeter.class.getName(),
            "greet",
            new String[] {"java.lang.String"},
            ObjectMarshalling.serialize(new Object[] {"world"}));
    clientThreads = Executors.newVirtualThreadPerTaskExecutor();
    clientThreads.submit(() -> FabricClient.call(address, request)).get(5, TimeUnit.SECONDS);
    provider.forceFlush();

    List<SpanData> spans =
        exporter.getFinishedSpanItems().stream()
            .filter(s -> s.getName().equals(Greeter.class.getName() + "#greet"))
            .toList();
    assertEquals(1, spans.size());
    SpanData span = spans.get(0);
    assertTrue(TraceId.isValid(span.getTraceId()), "trace id must not be the all-zero marker");
    assertEquals("0000000000000000", span.getParentSpanId(), "must be a fresh root, no parent");
  }
}
