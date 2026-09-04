package com.gimle.hugin.render;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code ?} overlay: every binding, and the one sentence that matters most about this tool --
 * that it cannot change anything. Drawn as a full replacement screen rather than a floating box: a
 * terminal has no compositing, and a box drawn over a live table has to be un-drawn correctly on
 * every refresh, which is a whole class of redraw bug for no benefit.
 */
public final class HelpOverlay {

  private static final List<String[]> BINDINGS =
      List.of(
          new String[] {"↑ ↓ / j k", "move the selection"},
          new String[] {"⏎", "inspect whatever the cursor is on"},
          new String[] {"tab", "move the cursor between the node and instance tables"},
          new String[] {"s", "services and the endpoints they resolve to"},
          new String[] {"x", "the dependency tree: service → deployment → instance"},
          new String[] {"P", "one-screen health: the control plane and what it runs"},
          new String[] {"esc", "back to the cluster view"},
          new String[] {"a", "cluster activity; c switches audit / lifecycle / alerts"},
          new String[] {":", "open a kind: tenants, roles, volumes, a registered kind…"},
          new String[] {": (empty)", "list every kind this cluster can show"},
          new String[] {":ctx NAME", "point at another control plane, by context or host:port"},
          new String[] {"o", "cycle the sort: name, state, then each metric worst-first"},
          new String[] {"1 … 9", "sort by that column outright, on the focused table"},
          new String[] {"d", "describe the workload behind the selected instance"},
          new String[] {"/", "filter; enter applies, esc clears — tables and the log tail alike"},
          new String[] {"p", "pause / resume refresh"},
          new String[] {"r", "refresh now"},
          new String[] {"c", "cycle the log category"},
          new String[] {"w / t", "wrap long log lines / show the clock column"},
          new String[] {"⏎ (kind)", "describe the selected resource as YAML"},
          new String[] {"g / G", "jump to the top / bottom"},
          new String[] {"?", "this help"},
          new String[] {"q / ctrl-c", "quit, restoring the terminal"});

  private final Painter painter;

  public HelpOverlay(final Painter painter) {
    this.painter = painter;
  }

  public List<String> render(final Viewport viewport) {
    List<String> lines = new ArrayList<>();
    lines.add(
        new Line(painter)
            .add(" GIMLÉ TOP", Style.fg(Palette.PRIMARY).on(Palette.CARD).asBold())
            .add("  keys", Style.fg(Palette.MUTED_FOREGROUND).on(Palette.CARD))
            .fillTo(viewport.columns(), Style.fg(Palette.MUTED_FOREGROUND).on(Palette.CARD))
            .build());
    lines.add("");
    for (String[] binding : BINDINGS) {
      lines.add(
          new Line(painter)
              .pad(2)
              .cell(binding[0], 14, Style.fg(Palette.PRIMARY))
              .pad(2)
              .add(binding[1], Style.fg(Palette.FOREGROUND))
              .build());
    }
    lines.add("");
    lines.add(
        new Line(painter)
            .pad(2)
            .add("This view is read-only.", Style.fg(Palette.HUD).asBold())
            .add(
                " Nothing here can cordon, scale, delete or roll back anything;",
                Style.fg(Palette.MUTED_FOREGROUND))
            .build());
    lines.add(
        new Line(painter)
            .pad(2)
            .add(
                "it sees exactly what your own certificate already permits on a GET.",
                Style.fg(Palette.MUTED_FOREGROUND))
            .build());

    String keyBar =
        new Line(painter)
            .add(" ", Style.fg(Palette.MUTED_FOREGROUND).on(Palette.CARD))
            .add("any key", Style.fg(Palette.PRIMARY).on(Palette.CARD).asBold())
            .add(" to close", Style.fg(Palette.MUTED_FOREGROUND).on(Palette.CARD))
            .fillTo(viewport.columns(), Style.fg(Palette.MUTED_FOREGROUND).on(Palette.CARD))
            .build();
    return Frame.fitWithKeyBar(lines, keyBar, viewport);
  }
}
