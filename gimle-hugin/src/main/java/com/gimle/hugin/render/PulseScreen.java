package com.gimle.hugin.render;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ClusterSnapshot;
import com.gimle.hugin.model.PulseSnapshot;
import com.gimle.hugin.model.WorkloadRow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One screen answering "is this cluster all right", from the two readings that together say so: the
 * control plane's own account of itself, and the state of what it is running.
 *
 * <p>Both are needed. A control plane that has lost its store still answers every list route from
 * nothing, so a cluster view alone would look serene; a healthy control plane says nothing about
 * instances crash-looping under it. Neither reading catches the other's failure.
 *
 * <p>Nothing here is a number an operator has to interpret. Every line reads as a judgement in
 * words -- healthy, or what is wrong -- because a screen opened to answer one question should
 * answer it, not present the evidence for it.
 */
public final class PulseScreen {

  /** How many busiest/erroring deployments a block will list before it stops. */
  private static final int TOP = 5;

  private static final int LABEL_CELLS = 22;

  private final Painter painter;

  public PulseScreen(final Painter painter) {
    this.painter = painter;
  }

  public List<String> render(
      final PulseSnapshot pulse,
      final ClusterSnapshot cluster,
      final UiState ui,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    List<String> lines = new ArrayList<>();
    lines.add(statusLine(pulse, ui, viewport, paused, now));
    lines.add("");
    lines.addAll(controlPlaneBlock(pulse));
    lines.add("");
    lines.addAll(clusterBlock(cluster, now));
    lines.add("");
    lines.addAll(trafficBlock(pulse));
    return Frame.fitWithKeyBar(lines, StatusBar.pulseKeys(painter, ui, viewport), viewport);
  }

  private String statusLine(
      final PulseSnapshot pulse,
      final UiState ui,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    return TitleBar.of(painter, "pulse")
        .subject(pulse.serverAddress())
        .connection(pulse.connected(), pulse.staleReason(), pulse.age(now))
        .scope(ui)
        .paused(paused)
        .build(viewport);
  }

  private List<String> controlPlaneBlock(final PulseSnapshot pulse) {
    List<String> lines = new ArrayList<>();
    lines.add(sectionLabel("CONTROL PLANE"));
    StatusVariant variant =
        switch (pulse.status()) {
          case "UP" -> StatusVariant.OK;
          case "DOWN", "UNREACHABLE" -> StatusVariant.BAD;
          default -> StatusVariant.WARN;
        };
    lines.add(reading("status", pulse.status(), variant));
    pulse.reason().ifPresent(reason -> lines.add(reading("reason", reason, StatusVariant.BAD)));
    if (pulse.healthy()) {
      lines.add(
          reading(
              "uptime", Text.age(Duration.ofSeconds(pulse.uptimeSeconds())), StatusVariant.MUTED));
      lines.add(reading("transport", pulse.transportProtocol(), StatusVariant.MUTED));
      lines.add(
          reading(
              "tenants in store", String.valueOf(pulse.storeTenantCount()), StatusVariant.MUTED));
    }
    return lines;
  }

  private List<String> clusterBlock(final ClusterSnapshot cluster, final Instant now) {
    List<String> lines = new ArrayList<>();
    lines.add(sectionLabel("CLUSTER"));

    long nodesReady =
        cluster.nodes().stream().filter(node -> "READY".equals(node.state(now))).count();
    lines.add(
        reading(
            "nodes ready",
            nodesReady + " of " + cluster.nodes().size(),
            nodesReady == cluster.nodes().size() && !cluster.nodes().isEmpty()
                ? StatusVariant.OK
                : StatusVariant.WARN));

    long bad =
        cluster.instances().stream()
            .filter(
                row -> StatusVariant.ofLifecycleState(row.lifecycleState()) == StatusVariant.BAD)
            .count();
    long notReady = cluster.instances().stream().filter(row -> !row.ready()).count();
    lines.add(
        reading(
            "instances failed",
            String.valueOf(bad),
            bad == 0 ? StatusVariant.OK : StatusVariant.BAD));
    lines.add(
        reading(
            "instances not ready",
            String.valueOf(notReady),
            notReady == 0 ? StatusVariant.OK : StatusVariant.WARN));

    int unplaced = cluster.unplacedCount();
    lines.add(
        reading(
            "replicas unplaced",
            String.valueOf(unplaced),
            unplaced == 0 ? StatusVariant.OK : StatusVariant.BAD));

    List<WorkloadRow> unsettled = cluster.unsettledWorkloads();
    lines.add(
        reading(
            "workloads unsettled",
            String.valueOf(unsettled.size()),
            unsettled.isEmpty() ? StatusVariant.OK : StatusVariant.WARN));
    return lines;
  }

  private List<String> trafficBlock(final PulseSnapshot pulse) {
    List<String> lines = new ArrayList<>();
    lines.add(sectionLabel("TRAFFIC"));
    if (pulse.traffic().isEmpty()) {
      // Absent because it could not be read, not because nothing is serving: this rollup is gated
      // on its own permission, and reporting an empty cluster for a refused read would be a lie.
      lines.add(muted("  no per-deployment rollup readable"));
      return lines;
    }
    List<PulseSnapshot.DeploymentTraffic> erroring = pulse.erroring();
    if (erroring.isEmpty()) {
      lines.add(reading("erroring", "none", StatusVariant.OK));
    } else {
      for (PulseSnapshot.DeploymentTraffic row : erroring.stream().limit(TOP).toList()) {
        lines.add(
            reading(
                row.deploymentName(),
                Text.rate(row.errorRatePerSecond()) + " err/s",
                StatusVariant.BAD));
      }
    }
    for (PulseSnapshot.DeploymentTraffic row : pulse.busiestFirst().stream().limit(TOP).toList()) {
      lines.add(
          reading(
              row.deploymentName(),
              Text.rate(row.requestRatePerSecond()) + " req/s over " + row.instanceCount(),
              StatusVariant.MUTED));
    }
    return lines;
  }

  private String reading(final String label, final String value, final StatusVariant variant) {
    return new Line(painter)
        .pad(2)
        .cell(label, LABEL_CELLS, Style.fg(Palette.MUTED_FOREGROUND))
        .add(value, Style.fg(variant))
        .build();
  }

  private String sectionLabel(final String label) {
    return new Line(painter).add(label, Style.fg(Palette.HUD).asBold()).build();
  }

  private String muted(final String message) {
    return new Line(painter).add(message, Style.fg(Palette.MUTED)).build();
  }
}
