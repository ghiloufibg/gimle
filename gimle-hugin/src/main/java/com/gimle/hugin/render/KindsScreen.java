package com.gimle.hugin.render;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ResourceCatalog;
import com.gimle.hugin.model.ResourceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What {@code :} can open, listed rather than guessed at.
 *
 * <p>The prompt takes a name, which is only useful to someone who already knows the names. This is
 * how they learn them, and it is reached the way the question is actually asked: press {@code :},
 * press enter, and the answer is the list. A cluster's own registered kinds are in it too, which is
 * the part no documentation could carry -- they differ per cluster.
 *
 * <p>Not drawn through the resource browser despite looking like a table: this is a client-side
 * list of what can be read, not a collection the control plane serves, and routing it through a
 * snapshot and a poller would mean inventing a read that never happens.
 */
public final class KindsScreen {

  /**
   * Rows the layout spends on everything that isn't a kind: the title, a blank, the label, the line
   * naming what else the prompt takes, the header -- and one held back for the note saying how many
   * kinds did not fit, which is worth a row precisely when there is no room for it.
   */
  private static final int CHROME_ROWS = 7;

  private static final int KEY_CELLS = 22;
  private static final int ROUTE_CELLS = 26;
  private static final int GAP = 2;

  private final Painter painter;

  public KindsScreen(final Painter painter) {
    this.painter = painter;
  }

  public List<String> render(
      final ResourceCatalog catalog,
      final String serverAddress,
      final UiState ui,
      final Viewport viewport) {
    List<String> lines = new ArrayList<>();
    lines.add(title(catalog, serverAddress, ui, viewport));
    lines.add("");
    lines.add(
        new Line(painter)
            .add("KINDS", Style.fg(Palette.HUD).asBold())
            .add("  type one after ", Style.fg(Palette.MUTED_FOREGROUND))
            .add(":", Style.fg(Palette.PRIMARY))
            .add(" to open it", Style.fg(Palette.MUTED_FOREGROUND))
            .build());
    // The prompt takes more than kinds, and a list that showed only kinds would be read as the
    // whole of what ":" accepts.
    lines.add(
        new Line(painter)
            .add("  it also takes ", Style.fg(Palette.MUTED_FOREGROUND))
            .add("scan", Style.fg(Palette.PRIMARY))
            .add(", ", Style.fg(Palette.MUTED_FOREGROUND))
            .add("can", Style.fg(Palette.PRIMARY))
            .add(", ", Style.fg(Palette.MUTED_FOREGROUND))
            .add("ctx <name>", Style.fg(Palette.PRIMARY))
            .add(" and ", Style.fg(Palette.MUTED_FOREGROUND))
            .add("tenant <id>", Style.fg(Palette.PRIMARY))
            .build());
    lines.add(header());

    int available = Math.max(1, viewport.rows() - CHROME_ROWS);
    List<ResourceKind> kinds = catalog.kinds();
    for (ResourceKind kind : kinds.stream().limit(available).toList()) {
      lines.add(kindLine(kind, viewport));
    }
    if (kinds.size() > available) {
      lines.add(
          new Line(painter)
              .add(
                  "  " + (kinds.size() - available) + " more than this window holds",
                  Style.fg(Palette.MUTED))
              .build());
    }
    return Frame.fitWithKeyBar(lines, StatusBar.kindsKeys(painter, viewport), viewport);
  }

  private String title(
      final ResourceCatalog catalog,
      final String serverAddress,
      final UiState ui,
      final Viewport viewport) {
    long custom = catalog.kinds().stream().filter(ResourceKind::custom).count();
    TitleBar bar =
        TitleBar.of(painter, "kinds")
            .subject(serverAddress)
            .scope(ui)
            .stat("kinds", catalog.kinds().size());
    if (custom > 0) {
      bar.stat("registered", custom);
    }
    return bar.build(viewport);
  }

  private String header() {
    Style style = Style.fg(Palette.MUTED_FOREGROUND);
    return new Line(painter)
        .cell("KIND", KEY_CELLS, style)
        .pad(GAP)
        .cell("ROUTE", ROUTE_CELLS, style)
        .pad(GAP)
        .add("WHAT IT LISTS", style)
        .build();
  }

  private String kindLine(final ResourceKind kind, final Viewport viewport) {
    int described = Math.max(10, viewport.columns() - KEY_CELLS - ROUTE_CELLS - 2 * GAP);
    return new Line(painter)
        .cell(kind.key(), KEY_CELLS, Style.fg(Palette.PRIMARY))
        .pad(GAP)
        .cell(kind.route(), ROUTE_CELLS, Style.fg(Palette.MUTED_FOREGROUND))
        .pad(GAP)
        .cell(describe(kind), described, Style.PLAIN)
        .build();
  }

  /**
   * A registered kind says so, because its columns and even its existence are that cluster's own
   * choice -- the same reading on another cluster would be a different list.
   */
  private static String describe(final ResourceKind kind) {
    String label = kind.label().toLowerCase(Locale.ROOT);
    return kind.custom() ? label + "  (registered kind)" : label;
  }
}
