package com.gimle.hugin.render;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.VersionRow;
import com.gimle.hugin.model.VersionSnapshot;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Every revision one config key, ConfigMap, secret or SecretMap has had, newest first.
 *
 * <p>The table above it says what a thing is now. This says what it has been, which is the question
 * actually being asked when something started failing at a time nothing was deployed.
 *
 * <p>No revision's secret is shown, because none is read: the plaintext config ledger records
 * values and an encrypted write never enters it, while the secret ledgers record only who wrote a
 * revision and when.
 */
public final class VersionScreen {

  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("MMM dd HH:mm").withZone(ZoneId.systemDefault());

  /** The key bar, plus the row the "N more" note needs when the ledger is longer than the pane. */
  private static final int FOOTER_ROWS = 2;

  private static final int VERSION_CELLS = 9;
  private static final int WHEN_CELLS = 14;
  private static final int AUTHOR_CELLS = 18;
  private static final int STATE_CELLS = 9;
  private static final int GAP = 2;

  private final Painter painter;

  public VersionScreen(final Painter painter) {
    this.painter = painter;
  }

  public List<String> render(
      final VersionSnapshot snapshot,
      final UiState ui,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    List<String> lines = new ArrayList<>();
    lines.add(title(snapshot, viewport, paused, now));
    lines.add("");

    if (!snapshot.available()) {
      lines.add(label(snapshot, 0, ui.filter()));
      lines.add(
          new Line(painter)
              .add(
                  "  " + snapshot.staleReason().orElse("no revision history here"),
                  Style.fg(Palette.MUTED))
              .build());
      return Frame.fitWithKeyBar(lines, StatusBar.versionKeys(painter, ui, viewport), viewport);
    }

    List<VersionRow> rows = snapshot.matching(ui.filter());
    lines.add(label(snapshot, rows.size(), ui.filter()));
    lines.add(header(viewport));

    if (rows.isEmpty()) {
      lines.add(
          new Line(painter)
              .add(
                  snapshot.rows().isEmpty()
                      ? "  no revision has been recorded for this one yet"
                      : "  nothing matches",
                  Style.fg(Palette.MUTED))
              .build());
    }

    // Against the whole ledger rather than the filtered view: filtering to older revisions must
    // not promote one of them into looking like the revision currently in effect.
    int inEffect = snapshot.current().map(VersionRow::version).orElse(-1);
    int available = Math.max(1, viewport.rows() - lines.size() - FOOTER_ROWS);
    int first = ClusterScreen.scrollOffset(ui.versionOffset(), rows.size(), available);
    for (int index = first; index < rows.size() && index < first + available; index++) {
      VersionRow row = rows.get(index);
      lines.add(versionLine(row, row.version() == inEffect, viewport));
    }
    if (rows.size() > available) {
      lines.add(
          new Line(painter)
              .add(
                  "  " + (rows.size() - available) + " more, scroll with ↑↓",
                  Style.fg(Palette.MUTED))
              .build());
    }
    return Frame.fitWithKeyBar(lines, StatusBar.versionKeys(painter, ui, viewport), viewport);
  }

  private String title(
      final VersionSnapshot snapshot,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    Style bar = Style.fg(Palette.MUTED_FOREGROUND).on(Palette.CARD);
    Line line =
        new Line(painter)
            .add(" ", bar)
            .add("GIMLÉ", Style.fg(Palette.PRIMARY).on(Palette.CARD).asBold())
            .add(" TOP", bar.asBold())
            .add("   HISTORY   ", bar.asBold())
            .add(snapshot.subject(), bar);
    if (snapshot.connected()) {
      line.add("  ", bar).add("●", Style.fg(Palette.OK).on(Palette.CARD)).add(" connected", bar);
    } else {
      Style warn = Style.fg(Palette.WARN).on(Palette.CARD);
      String age = snapshot.age(now).map(Text::age).map(value -> " " + value + " old").orElse("");
      line.add("  ", bar)
          .add("●", warn)
          .add(" " + snapshot.staleReason().orElse("disconnected") + age, warn);
    }
    if (paused) {
      line.add("   PAUSED", Style.fg(Palette.WARN).on(Palette.CARD).asBold());
    }
    return line.fillTo(viewport.columns(), bar).build();
  }

  private String label(final VersionSnapshot snapshot, final int shown, final String filter) {
    Line line =
        new Line(painter)
            .add("HISTORY", Style.fg(Palette.HUD).asBold())
            .add(
                "  " + shown + (shown == 1 ? " revision" : " revisions") + ", newest first",
                Style.fg(Palette.MUTED_FOREGROUND));
    snapshot
        .current()
        .ifPresent(
            row ->
                line.add("   in effect ", Style.fg(Palette.MUTED_FOREGROUND))
                    .add("v" + row.version(), Style.fg(Palette.PRIMARY)));
    if (filter != null && !filter.isBlank()) {
      line.add("   filter ", Style.fg(Palette.MUTED_FOREGROUND))
          .add(filter, Style.fg(Palette.PRIMARY));
    }
    return line.build();
  }

  private String header(final Viewport viewport) {
    Style style = Style.fg(Palette.MUTED_FOREGROUND);
    return new Line(painter)
        .cell("VERSION", VERSION_CELLS, style)
        .pad(GAP)
        .cell("WHEN", WHEN_CELLS, style)
        .pad(GAP)
        .cell("AUTHOR", AUTHOR_CELLS, style)
        .pad(GAP)
        .cell("STATE", STATE_CELLS, style)
        .pad(GAP)
        .cell("WAS", detailCells(viewport), style)
        .build();
  }

  /**
   * The revision in effect is the only one painted. Every row below it is a predecessor, and
   * colouring those would make an ordinary history look like a list of problems.
   */
  private String versionLine(
      final VersionRow row, final boolean inEffect, final Viewport viewport) {
    Style muted = Style.fg(Palette.MUTED_FOREGROUND);
    return new Line(painter)
        .cell(
            "v" + row.version(),
            VERSION_CELLS,
            inEffect ? Style.fg(Palette.PRIMARY).asBold() : Style.PLAIN)
        .pad(GAP)
        .cell(row.at().map(WHEN::format).orElse("—"), WHEN_CELLS, muted)
        .pad(GAP)
        .cell(row.author().orElse("—"), AUTHOR_CELLS, muted)
        .pad(GAP)
        .cell(row.deleted() ? "DELETED" : "", STATE_CELLS, Style.fg(StatusVariant.WARN))
        .pad(GAP)
        .cell(row.detail(), detailCells(viewport), muted)
        .build();
  }

  private static int detailCells(final Viewport viewport) {
    return Math.max(
        10, viewport.columns() - VERSION_CELLS - WHEN_CELLS - AUTHOR_CELLS - STATE_CELLS - 4 * GAP);
  }
}
