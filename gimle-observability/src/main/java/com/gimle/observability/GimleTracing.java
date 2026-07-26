package com.gimle.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * Installs the process-wide {@link OpenTelemetry} instance {@code gimle-fabric}'s {@code
 * FabricServer}/{@code FabricServiceRegistry} read via {@link GlobalOpenTelemetry} (Phase 4 §11 --
 * this is where OpenTelemetry is actually introduced, matching the spec's own phase placement).
 *
 * <p>Which exporter backend a real deployment wants (OTLP to a collector, something else, or none)
 * is a deployment concern independent of the propagation mechanism this phase wires end to end
 * (design §14, open question 1); defaulting to {@link LoggingSpanExporter} here mirrors {@link
 * WorkerMetrics}'s own "{@code SimpleMeterRegistry}, no exporter wired up yet" precedent -- spans
 * are real and correctly parented, just not shipped anywhere yet.
 */
public final class GimleTracing {

  private static volatile boolean installed;

  private GimleTracing() {}

  /**
   * Idempotent: a worker process that's already installed a tracer provider (or a test that
   * pre-configured one) is left alone rather than double-registering.
   */
  public static synchronized void installDefault() {
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
