package com.gimle.hugin.render;

import com.gimle.hugin.model.ActivityRow;
import com.gimle.hugin.model.ActivitySnapshot;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The activity feed: what has been done to this cluster, newest first.
 *
 * <p>What it shows is the authorization trail -- who asked, of what, and whether it was allowed --
 * because that is the only cluster-wide feed the control plane serves. It is deliberately labelled
 * as such rather than as a lifecycle log: an instance's own transitions are a different record, and
 * one this feed would silently fail to contain.
 */
public final class ActivityScreen {

  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

  /** Rows the layout spends on everything that isn't an event row. */
  private static final int CHROME_ROWS = 5;

  private final Painter painter;

  public ActivityScreen(final Painter painter) {
    this.painter = painter;
  }

  public List<String> render(
      final ActivitySnapshot snapshot,
      final String filter,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    List<String> lines = new ArrayList<>();
    lines.add(statusLine(snapshot, viewport, paused, now));
    lines.add("");

    if (!snapshot.permitted()) {
      lines.add(new Line(painter).add("ACTIVITY", Style.fg(Palette.HUD).asBold()).build());
      lines.add(
          new Line(painter)
              .add(
                  "  your certificate does not carry permission to read the audit trail",
                  Style.fg(Palette.WARN))
              .build());
      lines.add(
          new Line(painter)
              .add(
                  "  nothing is hidden here that `gimle audit` would show you either",
                  Style.fg(Palette.MUTED))
              .build());
      return Frame.fitWithKeyBar(lines, StatusBar.activityKeys(painter, viewport), viewport);
    }

    List<ActivityRow> events = snapshot.matching(filter);
    lines.add(label(events.size(), snapshot.events().size(), filter));
    lines.add(header(viewport));
    if (events.isEmpty()) {
      lines.add(
          new Line(painter)
              .add(
                  snapshot.events().isEmpty() ? "  nothing recorded yet" : "  nothing matches",
                  Style.fg(Palette.MUTED))
              .build());
    }
    int available = Math.max(1, viewport.rows() - CHROME_ROWS);
    for (ActivityRow event : events.stream().limit(available).toList()) {
      lines.add(eventLine(event, viewport));
    }
    return Frame.fitWithKeyBar(lines, StatusBar.activityKeys(painter, viewport), viewport);
  }

  private String statusLine(
      final ActivitySnapshot snapshot,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    Style bar = Style.fg(Palette.MUTED_FOREGROUND).on(Palette.CARD);
    Line line =
        new Line(painter)
            .add(" ", bar)
            .add("GIMLÉ", Style.fg(Palette.PRIMARY).on(Palette.CARD).asBold())
            .add(" TOP", bar.asBold())
            .add("   activity   ", bar)
            .add(snapshot.serverAddress(), bar);
    if (snapshot.connected()) {
      line.add("  ", bar).add("●", Style.fg(Palette.OK).on(Palette.CARD)).add(" connected", bar);
    } else {
      Style warn = Style.fg(Palette.WARN).on(Palette.CARD);
      String age = snapshot.age(now).map(Text::age).map(value -> " " + value + " old").orElse("");
      line.add("  ", bar)
          .add("●", warn)
          .add(" " + snapshot.staleReason().orElse("disconnected") + age, warn);
    }
    line.add("   decisions ", bar).add(String.valueOf(snapshot.events().size()), bar.asBold());
    long refused = snapshot.refusedCount();
    if (refused > 0) {
      line.add("   ", bar)
          .add(refused + " refused", Style.fg(Palette.BAD).on(Palette.CARD).asBold());
    }
    if (paused) {
      line.add("   PAUSED", Style.fg(Palette.WARN).on(Palette.CARD).asBold());
    }
    return line.fillTo(viewport.columns(), bar).build();
  }

  private String label(final int shown, final int total, final String filter) {
    Line line =
        new Line(painter)
            .add("ACTIVITY", Style.fg(Palette.HUD).asBold())
            .add("  authorization decisions, newest first", Style.fg(Palette.MUTED_FOREGROUND));
    if (filter != null && !filter.isBlank()) {
      line.add("   filter ", Style.fg(Palette.MUTED_FOREGROUND))
          .add(filter, Style.fg(Palette.PRIMARY))
          .add("  " + shown + " of " + total, Style.fg(Palette.MUTED_FOREGROUND));
    }
    return line.build();
  }

  private String header(final Viewport viewport) {
    Style style = Style.fg(Palette.MUTED_FOREGROUND);
    return new Line(painter)
        .cell("TIME", 8, style)
        .pad(2)
        .cell("PRINCIPAL", principalCells(viewport), style)
        .pad(2)
        .cell("VERB", 8, style)
        .pad(2)
        .cell("VERDICT", 9, style)
        .pad(2)
        .cell("TARGET", Math.max(10, targetCells(viewport)), style)
        .build();
  }

  private String eventLine(final ActivityRow event, final Viewport viewport) {
    String verdict = event.verdict();
    return new Line(painter)
        .cell(CLOCK.format(event.occurredAt()), 8, Style.fg(Palette.MUTED_FOREGROUND))
        .pad(2)
        .cell(event.principal(), principalCells(viewport), Style.PLAIN)
        .pad(2)
        .cell(event.verb(), 8, Style.PLAIN)
        .pad(2)
        .cell(verdict, 9, Style.fg(verdictVariant(verdict)))
        .pad(2)
        .cell(event.target(), Math.max(10, targetCells(viewport)), Style.PLAIN)
        .build();
  }

  /**
   * A refusal is what an operator opens this feed to find, so it reads as bad; an applied decision
   * is the ordinary case and stays neutral rather than painting the whole column green.
   */
  private static StatusVariant verdictVariant(final String verdict) {
    return switch (verdict) {
      case "DENIED" -> StatusVariant.BAD;
      case "REJECTED" -> StatusVariant.WARN;
      default -> StatusVariant.MUTED;
    };
  }

  private static int principalCells(final Viewport viewport) {
    return Math.clamp(viewport.columns() / 4, 12, 28);
  }

  private static int targetCells(final Viewport viewport) {
    return viewport.columns() - 8 - principalCells(viewport) - 8 - 9 - 8;
  }
}
