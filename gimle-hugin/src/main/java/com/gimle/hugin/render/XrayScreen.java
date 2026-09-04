package com.gimle.hugin.render;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ClusterSnapshot;
import com.gimle.hugin.model.ServiceSnapshot;
import com.gimle.hugin.model.Xray;
import com.gimle.hugin.model.XrayRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The dependency tree: which Service reaches which deployment, and which instances are behind it.
 *
 * <p>Both halves are already drawn elsewhere; what is only visible here is the gap between them. A
 * Service pointing at a name nothing is running, and a workload no Service fronts, each look
 * healthy in their own table and wrong only side by side.
 */
public final class XrayScreen {

  /** Rows the layout spends on everything that isn't a tree row. */
  private static final int CHROME_ROWS = 6;

  private static final int LABEL_CELLS = 34;
  private static final int STATE_CELLS = 13;
  private static final int GAP = 2;
  private static final int INDENT = 2;

  private final Painter painter;

  public XrayScreen(final Painter painter) {
    this.painter = painter;
  }

  public List<String> render(
      final ServiceSnapshot services,
      final ClusterSnapshot cluster,
      final UiState ui,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    List<XrayRow> rows = Xray.rows(services, cluster, ui.filter());
    List<String> lines = new ArrayList<>();
    lines.add(StatusBar.services(painter, services, ui, viewport, paused, now));
    lines.add("");
    lines.add(label(rows, ui.filter()));
    lines.add(header(viewport));

    if (rows.isEmpty()) {
      lines.add(
          new Line(painter)
              .add(
                  services.services().isEmpty()
                      ? "  no services declared, so nothing is fronted at all"
                      : "  nothing matches",
                  Style.fg(Palette.MUTED))
              .build());
    }

    int available = Math.max(1, viewport.rows() - CHROME_ROWS);
    int first = ClusterScreen.scrollOffset(ui.xrayOffset(), rows.size(), available);
    for (int index = first; index < rows.size() && index < first + available; index++) {
      lines.add(treeLine(rows.get(index), viewport));
    }
    if (rows.size() > available) {
      lines.add(
          new Line(painter)
              .add(
                  "  " + (rows.size() - available) + " more, scroll with ↑↓",
                  Style.fg(Palette.MUTED))
              .build());
    }
    return Frame.fitWithKeyBar(lines, StatusBar.xrayKeys(painter, ui, viewport), viewport);
  }

  /**
   * Counts the two findings the tree exists to surface, so they are legible without reading every
   * row of a cluster with a hundred of them.
   */
  private String label(final List<XrayRow> rows, final String filter) {
    long broken =
        rows.stream()
            .filter(row -> row.kind() == XrayRow.Kind.DEPLOYMENT)
            .filter(row -> !row.state().isBlank())
            .count();
    long unfronted = rows.stream().filter(row -> row.kind() == XrayRow.Kind.UNFRONTED).count();
    Line line =
        new Line(painter)
            .add("XRAY", Style.fg(Palette.HUD).asBold())
            .add("  service → deployment → instance", Style.fg(Palette.MUTED_FOREGROUND));
    if (broken > 0) {
      line.add("   " + broken + " fronting nothing live", Style.fg(Palette.BAD));
    }
    if (unfronted > 0) {
      line.add("   some workloads fronted by no Service", Style.fg(Palette.WARN));
    }
    if (filter != null && !filter.isBlank()) {
      line.add("   filter ", Style.fg(Palette.MUTED_FOREGROUND))
          .add(filter, Style.fg(Palette.PRIMARY));
    }
    return line.build();
  }

  private String header(final Viewport viewport) {
    Style style = Style.fg(Palette.MUTED_FOREGROUND);
    return new Line(painter)
        .cell("TREE", LABEL_CELLS, style)
        .pad(GAP)
        .cell("STATE", STATE_CELLS, style)
        .pad(GAP)
        .cell("DETAIL", detailCells(viewport), style)
        .build();
  }

  private String treeLine(final XrayRow row, final Viewport viewport) {
    String glyph =
        switch (row.kind()) {
          case SERVICE -> "◆ ";
          case UNFRONTED -> "▲ ";
          case DEPLOYMENT -> "├ ";
          case INSTANCE -> "· ";
        };
    Style labelStyle =
        switch (row.kind()) {
          case SERVICE -> Style.fg(Palette.PRIMARY).asBold();
          case UNFRONTED -> Style.fg(Palette.WARN).asBold();
          case DEPLOYMENT -> Style.PLAIN;
          case INSTANCE -> Style.fg(Palette.MUTED_FOREGROUND);
        };
    int indent = row.depth() * INDENT;
    return new Line(painter)
        .pad(indent)
        .add(glyph, labelStyle)
        .cell(row.label(), Math.max(4, LABEL_CELLS - indent - glyph.length()), labelStyle)
        .pad(GAP)
        .cell(row.state(), STATE_CELLS, Style.fg(variantOf(row)))
        .pad(GAP)
        .cell(row.detail(), detailCells(viewport), Style.fg(Palette.MUTED_FOREGROUND))
        .build();
  }

  /**
   * Only the states this tree exists to find are painted. An instance's own lifecycle keeps the
   * mapping every other screen uses, so {@code ACTIVE} reads the same here as it does there.
   */
  private static StatusVariant variantOf(final XrayRow row) {
    return switch (row.state()) {
      case "NOT FOUND", "NOT RUNNING", "NO ENDPOINTS" -> StatusVariant.BAD;
      case "UNKNOWN" -> StatusVariant.WARN;
      case "" -> StatusVariant.MUTED;
      default ->
          row.kind() == XrayRow.Kind.INSTANCE
              ? StatusVariant.ofLifecycleState(row.state())
              : StatusVariant.OK;
    };
  }

  private static int detailCells(final Viewport viewport) {
    return Math.max(10, viewport.columns() - LABEL_CELLS - STATE_CELLS - 2 * GAP);
  }
}
