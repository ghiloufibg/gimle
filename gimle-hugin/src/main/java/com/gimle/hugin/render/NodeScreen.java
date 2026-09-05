package com.gimle.hugin.render;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ClusterSnapshot;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.NodeRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One node's drill-down: what it is, what it will accept, and what is actually running on it.
 *
 * <p>Unlike the instance drill-down this needs no reads of its own -- every field here is already
 * in the {@code GET /nodes} response the cluster view polls, and the instance list is the same
 * snapshot filtered by node. So it costs nothing to open and cannot fail separately from the view
 * that opened it.
 */
public final class NodeScreen {

  private final Painter painter;

  public NodeScreen(final Painter painter) {
    this.painter = painter;
  }

  public List<String> render(
      final NodeRow node,
      final ClusterSnapshot snapshot,
      final UiState ui,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    List<String> lines = new ArrayList<>();
    lines.add(header(node, ui, viewport, paused, now));
    lines.add("");
    lines.addAll(detail(node, now));
    lines.add("");

    List<InstanceRow> hosted =
        snapshot.instances().stream().filter(row -> row.nodeId().equals(node.nodeId())).toList();
    lines.add(
        new Line(painter)
            .add("INSTANCES HERE", Style.fg(Palette.HUD).asBold())
            .add("  " + hosted.size(), Style.fg(Palette.MUTED_FOREGROUND))
            .build());
    if (hosted.isEmpty()) {
      lines.add(new Line(painter).add("  nothing placed here", Style.fg(Palette.MUTED)).build());
    }
    for (InstanceRow row : hosted) {
      lines.add(hostedLine(row, viewport));
    }

    return Frame.fitWithKeyBar(lines, StatusBar.nodeKeys(painter, viewport), viewport);
  }

  private String header(
      final NodeRow node,
      final UiState ui,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    String state = node.state();
    return TitleBar.of(painter, "node")
        .subject(node.nodeId())
        .scope(ui)
        .badge(state, StatusVariant.ofNodeState(state))
        .paused(paused)
        .build(viewport);
  }

  private List<String> detail(final NodeRow node, final Instant now) {
    List<String> lines = new ArrayList<>();
    lines.add(sectionLabel("NODE"));
    lines.add(field("state", node.state(), Style.fg(StatusVariant.ofNodeState(node.state()))));
    lines.add(
        field(
            "heartbeat",
            node.heartbeatAge(now).map(Text::age).map(age -> age + " ago").orElse(Text.ABSENT),
            Style.fg(node.isStale() ? Palette.WARN : Palette.MUTED_FOREGROUND)));
    lines.add("");
    lines.add(sectionLabel("CAPACITY"));
    if (!node.hasCapacity()) {
      lines.add(
          new Line(painter)
              .add("  this node has never reported capacity", Style.fg(Palette.MUTED))
              .build());
    } else {
      lines.add(
          capacityField(
              "cpu",
              Text.millicores(node.assignedCpuMillicores())
                  + " of "
                  + Text.millicores(node.totalCpuMillicores()),
              Text.fraction(node.assignedCpuMillicores(), node.totalCpuMillicores())));
      lines.add(
          capacityField(
              "memory",
              Text.gibibytes(node.assignedMemoryBytes())
                  + " of "
                  + Text.gibibytes(node.totalMemoryBytes())
                  + "Gi",
              Text.fraction(node.assignedMemoryBytes(), node.totalMemoryBytes())));
    }
    lines.add("");
    lines.add(sectionLabel("ACCEPTS"));
    lines.add(field("tiers", joinOrAbsent(node.supportedTiers()), Style.PLAIN));
    lines.add(field("labels", joinOrAbsent(node.labels()), Style.PLAIN));
    // A taint is the reason a node is skipped for a tenant it would otherwise fit, so it is
    // called out in the warning colour rather than listed as another neutral attribute.
    lines.add(
        field(
            "taints",
            joinOrAbsent(node.taints()),
            node.taints().isEmpty() ? Style.fg(Palette.MUTED) : Style.fg(Palette.WARN)));
    return lines;
  }

  private String capacityField(final String label, final String reading, final double fraction) {
    Line line =
        new Line(painter)
            .cell(label, 10, Style.fg(Palette.MUTED_FOREGROUND))
            .pad(2)
            .cell(reading, 22, Style.PLAIN)
            .pad(2);
    Gauge.draw(line, fraction, 16);
    return line.build();
  }

  private String hostedLine(final InstanceRow row, final Viewport viewport) {
    Line line =
        new Line(painter)
            .pad(2)
            .cell(row.deploymentName(), 26, Style.PLAIN)
            .pad(2)
            .rightCell(String.valueOf(row.instanceIndex()), 3, Style.PLAIN)
            .pad(2)
            .cell(
                row.lifecycleState(),
                11,
                Style.fg(StatusVariant.ofLifecycleState(row.lifecycleState())))
            .pad(2);
    int remaining = Math.max(6, viewport.columns() - line.width());
    return line.cell(row.moduleCoordinate().orElse(Text.ABSENT), remaining, Style.PLAIN).build();
  }

  private static String joinOrAbsent(final List<String> values) {
    return values.isEmpty() ? Text.ABSENT : String.join("  ", values);
  }

  private String field(final String label, final String value, final Style style) {
    return new Line(painter)
        .cell(label, 10, Style.fg(Palette.MUTED_FOREGROUND))
        .pad(2)
        .add(value, style)
        .build();
  }

  private String sectionLabel(final String label) {
    return new Line(painter).add(label, Style.fg(Palette.HUD).asBold()).build();
  }
}
