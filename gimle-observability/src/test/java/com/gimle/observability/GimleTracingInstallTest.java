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
  @Timeout(20)
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

    // BatchSpanProcessor's own default export interval is 5s -- a 5s awaitUntil races that exact
    // deadline and flakes under system load (a full reactor build, e.g.). 15s gives real headroom
    // without masking a genuine regression, matching this test's own @Timeout(10)'s replacement
    // above it with room to spare.
    awaitUntil(() -> !captured.isEmpty(), java.time.Duration.ofSeconds(15));
    assertTrue(captured.stream().anyMatch(span -> "swapped-in".equals(span.getName())));
  }

  @Test
  @Timeout(10)
  void flush_forces_the_batch_processor_to_export_before_the_next_periodic_tick() throws Exception {
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
    tracer.spanBuilder("flush-me").startSpan().end();

    // BatchSpanProcessor's own default export interval is 5s -- without an explicit flush(), this
    // span would not reach capturingExporter within this test's own patience.
    GimleTracing.flush();

    assertTrue(captured.stream().anyMatch(span -> "flush-me".equals(span.getName())));
  }

  @Test
  void flush_before_any_install_is_a_noop() {
    // resetForTesting() (run in @BeforeEach) clears any prior installation -- flush() must not
    // throw when nothing has ever been installed.
    GimleTracing.flush();
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
