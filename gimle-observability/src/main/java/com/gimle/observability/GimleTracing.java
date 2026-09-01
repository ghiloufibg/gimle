package com.gimle.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs the process-wide {@link OpenTelemetry} instance that {@code gimle-fabric}'s {@code
 * FabricServer}/{@code FabricServiceRegistry} reads via {@link GlobalOpenTelemetry}.
 *
 * <p>Which exporter backend a real deployment wants (OTLP to a collector, something else, or none)
 * is a deployment concern independent of the trace-propagation mechanism wired here; defaulting to
 * {@link LoggingSpanExporter} mirrors {@link WorkerMetrics}'s own "{@code SimpleMeterRegistry}, no
 * exporter wired up yet" approach -- spans are real and correctly parented, just not shipped
 * anywhere yet.
 *
 * <p>How much of that traffic is recorded is a deployment concern too: every provider built here
 * gets the same parent-based sampler, whose root sampling ratio comes from {@code
 * -Dgimle.tracing.samplingRatio} and records everything unless a process says otherwise. See {@link
 * #configuredSampler()}.
 */
public final class GimleTracing {

  private static final Logger log = LoggerFactory.getLogger(GimleTracing.class);

  // A private lock object, not `synchronized` on the class itself: locking on GimleTracing.class
  // would let any other code -- including untrusted code sharing this JVM -- synchronize on the
  // same intrinsic lock and stall this class's own initialization.
  private static final Object LOCK = new Object();
  private static volatile boolean installed;

  /**
   * The tracer provider {@link #install}/{@link #installDefault} last built, held only so {@link
   * #flush()} has something to call {@code forceFlush()} on -- {@link GlobalOpenTelemetry#get()}
   * exposes the {@link OpenTelemetry} API surface, not the SDK's own flush control.
   */
  private static volatile SdkTracerProvider currentTracerProvider;

  /**
   * Fraction of root spans to record, {@code 0.0}..{@code 1.0}, read from {@code
   * -Dgimle.tracing.samplingRatio}. Defaults to recording everything, which is the right default
   * for a cluster whose only span producer today is one span per fabric call: complete traces
   * matter more than volume until throughput actually makes them expensive. Turning it down is a
   * process-level knob rather than a code change precisely because that tradeoff belongs to whoever
   * runs the workload.
   */
  private static final String SAMPLING_RATIO_PROPERTY = "gimle.tracing.samplingRatio";

  private static final double DEFAULT_SAMPLING_RATIO = 1.0;

  private GimleTracing() {}

  /**
   * The sampler every tracer provider this class builds is configured with, so no install path can
   * accidentally sample differently from another.
   *
   * <p>Always parent-based: the sampling decision is made once, at the root of a trace, and every
   * downstream hop honours whatever the incoming context already decided. Sampling each hop
   * independently at ratio {@code r} would instead keep only {@code r^hops} of any multi-process
   * trace intact and leave the rest as orphaned fragments -- exactly the traces a cross-worker
   * fabric call needs whole to be worth anything.
   */
  static Sampler configuredSampler() {
    return Sampler.parentBased(rootSampler(configuredSamplingRatio()));
  }

  private static Sampler rootSampler(double ratio) {
    if (ratio >= 1.0) {
      return Sampler.alwaysOn();
    }
    if (ratio <= 0.0) {
      return Sampler.alwaysOff();
    }
    return Sampler.traceIdRatioBased(ratio);
  }

  /**
   * A malformed or out-of-range ratio falls back to the default rather than failing process
   * startup: a mistyped observability knob must never be the reason a worker or control plane
   * refuses to boot.
   */
  private static double configuredSamplingRatio() {
    String configured = System.getProperty(SAMPLING_RATIO_PROPERTY);
    if (configured == null || configured.isBlank()) {
      return DEFAULT_SAMPLING_RATIO;
    }
    double ratio;
    try {
      ratio = Double.parseDouble(configured.trim());
    } catch (NumberFormatException e) {
      log.warn(
          "ignoring unparseable {}={} -- sampling every trace instead",
          SAMPLING_RATIO_PROPERTY,
          configured);
      return DEFAULT_SAMPLING_RATIO;
    }
    if (Double.isNaN(ratio) || ratio < 0.0 || ratio > 1.0) {
      log.warn(
          "ignoring out-of-range {}={} (expected 0.0..1.0) -- sampling every trace instead",
          SAMPLING_RATIO_PROPERTY,
          configured);
      return DEFAULT_SAMPLING_RATIO;
    }
    return ratio;
  }

  /**
   * Idempotent: a worker process that's already installed a tracer provider (or a test that
   * pre-configured one) is left alone rather than double-registering. Unaffected by the {@link
   * #install}/{@link #installWithMuninnShipping} additions below -- {@code WorkerMain}'s existing
   * call, and any process without a configured Muninn endpoint, keeps this exact {@link
   * LoggingSpanExporter}-over-{@link SimpleSpanProcessor} behavior.
   */
  public static void installDefault() {
    synchronized (LOCK) {
      if (installed) {
        return;
      }
      SdkTracerProvider tracerProvider =
          SdkTracerProvider.builder()
              .setSampler(configuredSampler())
              .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
              .build();
      OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
      GlobalOpenTelemetry.set(sdk);
      currentTracerProvider = tracerProvider;
      installed = true;
    }
  }

  /**
   * Generalizes {@link #installDefault()} to an arbitrary {@link SpanExporter} -- {@link
   * BatchSpanProcessor}, not {@link SimpleSpanProcessor}: a real network-bound exporter (like
   * {@link MuninnSpanExporter}) shouldn't block the exporting thread on every single span the way
   * {@code SimpleSpanProcessor} does; batching is exactly what a periodic-ship-to- Muninn posture
   * wants. Same idempotency contract as {@link #installDefault()}.
   */
  public static void install(SpanExporter exporter) {
    synchronized (LOCK) {
      if (installed) {
        return;
      }
      SdkTracerProvider tracerProvider =
          SdkTracerProvider.builder()
              .setSampler(configuredSampler())
              .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
              .build();
      OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
      GlobalOpenTelemetry.set(sdk);
      currentTracerProvider = tracerProvider;
      installed = true;
    }
  }

  /**
   * Convenience for the common case: a process with a configured Muninn endpoint installs a {@link
   * MuninnSpanExporter} wrapping its own already-constructed {@code shipper} (already bound to that
   * process's own {@code /ingest/traces/{processKind}/{processId}} path the same way its metrics
   * shipper is -- see {@code ControlPlaneMain}'s own wiring).
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
      currentTracerProvider = null;
      GlobalOpenTelemetry.resetForTest();
    }
  }

  /**
   * Best-effort, bounded-wait flush of the currently-installed tracer provider's {@link
   * BatchSpanProcessor}: a short-lived instance -- a completed {@code JobRun}, an instance torn
   * down right after {@code StopModule} -- shouldn't lose its final spans to the batch processor's
   * own periodic export interval, which may never fire again before the worker process exits. A
   * no-op before any tracer provider has been installed.
   */
  public static void flush() {
    SdkTracerProvider provider = currentTracerProvider;
    if (provider != null) {
      provider.forceFlush().join(2, TimeUnit.SECONDS);
    }
  }
}
