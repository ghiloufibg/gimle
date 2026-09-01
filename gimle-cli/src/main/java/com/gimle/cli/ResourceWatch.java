package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The client-side poll behind {@code gimle get <resource> --watch}: re-fetches the same snapshot
 * the one-shot form would print, on a fixed interval, and prints what changed since the previous
 * one. There is no watch/streaming API on the control plane outside {@code /logs}, so this is a
 * poll, not a subscription -- which is exactly why the interval is a documented, overridable knob
 * rather than a hidden constant.
 *
 * <p><b>What each tick prints.</b> Not the whole table again -- a terminal filling with identical
 * tables every couple of seconds is unreadable, and an operator watching a rollout cares about the
 * two rows that moved, not the eighty that didn't. Instead the first tick prints the full snapshot
 * and every later tick prints only the rows that changed, one line each, under the header printed
 * once. Every line carries a leading {@code EVENT} column ({@code ADDED}/{@code MODIFIED}/{@code
 * DELETED}) so the three cases stay distinguishable: a poll-derived diff has nowhere else to say
 * that a row vanished, which a bare re-print of the surviving rows could not express at all.
 *
 * <p><b>{@code -o json}.</b> A stream that never ends has no closing bracket, so {@code --watch}
 * emits NDJSON -- one JSON object per line -- the same convention {@code gimle logs --follow}
 * already uses against a one-array-per-request non-follow form. Each line is {@code
 * {"event":...,"object":{...}}}: the event kind is derived here, not carried by the resource, so it
 * needs an envelope of its own rather than being smuggled into the resource's own field set.
 *
 * <p><b>Termination.</b> Ctrl-C ends the watch cleanly: the shutdown hook below breaks the loop out
 * of its sleep rather than letting a poll start against a JVM that is already going away, and an
 * interrupt is treated as "stop", never as a failure to report. {@code --watch-ticks=N} is the
 * bounded form, for a script (or a test) that wants N snapshots and a normal exit rather than an
 * operator's Ctrl-C.
 *
 * <p><b>Failure.</b> A failed first poll fails the command outright, exactly as the one-shot form
 * would -- there is nothing to watch yet. A failure later on is reported on stderr and retried with
 * an exponential backoff, so a control plane bouncing mid-rollout doesn't end the watch, and gives
 * up with the underlying failure's own exit code once {@link #MAX_CONSECUTIVE_FAILURES} polls in a
 * row have failed: never a silent spin, never a hang against a server that is not coming back.
 */
final class ResourceWatch {

  private static final int MAX_CONSECUTIVE_FAILURES = 5;
  private static final Duration MAX_RETRY_BACKOFF = Duration.ofSeconds(30);
  private static final String EVENT_COLUMN = "EVENT";
  private static final String ADDED = "ADDED";
  private static final String MODIFIED = "MODIFIED";
  private static final String DELETED = "DELETED";

  /**
   * Field sets tried in order to identify a row across ticks, so a row that merely changed is
   * reported {@code MODIFIED} rather than as a {@code DELETED}/{@code ADDED} pair. The composite
   * comes first because a node assignment is identified by both its halves and by {@code
   * deploymentName} alone would collapse every instance of one deployment into a single row.
   */
  private static final List<List<String>> IDENTITY_FIELD_SETS =
      List.of(
          List.of("deploymentName", "instanceIndex"),
          List.of("name"),
          List.of("nodeId"),
          List.of("id"),
          List.of("username"),
          List.of("tenantId"));

  private final WatchOptions options;
  private final OutputFormat.Kind output;
  private final PrintStream out;
  private final PrintStream err;
  private final CountDownLatch stopped = new CountDownLatch(1);

  private List<String> columns;

  ResourceWatch(
      final WatchOptions options,
      final OutputFormat.Kind output,
      final PrintStream out,
      final PrintStream err) {
    this.options = options;
    this.output = output;
    this.out = out;
    this.err = err;
  }

  void run(final Supplier<List<Map<String, Object>>> snapshot) {
    final Thread stopOnShutdown = new Thread(stopped::countDown, "gimle-watch-stop");
    Runtime.getRuntime().addShutdownHook(stopOnShutdown);
    try {
      poll(snapshot);
    } finally {
      removeShutdownHook(stopOnShutdown);
    }
  }

  private void poll(final Supplier<List<Map<String, Object>>> snapshot) {
    Map<String, Map<String, Object>> previous = null;
    int printed = 0;
    int consecutiveFailures = 0;
    while (stopped.getCount() > 0) {
      final Map<String, Map<String, Object>> current;
      try {
        current = index(snapshot.get());
      } catch (CliException e) {
        if (previous == null) {
          throw e;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
          throw new CliException(
              "watch gave up after "
                  + consecutiveFailures
                  + " consecutive failed polls: "
                  + e.getMessage(),
              e.exitCode(),
              e);
        }
        err.println(
            "warning: watch poll failed ("
                + consecutiveFailures
                + "/"
                + MAX_CONSECUTIVE_FAILURES
                + "), retrying: "
                + e.getMessage());
        if (!sleep(backoff(consecutiveFailures))) {
          return;
        }
        continue;
      }
      consecutiveFailures = 0;
      emit(previous, current);
      previous = current;
      printed++;
      if (options.ticks() > 0 && printed >= options.ticks()) {
        return;
      }
      if (!sleep(options.interval())) {
        return;
      }
    }
  }

