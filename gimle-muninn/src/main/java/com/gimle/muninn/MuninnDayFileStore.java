package com.gimle.muninn;

import com.gimle.core.logging.LogFileReader.LogPage;
import com.gimle.core.logging.LogFilter;
import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backs all three of Muninn's ingested data kinds -- logs, metrics, and traces: one day-bucketed
 * JSON-lines file per calendar day (UTC) under a caller-chosen subtree, e.g. {@code
 * logs/nodes/<nodeId>/<category>/2026-08-10.log}. Deliberately its own small reader/writer rather
 * than a literal reuse of {@link com.gimle.core.logging.LogFileReader}: that class tails one
 * "active file" plus a fixed, small number of count-rotated {@code .1}.. {@code .N} copies (a
 * single node's own local disk), whereas Muninn accumulates an unbounded, ever-growing history from
 * every shipper across the whole cluster, which needs age-based (not count-based) retention -- a
 * fundamentally different file-naming scheme, not a smaller version of the same one. What *is*
 * shared: the JSON-line shape a line round-trips as ({@code Map<String, Object>}, keyed at minimum
 * by {@code timestamp}), the {@link LogPage} result shape (reused directly, not re-declared), and
 * the oldest-first/cursor-by-timestamp paging semantics -- so {@code ApiServer}'s proxy and the
 * console/CLI callers on the other end of it see the same behavior regardless of which store
 * actually answered.
 */
final class MuninnDayFileStore {

  private static final Logger log = LoggerFactory.getLogger(MuninnDayFileStore.class);
  private static final Pattern DAY_FILE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\.log");
  private static final int MAX_LOGGED_LINE_LENGTH = 200;

  private final Path dataRoot;

  MuninnDayFileStore(Path dataRoot) {
    this.dataRoot = dataRoot;
  }

  /**
   * {@code subtreePath} segments are built from a {@code processId}, which is a {@code host:port}
   * string for every process kind except {@code AGENT} -- see {@code MuninnServer}'s own {@code
   * PROCESS_ID_SEGMENT} pattern, which allows {@code :} deliberately. On Windows, {@code
   * java.nio.file.Path} reserves {@code :} for drive letters (e.g. {@code C:}) and rejects it
   * anywhere else in a path with {@code InvalidPathException: Illegal char <:>} -- discovered via
   * {@code /ingest/metrics/CONTROLPLANE/127.0.0.1:8080} 400ing outright on Windows, where every
   * real control-plane/store/fafnir processId takes exactly that shape.
   *
   * <p>{@code :} is therefore escaped to {@link #COLON_ESCAPE} in the on-disk directory name only.
   * The escape is percent-shaped rather than a plain {@code _} substitution precisely so it is
   * reversible: a {@code processId} may itself contain {@code _} but never {@code %} (see that same
   * {@code PROCESS_ID_SEGMENT} pattern), so {@link #decodeProcessId} recovers the exact original. A
   * cross-process search has no request URL to read the {@code processId} off -- a directory
   * listing is its only source -- so a lossy name would leave it unable to say which process a span
   * it found actually ran on.
   */
  private Path resolveSubtree(String subtreePath) {
    return dataRoot.resolve(encodeProcessIds(subtreePath));
  }

  private static final String COLON_ESCAPE = "%3A";

  private static String encodeProcessIds(String subtreePath) {
    return subtreePath.replace(":", COLON_ESCAPE);
  }

  /** The inverse of the {@code :} escaping {@link #resolveSubtree} applies to a directory name. */
  private static String decodeProcessId(String directoryName) {
    return directoryName.replace(COLON_ESCAPE, ":");
  }

  /**
   * Validates every line in {@code lines} before writing any of them -- a malformed line anywhere
   * in the batch fails the whole append, so a shipper's cursor-advance decision (only advance past
   * a batch that was actually accepted) never desyncs from a partially-written result.
   *
   * @throws IllegalArgumentException if any line is not a JSON object with a parseable {@code
   *     timestamp} string field
   */
  void appendLines(String subtreePath, List<Map<String, Object>> lines) throws IOException {
    if (lines.isEmpty()) {
      return;
    }
    Map<String, StringBuilder> byDayFile = new LinkedHashMap<>();
    for (Map<String, Object> line : lines) {
      Instant timestamp = requireTimestamp(line);
      String dayFile = timestamp.atZone(ZoneOffset.UTC).toLocalDate() + ".log";
      byDayFile
          .computeIfAbsent(dayFile, k -> new StringBuilder())
          .append(Json.write(line))
          .append('\n');
    }
    Path dir = resolveSubtree(subtreePath);
    Files.createDirectories(dir);
    for (Map.Entry<String, StringBuilder> entry : byDayFile.entrySet()) {
      // Plain append, not AtomicFiles-style write-then-rename: this is a log stream (one line at a
      // time has always been the durability unit, the same posture PlatformFileAppender's own
      // rolling file appenders take), not a resource where a torn write would corrupt the whole
      // file's meaning the way a torn StateStore YAML file would.
      Files.writeString(
          dir.resolve(entry.getKey()),
          entry.getValue().toString(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    }
  }

  /**
   * Lines strictly after {@code cursor} (or everything currently on disk if {@code cursor} is
   * null), oldest first -- mirrors {@code LogFileReader.readAfter}'s own contract, capped to the
   * oldest {@code limit} matches. Without this cap a {@code since} cursor set far in the past could
   * return Muninn's entire unbounded history in one response, the same unboundedness {@link
   * #readOlder} already guards against via its own {@code limit} parameter. Truncation is signalled
   * the same way {@link #readOlder} signals its own: the caller re-issues {@code since} with the
   * last returned line's own timestamp to fetch the next page. {@code filter} is applied before the
   * cap, so the cap counts matching lines only -- an operator's filtered read of shipped history
   * returns the same lines the identical filtered read of a still-live agent would.
   */
  List<Map<String, Object>> readAfter(
      String subtreePath, String cursor, int limit, LogFilter filter) throws IOException {
    Instant after = cursor == null ? null : parseCursor(cursor);
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> line : readAllLinesSorted(subtreePath)) {
      if ((after == null || timestampOf(line).isAfter(after)) && filter.matches(line)) {
        result.add(line);
        if (result.size() >= limit) {
          break;
        }
      }
    }
    return result;
  }

  /**
   * Lines strictly before {@code cursor} (or the whole history if {@code cursor} is null), trimmed
   * to the most recent {@code limit}, oldest-first -- mirrors {@code LogFileReader.readOlder}'s own
   * contract, including its {@link LogPage} return shape.
   */
  LogPage readOlder(String subtreePath, String cursor, int limit, LogFilter filter)
      throws IOException {
    Instant before = cursor == null ? null : parseCursor(cursor);
    List<Map<String, Object>> matching = new ArrayList<>();
    for (Map<String, Object> line : readAllLinesSorted(subtreePath)) {
      if ((before == null || timestampOf(line).isBefore(before)) && filter.matches(line)) {
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
   * One span carrying a searched trace id, together with the process whose shipped history holds it
   * -- the {@code processKind}/{@code processId} pair naming that process on every other route of
   * this store's own HTTP surface.
   */
  record TraceSpanHit(String processKind, String processId, Map<String, Object> span) {}

  /**
   * Every span carrying {@code traceId}, across every process that has ever shipped traces here,
   * oldest first, capped at {@code limit} matches.
   *
   * <p>This exists because a trace is the one thing here that is inherently cross-process: a
   * cross-worker call puts the caller's span in one process's history and the callee's in another,
   * and neither process knows the other's identity. Assembled from the per-process read route
   * instead, the same question becomes "page backwards through every process you can name until you
   * happen to see it" -- which silently stops being able to answer at all once any one of those
   * processes emits more spans than the pages a caller is willing to walk, and can never reach a
   * process the caller cannot name (a worker that has since been replaced still has its shipped
   * history here under the id it had at the time). Both failures look exactly like "that span was
   * never created."
   *
   * <p>Each line is pre-filtered by a plain substring test before being parsed as JSON: a trace id
   * is a 32-character hex string, so the overwhelming majority of lines are eliminated without
   * paying for a parse. The substring test alone is not the answer -- the exact {@code traceId}
   * field is still compared after parsing, so an id that happens to appear inside some other field
   * never counts as a match.
   */
  List<TraceSpanHit> findSpansByTraceId(String traceId, int limit) throws IOException {
    Path tracesRoot = dataRoot.resolve("traces");
    if (!Files.isDirectory(tracesRoot)) {
      return List.of();
    }
    List<TraceSpanHit> hits = new ArrayList<>();
    for (Path kindDir : sortedDirectories(tracesRoot)) {
      String processKind = fileNameOf(kindDir);
      for (Path processDir : sortedDirectories(kindDir)) {
        String processId = decodeProcessId(fileNameOf(processDir));
        collectMatchingSpans(processDir, processKind, processId, traceId, limit, hits);
        if (hits.size() >= limit) {
          return sortedByTimestamp(hits);
        }
      }
    }
    return sortedByTimestamp(hits);
  }

  private void collectMatchingSpans(
      Path processDir,
      String processKind,
      String processId,
      String traceId,
      int limit,
      List<TraceSpanHit> hits)
      throws IOException {
    for (Path dayFile : sortedDayFiles(processDir)) {
      List<String> lines;
      try {
        lines = Files.readAllLines(dayFile, StandardCharsets.UTF_8);
      } catch (NoSuchFileException e) {
        // Same lost-race-against-the-retention-sweep skip appendLinesFrom already takes.
        log.debug(
            "muninn day file {} was removed by a concurrent retention sweep; skipping", dayFile);
        continue;
      }
      for (String line : lines) {
        if (line.isBlank() || !line.contains(traceId)) {
          continue;
        }
        Map<String, Object> span;
        try {
          span = Json.asObject(Json.parse(line));
        } catch (IllegalArgumentException | ClassCastException e) {
          log.warn(
              "skipping malformed line in muninn day file {}: {}",
              dayFile,
              truncateForLogging(line),
              e);
          continue;
        }
        if (!traceId.equals(span.get("traceId"))) {
          continue;
        }
        hits.add(new TraceSpanHit(processKind, processId, span));
        if (hits.size() >= limit) {
          return;
        }
      }
    }
  }

  /**
   * A line whose timestamp will not parse sorts to the front rather than being dropped: this is a
   * targeted search for one named trace, so silently discarding a span that does carry the searched
   * id would hide exactly what the caller is hunting for.
   */
  private static List<TraceSpanHit> sortedByTimestamp(List<TraceSpanHit> hits) {
    List<TraceSpanHit> sorted = new ArrayList<>(hits);
    sorted.sort(
        (a, b) ->
            tryParseTimestamp(a.span())
                .orElse(Instant.EPOCH)
                .compareTo(tryParseTimestamp(b.span()).orElse(Instant.EPOCH)));
    return List.copyOf(sorted);
  }

  private static List<Path> sortedDirectories(Path parent) throws IOException {
    try (var entries = Files.list(parent)) {
      return entries.filter(Files::isDirectory).sorted().toList();
    }
  }

  private static List<Path> sortedDayFiles(Path parent) throws IOException {
    if (!Files.isDirectory(parent)) {
      return List.of();
    }
    try (var entries = Files.list(parent)) {
      return entries.filter(MuninnDayFileStore::isDayFile).sorted().toList();
    }
  }

  private static String fileNameOf(Path path) {
    Path name = path.getFileName();
    return name == null ? "" : name.toString();
  }

  private List<Map<String, Object>> readAllLinesSorted(String subtreePath) throws IOException {
    Path dir = resolveSubtree(subtreePath);
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    List<Path> dayFiles;
    try (var files = Files.list(dir)) {
      dayFiles = files.filter(MuninnDayFileStore::isDayFile).sorted().toList();
    }
    List<Map<String, Object>> all = new ArrayList<>();
    for (Path file : dayFiles) {
      appendLinesFrom(file, all);
    }
    // A line that parsed as JSON but carries no valid "timestamp" (a hand-edited file, future
    // format drift) is dropped here rather than left in -- every timestampOf() call below,
    // including the sort comparator, assumes a valid timestamp on every remaining line, so
    // filtering here is what keeps one bad line from crashing this whole read instead of just
    // being skipped, the same posture appendLinesFrom already takes for unparseable JSON.
    all.removeIf(
        line -> {
          if (tryParseTimestamp(line).isPresent()) {
            return false;
          }
          log.warn(
              "skipping line with missing/unparseable timestamp in muninn day file read: {}",
              truncateForLogging(String.valueOf(line)));
          return true;
        });
    // Day files are read in filename (i.e. date) order, but a shipper's own batches can arrive
    // out of order within one day (retry after a partial failure, several shippers racing) -- a
    // final stable sort by timestamp is what actually guarantees oldest-first, the same reasoning
    // StateStore.loadAll() sorts InstanceEvent/AuditEvent after loading rather than trusting
    // directory/append order alone.
    all.sort((a, b) -> timestampOf(a).compareTo(timestampOf(b)));
    return all;
  }

  private static boolean isDayFile(Path path) {
    var fileName = path.getFileName();
    return fileName != null && DAY_FILE.matcher(fileName.toString()).matches();
  }

  private static void appendLinesFrom(Path file, List<Map<String, Object>> out) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
      // Lost a race against RetentionSweeper's own deleteIfExists: this file was still in the
      // listing readAllLinesSorted just took, but aged past the cutoff and got swept before this
      // read reached it. Treat exactly like the file never existed -- the same graceful-skip
      // posture already applied to a malformed line below -- rather than letting a read that
      // should have simply returned one file short surface as a 500.
      log.debug("muninn day file {} was removed by a concurrent retention sweep; skipping", file);
      return;
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read muninn day file " + file, e);
    }
    for (String line : lines) {
      if (line.isBlank()) {
        continue;
      }
      // A plain-append writer (see appendLines' own comment on why this file is never
      // written-then-renamed) can leave a torn last line behind a crash mid-write. Skipping just
      // that one malformed line -- rather than letting the exception propagate -- is what keeps a
      // single torn write from permanently breaking every future read of this whole day file.
      try {
        out.add(Json.asObject(Json.parse(line)));
      } catch (IllegalArgumentException | ClassCastException e) {
        log.warn(
            "skipping malformed line in muninn day file {}: {}", file, truncateForLogging(line), e);
      }
    }
  }

  private static String truncateForLogging(String line) {
    if (line.length() <= MAX_LOGGED_LINE_LENGTH) {
      return line;
    }
    return line.substring(0, MAX_LOGGED_LINE_LENGTH) + "...(truncated)";
  }

  private static Instant requireTimestamp(Map<String, Object> line) {
    Object raw = line.get("timestamp");
    if (!(raw instanceof String text)) {
      throw new IllegalArgumentException("line is missing a string \"timestamp\" field: " + line);
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("line has an unparseable \"timestamp\": " + text, e);
    }
  }

  private static Instant timestampOf(Map<String, Object> line) {
    return Instant.parse((String) line.get("timestamp"));
  }

  private static Optional<Instant> tryParseTimestamp(Map<String, Object> line) {
    if (!(line.get("timestamp") instanceof String text)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.parse(text));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  private static Instant parseCursor(String cursor) {
    try {
      return Instant.parse(cursor);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("invalid cursor: " + cursor, e);
    }
  }
}
