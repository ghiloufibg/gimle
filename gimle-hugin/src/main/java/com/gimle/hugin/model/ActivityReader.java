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
 * Reads whichever record of cluster activity the view is currently showing.
 *
 * <p>Three feeds, one reader, because they differ only in the route they call and the shape they
 * fold into a {@link FeedRow}. The audit trail and the lifecycle timeline both page the same way,
 * off a {@code nextCursor}; the alert feed does not page at all, because it is a list of declared
 * rules rather than a history, and it costs one extra request per rule since the rule list carries
 * no firing state of its own.
 */
public final class ActivityReader {

  /** Enough to fill any terminal, so the first page is all an operator usually needs. */
  private static final int PAGE_SIZE = 200;

  private final ClusterReader reader;
  private final FeedMode mode;

  /**
   * How many pages deep the operator has asked to go. Held on the reader rather than in the
   * snapshot so a refresh re-reads everything already on screen instead of silently shrinking it
   * back to one page under someone who had scrolled. Atomic because the render loop increments it
   * while the poll thread is reading it.
   */
  private final AtomicInteger pages = new AtomicInteger(1);

  public ActivityReader(final ClusterReader reader, final FeedMode mode) {
    this.reader = reader;
    this.mode = mode;
  }

  /** Asks for one more page on the next read. A no-op once the feed has no more to give. */
  public void loadMore() {
    pages.incrementAndGet();
  }

  public ActivitySnapshot read() {
    return mode == FeedMode.ALERTS ? readAlerts() : readPagedHistory();
  }

  // ---- audit and lifecycle: a paged history, newest first ----

  private ActivitySnapshot readPagedHistory() {
    List<FeedRow> rows = new ArrayList<>();
    Optional<String> cursor = Optional.empty();
    int wanted = pages.get();
    for (int page = 0; page < wanted; page++) {
      PageResult result = readPage(cursor);
      if (result == null) {
        return ActivitySnapshot.forbidden(reader.serverAddress(), mode);
      }
      rows.addAll(result.rows());
      cursor = result.nextCursor();
      if (cursor.isEmpty()) {
        break;
      }
    }
    // Newest first: an operator opening this wants what just happened, not what happened first.
    rows.sort(Comparator.comparing(FeedRow::at).reversed());
    return settled(rows, cursor);
  }

  private record PageResult(List<FeedRow> rows, Optional<String> nextCursor) {}

  /** Returns {@code null} for the one failure that is a state to report rather than retry. */
  private PageResult readPage(final Optional<String> cursor) {
    Map<String, Object> body;
    try {
      body =
          reader.getObject(
              mode.route() + "?limit=" + PAGE_SIZE + cursor.map(c -> "&cursor=" + c).orElse(""));
    } catch (CliException e) {
      if (e.exitCode() == CliExitCode.FORBIDDEN) {
        return null;
      }
      throw e;
    }
    List<FeedRow> rows = new ArrayList<>();
    for (Object raw : body.get("events") instanceof List<?> list ? list : List.of()) {
      if (raw instanceof Map<?, ?>) {
        Map<String, Object> event = Json.asObject(raw);
        (mode == FeedMode.AUDIT ? auditRow(event) : lifecycleRow(event)).ifPresent(rows::add);
      }
    }
    return new PageResult(rows, optionalString(body.get("nextCursor")));
  }

  private static Optional<FeedRow> auditRow(final Map<String, Object> event) {
    String principal = string(event.get("principal"));
    if (principal.isBlank()) {
      return Optional.empty();
    }
    boolean allowed = !Boolean.FALSE.equals(event.get("allowed"));
    String outcome = stringOrDefault(event.get("outcome"), "APPLIED");
    // Refused for want of permission and refused on its merits are different things to see.
    String verdict = allowed ? ("REJECTED".equals(outcome) ? "REJECTED" : "APPLIED") : "DENIED";
    String kind = stringOrDefault(event.get("resourceKind"), "UNKNOWN");
    return Optional.of(
        new FeedRow(
            at(event),
            principal,
            stringOrDefault(event.get("verb"), "UNKNOWN"),
            verdict,
            kind + optionalString(event.get("targetId")).map(id -> " " + id).orElse("")));
  }

  private static Optional<FeedRow> lifecycleRow(final Map<String, Object> event) {
    String deployment = string(event.get("deploymentName"));
    if (deployment.isBlank()) {
      return Optional.empty();
    }
    int index = event.get("instanceIndex") instanceof Number n ? n.intValue() : 0;
    String message = stringOrDefault(event.get("message"), "");
    String cause = optionalString(event.get("causeSummary")).map(c -> " (" + c + ")").orElse("");
    return Optional.of(
        new FeedRow(
            at(event),
            deployment + "/" + index,
            "transition",
            stringOrDefault(event.get("kind"), "UNKNOWN"),
            message + cause));
  }

  // ---- alerts: declared rules, each asked separately whether it is firing ----

  private ActivitySnapshot readAlerts() {
    List<Map<String, Object>> rules;
    try {
      rules = reader.getList(mode.route());
    } catch (CliException e) {
      if (e.exitCode() == CliExitCode.FORBIDDEN) {
        return ActivitySnapshot.forbidden(reader.serverAddress(), mode);
      }
      throw e;
    }
    List<FeedRow> rows = new ArrayList<>();
    for (Map<String, Object> rule : rules) {
      String name = string(rule.get("name"));
      if (name.isBlank()) {
        continue;
      }
      rows.add(
          new FeedRow(
              Instant.now(),
              name,
              stringOrDefault(rule.get("metric"), "UNKNOWN"),
              verdictOf(rule, name),
              stringOrDefault(rule.get("deploymentName"), "—")
                  + "  "
                  + stringOrDefault(rule.get("comparator"), "")
                  + " "
                  + rule.getOrDefault("threshold", "")));
    }
    // Firing first: the whole reason to open this feed is to find what is currently wrong.
    rows.sort(
        Comparator.comparing((FeedRow row) -> !"FIRING".equals(row.verdict()))
            .thenComparing(FeedRow::actor));
    return settled(rows, Optional.empty());
  }

  /**
   * A disabled rule never fires and says so; an enabled one is asked. Its firing state lives behind
   * its own sub-route because the rule list carries none, and a rule the control plane has no
   * reading for yet is reported as unknown rather than as quiet.
   */
  private String verdictOf(final Map<String, Object> rule, final String name) {
    if (Boolean.FALSE.equals(rule.get("enabled"))) {
      return "DISABLED";
    }
    Map<String, Object> state;
    try {
      state = reader.getObject(mode.route() + "/" + name + "/firing");
    } catch (CliException e) {
      return "UNKNOWN";
    }
    if (!Boolean.TRUE.equals(state.get("known"))) {
      return "UNKNOWN";
    }
    return Boolean.TRUE.equals(state.get("firing")) ? "FIRING" : "OK";
  }

  private ActivitySnapshot settled(final List<FeedRow> rows, final Optional<String> cursor) {
    return new ActivitySnapshot(
        reader.serverAddress(),
        Optional.of(Instant.now()),
        rows,
        mode,
        true,
        cursor,
        Optional.empty());
  }

  private static Instant at(final Map<String, Object> event) {
    return Instant.ofEpochMilli(
        event.get("occurredAtEpochMilli") instanceof Number n ? n.longValue() : 0L);
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
