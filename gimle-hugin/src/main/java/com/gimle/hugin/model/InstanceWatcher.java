package com.gimle.hugin.model;

import com.gimle.cli.spi.ClusterReader;
import com.gimle.core.protocol.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Everything the drill-down watches for one instance: its lifecycle timeline, polled on a slower
 * interval than the cluster itself (events are a history, not a live reading), and a live log tail
 * held open on its own virtual thread.
 *
 * <p>The tail is seeded with one ordinary page of backlog before the follow stream opens, so
 * inspecting a quiet instance shows the lines that explain how it got here instead of an empty pane
 * waiting for the next one. The follow stream then resumes from that page's own last timestamp,
 * which is exactly the cursor the log routes speak.
 *
 * <p>Alongside those two it keeps a third, slower read: the worker JVM's own shipped meter history
 * and, for an instance that is not alive, whatever crash dumps its node kept. Both are addressed by
 * more than the instance key alone, so the render loop publishes the row it is drawing through
 * {@link #observe} and this thread reads it from there.
 *
 * <p>Closing is the operator pressing {@code esc}: every thread observes it, the stream is closed
 * underneath the reader, and a stream that ends on its own is not an error -- an instance whose
 * node went away simply stops producing lines.
 */
public final class InstanceWatcher implements AutoCloseable {

  /** How many lines the pane keeps. Beyond this the oldest are dropped, as a tail should. */
  private static final int MAX_LINES = 500;

  private static final int BACKLOG_LINES = 200;
  private static final Duration EVENT_INTERVAL = Duration.ofSeconds(5);

  /**
   * Slower again than the timeline: a process ships its registry to Muninn on its own interval, so
   * re-reading the history faster than it is written only re-fetches the same page.
   */
  private static final Duration HISTORY_INTERVAL = Duration.ofSeconds(10);

  private final ClusterReader reader;
  private final MetricsHistoryReader metricsReader;
  private final InstanceKey key;
  private final LogCategory category;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Deque<LogLine> lines = new ArrayDeque<>();

  private volatile List<LifecycleEventRow> events = List.of();
  private volatile Optional<String> logError = Optional.empty();
  private volatile InputStream stream;
  private volatile InstanceRow target;
  private volatile MetricsHistory metrics = MetricsHistory.EMPTY;
  private volatile List<CrashDump> crashDumps = List.of();
  private volatile Thread historyThread;

  public InstanceWatcher(
      final ClusterReader reader, final InstanceKey key, final LogCategory category) {
    this.reader = reader;
    this.metricsReader = new MetricsHistoryReader(reader);
    this.key = key;
    this.category = category;
  }

  public InstanceKey key() {
    return key;
  }

  public LogCategory category() {
    return category;
  }

  public void start() {
    Thread.ofVirtual().name("hugin-events").start(this::pollEvents);
    Thread.ofVirtual().name("hugin-logs").start(this::tailLogs);
    historyThread = Thread.ofVirtual().name("hugin-history").start(this::pollHistory);
  }

  public List<LifecycleEventRow> events() {
    return events;
  }

  /**
   * The worker's shipped meter history for this instance's own module, empty until a first read
   * lands and empty forever on a cluster that ships nowhere.
   */
  public MetricsHistory metrics() {
    return metrics;
  }

  /** The crash dumps this instance's node kept, empty for one that has not crashed. */
  public List<CrashDump> crashDumps() {
    return crashDumps;
  }

  /**
   * The row the drill-down is currently drawing, published from the render loop because that is the
   * only place holding both it and this watcher. The history reads need more than the instance key:
   * a worker's metrics are filed under its node and worker ids, and whether a crash dump is worth
   * asking for at all depends on the instance's own liveness.
   *
   * <p>A volatile write and, only when the answer would change, an unpark -- no request is ever
   * issued on the caller's thread.
   */
  public void observe(final InstanceRow row) {
    InstanceRow previous = target;
    target = row;
    if (previous == null || !addressesSameHistory(previous, row)) {
      // Null until start(), which unpark treats as a no-op -- the loop's own first pass covers it.
      LockSupport.unpark(historyThread);
    }
  }

  private static boolean addressesSameHistory(final InstanceRow before, final InstanceRow after) {
    return before.nodeId().equals(after.nodeId())
        && before.workerId().equals(after.workerId())
        && before.moduleCoordinate().equals(after.moduleCoordinate())
        && crashDumpsPlausible(before) == crashDumpsPlausible(after);
  }

  public Optional<String> logError() {
    return logError;
  }

  /** The lines currently held, oldest first. Copied out, since the tail thread keeps appending. */
  public synchronized List<LogLine> lines() {
    return List.copyOf(lines);
  }

  private synchronized void append(final LogLine line) {
    lines.addLast(line);
    while (lines.size() > MAX_LINES) {
      lines.removeFirst();
    }
  }

  private void pollEvents() {
    while (running.get()) {
      try {
        List<LifecycleEventRow> polled = new ArrayList<>();
        for (Map<String, Object> event : reader.getList(eventsPath())) {
          polled.add(LifecycleEventRow.from(event));
        }
        events = List.copyOf(polled);
      } catch (RuntimeException ignored) {
        // The timeline is context, not the point of the pane: a failed poll leaves the last one up
        // rather than replacing a useful history with an error message.
      }
      LockSupport.parkNanos(this, EVENT_INTERVAL.toNanos());
    }
  }

  private void pollHistory() {
    while (running.get()) {
      InstanceRow row = target;
      if (row != null) {
        metrics = readMetrics(row);
        crashDumps = readCrashDumps(row);
      }
      LockSupport.parkNanos(this, HISTORY_INTERVAL.toNanos());
    }
  }

  /**
   * A worker's history is addressable only once the node has said which worker JVM is hosting this
   * instance -- an instance still waiting for one has no history of its own to read.
   */
  private MetricsHistory readMetrics(final InstanceRow row) {
    return row.workerId()
        .map(workerId -> metricsReader.read(row.nodeId(), workerId, row.moduleCoordinate()))
        .orElse(MetricsHistory.EMPTY);
  }

  /**
   * A route that 404s or fails leaves the listing empty rather than reporting anything: an absent
   * section says the same thing an empty one would, without spending a row saying it.
   */
  private List<CrashDump> readCrashDumps(final InstanceRow row) {
    if (!crashDumpsPlausible(row)) {
      return List.of();
    }
    try {
      List<CrashDump> dumps = new ArrayList<>();
      for (Map<String, Object> entry : reader.getList(crashDumpsPath())) {
        CrashDump.from(entry).ifPresent(dumps::add);
      }
      return List.copyOf(dumps);
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  /**
   * Whether a crash dump could plausibly exist. A JVM only writes one when it dies, so a healthy
   * instance's listing can only ever answer with an empty array -- asking for it on every refresh
   * buys nothing. An instance the node has not reported on at all is not "crashed", it is
   * unstarted.
   */
  private static boolean crashDumpsPlausible(final InstanceRow row) {
    return "FAILED".equals(row.lifecycleState()) || (row.observed() && !row.alive());
  }

  private void tailLogs() {
    String cursor = readBacklog();
    if (!running.get()) {
      return;
    }
    final InputStream open;
    try {
      open = reader.openStream(followPath(cursor));
    } catch (RuntimeException e) {
      if (running.get()) {
        logError = Optional.of(describe(e));
      }
      return;
    }
    // Published before it is read from, so a close() arriving while this thread is parked inside
    // readLine has something to close -- that is the only thing that ever wakes it.
    stream = open;
    try (InputStream owned = open;
        BufferedReader buffered =
            new BufferedReader(new InputStreamReader(owned, StandardCharsets.UTF_8))) {
      String line;
      while (running.get() && (line = buffered.readLine()) != null) {
        if (!line.isBlank()) {
          append(LogLine.from(Json.asObject(Json.parse(line))));
        }
      }
    } catch (IOException | RuntimeException e) {
      if (running.get()) {
        logError = Optional.of(describe(e));
      }
    }
  }

  /** Returns the cursor the follow stream should resume from: the newest backlog line's own. */
  private String readBacklog() {
    try {
      Map<String, Object> page = reader.getObject(backlogPath());
      List<Object> raw = Json.asArray(page.getOrDefault("lines", List.of()));
      String cursor = null;
      for (Object entry : raw) {
        LogLine line = LogLine.from(Json.asObject(entry));
        append(line);
        cursor = line.timestamp();
      }
      return cursor == null ? Instant.now().toString() : cursor;
    } catch (RuntimeException e) {
      logError = Optional.of(describe(e));
      return Instant.now().toString();
    }
  }

  private String logsPath() {
    return "/logs/instances/" + encode(key.deploymentName()) + "/" + key.instanceIndex();
  }

  private String backlogPath() {
    return logsPath() + "?category=" + category + "&limit=" + BACKLOG_LINES + tenantQuery();
  }

  private String followPath(final String cursor) {
    return logsPath()
        + "?category="
        + category
        + "&follow=true&cursor="
        + encode(cursor)
        + tenantQuery();
  }

  /**
   * This route carries no query of its own, so the tenant is its first parameter rather than an
   * additional one -- the same {@code tenant=<id>} scoping, introduced with a {@code ?}.
   */
  private String crashDumpsPath() {
    String tenant = tenantParameter();
    return logsPath() + "/crashdumps" + (tenant.isEmpty() ? "" : "?" + tenant);
  }

  private String eventsPath() {
    return "/events?deployment="
        + encode(key.deploymentName())
        + "&instance="
        + key.instanceIndex()
        + tenantQuery();
  }

  /**
   * The owning tenant travels as {@code ?tenant=<id>} the same way every other tenant-scoped route
   * expects -- without it, a name shared by two tenants resolves to whichever the untenanted
   * namespace happens to hold.
   */
  private String tenantQuery() {
    String tenant = tenantParameter();
    return tenant.isEmpty() ? "" : "&" + tenant;
  }

  private String tenantParameter() {
    return key.tenantId().map(id -> "tenant=" + encode(id)).orElse("");
  }

  private static String encode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String describe(final Exception e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  @Override
  public void close() {
    running.set(false);
    LockSupport.unpark(historyThread);
    InputStream open = stream;
    if (open != null) {
      try {
        // Closing the stream is what unblocks the tail thread: it is parked inside readLine on a
        // response that has no natural end, so nothing else would ever wake it.
        open.close();
      } catch (IOException ignored) {
        // Already gone, which is the outcome this was asking for.
      }
    }
  }
}
