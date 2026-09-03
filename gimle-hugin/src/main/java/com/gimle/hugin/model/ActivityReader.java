package com.gimle.hugin.model;

import com.gimle.cli.CliException;
import com.gimle.cli.CliExitCode;
import com.gimle.cli.spi.ClusterReader;
import com.gimle.core.protocol.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads the cluster-wide audit trail out of {@code GET /audit}.
 *
 * <p>This is the only cluster-wide feed the control plane serves: {@code /events} is keyed to one
 * instance and cannot answer "what has been happening here". The trail records authorization
 * decisions rather than lifecycle transitions, which is a narrower thing than a full activity log
 * but a real one, and the view says which it is rather than implying the other.
 */
public final class ActivityReader {

  /** Enough to fill any terminal, so the first page is all an operator usually needs. */
  private static final int PAGE_SIZE = 200;

  private final ClusterReader reader;

  /**
   * How many pages deep the operator has asked to go. Held on the reader rather than in the
   * snapshot so a refresh re-reads everything already on screen instead of silently shrinking it
   * back to one page under someone who had scrolled. Atomic because the render loop increments it
   * while the poll thread is reading it.
   */
  private final AtomicInteger pages = new AtomicInteger(1);

  public ActivityReader(final ClusterReader reader) {
    this.reader = reader;
  }

  /** Asks for one more page on the next read. A no-op once the trail has no more to give. */
  public void loadMore() {
    pages.incrementAndGet();
  }

  public ActivitySnapshot read() {
    List<ActivityRow> rows = new ArrayList<>();
    Optional<String> cursor = Optional.empty();
    int wanted = pages.get();
    for (int page = 0; page < wanted; page++) {
      PageResult result = readPage(cursor);
      if (result == null) {
        return ActivitySnapshot.forbidden(reader.serverAddress());
      }
      rows.addAll(result.rows());
      cursor = result.nextCursor();
      if (cursor.isEmpty()) {
        break;
      }
    }
    // Newest first: an operator opening this wants what just happened, not what happened first.
    rows.sort(Comparator.comparing(ActivityRow::occurredAt).reversed());
    return new ActivitySnapshot(
        reader.serverAddress(), Optional.of(Instant.now()), rows, true, cursor, Optional.empty());
  }

  private record PageResult(List<ActivityRow> rows, Optional<String> nextCursor) {}

  /** Returns {@code null} for the one failure that is a state to report rather than retry. */
  private PageResult readPage(final Optional<String> cursor) {
    Map<String, Object> body;
    try {
      body =
          reader.getObject(
              "/audit?limit=" + PAGE_SIZE + cursor.map(c -> "&cursor=" + c).orElse(""));
    } catch (CliException e) {
      // The audit trail is the one read in this view gated on a permission of its own, and a
      // caller without it is a normal situation to report rather than a failure to retry: an
      // empty feed would read as a quiet cluster, which is the opposite of the truth.
      if (e.exitCode() == CliExitCode.FORBIDDEN) {
        return null;
      }
      throw e;
    }
    List<ActivityRow> rows = new ArrayList<>();
    for (Object raw : body.get("events") instanceof List<?> list ? list : List.of()) {
      if (raw instanceof Map<?, ?>) {
        row(Json.asObject(raw)).ifPresent(rows::add);
      }
    }
    return new PageResult(rows, optionalString(body.get("nextCursor")));
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
