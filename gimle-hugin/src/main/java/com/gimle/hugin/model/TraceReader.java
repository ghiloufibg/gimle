package com.gimle.hugin.model;

import com.gimle.cli.spi.ClusterReader;
import com.gimle.core.protocol.Json;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A worker JVM's own shipped spans, read through the control plane's {@code GET
 * /traces-history/WORKER/{nodeId}:{workerId}} proxy onto Muninn -- the same addressing its meter
 * history already uses, because a worker has no listening address of its own and is filed under its
 * node's id joined to its worker id.
 *
 * <p>Shipping to Muninn is optional, so a failure here is "no history", never an error: the pane
 * says nothing ships rather than claiming this worker served nothing. That is the one distinction
 * worth keeping, and the reason every failure resolves to {@link TraceSnapshot#notShipped} rather
 * than being thrown.
 */
public final class TraceReader {

  /** Enough spans to cover the recent past without paging, which this view does not offer. */
  private static final int PAGE_SIZE = 200;

  private final ClusterReader reader;
  private final String nodeId;
  private final String workerId;

  public TraceReader(final ClusterReader reader, final String nodeId, final String workerId) {
    this.reader = reader;
    this.nodeId = nodeId;
    this.workerId = workerId;
  }

  public TraceSnapshot read() {
    Map<String, Object> body;
    try {
      body = reader.getObject(path(nodeId, workerId) + "?limit=" + PAGE_SIZE);
    } catch (RuntimeException e) {
      return TraceSnapshot.notShipped(reader.serverAddress());
    }
    List<SpanRow> spans = new ArrayList<>();
    for (Object raw : body.get("lines") instanceof List<?> list ? list : List.of()) {
      if (raw instanceof Map<?, ?>) {
        span(Json.asObject(raw)).ifPresent(spans::add);
      }
    }
    return new TraceSnapshot(
        reader.serverAddress(), Optional.of(Instant.now()), spans, true, Optional.empty());
  }

  /**
   * A line with no trace or span id is not a span, whatever else it carries, so it is dropped
   * rather than drawn as one with blank identity.
   */
  private static Optional<SpanRow> span(final Map<String, Object> line) {
    String traceId = string(line.get("traceId"));
    String spanId = string(line.get("spanId"));
    if (traceId.isBlank() || spanId.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(
        new SpanRow(
            at(line.get("timestamp")),
            traceId,
            spanId,
            Optional.of(string(line.get("parentSpanId"))).filter(id -> !id.isBlank()),
            stringOrDefault(line.get("name"), "—"),
            stringOrDefault(line.get("kind"), "INTERNAL"),
            stringOrDefault(line.get("status"), "UNSET")));
  }

  static String path(final String nodeId, final String workerId) {
    return "/traces-history/WORKER/"
        + URLEncoder.encode(nodeId + ":" + workerId, StandardCharsets.UTF_8);
  }

  /** An unparseable timestamp sorts oldest rather than taking the whole read down with it. */
  private static Instant at(final Object value) {
    try {
      return Instant.parse(string(value));
    } catch (DateTimeParseException e) {
      return Instant.EPOCH;
    }
  }

  private static String string(final Object value) {
    return value instanceof String s ? s : "";
  }

  private static String stringOrDefault(final Object value, final String fallback) {
    String text = string(value);
    return text.isBlank() ? fallback : text;
  }
}
