package com.gimle.hugin.render;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ServiceRow;
import com.gimle.hugin.model.ServiceSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The services view: every declared Service, what it fronts, and how many live endpoints it
 * currently resolves to. Like the other screens, a pure function of the snapshot and the viewport.
 *
 * <p>What the table is for is the Service that resolves to nothing -- one naming deployments that
 * do not exist, or whose backing instances are all down. That reads exactly as a failed instance
 * does, in the same bad token and, since nothing here is ever colour-only, in words: the state
 * column says {@code NO ENDPOINTS} whether or not the terminal has colour to give it.
 */
public final class ServiceScreen {

  /** Rows the layout spends on everything that isn't a service row. */
  private static final int CHROME_ROWS = 6;

  private final Painter painter;

  public ServiceScreen(final Painter painter) {
    this.painter = painter;
  }

  public List<String> render(
      final ServiceSnapshot snapshot,
      final UiState ui,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    List<String> lines = new ArrayList<>();
    lines.add(StatusBar.services(painter, snapshot, viewport, paused, now));
    lines.add("");
    lines.add(sectionLabel(snapshot));
    lines.add(header(viewport));

    List<ServiceRow> services = snapshot.matching(ui.filter());
    if (services.isEmpty()) {
      lines.add(muted("  no services declared"));
    }
    int available = Math.max(1, viewport.rows() - CHROME_ROWS);
    for (ServiceRow service : services.stream().limit(available).toList()) {
      lines.add(serviceLine(service, viewport));
    }
    if (services.size() > available) {
      // Said out loud rather than left to the frame's own silent cut: a table that stops without
      // saying so reads as a complete list of the cluster's Services, which it isn't.
      lines.add(muted("  " + (services.size() - available) + " more below this window"));
    }

    return Frame.fitWithKeyBar(lines, StatusBar.serviceKeys(painter, ui, viewport), viewport);
  }

  private String sectionLabel(final ServiceSnapshot snapshot) {
    Line line =
        new Line(painter)
            .add("SERVICES", Style.fg(Palette.HUD).asBold())
            .add("  " + snapshot.services().size(), Style.fg(Palette.MUTED_FOREGROUND));
    int unresolved = snapshot.unresolvedCount();
    if (unresolved > 0) {
      line.add("   " + unresolved + " with no endpoints", Style.fg(Palette.BAD));
    }
    return line.build();
  }

  private String header(final Viewport viewport) {
    ServiceLayout layout = ServiceLayout.forWidth(viewport.columns());
    Style style = Style.fg(Palette.MUTED_FOREGROUND);
    return new Line(painter)
        .cell("NAME", layout.name(), style)
        .pad(layout.gap())
        .cell("TENANT", layout.tenant(), style)
        .pad(layout.gap())
        .cell("PORT", layout.port(), style)
        .pad(layout.gap())
        .cell("PROTO", layout.protocol(), style)
        .pad(layout.gap())
        .cell("STATE", layout.state(), style)
        .pad(layout.gap())
        .rightCell("EPS", layout.endpoints(), style)
        .pad(layout.gap())
        .cell("BACKING", layout.deployments(), style)
        .build();
  }

  private String serviceLine(final ServiceRow row, final Viewport viewport) {
    ServiceLayout layout = ServiceLayout.forWidth(viewport.columns());
    Style stateStyle = Style.fg(variantOf(row));
    Style muted = Style.fg(Palette.MUTED_FOREGROUND);
    return new Line(painter)
        .cell(row.name(), layout.name())
        .pad(layout.gap())
        .cell(row.tenantId().orElse(Text.ABSENT), layout.tenant(), muted)
        .pad(layout.gap())
        .cell(portText(row), layout.port())
        .pad(layout.gap())
        .cell(row.protocol(), layout.protocol(), muted)
        .pad(layout.gap())
        .cell(row.state(), layout.state(), stateStyle)
        .pad(layout.gap())
        .rightCell(endpointText(row), layout.endpoints(), stateStyle)
        .pad(layout.gap())
        .cell(backingText(row), layout.deployments())
        .build();
  }

  /**
   * A Service resolving to nothing reads exactly as a failed instance does -- it is declared, and
   * no call to it can land. An unknown count is muted rather than bad: the read failed, which says
   * nothing about the Service itself.
   */
  private static StatusVariant variantOf(final ServiceRow row) {
    if (row.endpointCount().isEmpty()) {
      return StatusVariant.MUTED;
    }
    return row.unresolved() ? StatusVariant.BAD : StatusVariant.OK;
  }

  private static String endpointText(final ServiceRow row) {
    return row.endpointCount().isPresent()
        ? String.valueOf(row.endpointCount().getAsInt())
        : Text.ABSENT;
  }

  /**
   * {@code port→targetPort}. An undeclared target port is drawn as {@code auto} rather than
   * repeated from the left of the arrow: it means "whatever single port the instance reports",
   * which is a different thing from a target port that happens to equal the dialled one.
   */
  private static String portText(final ServiceRow row) {
    if (row.port() <= 0) {
      return Text.ABSENT;
    }
    String target =
        row.targetPort().isPresent() ? String.valueOf(row.targetPort().getAsInt()) : "auto";
    return row.port() + "→" + target;
  }

  /** What the Service fronts: the deployments it names, or the external host it aliases. */
  private static String backingText(final ServiceRow row) {
    if (row.external()) {
      return "→ " + row.externalName().orElseThrow();
    }
    return row.deploymentNames().isEmpty() ? Text.ABSENT : String.join(",", row.deploymentNames());
  }

  private String muted(final String message) {
    return new Line(painter).add(message, Style.fg(Palette.MUTED)).build();
  }
}
