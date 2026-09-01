package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * The sampling half of {@link GimleTracing}: what {@code -Dgimle.tracing.samplingRatio} configures,
 * how a bad value degrades, and that the decision stays parent-based so one trace can't be sampled
 * differently on each hop. Holds {@code GlobalOpenTelemetry}'s static state exclusively for the
 * same reason {@code GimleTracingInstallTest} does.
 */
@ResourceLock(Resources.GLOBAL)
class GimleTracingSamplingTest {

  private static final String RATIO_PROPERTY = "gimle.tracing.samplingRatio";

  @BeforeEach
  void resetTracingBefore() {
    System.clearProperty(RATIO_PROPERTY);
    GimleTracing.resetForTesting();
  }

  @AfterEach
  void resetTracingAfter() {
    System.clearProperty(RATIO_PROPERTY);
    GimleTracing.resetForTesting();
  }

  @Test
  void an_unconfigured_process_records_every_trace() {
    assertEquals(
        "ParentBased{root:AlwaysOnSampler,remoteParentSampled:AlwaysOnSampler,"
            + "remoteParentNotSampled:AlwaysOffSampler,localParentSampled:AlwaysOnSampler,"
            + "localParentNotSampled:AlwaysOffSampler}",
        GimleTracing.configuredSampler().getDescription());
  }

  @Test
  void a_configured_ratio_becomes_the_parent_based_sampler_s_own_root_decision() {
    System.setProperty(RATIO_PROPERTY, "0.1");

    String description = GimleTracing.configuredSampler().getDescription();

    assertTrue(description.startsWith("ParentBased{root:TraceIdRatioBased{0.100000}"), description);
  }

  @Test
  void an_unparseable_ratio_falls_back_to_recording_everything_rather_than_failing_startup() {
    System.setProperty(RATIO_PROPERTY, "not-a-number");

    assertTrue(GimleTracing.configuredSampler().getDescription().contains("root:AlwaysOnSampler"));
  }

  @Test
  void an_out_of_range_ratio_falls_back_to_recording_everything() {
    System.setProperty(RATIO_PROPERTY, "7.5");

    assertTrue(GimleTracing.configuredSampler().getDescription().contains("root:AlwaysOnSampler"));
  }

  @Test
  void a_zero_ratio_drops_a_root_span_instead_of_exporting_it() {
    System.setProperty(RATIO_PROPERTY, "0");
    CapturingSpanExporter exporter = new CapturingSpanExporter();
    GimleTracing.install(exporter);

    Tracer tracer = GlobalOpenTelemetry.get().getTracer("test");
    tracer.spanBuilder("dropped-root").startSpan().end();
    GimleTracing.flush();

    assertTrue(exporter.captured().isEmpty(), "a root span at ratio 0 must not be recorded");
  }

  @Test
  void a_span_whose_incoming_parent_was_sampled_is_kept_even_at_a_zero_root_ratio() {
    System.setProperty(RATIO_PROPERTY, "0");
    CapturingSpanExporter exporter = new CapturingSpanExporter();
    GimleTracing.install(exporter);

    // Exactly the shape a cross-worker fabric call arrives in: the caller already decided to
    // sample, and the callee's own span has to join that trace rather than re-deciding.
    Context sampledParent =
        Context.root()
            .with(
                Span.wrap(
                    SpanContext.createFromRemoteParent(
                        "0af7651916cd43dd8448eb211c80319c",
                        "b7ad6b7169203331",
                        TraceFlags.getSampled(),
                        TraceState.getDefault())));

    Tracer tracer = GlobalOpenTelemetry.get().getTracer("test");
    tracer.spanBuilder("joined-child").setParent(sampledParent).startSpan().end();
    GimleTracing.flush();

    assertFalse(exporter.captured().isEmpty(), "a sampled parent must carry the child with it");
    assertTrue(
        exporter.captured().stream().anyMatch(span -> "joined-child".equals(span.getName())));
  }
}
