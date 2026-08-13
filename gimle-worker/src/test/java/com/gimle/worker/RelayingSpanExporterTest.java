package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.ControlMessage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * A worker has no outbound network identity of its own -- {@link RelayingSpanExporter} hands every
 * batch to a {@code Consumer<ControlMessage>} sink instead of shipping to Muninn directly,
 * exercised here against a real SDK-produced span the same way {@code SpanLineCodecTest}/{@code
 * MuninnSpanExporterTest} do.
 */
class RelayingSpanExporterTest {

  @Test
  void a_real_span_batch_relays_as_a_traces_snapshot_with_the_given_worker_id() {
    List<ControlMessage> sent = new CopyOnWriteArrayList<>();
    RelayingSpanExporter exporter = new RelayingSpanExporter("worker-42", sent::add);

    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    Tracer tracer = tracerProvider.get("test");
    Span span = tracer.spanBuilder("do-something").setAttribute("http.method", "GET").startSpan();
    span.end();
    tracerProvider.close();

    assertEquals(1, sent.size());
    ControlMessage.TracesSnapshot snapshot = (ControlMessage.TracesSnapshot) sent.get(0);
    assertEquals("worker-42", snapshot.workerId());
    assertTrue(snapshot.ndjsonPayload().contains("do-something"));
    assertTrue(snapshot.ndjsonPayload().contains("GET"));
  }

  @Test
  void export_never_throws_even_when_the_sink_throws() {
    RelayingSpanExporter exporter =
        new RelayingSpanExporter(
            "worker-42",
            message -> {
              throw new RuntimeException("sink unavailable");
            });

    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    Tracer tracer = tracerProvider.get("test");

    // SimpleSpanProcessor invokes export() synchronously on span.end() -- a sink throwing must
    // not propagate out through the OTel SDK's own call, or an unrelated onSpanEnd caller
    // (BoundedModuleScheduler's request handling) would be broken by a purely observability-side
    // failure. Reaching close() below without an exception is the assertion.
    tracer.spanBuilder("boom").startSpan().end();
    tracerProvider.close();
  }

  @Test
  void flush_and_shutdown_always_report_success() {
    RelayingSpanExporter exporter = new RelayingSpanExporter("worker-42", message -> {});
    assertTrue(exporter.flush().isSuccess());
    assertTrue(exporter.shutdown().isSuccess());
  }
}
