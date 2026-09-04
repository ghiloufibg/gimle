package com.gimle.hugin.render;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ClusterSnapshot;
import com.gimle.hugin.model.Scan;
import com.gimle.hugin.model.ScanFinding;
import com.gimle.hugin.model.ServiceSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything wrong with the cluster, worst first, on one screen.
 *
 * <p>Nothing here is a reading the other screens could not be made to show. What this adds is that
 * an operator looking for trouble does not have to already know which table it would appear in --
 * an unplaced replica, a Service resolving to nothing and a node that stopped heartbeating are
 * three tables apart and one problem.
 *
 * <p>An empty list is stated as a result rather than left as a blank pane, and it says what was
 * checked: "nothing found" is only worth reading if you can tell it apart from "nothing ran".
 */
public final class ScanScreen {

  /** Rows the layout spends on everything that isn't a finding. */
  private static final int CHROME_ROWS = 6;

  private static final int SEVERITY_CELLS = 9;
  private static final int GROUP_CELLS = 11;
  private static final int SUBJECT_CELLS = 30;
  private static final int GAP = 2;

  private final Painter painter;

  public ScanScreen(final Painter painter) {
    this.painter = painter;
  }

  public List<String> render(
      final ClusterSnapshot cluster,
      final ServiceSnapshot services,
      final UiState ui,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    List<ScanFinding> findings = Scan.findings(cluster, services, now, ui.filter());
    List<String> lines = new ArrayList<>();
    lines.add(StatusBar.cluster(painter, cluster, ui, viewport, paused, now));
    lines.add("");
    lines.add(label(findings, ui.filter()));
    lines.add(header(viewport));

    if (findings.isEmpty()) {
      lines.add(
          new Line(painter).add("  " + emptyReason(ui.filter()), Style.fg(Palette.MUTED)).build());
    }

    int available = Math.max(1, viewport.rows() - CHROME_ROWS);
    int first = ClusterScreen.scrollOffset(ui.scanOffset(), findings.size(), available);
    for (int index = first; index < findings.size() && index < first + available; index++) {
      lines.add(findingLine(findings.get(index), viewport));
    }
    if (findings.size() > available) {
      lines.add(
          new Line(painter)
              .add(
                  "  " + (findings.size() - available) + " more, scroll with ↑↓",
                  Style.fg(Palette.MUTED))
              .build());
    }
    return Frame.fitWithKeyBar(lines, StatusBar.scanKeys(painter, ui, viewport), viewport);
  }

  private String label(final List<ScanFinding> findings, final String filter) {
    long errors = count(findings, ScanFinding.Severity.ERROR);
    long warnings = count(findings, ScanFinding.Severity.WARNING);
    Line line =
        new Line(painter)
            .add("SCAN", Style.fg(Palette.HUD).asBold())
            .add(
                "  " + findings.size() + (findings.size() == 1 ? " finding" : " findings"),
                Style.fg(Palette.MUTED_FOREGROUND));
    if (errors > 0) {
      line.add("   " + errors + " to fix now", Style.fg(Palette.BAD));
    }
    if (warnings > 0) {
      line.add("   " + warnings + " to watch", Style.fg(Palette.WARN));
    }
    if (filter != null && !filter.isBlank()) {
      line.add("   filter ", Style.fg(Palette.MUTED_FOREGROUND))
          .add(filter, Style.fg(Palette.PRIMARY));
    }
    return line.build();
  }

  /**
   * A filtered-to-nothing list and a genuinely clean cluster read identically as an empty table,
   * and only one of them is good news.
   */
  private static String emptyReason(final String filter) {
    return filter == null || filter.isBlank()
        ? "nothing found: every node, workload, instance and Service reads as it should"
        : "nothing matches";
  }

  private String header(final Viewport viewport) {
    Style style = Style.fg(Palette.MUTED_FOREGROUND);
    return new Line(painter)
        .cell("SEVERITY", SEVERITY_CELLS, style)
        .pad(GAP)
        .cell("KIND", GROUP_CELLS, style)
        .pad(GAP)
        .cell("SUBJECT", SUBJECT_CELLS, style)
        .pad(GAP)
        .cell("FINDING", detailCells(viewport), style)
        .build();
  }

  private String findingLine(final ScanFinding finding, final Viewport viewport) {
    return new Line(painter)
        .cell(finding.severity().name(), SEVERITY_CELLS, Style.fg(variantOf(finding)).asBold())
        .pad(GAP)
        .cell(finding.group(), GROUP_CELLS, Style.fg(Palette.MUTED_FOREGROUND))
        .pad(GAP)
        .cell(finding.subject(), SUBJECT_CELLS, Style.PLAIN)
        .pad(GAP)
        .cell(finding.detail(), detailCells(viewport), Style.fg(Palette.MUTED_FOREGROUND))
        .build();
  }

  private static StatusVariant variantOf(final ScanFinding finding) {
    return switch (finding.severity()) {
      case ERROR -> StatusVariant.BAD;
      case WARNING -> StatusVariant.WARN;
      case NOTE -> StatusVariant.MUTED;
    };
  }

  private static long count(final List<ScanFinding> findings, final ScanFinding.Severity severity) {
    return findings.stream().filter(finding -> finding.severity() == severity).count();
  }

  private static int detailCells(final Viewport viewport) {
    return Math.max(
        10, viewport.columns() - SEVERITY_CELLS - GROUP_CELLS - SUBJECT_CELLS - 3 * GAP);
  }
}
