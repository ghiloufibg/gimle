package com.gimle.hugin.render;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ResourceColumn;
import com.gimle.hugin.model.ResourceKind;
import com.gimle.hugin.model.ResourceRow;
import com.gimle.hugin.model.ResourceSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The resource browser: one kind's collection as a table, with the columns that kind declares.
 *
 * <p>One screen for every kind rather than one per kind, because what differs between them is data
 * -- a route and a list of columns -- and not layout. That is also what lets a custom kind the
 * cluster registered after this code was written render here at all: it arrives as the same shape a
 * built-in kind does.
 *
 * <p>Nothing here interprets a cell. The other tables colour a state because they know what its
 * words mean; this one is showing fields whose meaning it does not and cannot know, so painting one
 * would be inventing a judgement. The cursor is the only thing drawn in colour.
 */
public final class ResourceScreen {

  /** Rows the layout spends on everything that isn't a resource row. */
  private static final int CHROME_ROWS = 6;

  private final Painter painter;

  public ResourceScreen(final Painter painter) {
    this.painter = painter;
  }

  public List<String> render(
      final ResourceSnapshot snapshot,
      final UiState ui,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    List<String> lines = new ArrayList<>();
    lines.add(StatusBar.resources(painter, snapshot, ui, viewport, paused, now));
    lines.add("");

    if (!snapshot.permitted()) {
      lines.add(label(snapshot, 0, 0, ui.filter()));
      lines.add(
          new Line(painter)
              .add(
                  "  your certificate does not carry permission to list " + snapshot.kind().label(),
                  Style.fg(Palette.WARN))
              .build());
      lines.add(
          new Line(painter)
              .add("  : opens another kind you may be permitted", Style.fg(Palette.MUTED))
              .build());
      return Frame.fitWithKeyBar(lines, StatusBar.resourceKeys(painter, ui, viewport), viewport);
    }

    List<ResourceRow> rows = snapshot.matching(ui.filter());
    lines.add(label(snapshot, rows.size(), snapshot.rows().size(), ui.filter()));
    ResourceLayout layout = ResourceLayout.forWidth(snapshot.kind().columns(), viewport.columns());
    lines.add(header(snapshot.kind(), layout));
    if (rows.isEmpty()) {
      lines.add(
          new Line(painter)
              .add(
                  snapshot.rows().isEmpty()
                      ? "  no " + snapshot.kind().label() + " declared"
                      : "  nothing matches",
                  Style.fg(Palette.MUTED))
              .build());
    }

    int available = Math.max(1, viewport.rows() - CHROME_ROWS);
    int cursor = ui.resourceSelectionIndex(rows);
    int first = ClusterScreen.scrollOffset(cursor, rows.size(), available);
    for (int index = first; index < rows.size() && index < first + available; index++) {
      lines.add(resourceLine(rows.get(index), layout, index == cursor, viewport));
    }
    if (rows.size() > available) {
      // Said out loud rather than left to the frame's own silent cut: a table that stops without
      // saying so reads as the whole collection, which it isn't.
      lines.add(
          new Line(painter)
              .add(
                  "  " + (rows.size() - available) + " more, scroll with ↑↓",
                  Style.fg(Palette.MUTED))
              .build());
    }
    return Frame.fitWithKeyBar(lines, StatusBar.resourceKeys(painter, ui, viewport), viewport);
  }

  private String label(
      final ResourceSnapshot snapshot, final int shown, final int total, final String filter) {
    ResourceKind kind = snapshot.kind();
    SectionLabel label = SectionLabel.of(painter, kind.key()).detail(kind.route());
    if (kind.custom()) {
      // Said out loud: a custom kind's columns were chosen by whoever registered it, which is why
      // two clusters can show the same kind differently.
      label.aside("registered kind");
    }
    if (filter != null && !filter.isBlank()) {
      label.note(shown + " of " + total);
    }
    return label.filter(filter).build();
  }

  private String header(final ResourceKind kind, final ResourceLayout layout) {
    Style style = Style.fg(Palette.MUTED_FOREGROUND);
    Line line = new Line(painter);
    List<ResourceColumn> columns = kind.columns();
    for (int index = 0; index < columns.size(); index++) {
      if (index > 0) {
        line.pad(layout.gap());
      }
      line.cell(columns.get(index).header(), layout.width(index), style);
    }
    return line.build();
  }

  private String resourceLine(
      final ResourceRow row,
      final ResourceLayout layout,
      final boolean selected,
      final Viewport viewport) {
    Style style = selected ? Style.fg(Palette.FOREGROUND).on(Palette.SELECTION) : Style.PLAIN;
    Line line = new Line(painter);
    for (int index = 0; index < row.cells().size() && index < layout.widths().size(); index++) {
      if (index > 0) {
        line.pad(layout.gap());
      }
      String cell = row.cells().get(index);
      line.cell(cell.isBlank() ? Text.ABSENT : cell, layout.width(index), style);
    }
    return selected ? line.fillTo(viewport.columns(), style).build() : line.build();
  }
}
