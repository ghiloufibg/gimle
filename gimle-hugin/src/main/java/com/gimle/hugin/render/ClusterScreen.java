package com.gimle.hugin.render;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ClusterSnapshot;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.NodeRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The cluster view: a status line, the node table, the instance table, and a key bar. Rendering is
 * a pure function of the snapshot, the operator's own state and the viewport -- no terminal, no
 * clock of its own, no I/O -- which is why almost all of this can be tested by asserting on
 * strings.
 */
public final class ClusterScreen {

  /** Rows the layout spends on everything that isn't an instance row. */
  private static final int CHROME_ROWS = 8;

  private final Painter painter;

  public ClusterScreen(final Painter painter) {
    this.painter = painter;
  }

  public List<String> render(
      final ClusterSnapshot snapshot,
      final UiState ui,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    List<String> lines = new ArrayList<>();
    lines.add(StatusBar.cluster(painter, snapshot, viewport, paused, now));
    lines.add("");

    List<NodeRow> nodes = snapshot.nodesMatching(ui.filter());
    lines.add(sectionLabel("NODES", nodes.size(), snapshot.nodes().size(), ui.filter()));
    lines.add(nodeHeader(viewport));
    if (nodes.isEmpty()) {
      lines.add(emptyNote(snapshot.nodes().isEmpty() ? "no nodes registered" : "no nodes match"));
    }
    for (NodeRow node : nodes) {
      lines.add(nodeLine(node, viewport, now));
    }
    lines.add("");

    List<InstanceRow> instances = snapshot.instancesMatching(ui.filter());
    lines.add(
        sectionLabel("INSTANCES", instances.size(), snapshot.instances().size(), ui.filter()));
    lines.add(instanceHeader(viewport));

    int available =
        Math.max(1, viewport.rows() - CHROME_ROWS - nodes.size() - (nodes.isEmpty() ? 1 : 0));
    if (instances.isEmpty()) {
      lines.add(
          emptyNote(snapshot.instances().isEmpty() ? "no instances placed" : "no instances match"));
    }
    int selection = ui.selectionIndex(instances);
    int firstVisible = scrollOffset(selection, instances.size(), available);
    for (int index = firstVisible;
        index < Math.min(instances.size(), firstVisible + available);
        index++) {
      lines.add(instanceLine(instances.get(index), index == selection, viewport));
    }

    while (lines.size() < viewport.rows() - 1) {
      lines.add("");
    }
    lines.add(StatusBar.clusterKeys(painter, ui, viewport));
    return Frame.fit(lines, viewport);
  }

  /**
   * Keeps the selected row on screen while scrolling as little as possible: the window only moves
   * when the cursor would otherwise leave it, so a list that fits never scrolls at all.
   */
  static int scrollOffset(final int selection, final int total, final int available) {
    if (total <= available || selection < 0) {
      return 0;
    }
    int centred = selection - available / 2;
    return Math.clamp(centred, 0, total - available);
  }

  private String sectionLabel(
      final String label, final int shown, final int total, final String filter) {
    Line line = new Line(painter).add(label, Style.fg(Palette.HUD).asBold());
    if (!filter.isBlank()) {
      line.padTo(label.length() + 4)
          .add("filter ", Style.fg(Palette.MUTED_FOREGROUND))
          .add(filter, Style.fg(Palette.PRIMARY))
          .add("  " + shown + " of " + total, Style.fg(Palette.MUTED_FOREGROUND));
    }
    return line.build();
  }

  private String emptyNote(final String message) {
    return new Line(painter).add("  " + message, Style.fg(Palette.MUTED)).build();
  }

  // ---- nodes ----

  private String nodeHeader(final Viewport viewport) {
    NodeLayout layout = NodeLayout.forWidth(viewport.columns());
    Style style = Style.fg(Palette.MUTED_FOREGROUND);
    return new Line(painter)
        .cell("ID", layout.id(), style)
        .pad(2)
        .cell("STATE", layout.state(), style)
        .pad(2)
        .cell("CPU", layout.cpu(), style)
        .pad(2)
        .cell("MEMORY", layout.memory(), style)
        .pad(2)
        .rightCell("INST", layout.instances(), style)
        .pad(2)
        .cell("HEARTBEAT", layout.heartbeat(), style)
        .build();
  }

  private String nodeLine(final NodeRow node, final Viewport viewport, final Instant now) {
    NodeLayout layout = NodeLayout.forWidth(viewport.columns());
    String state = node.state(now);
    Line line =
        new Line(painter)
            .cell(node.nodeId(), layout.id())
            .pad(2)
            .cell(state, layout.state(), Style.fg(StatusVariant.ofNodeState(state)))
            .pad(2);
    resourceGauge(
        line,
        node.hasCapacity()
            ? Text.millicores(node.assignedCpuMillicores())
                + "/"
                + Text.millicores(node.totalCpuMillicores())
            : Text.ABSENT,
        Text.fraction(node.assignedCpuMillicores(), node.totalCpuMillicores()),
        layout.cpu());
    line.pad(2);
    resourceGauge(
        line,
        node.hasCapacity()
            ? Text.gibibytes(node.assignedMemoryBytes())
                + "/"
                + Text.gibibytes(node.totalMemoryBytes())
                + "Gi"
            : Text.ABSENT,
        Text.fraction(node.assignedMemoryBytes(), node.totalMemoryBytes()),
        layout.memory());
    return line.pad(2)
        .rightCell(String.valueOf(node.instanceCount()), layout.instances(), Style.PLAIN)
        .pad(2)
        .cell(
            node.heartbeatAge(now).map(Text::age).orElse(Text.ABSENT),
            layout.heartbeat(),
            Style.fg(node.isStale(now) ? Palette.WARN : Palette.MUTED_FOREGROUND))
        .build();
  }

