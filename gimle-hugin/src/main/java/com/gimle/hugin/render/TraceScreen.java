package com.gimle.hugin.render;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.SpanRow;
import com.gimle.hugin.model.TraceSnapshot;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * One instance's recent spans, grouped into the traces they belong to.
 *
 * <p>Grouped rather than listed flat because a span alone says almost nothing: what is being looked
 * for is the call that failed and what it was part of, and that only reads as a whole. Each trace
 * is a heading with its spans indented under it, oldest span first, so the chain reads in the order
 * it happened.
 *
 * <p>No duration is shown anywhere, and that is not an omission to fill in later: the shipper
 * records only each span's end instant, so any elapsed time here would be invented rather than
 * read.
 */
public final class TraceScreen {

  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

  /** Rows the layout spends on everything that isn't a span or a trace heading. */
  private static final int CHROME_ROWS = 6;

  private static final int TIME_CELLS = 8;
  private static final int KIND_CELLS = 9;
  private static final int STATUS_CELLS = 7;
  private static final int GAP = 2;

  private final Painter painter;

  public TraceScreen(final Painter painter) {
    this.painter = painter;
  }

  public List<String> render(
      final InstanceRow instance,
      final TraceSnapshot snapshot,
      final UiState ui,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    List<String> lines = new ArrayList<>();
    lines.add(title(instance, snapshot, viewport, paused, now));
    lines.add("");

    if (!snapshot.shipped()) {
      lines.add(label(snapshot, 0, ui.filter()));
      lines.add(
          new Line(painter)
              .add(
                  "  this worker ships no traces, so there is no history to read",
                  Style.fg(Palette.MUTED))
              .build());
      lines.add(
          new Line(painter)
              .add(
                  "  a process ships only when its own muninnEndpoint is configured",
                  Style.fg(Palette.MUTED))
              .build());
      return Frame.fitWithKeyBar(lines, StatusBar.traceKeys(painter, ui, viewport), viewport);
    }

    List<TraceSnapshot.Trace> traces = snapshot.traces(ui.filter());
    lines.add(label(snapshot, traces.size(), ui.filter()));
    lines.add(header(viewport));

    List<String> body = new ArrayList<>();
    for (TraceSnapshot.Trace trace : traces) {
      body.add(traceLine(trace, viewport));
      for (SpanRow span : trace.spans()) {
        body.add(spanLine(span, viewport));
      }
    }
    if (body.isEmpty()) {
      lines.add(
          new Line(painter)
              .add(
                  snapshot.spans().isEmpty() ? "  nothing shipped yet" : "  nothing matches",
                  Style.fg(Palette.MUTED))
              .build());
    }

    int available = Math.max(1, viewport.rows() - CHROME_ROWS);
    int first = ClusterScreen.scrollOffset(ui.traceOffset(), body.size(), available);
    for (int index = first; index < body.size() && index < first + available; index++) {
      lines.add(body.get(index));
    }
    if (body.size() > available) {
      lines.add(
          new Line(painter)
              .add(
                  "  " + (body.size() - available) + " more, scroll with ↑↓",
                  Style.fg(Palette.MUTED))
              .build());
    }
    return Frame.fitWithKeyBar(lines, StatusBar.traceKeys(painter, ui, viewport), viewport);
  }

  private String title(
      final InstanceRow instance,
      final TraceSnapshot snapshot,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    return TitleBar.of(painter, "traces")
        .subject(instance.deploymentName() + " / " + instance.instanceIndex())
        .connection(snapshot.connected(), snapshot.staleReason(), snapshot.age(now))
        .tenant(instance.tenantId())
        .paused(paused)
        .build(viewport);
  }

  private String label(final TraceSnapshot snapshot, final int shown, final String filter) {
    SectionLabel label = SectionLabel.of(painter, "traces").detail(shown + " recent, newest first");
    long failed = snapshot.failedTraceCount();
    if (failed > 0) {
      label.alert(failed + " with a failed span", StatusVariant.BAD);
    }
    return label.filter(filter).build();
  }

  private String header(final Viewport viewport) {
    Style style = Style.fg(Palette.MUTED_FOREGROUND);
    return new Line(painter)
        .cell("TIME", TIME_CELLS, style)
        .pad(GAP)
        .cell("SPAN", nameCells(viewport), style)
        .pad(GAP)
        .cell("KIND", KIND_CELLS, style)
        .pad(GAP)
        .cell("STATUS", STATUS_CELLS, style)
        .build();
  }

  private String traceLine(final TraceSnapshot.Trace trace, final Viewport viewport) {
    Style style =
        trace.failed() ? Style.fg(Palette.BAD).asBold() : Style.fg(Palette.PRIMARY).asBold();
    return new Line(painter)
        .cell("", TIME_CELLS, Style.PLAIN)
        .pad(GAP)
        .add("◆ ", style)
        .cell(trace.label(), Math.max(4, nameCells(viewport) - 2), style)
        .pad(GAP)
        .cell(
            trace.spans().size() + (trace.spans().size() == 1 ? " span" : " spans"),
            KIND_CELLS,
            Style.fg(Palette.MUTED_FOREGROUND))
        .pad(GAP)
        .cell(trace.failed() ? "FAILED" : "", STATUS_CELLS, Style.fg(StatusVariant.BAD))
        .build();
  }

  private String spanLine(final SpanRow span, final Viewport viewport) {
    return new Line(painter)
        .cell(CLOCK.format(span.at()), TIME_CELLS, Style.fg(Palette.MUTED_FOREGROUND))
        .pad(GAP)
        .pad(2)
        .cell(span.name(), Math.max(4, nameCells(viewport) - 2), Style.PLAIN)
        .pad(GAP)
        .cell(span.kind(), KIND_CELLS, Style.fg(Palette.MUTED_FOREGROUND))
        .pad(GAP)
        .cell(span.status(), STATUS_CELLS, Style.fg(statusVariant(span)))
        .build();
  }

  /**
   * Only a failed span is painted. {@code UNSET} is the ordinary case for a span nobody explicitly
   * marked, so colouring it would make every trace look like it had something to say.
   */
  private static StatusVariant statusVariant(final SpanRow span) {
    return switch (span.status()) {
      case "ERROR" -> StatusVariant.BAD;
      case "OK" -> StatusVariant.OK;
      default -> StatusVariant.MUTED;
    };
  }

  private static int nameCells(final Viewport viewport) {
    return Math.max(10, viewport.columns() - TIME_CELLS - KIND_CELLS - STATUS_CELLS - 3 * GAP);
  }
}
