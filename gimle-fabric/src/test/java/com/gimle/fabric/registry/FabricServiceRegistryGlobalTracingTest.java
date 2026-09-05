package com.gimle.fabric.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.fabric.cluster.MemberId;
import com.gimle.fabric.transport.FabricServer;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import com.gimle.observability.WorkerMetrics;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Exercises a real cross-worker fabric call end to end against a real installed OTel SDK -- the P3
 * finding this guards (OBS-4): before {@link FabricServiceRegistry#invokeOverWire} started its own
 * {@link SpanKind#CLIENT} span, a caller with no already-active span captured the all-zero "no
 * active span" marker, so {@code FabricServer}'s inbound {@code SERVER} span always came up as a
 * disconnected root -- real caller activity or not. Also covers OBS-5's metrics half: a worker that
 * only ever calls out through the fabric (never receives an inbound call, so {@code FabricServer}'s
 * own request counters never fire) now still produces real client-side request telemetry via {@link
 * WorkerMetrics#recordClientRequest}.
 *
 * <p>{@code @Isolated} for the same reason {@code FabricServerGlobalTracingTest} already documents:
 * mutating the process-wide {@link GlobalOpenTelemetry} singleton can't safely run concurrently
 * with this module's other tests, which touch it via its default lazy no-op init.
 */
@Isolated
// Reads gimle.transport.protocol (through FabricClient/FabricServer) without ever setting it: a
// READ lock lets these plaintext classes run concurrently with each other while excluding any
// class that mutates the JVM-global TLS properties mid-run (see FabricTransportTlsTest).
@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ)
class FabricServiceRegistryGlobalTracingTest {

  private static final ModuleInstanceId OWNER =
      ModuleInstanceId.unattached(
          new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0")));
  private static final ServiceExport GREETER_EXPORT =
      new ServiceExport(Greeter.class.getName(), Version.parse("1.0.0"));

  private final MemberId selfNode =
      new MemberId("node-a", new InetSocketAddress("127.0.0.1", 7946));

  private FabricServer server;
  private SdkTracerProvider provider;
  private InMemorySpanExporter exporter;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
    GlobalOpenTelemetry.resetForTest();
  }

  private void installRealSdk() {
    exporter = InMemorySpanExporter.create();
    provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    GlobalOpenTelemetry.resetForTest();
    GlobalOpenTelemetry.set(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
  }

  private FabricServiceRegistry remoteRegistry(
      ServiceCatalog catalog, Optional<WorkerMetrics> metrics) {
    return new FabricServiceRegistry(
        selfNode,
        "worker-self",
        new SimpleServiceRegistry(),
        catalog,
        owner -> List.of(GREETER_EXPORT),
        message -> {},
        Greeter.class.getClassLoader(),
        4,
        0.5,
        Duration.ofMillis(200),
        Optional.empty(),
        0.5,
        false,
        metrics);
  }

  @Test
  @Timeout(10)
  void a_remote_call_produces_a_client_span_that_parents_the_servers_span() throws IOException {
    installRealSdk();

    SimpleServiceRegistry backing = new SimpleServiceRegistry();
    backing.register(OWNER, Greeter.class, name -> "hello:" + name);
    server = new FabricServer(backing, Greeter.class.getClassLoader());
    InetSocketAddress remoteAddress =
        (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));

    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        GREETER_EXPORT,
        Optional.empty(),
        remoteAddress);
    FabricServiceRegistry registry = remoteRegistry(catalog, Optional.empty());

    Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
    assertEquals("hello:world", greeter.greet("world"));
    provider.forceFlush();

    List<SpanData> spans = exporter.getFinishedSpanItems();
    SpanData clientSpan =
        spans.stream().filter(s -> s.getKind() == SpanKind.CLIENT).findFirst().orElseThrow();
    SpanData serverSpan =
        spans.stream().filter(s -> s.getKind() == SpanKind.SERVER).findFirst().orElseThrow();

    assertEquals(Greeter.class.getName() + "#greet", clientSpan.getName());
    assertEquals(clientSpan.getTraceId(), serverSpan.getTraceId());
    assertEquals(clientSpan.getSpanId(), serverSpan.getParentSpanId());
    assertNotEquals("0000000000000000", clientSpan.getSpanId());
  }

  @Test
  @Timeout(10)
  void a_remote_call_records_client_side_request_metrics() throws IOException {
    installRealSdk();

    SimpleServiceRegistry backing = new SimpleServiceRegistry();
    backing.register(OWNER, Greeter.class, name -> "hello:" + name);
    server = new FabricServer(backing, Greeter.class.getClassLoader());
    InetSocketAddress remoteAddress =
        (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));

    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        GREETER_EXPORT,
        Optional.empty(),
        remoteAddress);
    WorkerMetrics metrics = new WorkerMetrics();
    FabricServiceRegistry registry = remoteRegistry(catalog, Optional.of(metrics));

    Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
    greeter.greet("world");

    assertEquals(1.0, metrics.clientRequestCount(Greeter.class.getName()));
    assertEquals(0.0, metrics.clientErrorCount(Greeter.class.getName()));
  }

  @Test
  @Timeout(10)
  void a_failed_remote_call_still_records_a_client_error_and_ends_the_span() throws IOException {
    installRealSdk();

    SimpleServiceRegistry backing = new SimpleServiceRegistry();
    backing.register(
        OWNER,
        Greeter.class,
        name -> {
          throw new IllegalStateException("missing config key 'greeting.prefix'");
        });
    server = new FabricServer(backing, Greeter.class.getClassLoader());
    InetSocketAddress remoteAddress =
        (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));

    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        GREETER_EXPORT,
        Optional.empty(),
        remoteAddress);
    WorkerMetrics metrics = new WorkerMetrics();
    FabricServiceRegistry registry = remoteRegistry(catalog, Optional.of(metrics));

    Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
    assertThrows(IllegalStateException.class, () -> greeter.greet("world"));
    provider.forceFlush();

    assertEquals(1.0, metrics.clientErrorCount(Greeter.class.getName()));
    List<SpanData> clientSpans =
        exporter.getFinishedSpanItems().stream()
            .filter(s -> s.getKind() == SpanKind.CLIENT)
            .toList();
    assertEquals(1, clientSpans.size());
    assertTrue(clientSpans.get(0).getStatus().getStatusCode().name().equals("ERROR"));
  }
}
