package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Against a real SDK-produced {@link SpanData}, same style {@code MuninnSpanExporterTest} uses --
 * no hand-built {@code SpanData} fake exists as a test dependency here.
 */
class SpanLineCodecTest {

  @Test
  void an_empty_batch_produces_an_empty_string() {
    assertEquals("", SpanLineCodec.toNdjson(List.of()));
  }

  @Test
  void one_line_per_span_with_attributes_flattened_onto_it() {
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
    Span span = tracer.spanBuilder("do-something").setAttribute("http.method", "GET").startSpan();
    span.end();
    tracerProvider.close();

    String body = SpanLineCodec.toNdjson(captured);

    long lineCount = body.lines().filter(l -> !l.isBlank()).count();
    assertEquals(1, lineCount);
    Map<String, Object> line = Json.asObject(Json.parse(body.trim()));
    assertEquals("do-something", line.get("name"));
    assertEquals("GET", line.get("http.method"));
    assertTrue(line.containsKey("traceId"));
    assertTrue(line.containsKey("spanId"));
    assertTrue(line.containsKey("timestamp"));
  }
}
