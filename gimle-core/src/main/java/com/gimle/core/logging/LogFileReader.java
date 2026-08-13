package com.gimle.core.logging;

import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads JSON-lines log files written by {@link PlatformFileAppender}/{@link
 * InstanceSiftingFileAppender}, tailing across an active file plus its rotated {@code .1}..{@code
 * .N-1} copies (oldest last, per {@code FixedWindowRollingPolicy}'s naming). Cursor is an opaque
 * ISO-8601 timestamp, not a byte offset or file index -- stable across rotation, since rotation
 * renames files but never changes what instant a given line was originally written at. A line that
 * fails to parse as JSON (a raw, unstructured SYSTEM capture) is surfaced as {@code {timestamp,
 * category: "SYSTEM", raw}} with a best-effort timestamp (the read time, since no real one is
 * recoverable from unstructured text).
 */
public final class LogFileReader {

  private LogFileReader() {}

  public record LogPage(List<Map<String, Object>> lines, String olderCursor, String newerCursor) {}

  /**
   * How many total copies (active + rotated) a reader should look for on disk -- must match
   * whatever {@link RollingFileAppenders} was configured with on the writing side, since both read
   * the same {@code gimle.log.maxFiles} system property (single source of truth, not duplicated).
   */
  public static int configuredMaxFiles() {
    return Integer.getInteger("gimle.log.maxFiles", 5);
  }

  /** Every line still on disk for this log stream (active file + rotated copies), oldest first. */
  private static List<Map<String, Object>> readAllLines(Path activeFile, int maxFiles) {
    List<Map<String, Object>> all = new ArrayList<>();
    for (int i = maxFiles - 1; i >= 1; i--) {
      appendLinesFrom(activeFile.resolveSibling(activeFile.getFileName() + "." + i), all);
    }
    appendLinesFrom(activeFile, all);
    return all;
  }

  private static void appendLinesFrom(Path file, List<Map<String, Object>> out) {
    if (!Files.isRegularFile(file)) {
      return;
    }
    try {
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (!line.isBlank()) {
          out.add(parseLine(line));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read log file " + file, e);
    }
  }

  private static Map<String, Object> parseLine(String line) {
    try {
      Map<String, Object> parsed = Json.asObject(Json.parse(line));
      // Confirm it's really a structured line (has the fields JsonLogEncoder always writes), not
      // just any JSON value -- a false positive here would silently mislabel a raw line as
      // structured.
      if (parsed.containsKey("timestamp") && parsed.containsKey("level")) {
        return parsed;
      }
    } catch (RuntimeException ignored) {
      // fall through to the raw-line shape below
    }
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("timestamp", Instant.now().toString());
    raw.put("category", "SYSTEM");
    raw.put("raw", line);
    return raw;
  }

  private static Instant timestampOf(Map<String, Object> line) {
    return Instant.parse((String) line.get("timestamp"));
  }

  private static Instant parseCursor(String cursor) {
    try {
      return Instant.parse(cursor);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("invalid log cursor: " + cursor, e);
    }
  }

  /**
   * Lines strictly before {@code cursor} (or the whole tail if {@code cursor} is null), trimmed to
   * the most recent {@code limit}, returned oldest-first. {@code olderCursor} lets a caller page
   * further back; it's {@code null} once nothing older remains on disk -- the same user-visible
   * behavior as {@code kubectl logs} once a container's older rotated logs have aged out.
   */
  public static LogPage readOlder(Path activeFile, int maxFiles, String cursor, int limit) {
    Instant before = cursor == null ? null : parseCursor(cursor);
    List<Map<String, Object>> matching = new ArrayList<>();
    for (Map<String, Object> line : readAllLines(activeFile, maxFiles)) {
      if (before == null || timestampOf(line).isBefore(before)) {
        matching.add(line);
      }
    }
    int fromIndex = Math.max(0, matching.size() - limit);
    List<Map<String, Object>> page = matching.subList(fromIndex, matching.size());
    String olderCursor = fromIndex > 0 ? timestampOf(page.get(0)).toString() : null;
    String newerCursor =
        page.isEmpty() ? cursor : timestampOf(page.get(page.size() - 1)).toString();
    return new LogPage(List.copyOf(page), olderCursor, newerCursor);
  }

  /**
   * Lines strictly after {@code cursor} (or everything currently on disk if {@code cursor} is
   * null), oldest first -- the building block both one-shot "what's new" polling and {@link
   * #streamFollow} use.
   */
  public static List<Map<String, Object>> readAfter(Path activeFile, int maxFiles, String cursor) {
    Instant after = cursor == null ? null : parseCursor(cursor);
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> line : readAllLines(activeFile, maxFiles)) {
      if (after == null || timestampOf(line).isAfter(after)) {
        result.add(line);
      }
    }
    return result;
  }

  /**
   * Blocks, polling for new lines every {@code pollInterval} and writing each as one NDJSON line to
   * {@code out} -- the {@code kubectl logs -f} equivalent for a single log stream. Starts just
   * after {@code cursor} (or from whatever's on disk right now if {@code cursor} is null, matching
   * "stream starting from now" semantics). Returns only when {@code out} throws {@link IOException}
   * (the client disconnected) or the current thread is interrupted -- the only two stopping signals
   * a live tail has; callers should treat either as "follow session ended," not an error.
   */
  public static void streamFollow(
      Path activeFile, int maxFiles, String cursor, Duration pollInterval, OutputStream out)
      throws IOException {
    String lastCursor = cursor == null ? latestCursor(activeFile, maxFiles) : cursor;
    while (true) {
      List<Map<String, Object>> lines = readAfter(activeFile, maxFiles, lastCursor);
      for (Map<String, Object> line : lines) {
        out.write((Json.write(line) + "\n").getBytes(StandardCharsets.UTF_8));
        lastCursor = (String) line.get("timestamp");
      }
      if (!lines.isEmpty()) {
        out.flush();
      }
      try {
        Thread.sleep(pollInterval);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static String latestCursor(Path activeFile, int maxFiles) {
    List<Map<String, Object>> all = readAllLines(activeFile, maxFiles);
    return all.isEmpty() ? null : timestampOf(all.get(all.size() - 1)).toString();
  }
}