  /** A "used/total" reading, right-aligned against its own bar so the bars line up as a column. */
  private void resourceGauge(
      final Line line, final String reading, final double fraction, final int cells) {
    int barCells = Math.min(8, Math.max(3, cells / 3));
    int labelCells = cells - barCells - 1;
    line.rightCell(reading, labelCells, Style.PLAIN).pad(1);
    Gauge.draw(line, fraction, barCells);
  }

  // ---- instances ----

  private String instanceHeader(final Viewport viewport) {
    InstanceLayout layout = InstanceLayout.forWidth(viewport.columns());
    Style style = Style.fg(Palette.MUTED_FOREGROUND);
    return new Line(painter)
        .cell("DEPLOYMENT", layout.deployment(), style)
        .pad(layout.gap())
        .rightCell("IDX", layout.index(), style)
        .pad(layout.gap())
        .cell("NODE", layout.node(), style)
        .pad(layout.gap())
        .cell("STATE", layout.state(), style)
        .pad(layout.gap())
        .cell("RDY", layout.ready(), style)
        .pad(layout.gap())
        .rightCell("REQ/S", layout.rate(), style)
        .pad(layout.gap())
        .rightCell("ERR/S", layout.errors(), style)
        .pad(layout.gap())
        .rightCell("QUEUE", layout.queue(), style)
        .pad(layout.gap())
        .rightCell("MEM", layout.memory(), style)
        .pad(layout.gap())
        .rightCell("CPU", layout.cpu(), style)
        .build();
  }

  private String instanceLine(
      final InstanceRow row, final boolean selected, final Viewport viewport) {
    InstanceLayout layout = InstanceLayout.forWidth(viewport.columns());
    // A selected row is drawn in one flat highlight rather than per-cell colour: two colour
    // dimensions at once (state colour over a selection background) reads as noise at terminal
    // contrast, and losing the state colour on exactly one row costs nothing -- the same row's
    // state is spelled out in words right there.
    if (selected) {
      Style style = Style.fg(Palette.FOREGROUND).on(Palette.SELECTION);
      return instanceCells(row, layout, style, style, style)
          .fillTo(viewport.columns(), style)
          .build();
    }
    return instanceCells(
            row,
            layout,
            Style.PLAIN,
            Style.fg(StatusVariant.ofLifecycleState(row.lifecycleState())),
            Style.PLAIN)
        .build();
  }

  private Line instanceCells(
      final InstanceRow row,
      final InstanceLayout layout,
      final Style base,
      final Style stateStyle,
      final Style metricStyle) {
    Line line =
        new Line(painter)
            .cell(row.deploymentName(), layout.deployment(), base)
            .pad(layout.gap())
            .rightCell(String.valueOf(row.instanceIndex()), layout.index(), base)
            .pad(layout.gap())
            .cell(row.nodeId(), layout.node(), base)
            .pad(layout.gap())
            .cell(row.lifecycleState(), layout.state(), stateStyle)
            .pad(layout.gap())
            .cell(readyGlyph(row), layout.ready(), stateStyle)
            .pad(layout.gap());
    if (!row.observed()) {
      // Nothing has been measured yet, so no metric column shows a number: a zero here would read
      // as "idle" about an instance nobody has heard from.
      return line.rightCell(Text.ABSENT, layout.rate(), metricStyle)
          .pad(layout.gap())
          .rightCell(Text.ABSENT, layout.errors(), metricStyle)
          .pad(layout.gap())
          .rightCell(Text.ABSENT, layout.queue(), metricStyle)
          .pad(layout.gap())
          .rightCell(Text.ABSENT, layout.memory(), metricStyle)
          .pad(layout.gap())
          .rightCell(Text.ABSENT, layout.cpu(), metricStyle);
    }
    return line.rightCell(Text.rate(row.requestRatePerSecond()), layout.rate(), metricStyle)
        .pad(layout.gap())
        .rightCell(
            Text.rate(row.errorRatePerSecond()),
            layout.errors(),
            row.errorRatePerSecond() > 0 ? errorStyle(metricStyle) : metricStyle)
        .pad(layout.gap())
        .rightCell(String.valueOf(row.queueDepth()), layout.queue(), metricStyle)
        .pad(layout.gap())
        .rightCell(Text.bytes(row.memoryBytesUsed()), layout.memory(), metricStyle)
        .pad(layout.gap())
        .rightCell(Text.millicores(row.cpuMillicoresUsed()), layout.cpu(), metricStyle);
  }

  private static Style errorStyle(final Style metricStyle) {
    return metricStyle.background().isPresent() ? metricStyle : Style.fg(Palette.BAD);
  }

  private static String readyGlyph(final InstanceRow row) {
    if (!row.observed()) {
      return "·";
    }
    if (row.ready()) {
      return "✓";
    }
    return row.alive() ? "·" : "✗";
  }
}
