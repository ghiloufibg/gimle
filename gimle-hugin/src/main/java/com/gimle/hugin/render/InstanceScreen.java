package com.gimle.hugin.render;

import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.InstanceWatcher;
import com.gimle.hugin.model.LifecycleEventRow;
import com.gimle.hugin.model.LogLine;
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
 * <p>The detail pane shows only what the control plane actually serves for an instance. Resource
 * limits and isolation tier come from the module's own descriptor, which no read route exposes, so
 * they are absent here rather than approximated from something else.
 */
public final class InstanceScreen {

  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

  /** How many events the pane shows before the log tail takes the rest of the screen. */
  private static final int MAX_EVENTS = 5;

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
    List<String> lines = new ArrayList<>();
    lines.add(header(row, viewport, paused));
    lines.add("");
    lines.addAll(detail(row));
    lines.add("");

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

    while (lines.size() < viewport.rows() - 1) {
      lines.add("");
    }
    lines.add(StatusBar.instanceKeys(painter, viewport));
    return Frame.fit(lines, viewport);
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

  private List<String> detail(final InstanceRow row) {
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
    lines.add(sectionLabel("MEASURED"));
    if (!row.observed()) {
      lines.add(muted("  placed, but this node has not reported on it yet"));
      return lines;
    }
    lines.add(field("memory", Text.bytes(row.memoryBytesUsed()), Style.PLAIN));
    lines.add(field("cpu", Text.millicores(row.cpuMillicoresUsed()), Style.PLAIN));
    lines.add(field("req/s", Text.rate(row.requestRatePerSecond()), Style.PLAIN));
    lines.add(
        field(
            "err/s",
            Text.rate(row.errorRatePerSecond()),
            row.errorRatePerSecond() > 0 ? Style.fg(Palette.BAD) : Style.PLAIN));
    lines.add(field("queue", String.valueOf(row.queueDepth()), Style.PLAIN));
    return lines;
  }

  private String field(final String label, final String value, final Style style) {
    return new Line(painter)
        .cell(label, 10, Style.fg(Palette.MUTED_FOREGROUND))
        .pad(2)
        .add(value, style)
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
