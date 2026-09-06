package com.gimle.observability;

import com.gimle.core.protocol.Json;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure (no-I/O) NDJSON serialization for an exported {@link SpanData} batch -- the same no-envelope
 * JSON-line shape {@link MuninnSpanExporter} has always shipped ({@code traceId}/{@code spanId}/
 * {@code parentSpanId}/{@code name}/{@code kind}/{@code timestamp}/{@code status}, plus every span
 * attribute flattened directly onto the line), extracted here so {@code gimle-worker}'s own {@code
 * RelayingSpanExporter} -- which relays through the agent over the control channel rather than
 * shipping to Muninn directly, since workers have no outbound network identity of their own --
 * produces byte-identical output to {@link MuninnSpanExporter}, without either exporter duplicating
 * the span-to-JSON mapping. Lives in {@code gimle-observability}, not {@code gimle-worker}: unlike
 * {@code RelayingSpanExporter} itself, this class needs no control- channel dependency, only the
 * OpenTelemetry SDK types {@code gimle-observability} already depends on.
 */
public final class SpanLineCodec {

  private SpanLineCodec() {}

  /** Empty string for an empty batch -- callers treat that as "nothing to ship". */
  public static String toNdjson(Collection<SpanData> spans) {
    return Json.writeNdjson(spans, SpanLineCodec::toLine);
  }

  static Map<String, Object> toLine(SpanData span) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("timestamp", Instant.ofEpochSecond(0, span.getEndEpochNanos()).toString());
    line.put("traceId", span.getTraceId());
    line.put("spanId", span.getSpanId());
    // A root span reports the all-zero invalid span id here, not the absence of a parent. Writing
    // that through verbatim makes every root span look like it names a parent nothing can resolve,
    // so a reader assembling a trace reports it as provably incomplete when it is in fact whole.
    // The empty string is the only unambiguous "no parent" this flat line shape can carry.
    line.put("parentSpanId", span.getParentSpanContext().isValid() ? span.getParentSpanId() : "");
    line.put("name", span.getName());
    line.put("kind", span.getKind().name());
    line.put("status", span.getStatus().getStatusCode().name());
    span.getAttributes().forEach((key, value) -> line.put(key.getKey(), value));
    return line;
  }
}
