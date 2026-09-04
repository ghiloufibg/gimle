package com.gimle.hugin.render;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ActivitySnapshot;
import com.gimle.hugin.model.FeedMode;
import com.gimle.hugin.model.FeedRow;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The activity view: what is going on in this cluster, in whichever of three records answers the
 * question being asked -- authorization decisions, instance lifecycle transitions, or alert rules
 * and whether they are firing.
 *
 * <p>All three read the same shape on screen, so they share one table rather than one each, and the
 * label always names which record is showing: they answer genuinely different questions, and a feed
 * that let itself be mistaken for another would silently omit exactly what was being looked for.
 */
public final class ActivityScreen {

  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

  /** Rows the layout spends on everything that isn't an event row. */
  private static final int CHROME_ROWS = 5;

  // The fixed columns, named once: the subject column is whatever is left after them, and a
  // header and row that each did that arithmetic from their own copy would drift apart the
  // moment one of these changed.
  private static final int TIME_CELLS = 8;
  private static final int ACTION_CELLS = 10;
  private static final int STATE_CELLS = 9;
  // A lifecycle verdict is an event kind, and the longest -- TRANSITION_FAILED -- is also the one
  // an operator opened this feed to find. Truncating that to fit the other feeds' narrower column
  // would hide exactly the row that matters.
  private static final int LIFECYCLE_STATE_CELLS = 18;
  private static final int GAP = 2;
  private static final int GAPS = 4;

  private final Painter painter;

  public ActivityScreen(final Painter painter) {
    this.painter = painter;
  }

  public List<String> render(
      final ActivitySnapshot snapshot,
      final UiState ui,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    List<String> lines = new ArrayList<>();
    lines.add(statusLine(snapshot, ui, viewport, paused, now));
    lines.add("");

    if (!snapshot.permitted()) {
      lines.add(new Line(painter).add("ACTIVITY", Style.fg(Palette.HUD).asBold()).build());
      lines.add(
          new Line(painter)
              .add(
                  "  your certificate does not carry permission to read the "
                      + snapshot.mode().label()
                      + " feed",
                  Style.fg(Palette.WARN))
              .build());
      lines.add(
          new Line(painter)
              .add(
                  "  c switches to another feed you may be permitted to read",
                  Style.fg(Palette.MUTED))
              .build());
      return Frame.fitWithKeyBar(lines, StatusBar.activityKeys(painter, ui, viewport), viewport);
    }

    List<FeedRow> events = snapshot.matching(ui.filter());
    lines.add(label(events.size(), snapshot.events().size(), ui.filter(), snapshot.mode()));
    lines.add(header(viewport, snapshot.mode()));
    if (events.isEmpty()) {
      lines.add(
          new Line(painter)
              .add(
                  snapshot.events().isEmpty() ? "  nothing recorded yet" : "  nothing matches",
                  Style.fg(Palette.MUTED))
              .build());
    }
    int available = Math.max(1, viewport.rows() - CHROME_ROWS);
    for (FeedRow event : events.stream().limit(available).toList()) {
      lines.add(eventLine(event, viewport, snapshot.mode()));
    }
    if (snapshot.hasMore()) {
      lines.add(
          new Line(painter)
              .add("  m", Style.fg(Palette.PRIMARY).asBold())
              .add(" for older entries", Style.fg(Palette.MUTED))
              .build());
    }
    return Frame.fitWithKeyBar(lines, StatusBar.activityKeys(painter, ui, viewport), viewport);
  }

  private String statusLine(
      final ActivitySnapshot snapshot,
      final UiState ui,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    TitleBar bar =
        TitleBar.of(painter, "activity")
            .subject(snapshot.serverAddress())
            .connection(snapshot.connected(), snapshot.staleReason(), snapshot.age(now))
            .scope(ui)
            .stat(snapshot.mode().label(), snapshot.events().size());
    long notable = snapshot.notableCount();
    if (notable > 0) {
      bar.badge(
          notable + " " + (snapshot.mode() == FeedMode.ALERTS ? "firing" : "refused"),
          StatusVariant.BAD);
    }
    return bar.paused(paused).build(viewport);
  }

