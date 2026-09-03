package com.gimle.hugin.render;

import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import com.gimle.hugin.model.CrashDump;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.InstanceWatcher;
import com.gimle.hugin.model.LifecycleEventRow;
import com.gimle.hugin.model.LogLine;
import com.gimle.hugin.model.MetricSeries;
import com.gimle.hugin.model.MetricsHistory;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The drill-down: what this instance is and how it is doing, the last few lifecycle transitions,
 * and a live tail of its own logging. Like the cluster view, a pure function of what it is given.
 *
 * <p>The detail pane shows only what the control plane actually serves for an instance, and reads
 * the declared limit differently per tier, because the platform enforces it differently: a
 * dedicated worker JVM is started with that memory figure as its own {@code -Xmx}, so measured
 * memory against it is a real headroom reading and gets a gauge. A shared worker JVM has one heap
 * for every instance on it, so the same figure is only what the scheduler admitted against -- shown
 * as text, with no gauge that would imply a ceiling this instance can individually hit.
 *
 * <p>Two of the panes appear only when there is something behind them. Shipped meter history is
 * optional -- a cluster running no observability sink has none -- so a measured row carries a
 * sparkline where one exists and is otherwise the row it has always been, never an empty chart. A
 * crash dump listing is the same: a section for an instance that has one, and no section at all for
 * the overwhelming majority that never will.
 */
public final class InstanceScreen {

  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

  /** How many events the pane shows before the log tail takes the rest of the screen. */
  private static final int MAX_EVENTS = 5;

  /** Width of the memory headroom bar, matching the gauges the cluster view draws for a node. */
  private static final int GAUGE_CELLS = 12;

  /** Width of a measured row's sparkline, and the column every one of them starts at. */
  private static final int SPARK_CELLS = 16;

  private static final int SPARK_COLUMN = 28;

  /**
   * The worker meters drawn as history, named exactly as the worker's own registry registers them.
   */
  private static final String REQUEST_COUNT_METER = "gimle.module.request.count";

  private static final String REQUEST_ERRORS_METER = "gimle.module.request.errors";

  private static final String METASPACE_METER = "gimle.module.metaspace.bytes";

  private final Painter painter;

  public InstanceScreen(final Painter painter) {
    this.painter = painter;
  }

  public List<String> render(
      final InstanceRow row,
      final InstanceWatcher watcher,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    // The render loop is the only place holding both the row and the watcher, so this is where the
    // watcher learns which instance it is drawing. A volatile publish, not a request.
    watcher.observe(row);

    List<String> lines = new ArrayList<>();
    lines.add(header(row, viewport, paused));
    lines.add("");
    lines.addAll(detail(row, watcher.metrics()));
    lines.add("");
    lines.addAll(crashDumps(watcher.crashDumps(), now));

    List<LifecycleEventRow> events = watcher.events();
    lines.add(sectionLabel("RECENT EVENTS"));
    if (events.isEmpty()) {
      lines.add(muted("  no lifecycle events recorded"));
    }
    for (LifecycleEventRow event : events.stream().limit(MAX_EVENTS).toList()) {
      lines.add(eventLine(event, viewport));
    }
    lines.add("");

    lines.add(logsLabel(watcher, viewport));
    int available = Math.max(1, viewport.rows() - lines.size() - 1);
    List<LogLine> logLines = watcher.lines();
    List<LogLine> visible =
        logLines.size() > available
            ? logLines.subList(logLines.size() - available, logLines.size())
            : logLines;
    if (visible.isEmpty()) {
      lines.add(muted("  waiting for output…"));
    }
    for (LogLine line : visible) {
      lines.add(logLine(line, viewport));
    }

    return Frame.fitWithKeyBar(lines, StatusBar.instanceKeys(painter, viewport), viewport);
  }

  private String header(final InstanceRow row, final Viewport viewport, final boolean paused) {
    Style bar = Style.fg(Palette.MUTED_FOREGROUND).on(Palette.CARD);
    Line line =
        new Line(painter)
            .add(" ", bar)
            .add("GIMLÉ", Style.fg(Palette.PRIMARY).on(Palette.CARD).asBold())
            .add(" TOP", bar.asBold())
            .add("   ", bar)
            .add(row.deploymentName(), Style.fg(Palette.FOREGROUND).on(Palette.CARD))
            .add(" / ", bar)
            .add(
                String.valueOf(row.instanceIndex()), Style.fg(Palette.FOREGROUND).on(Palette.CARD));
    row.tenantId().ifPresent(tenant -> line.add("   tenant ", bar).add(tenant, bar.asBold()));
    line.add("   node ", bar).add(row.nodeId(), bar.asBold());
    if (paused) {
      line.add("   PAUSED", Style.fg(Palette.WARN).on(Palette.CARD).asBold());
    }
    return line.fillTo(viewport.columns(), bar).build();
  }

