package com.gimle.hugin.render;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.PermissionRow;
import com.gimle.hugin.model.PermissionSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What this caller may do, kind by kind and verb by verb, as the control plane answered it.
 *
 * <p>The one cluster fact no other screen can show. Roles, bindings and accounts are all browsable
 * as tables, but reading a grant out of them is the authorizer's job, and three tables plus mental
 * arithmetic is not an answer to "may I delete this".
 *
 * <p>Every cell is a word rather than a mark, so the grid says the same thing with colour switched
 * off as with it on.
 */
public final class PermissionScreen {

  private static final int KIND_CELLS = 22;
  private static final int VERB_CELLS = 10;
  private static final int GAP = 2;

  /** The key bar, plus the row the "N more" note needs when the grid is taller than the pane. */
  private static final int FOOTER_ROWS = 2;

  private final Painter painter;

  public PermissionScreen(final Painter painter) {
    this.painter = painter;
  }

  public List<String> render(
      final PermissionSnapshot snapshot,
      final UiState ui,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    List<String> lines = new ArrayList<>();
    lines.add(title(snapshot, viewport, paused, now));
    lines.add("");

    if (!snapshot.readable()) {
      lines.add(label(snapshot, 0, ui.filter()));
      lines.add(
          new Line(painter)
              .add(
                  "  the control plane would not say: "
                      + snapshot.staleReason().orElse("no reason given"),
                  Style.fg(Palette.MUTED))
              .build());
      return Frame.fitWithKeyBar(lines, StatusBar.permissionKeys(painter, ui, viewport), viewport);
    }

    List<PermissionRow> rows = snapshot.matching(ui.filter());
    lines.add(label(snapshot, rows.size(), ui.filter()));
    if (snapshot.anonymous()) {
      lines.addAll(anonymousWarning());
    }
    lines.add(header(snapshot.verbs()));

    if (rows.isEmpty()) {
      lines.add(new Line(painter).add("  nothing matches", Style.fg(Palette.MUTED)).build());
    }

    int available = Math.max(1, viewport.rows() - lines.size() - FOOTER_ROWS);
    int first = ClusterScreen.scrollOffset(ui.permissionOffset(), rows.size(), available);
    for (int index = first; index < rows.size() && index < first + available; index++) {
      lines.add(gridLine(rows.get(index), snapshot.verbs()));
    }
    if (rows.size() > available) {
      lines.add(
          new Line(painter)
              .add(
                  "  " + (rows.size() - available) + " more, scroll with ↑↓",
                  Style.fg(Palette.MUTED))
              .build());
    }
    return Frame.fitWithKeyBar(lines, StatusBar.permissionKeys(painter, ui, viewport), viewport);
  }

  private String title(
      final PermissionSnapshot snapshot,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    return TitleBar.of(painter, "permissions")
        .subject(snapshot.serverAddress())
        .connection(snapshot.connected(), snapshot.staleReason(), snapshot.age(now))
        .tenant(snapshot.tenantId())
        .paused(paused)
        .build(viewport);
  }

  /**
   * Names the identity the answers are about, because the grid is worthless without it: the same
   * cluster answers a different grid for every certificate that asks.
   */
  private String label(final PermissionSnapshot snapshot, final int shown, final String filter) {
    SectionLabel label =
        SectionLabel.of(painter, "as")
            .subject(snapshot.principal())
            .note(shown + (shown == 1 ? " kind" : " kinds"));
    if (snapshot.readable()) {
      label.note(snapshot.allowedKindCount() + " with something permitted");
    }
    long unanswered = snapshot.unansweredCount();
    if (unanswered > 0) {
      label.alert(unanswered + " unanswered", StatusVariant.WARN);
    }
    return label.filter(filter).build();
  }

  /**
   * Said in full rather than left to be inferred from the principal's name, and split across two
   * short lines so neither is cut on a narrow terminal -- half a warning is worse than none.
   *
   * <p>A grid of unbroken yes is exactly what an over-privileged account would also produce, and
   * mistaking one for the other is the only way this screen could do harm.
   */
  private List<String> anonymousWarning() {
    return List.of(
        new Line(painter)
            .add("  no client certificate was presented, so every cell says yes", warn())
            .build(),
        new Line(painter)
            .add("  this grid is about the transport, not about any account's grants", warn())
            .build());
  }

  private static Style warn() {
    return Style.fg(Palette.WARN);
  }

  private String header(final List<String> verbs) {
    Style style = Style.fg(Palette.MUTED_FOREGROUND);
    Line line = new Line(painter).cell("KIND", KIND_CELLS, style);
    for (String verb : verbs) {
      line.pad(GAP).cell(verb, VERB_CELLS, style);
    }
    return line.build();
  }

  private String gridLine(final PermissionRow row, final List<String> verbs) {
    Line line =
        new Line(painter)
            .cell(
                row.kind(),
                KIND_CELLS,
                row.anyAllowed() ? Style.PLAIN : Style.fg(Palette.MUTED_FOREGROUND));
    for (String verb : verbs) {
      line.pad(GAP).cell(word(row, verb), VERB_CELLS, Style.fg(variantOf(row, verb)));
    }
    return line.build();
  }

  private static String word(final PermissionRow row, final String verb) {
    return row.allowed(verb).map(allowed -> allowed ? "yes" : "no").orElse("unknown");
  }

  /**
   * A denial is the ordinary case for most kinds and is left unpainted; only a grant and a question
   * nobody answered are worth the eye. Painting every "no" would make a normal account's grid look
   * like a wall of failure.
   */
  private static StatusVariant variantOf(final PermissionRow row, final String verb) {
    return row.allowed(verb)
        .map(allowed -> allowed ? StatusVariant.OK : StatusVariant.MUTED)
        .orElse(StatusVariant.WARN);
  }
}