  private String label(final int shown, final int total, final String filter, final FeedMode mode) {
    SectionLabel label = SectionLabel.of(painter, "activity").detail(describe(mode));
    if (filter != null && !filter.isBlank()) {
      label.note(shown + " of " + total);
    }
    return label.filter(filter).build();
  }

  /** What each feed is a record of, said so a reader never has to infer which one they are in. */
  private static String describe(final FeedMode mode) {
    return switch (mode) {
      case AUDIT -> "authorization decisions, newest first";
      case LIFECYCLE -> "instance lifecycle transitions, newest first";
      case ALERTS -> "declared alert rules, firing first";
    };
  }

  private String header(final Viewport viewport, final FeedMode mode) {
    Style style = Style.fg(Palette.MUTED_FOREGROUND);
    String actor =
        switch (mode) {
          case AUDIT -> "PRINCIPAL";
          case LIFECYCLE -> "INSTANCE";
          case ALERTS -> "RULE";
        };
    String action = mode == FeedMode.ALERTS ? "METRIC" : "ACTION";
    String subject = mode == FeedMode.ALERTS ? "WATCHES" : "TARGET";
    return new Line(painter)
        .cell(mode == FeedMode.ALERTS ? "" : "TIME", TIME_CELLS, style)
        .pad(GAP)
        .cell(actor, actorCells(viewport), style)
        .pad(GAP)
        .cell(action, ACTION_CELLS, style)
        .pad(GAP)
        .cell("STATE", stateCells(mode), style)
        .pad(GAP)
        .cell(subject, subjectCells(viewport, mode), style)
        .build();
  }

  private String eventLine(final FeedRow event, final Viewport viewport, final FeedMode mode) {
    String verdict = event.verdict();
    // An alert rule has no moment of its own -- it is a standing declaration, not something that
    // happened -- so its time column stays blank rather than showing when it was last read.
    String when = mode == FeedMode.ALERTS ? "" : CLOCK.format(event.at());
    return new Line(painter)
        .cell(when, TIME_CELLS, Style.fg(Palette.MUTED_FOREGROUND))
        .pad(GAP)
        .cell(event.actor(), actorCells(viewport), Style.PLAIN)
        .pad(GAP)
        .cell(event.action(), ACTION_CELLS, Style.PLAIN)
        .pad(GAP)
        .cell(verdict, stateCells(mode), Style.fg(verdictVariant(verdict)))
        .pad(GAP)
        .cell(event.subject(), subjectCells(viewport, mode), Style.PLAIN)
        .build();
  }

  /**
   * What an operator opens a feed to find reads as bad; everything else earns colour only where it
   * is the answer to a question. An applied decision is the ordinary case in an audit trail and
   * stays neutral rather than painting the whole column green, whereas a rule reading {@code OK} is
   * exactly what the alert feed was opened to check, so it says so.
   */
  private static StatusVariant verdictVariant(final String verdict) {
    return switch (verdict) {
      case "DENIED", "FIRING", "TRANSITION_FAILED", "FAILED" -> StatusVariant.BAD;
      case "REJECTED", "UNKNOWN" -> StatusVariant.WARN;
      case "OK", "ACTIVE" -> StatusVariant.OK;
      default -> StatusVariant.MUTED;
    };
  }

  private static int actorCells(final Viewport viewport) {
    return Math.clamp(viewport.columns() / 4, 12, 28);
  }

  private static int stateCells(final FeedMode mode) {
    return mode == FeedMode.LIFECYCLE ? LIFECYCLE_STATE_CELLS : STATE_CELLS;
  }

  /** Whatever the fixed columns and their gaps leave, never less than a readable minimum. */
  private static int subjectCells(final Viewport viewport, final FeedMode mode) {
    int fixed = TIME_CELLS + actorCells(viewport) + ACTION_CELLS + stateCells(mode) + GAPS * GAP;
    return Math.max(10, viewport.columns() - fixed);
  }
}
