package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * {@link GimleTracing#install}'s generalization of {@link GimleTracing#installDefault()} (design
 * doc Part B/O-13) -- both share the identical once-only idempotency contract, so this class holds
 * {@code GlobalOpenTelemetry}'s own static state exclusively (via {@link Resources#GLOBAL}) and
 * resets {@link GimleTracing#resetForTesting()} on both sides of every test: any other test in this
 * JVM fork that already installed a tracer provider would otherwise make {@link
 * GimleTracing#install} silently no-op here.
 */
@ResourceLock(Resources.GLOBAL)
class GimleTracingInstallTest {

  @BeforeEach
  void resetTracingBefore() {
    GimleTracing.resetForTesting();
  }

  @AfterEach
  void resetTracingAfter() {
    GimleTracing.resetForTesting();
  }

  @Test
  @Timeout(10)
  void install_swaps_in_the_given_exporter_and_a_real_span_reaches_it() throws Exception {
    List<SpanData> captured = new CopyOnWriteArrayList<>();
    SpanExporter capturingExporter =
        new SpanExporter() {
          @Override
          public CompletableResultCode export(Collection<SpanData> spans) {
            captured.addAll(spans);
            return CompletableResultCode.ofSuccess();
          }

          @Override
          public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
          }

          @Override
          public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
          }
        };

    GimleTracing.install(capturingExporter);
    Tracer tracer = GlobalOpenTelemetry.get().getTracer("test");
    tracer.spanBuilder("swapped-in").startSpan().end();

    awaitUntil(() -> !captured.isEmpty(), java.time.Duration.ofSeconds(5));
    assertTrue(captured.stream().anyMatch(span -> "swapped-in".equals(span.getName())));
  }

  @Test
  void flush_with_nothing_installed_is_a_no_op() {
    // resetTracingBefore() already cleared installedProvider -- must not throw.
    GimleTracing.flush();
  }

  @Test
  @Timeout(10)
  void flush_forces_a_pending_batched_span_out_immediately() {
    // A deliberately long batch delay: without a real forceFlush() call, this span would sit
    // unexported for far longer than this test's own timeout -- proving flush() actually forces
    // the SDK's own BatchSpanProcessor, not just that a span eventually arrives on its own
    // schedule.
    List<SpanData> captured = new CopyOnWriteArrayList<>();
    SpanExporter capturingExporter =
        new SpanExporter() {
          @Override
          public CompletableResultCode export(Collection<SpanData> spans) {
            captured.addAll(spans);
            return CompletableResultCode.ofSuccess();
          }

          @Override
          public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
          }

          @Override
          public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
          }
        };

    GimleTracing.install(capturingExporter);
    Tracer tracer = GlobalOpenTelemetry.get().getTracer("test");
    tracer.spanBuilder("about-to-be-flushed").startSpan().end();

    assertTrue(captured.isEmpty(), "the span shouldn't have exported on its own yet");
    GimleTracing.flush();
    assertTrue(captured.stream().anyMatch(span -> "about-to-be-flushed".equals(span.getName())));
  }

  private static void awaitUntil(
      java.util.function.BooleanSupplier condition, java.time.Duration timeout)
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