  private List<String> detail(final InstanceRow row, final MetricsHistory history) {
    List<String> lines = new ArrayList<>();
    lines.add(sectionLabel("INSTANCE"));
    StatusVariant state = StatusVariant.ofLifecycleState(row.lifecycleState());
    lines.add(field("state", row.lifecycleState(), Style.fg(state)));
    lines.add(
        field("ready", flag(row.observed(), row.ready()), flagStyle(row.observed(), row.ready())));
    lines.add(
        field("alive", flag(row.observed(), row.alive()), flagStyle(row.observed(), row.alive())));
    lines.add(field("module", row.moduleCoordinate().orElse(Text.ABSENT), Style.PLAIN));
    lines.add(field("worker", row.workerId().orElse(Text.ABSENT), Style.PLAIN));
    lines.add("");
    lines.addAll(isolation(row));
    lines.add("");
    lines.add(sectionLabel("MEASURED"));
    if (!row.observed()) {
      lines.add(muted("  placed, but this node has not reported on it yet"));
      return lines;
    }
    lines.add(memoryField(row));
    lines.add(field("cpu", Text.millicores(row.cpuMillicoresUsed()), Style.PLAIN));
    // Both counters are cumulative totals, so their history only means anything as the per-second
    // change between consecutive snapshots -- the same reading the live column beside it shows.
    lines.add(
        field(
            "req/s",
            Text.rate(row.requestRatePerSecond()),
            Style.PLAIN,
            rates(history, REQUEST_COUNT_METER),
            StatusVariant.INFO));
    lines.add(
        field(
            "err/s",
            Text.rate(row.errorRatePerSecond()),
            row.errorRatePerSecond() > 0 ? Style.fg(Palette.BAD) : Style.PLAIN,
            rates(history, REQUEST_ERRORS_METER),
            StatusVariant.BAD));
    lines.add(field("queue", String.valueOf(row.queueDepth()), Style.PLAIN));
    // The module's own classloader footprint, and the one memory reading a worker actually ships.
    // Its own line rather than an addition to "memory" above, which reads the whole worker JVM.
    history
        .series(METASPACE_METER)
        .filter(series -> !series.isEmpty())
        .ifPresent(
            series ->
                lines.add(
                    field(
                        "metaspace",
                        Text.bytes((long) series.latest()),
                        Style.PLAIN,
                        series.values(),
                        StatusVariant.OK)));
    // Both are absent on most instances -- a module that only answers over the fabric reports no
    // port, and one with no volume reports no usage -- so each earns its line only when there is
    // something to put on it, rather than standing as a permanent em dash.
    if (!row.ports().isEmpty()) {
      lines.add(field("ports", row.portSummary(), Style.PLAIN));
    }
    if (row.volumeUsageBytes() > 0) {
      lines.add(field("volume", Text.bytes(row.volumeUsageBytes()), Style.PLAIN));
    }
    return lines;
  }

  private List<String> isolation(final InstanceRow row) {
    List<String> lines = new ArrayList<>();
    lines.add(sectionLabel("ISOLATION"));
    lines.add(
        field(
            "tier",
            row.isolationTier().map(InstanceScreen::tierText).orElse(Text.ABSENT),
            row.isolationTier().isPresent() ? Style.PLAIN : Style.fg(Palette.MUTED)));
    lines.add(
        field(
            "limit",
            row.resourceLimit().map(InstanceScreen::limitText).orElse(Text.ABSENT),
            row.resourceLimit().isPresent() ? Style.PLAIN : Style.fg(Palette.MUTED)));
    if (row.resourceLimit().isPresent()
        && row.isolationTier().orElse(null) == IsolationTier.TIER_1) {
      lines.add(
          new Line(painter)
              .pad(12)
              .add("admission bound, not a per-instance ceiling", Style.fg(Palette.MUTED))
              .build());
    }
    return lines;
  }

  private static String tierText(final IsolationTier tier) {
    return switch (tier) {
      case TIER_1 -> "TIER_1  shared worker JVM";
      case TIER_2 -> "TIER_2  dedicated worker JVM";
      case TIER_3 -> "TIER_3  namespaced worker JVM";
    };
  }

  private static String limitText(final ResourceSpec limit) {
    return limit.memory() + " memory · " + limit.cpu() + " cpu";
  }

  /**
   * Memory used, with the headroom bar drawn only where the limit is one this instance can reach on
   * its own -- a dedicated worker JVM, whose heap it does not share with anything.
   */
  private String memoryField(final InstanceRow row) {
    String used = Text.bytes(row.memoryBytesUsed());
    if (row.isolationTier().orElse(null) != IsolationTier.TIER_2 || row.resourceLimit().isEmpty()) {
      return field("memory", used, Style.PLAIN);
    }
    long limitBytes = row.resourceLimit().orElseThrow().memoryBytes();
    double fraction = Text.fraction(row.memoryBytesUsed(), limitBytes);
    Line line =
        new Line(painter)
            .cell("memory", 10, Style.fg(Palette.MUTED_FOREGROUND))
            .pad(2)
            .add(used + " of " + Text.bytes(limitBytes), Style.PLAIN)
            .pad(2);
    Gauge.draw(line, fraction, GAUGE_CELLS);
    return line.add(String.format(Locale.ROOT, " %.0f%%", fraction * 100), Style.PLAIN).build();
  }

