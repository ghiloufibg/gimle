package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * {@link MuninnSpanExporterTest} already exercises {@link SpanLineCodec#toLine} indirectly, end to
 * end, through {@code MuninnSpanExporter}'s unchanged List-of-lines path (design doc §6c: zero
 * behavior change there). This covers the other consumer directly: {@link SpanLineCodec#toNdjson},
 * the joined-{@code String} shape {@code RelayingSpanExporter} (in {@code gimle-worker}) builds and
 * hands to its agent as a {@code ControlMessage.TracesSnapshot} payload, which nothing else
 * exercises.
 */
class SpanLineCodecTest {

  @Test
  void an_empty_span_batch_produces_an_empty_string() {
    assertEquals("", SpanLineCodec.toNdjson(List.of()));
  }

  @Test
  void one_ndjson_line_per_span_newline_terminated() {
    Collection<SpanData> spans = capturedSpans("first", "second");

    String ndjson = SpanLineCodec.toNdjson(spans);

    long lineCount = ndjson.lines().filter(l -> !l.isBlank()).count();
    assertEquals(2, lineCount);
    assertTrue(ndjson.endsWith("\n"));
    assertTrue(ndjson.contains("first"));
    assertTrue(ndjson.contains("second"));
  }

  private static Collection<SpanData> capturedSpans(String... names) {
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
    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(capturingExporter))
            .build();
    Tracer tracer = tracerProvider.get("test");
    for (String name : names) {
      tracer.spanBuilder(name).startSpan().end();
    }
    tracerProvider.close();
    return captured;
  }
}