  /**
   * The first tick reports every row as {@code ADDED} -- the full snapshot, the same content the
   * one-shot form prints -- and every later tick reports only what the previous snapshot doesn't
   * already say.
   */
  private void emit(
      final Map<String, Map<String, Object>> previous,
      final Map<String, Map<String, Object>> current) {
    if (previous == null) {
      if (current.isEmpty() && output == OutputFormat.Kind.TABLE) {
        // Silence on the very first tick would be indistinguishable from a watch that never
        // started; the header still appears later, once there is a row to print under it.
        out.println("No resources found.");
      }
      current.values().forEach(row -> print(ADDED, row));
      return;
    }
    for (Map.Entry<String, Map<String, Object>> entry : current.entrySet()) {
      final Map<String, Object> before = previous.get(entry.getKey());
      if (before == null) {
        print(ADDED, entry.getValue());
      } else if (!before.equals(entry.getValue())) {
        print(MODIFIED, entry.getValue());
      }
    }
    for (Map.Entry<String, Map<String, Object>> entry : previous.entrySet()) {
      if (!current.containsKey(entry.getKey())) {
        print(DELETED, entry.getValue());
      }
    }
  }

  private void print(final String event, final Map<String, Object> row) {
    if (output == OutputFormat.Kind.JSON) {
      final Map<String, Object> envelope = new LinkedHashMap<>();
      envelope.put("event", event);
      envelope.put("object", row);
      out.println(Json.write(envelope));
      return;
    }
    if (columns == null) {
      // Column headers come from the first row ever printed and stay fixed for the life of the
      // watch, so every later line stays aligned under them -- the same first-row-wins rule the
      // one-shot table renderer uses, extended across ticks.
      columns = List.copyOf(row.keySet());
      out.println(EVENT_COLUMN + "\t" + String.join("\t", columns));
    }
    final List<String> cells = new ArrayList<>();
    cells.add(event);
    for (final String column : columns) {
      cells.add(formatValue(row.get(column)));
    }
    out.println(String.join("\t", cells));
  }

  private static Map<String, Map<String, Object>> index(final List<Map<String, Object>> rows) {
    final Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
    for (final Map<String, Object> row : rows) {
      indexed.put(identityOf(row), row);
    }
    return indexed;
  }

  /**
   * A stable identity for one row across ticks. Tries the known identifying field sets on the row
   * itself, then on its own {@code spec} (the raw {@code -o json} shapes nest their name there
   * rather than carrying it at the top level), and finally falls back to the row's entire content:
   * an unrecognized shape still diffs correctly that way, just reported as a {@code DELETED}/{@code
   * ADDED} pair rather than one {@code MODIFIED}.
   */
  private static String identityOf(final Map<String, Object> row) {
    final String direct = identityFrom(row);
    if (direct != null) {
      return direct;
    }
    if (row.get("spec") instanceof Map<?, ?> spec) {
      final String nested = identityFrom(spec);
      if (nested != null) {
        return nested;
      }
    }
    return Json.write(row);
  }

  private static String identityFrom(final Map<?, ?> row) {
    for (final List<String> fields : IDENTITY_FIELD_SETS) {
      final List<String> parts = new ArrayList<>();
      for (final String field : fields) {
        final Object value = row.get(field);
        if (value == null) {
          break;
        }
        parts.add(field + "=" + value);
      }
      if (parts.size() == fields.size()) {
        return String.join("/", parts);
      }
    }
    return null;
  }

  /**
   * Mirrors the one-shot table renderer's own cell formatting: a missing value renders as a dash,
   * and a nested object or array renders as its own compact JSON rather than as a flattened
   * sub-table.
   */
  private static String formatValue(final Object value) {
    if (value == null) {
      return "-";
    }
    if (value instanceof Map || value instanceof List) {
      return Json.write(value);
    }
    return String.valueOf(value);
  }

  private Duration backoff(final int consecutiveFailures) {
    final Duration doubled = options.interval().multipliedBy(1L << (consecutiveFailures - 1));
    return doubled.compareTo(MAX_RETRY_BACKOFF) > 0 ? MAX_RETRY_BACKOFF : doubled;
  }

  /** {@code false} once the watch has been asked to stop, whether by Ctrl-C or by an interrupt. */
  private boolean sleep(final Duration duration) {
    try {
      return !stopped.await(duration.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * A shutdown hook cannot be removed once shutdown has begun, which is precisely the case that
   * runs this on the way out of a Ctrl-C -- so the resulting refusal is the expected outcome there,
   * not a failure worth surfacing.
   */
  private static void removeShutdownHook(final Thread hook) {
    try {
      Runtime.getRuntime().removeShutdownHook(hook);
    } catch (IllegalStateException e) {
      // Shutdown already in progress; the hook has served its purpose.
    }
  }
}
