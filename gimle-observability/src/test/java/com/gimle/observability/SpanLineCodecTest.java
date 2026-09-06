package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.Map;
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
    CapturingSpanExporter capturingExporter = new CapturingSpanExporter();
    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(capturingExporter))
            .build();
    Tracer tracer = tracerProvider.get("test");
    Span span = tracer.spanBuilder("do-something").setAttribute("http.method", "GET").startSpan();
    span.end();
    tracerProvider.close();

    String body = SpanLineCodec.toNdjson(capturingExporter.captured());

    long lineCount = body.lines().filter(l -> !l.isBlank()).count();
    assertEquals(1, lineCount);
    Map<String, Object> line = Json.asObject(Json.parse(body.trim()));
    assertEquals("do-something", line.get("name"));
    assertEquals("GET", line.get("http.method"));
    assertTrue(line.containsKey("traceId"));
    assertTrue(line.containsKey("spanId"));
    assertTrue(line.containsKey("timestamp"));
  }

  /**
   * The all-zero span id OpenTelemetry reports for a parentless span is "no parent," not a parent
   * id -- a reader that takes it literally has to conclude the trace is missing a hop it never had.
   */
  @Test
  void a_root_span_reports_no_parent_rather_than_the_all_zero_span_id() {
    CapturingSpanExporter capturingExporter = new CapturingSpanExporter();
    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(capturingExporter))
            .build();
    Tracer tracer = tracerProvider.get("test");
    tracer.spanBuilder("root").startSpan().end();
    tracerProvider.close();

    Map<String, Object> line =
        Json.asObject(Json.parse(SpanLineCodec.toNdjson(capturingExporter.captured()).trim()));

    assertEquals("", line.get("parentSpanId"));
  }

  @Test
  void a_child_span_still_reports_its_own_parents_span_id() {
    CapturingSpanExporter capturingExporter = new CapturingSpanExporter();
    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(capturingExporter))
            .build();
    Tracer tracer = tracerProvider.get("test");
    Span parent = tracer.spanBuilder("parent").startSpan();
    try (Scope scope = parent.makeCurrent()) {
      tracer.spanBuilder("child").startSpan().end();
    }
    parent.end();
    tracerProvider.close();

    List<Map<String, Object>> lines =
        SpanLineCodec.toNdjson(capturingExporter.captured())
            .lines()
            .filter(l -> !l.isBlank())
            .map(l -> Json.asObject(Json.parse(l)))
            .toList();
    Map<String, Object> parentLine = lineNamed(lines, "parent");
    Map<String, Object> childLine = lineNamed(lines, "child");

    assertEquals(parentLine.get("spanId"), childLine.get("parentSpanId"));
  }

  private static Map<String, Object> lineNamed(List<Map<String, Object>> lines, String name) {
    return lines.stream()
        .filter(line -> name.equals(line.get("name")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no span line named " + name));
  }
}
