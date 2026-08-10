package com.gimle.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * Installs the process-wide {@link OpenTelemetry} instance that {@code gimle-fabric}'s {@code
 * FabricServer}/{@code FabricServiceRegistry} reads via {@link GlobalOpenTelemetry}.
 *
 * <p>Which exporter backend a real deployment wants (OTLP to a collector, something else, or none)
 * is a deployment concern independent of the trace-propagation mechanism wired here; defaulting to
 * {@link LoggingSpanExporter} mirrors {@link WorkerMetrics}'s own "{@code SimpleMeterRegistry}, no
 * exporter wired up yet" approach -- spans are real and correctly parented, just not shipped
 * anywhere yet.
 */
public final class GimleTracing {

  // A private lock object, not `synchronized` on the class itself: locking on GimleTracing.class
  // would let any other code -- including untrusted code sharing this JVM -- synchronize on the
  // same intrinsic lock and stall this class's own initialization.
  private static final Object LOCK = new Object();
  private static volatile boolean installed;

  private GimleTracing() {}

  /**
   * Idempotent: a worker process that's already installed a tracer provider (or a test that
   * pre-configured one) is left alone rather than double-registering. Unchanged by design doc Part
   * B/O-13's {@link #install}/{@link #installWithMuninnShipping} additions below -- {@code
   * WorkerMain}'s existing call, and any process without a configured Muninn endpoint, keeps this
   * exact {@link LoggingSpanExporter}-over-{@link SimpleSpanProcessor} behavior.
   */
  public static void installDefault() {
    synchronized (LOCK) {
      if (installed) {
        return;
      }
      SdkTracerProvider tracerProvider =
          SdkTracerProvider.builder()
              .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
              .build();
      OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
      GlobalOpenTelemetry.set(sdk);
      installed = true;
    }
  }

  /**
   * Generalizes {@link #installDefault()} to an arbitrary {@link SpanExporter} (design doc Part
   * B/O-13) -- {@link BatchSpanProcessor}, not {@link SimpleSpanProcessor}: a real network-bound
   * exporter (like {@link MuninnSpanExporter}) shouldn't block the exporting thread on every single
   * span the way {@code SimpleSpanProcessor} does; batching is exactly what a periodic-ship-to-
   * Muninn posture wants. Same idempotency contract as {@link #installDefault()}.
   */
  public static void install(SpanExporter exporter) {
    synchronized (LOCK) {
      if (installed) {
        return;
      }
      SdkTracerProvider tracerProvider =
          SdkTracerProvider.builder()
              .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
              .build();
      OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
      GlobalOpenTelemetry.set(sdk);
      installed = true;
    }
  }

  /**
   * Convenience for the common case (design doc Part B/O-13): a process with a configured Muninn
   * endpoint installs a {@link MuninnSpanExporter} wrapping its own already-constructed {@code
   * shipper} (already bound to that process's own {@code /ingest/traces/{processKind}/{processId}}
   * path the same way its metrics shipper is -- see {@code ControlPlaneMain}'s own wiring).
   */
  public static void installWithMuninnShipping(MuninnShipper shipper) {
    install(new MuninnSpanExporter(shipper));
  }

  /**
   * Test-only seam (package-private, no {@code private} modifier -- the same convention {@code
   * AgentMain}'s own static test seams already establish): clears the "already installed" latch so
   * a test can install a different exporter and observe it actually take effect, without leaking
   * installed state into whichever test happens to run next in the same JVM fork. Production code
   * never calls this -- a real process installs its tracer provider exactly once, for its whole
   * lifetime. {@link GlobalOpenTelemetry#resetForTest()} is the OTel API's own equivalent seam for
   * its separate "can only ever be set once" guard -- clearing only this class's own {@code
   * installed} latch without it would leave {@code GlobalOpenTelemetry.set} still throwing on the
   * next {@link #install}/{@link #installDefault()} call.
   */
  static void resetForTesting() {
    synchronized (LOCK) {
      installed = false;
      GlobalOpenTelemetry.resetForTest();
    }
  }
}
