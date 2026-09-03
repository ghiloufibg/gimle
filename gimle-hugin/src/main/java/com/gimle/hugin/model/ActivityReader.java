package com.gimle.hugin.model;

import com.gimle.cli.spi.ClusterReader;
import com.gimle.core.protocol.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the cluster-wide audit trail out of {@code GET /audit}.
 *
 * <p>This is the only cluster-wide feed the control plane serves: {@code /events} is keyed to one
 * instance and cannot answer "what has been happening here". The trail records authorization
 * decisions rather than lifecycle transitions, which is a narrower thing than a full activity log
 * but a real one, and the view says which it is rather than implying the other.
 */
public final class ActivityReader {

  /** Enough to fill any terminal without paging a trail that is already newest-first. */
  private static final int PAGE_SIZE = 200;

  private final ClusterReader reader;

  public ActivityReader(final ClusterReader reader) {
    this.reader = reader;
  }

  public ActivitySnapshot read() {
    Map<String, Object> body = reader.getObject("/audit?limit=" + PAGE_SIZE);
    List<ActivityRow> rows = new ArrayList<>();
    for (Object raw : body.get("events") instanceof List<?> list ? list : List.of()) {
      if (raw instanceof Map<?, ?>) {
        row(Json.asObject(raw)).ifPresent(rows::add);
      }
    }
    // Newest first: an operator opening this wants what just happened, not what happened first.
    rows.sort(Comparator.comparing(ActivityRow::occurredAt).reversed());
    return new ActivitySnapshot(
        reader.serverAddress(), Optional.of(Instant.now()), rows, true, Optional.empty());
  }

  private static Optional<ActivityRow> row(final Map<String, Object> event) {
    String principal = string(event.get("principal"));
    if (principal.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(
        new ActivityRow(
            principal,
            stringOrDefault(event.get("resourceKind"), "UNKNOWN"),
            stringOrDefault(event.get("verb"), "UNKNOWN"),
            optionalString(event.get("tenantId")),
            optionalString(event.get("targetId")),
            !Boolean.FALSE.equals(event.get("allowed")),
            stringOrDefault(event.get("outcome"), "APPLIED"),
            Instant.ofEpochMilli(
                event.get("occurredAtEpochMilli") instanceof Number n ? n.longValue() : 0L)));
  }

  private static String string(final Object value) {
    return value instanceof String s ? s : "";
  }

  private static String stringOrDefault(final Object value, final String fallback) {
    String text = string(value);
    return text.isBlank() ? fallback : text;
  }

  private static Optional<String> optionalString(final Object value) {
    String text = string(value);
    return text.isBlank() ? Optional.empty() : Optional.of(text);
  }
}
