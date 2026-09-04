package com.gimle.hugin.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * One span out of a worker's shipped trace history.
 *
 * <p>What a span carries here is what the shipper wrote: when it ended, the trace and span it
 * belongs to, its name, its kind and its status. Not its duration -- the exporter records only the
 * end instant, so any elapsed time shown would be invented rather than read. A root span is one
 * whose parent id is absent or all zeroes, which is how the OpenTelemetry SDK spells "no parent".
 */
public record SpanRow(
    Instant at,
    String traceId,
    String spanId,
    Optional<String> parentSpanId,
    String name,
    String kind,
    String status) {

  /** How the SDK spells an absent parent: a span id of all zeroes rather than a missing field. */
  private static final String NO_PARENT = "0000000000000000";

  public SpanRow {
    if (at == null) {
      throw new IllegalArgumentException("at must not be null");
    }
    if (traceId == null || spanId == null || name == null || kind == null || status == null) {
      throw new IllegalArgumentException("span fields must not be null");
    }
    if (parentSpanId == null) {
      throw new IllegalArgumentException("parentSpanId must not be null; use Optional.empty()");
    }
    parentSpanId = parentSpanId.filter(id -> !id.isBlank() && !NO_PARENT.equals(id));
  }

  /** Whether this span begins its trace, which is what makes a trace worth listing under it. */
  public boolean root() {
    return parentSpanId.isEmpty();
  }

  public boolean failed() {
    return "ERROR".equals(status);
  }

  /** The first eight characters, which is as much of a trace id as a table has room for. */
  public String shortTraceId() {
    return traceId.length() <= 8 ? traceId : traceId.substring(0, 8);
  }

  /** The text a filter is matched against: the name, the kind, the status and the trace. */
  public String searchText() {
    return (name + " " + kind + " " + status + " " + traceId).toLowerCase(Locale.ROOT);
  }
}