  private static List<Double> rates(final MetricsHistory history, final String meterName) {
    return history.series(meterName).map(MetricSeries::ratesPerSecond).orElse(List.of());
  }

  private String field(final String label, final String value, final Style style) {
    return field(label, value, style, List.of(), StatusVariant.MUTED);
  }

  /**
   * A measured row, with the meter's own shipped history beside it when there is any. With none --
   * the case on every cluster shipping nowhere -- this is byte for byte the row without it, rather
   * than a chart drawing a flat zero it has no reading for.
   */
  private String field(
      final String label,
      final String value,
      final Style style,
      final List<Double> history,
      final StatusVariant variant) {
    Line line =
        new Line(painter)
            .cell(label, 10, Style.fg(Palette.MUTED_FOREGROUND))
            .pad(2)
            .add(value, style);
    if (!history.isEmpty()) {
      line.padTo(Math.max(SPARK_COLUMN, line.width() + 2));
      Sparkline.draw(line, history, SPARK_CELLS, variant);
    }
    return line.build();
  }

  /**
   * The crash dumps the instance's node kept, listed only when there are any. Names are shown, not
   * contents: a dump is hundreds of lines of native frames, and what the pane is for is telling an
   * operator that one exists and where to go and read it.
   */
  private List<String> crashDumps(final List<CrashDump> dumps, final Instant now) {
    if (dumps.isEmpty()) {
      return List.of();
    }
    List<String> lines = new ArrayList<>();
    lines.add(sectionLabel("CRASH DUMPS"));
    for (CrashDump dump : dumps) {
      lines.add(crashDumpLine(dump, now));
    }
    lines.add("");
    return lines;
  }

  private String crashDumpLine(final CrashDump dump, final Instant now) {
    String age =
        dump.lastModified()
            .map(at -> Text.age(Duration.between(at, now)) + " ago")
            .orElse(Text.ABSENT);
    return new Line(painter)
        .pad(2)
        .cell(dump.name(), 24, Style.fg(Palette.BAD))
        .pad(2)
        .rightCell(Text.bytes(dump.sizeBytes()), 8, Style.PLAIN)
        .pad(2)
        .add(age, Style.fg(Palette.MUTED_FOREGROUND))
        .build();
  }

  private static String flag(final boolean observed, final boolean value) {
    if (!observed) {
      return Text.ABSENT;
    }
    return value ? "✓ true" : "✗ false";
  }

  private static Style flagStyle(final boolean observed, final boolean value) {
    if (!observed) {
      return Style.fg(Palette.MUTED);
    }
    return Style.fg(value ? Palette.OK : Palette.BAD);
  }

  private String eventLine(final LifecycleEventRow event, final Viewport viewport) {
    int messageCells = Math.max(10, viewport.columns() - 28);
    String message =
        event
            .causeSummary()
            .map(cause -> event.message() + " (" + cause + ")")
            .orElse(event.message());
    return new Line(painter)
        .add(CLOCK.format(event.occurredAt()), Style.fg(Palette.MUTED_FOREGROUND))
        .pad(2)
        .cell(event.kind(), 16, Style.fg(eventVariant(event.kind())))
        .pad(2)
        .cell(message, messageCells, Style.PLAIN)
        .build();
  }

  /**
   * Lifecycle event kinds are the same words as the lifecycle states they record a transition into,
   * with one addition: a failed transition, which the state vocabulary has no member for.
   */
  private static StatusVariant eventVariant(final String kind) {
    return "TRANSITION_FAILED".equals(kind)
        ? StatusVariant.BAD
        : StatusVariant.ofLifecycleState(kind);
  }

  private String logsLabel(final InstanceWatcher watcher, final Viewport viewport) {
    Line line =
        new Line(painter)
            .add("LOGS", Style.fg(Palette.HUD).asBold())
            .add(" ", Style.PLAIN)
            .add(
                watcher.category().name().toLowerCase(Locale.ROOT) + " · following",
                Style.fg(Palette.MUTED_FOREGROUND));
    watcher.logError().ifPresent(error -> line.add("  " + error, Style.fg(Palette.WARN)));
    return line.padTo(Math.max(line.width(), viewport.columns() - 18))
        .add("c: cycle category", Style.fg(Palette.MUTED))
        .build();
  }

  private String logLine(final LogLine line, final Viewport viewport) {
    Line rendered =
        new Line(painter)
            .cell(line.clock(), 12, Style.fg(Palette.MUTED_FOREGROUND))
            .pad(1)
            .cell(
                line.level().orElse(""),
                5,
                Style.fg(StatusVariant.ofLogLevel(line.level().orElse(""))))
            .pad(1);
    int messageCells = Math.max(10, viewport.columns() - rendered.width());
    return rendered.cell(line.message().orElse(""), messageCells, Style.PLAIN).build();
  }

  private String sectionLabel(final String label) {
    return new Line(painter).add(label, Style.fg(Palette.HUD).asBold()).build();
  }

  private String muted(final String message) {
    return new Line(painter).add(message, Style.fg(Palette.MUTED)).build();
  }
}
