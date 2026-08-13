package com.gimle.observability;

import com.gimle.core.protocol.Json;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pure (no-I/O) span-to-NDJSON-line serialization shared by every trace shipping path: {@link
 * MuninnSpanExporter} ships straight to Muninn over HTTP for a process with its own outbound
 * network identity (ControlPlane, Fafnir, Mimir, the agent); a worker JVM has none (design doc
 * §6a/§6c), so it builds the same NDJSON shape here and relays it to its agent over the control
 * channel instead. Extracted out of {@code MuninnSpanExporter} so both callers produce
 * byte-identical lines rather than maintaining two serializations of the same span shape.
 */
public final class SpanLineCodec {

  private SpanLineCodec() {}

  /** One NDJSON line per span in {@code spans}, newline-terminated. */
  public static String toNdjson(Collection<SpanData> spans) {
    StringBuilder body = new StringBuilder();
    for (SpanData span : spans) {
      body.append(Json.write(toLine(span))).append('\n');
    }
    return body.toString();
  }

  static Map<String, Object> toLine(SpanData span) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("timestamp", Instant.ofEpochSecond(0, span.getEndEpochNanos()).toString());
    line.put("traceId", span.getTraceId());
    line.put("spanId", span.getSpanId());
    line.put("parentSpanId", span.getParentSpanId());
    line.put("name", span.getName());
    line.put("kind", span.getKind().name());
    line.put("status", span.getStatus().getStatusCode().name());
    span.getAttributes().forEach((key, value) -> line.put(key.getKey(), value));
    return line;
  }
}
