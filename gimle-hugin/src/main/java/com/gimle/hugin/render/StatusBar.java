package com.gimle.hugin.render;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ClusterSnapshot;
import com.gimle.hugin.model.InstanceRow;
import java.time.Instant;
import java.util.List;

/**
 * The two bars that frame every screen: a status line across the top and a key hint line across the
 * bottom, both filled to the full terminal width in the console's own card colour.
 *
 * <p>The connection indicator carries no information the words next to it don't -- "connected" and
 * "stale 8s" read the same with colour switched off, which is the whole no-colour contract.
 */
final class StatusBar {

  private StatusBar() {}

  static String cluster(
      final Painter painter,
      final ClusterSnapshot snapshot,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    Style bar = Style.fg(Palette.MUTED_FOREGROUND).on(Palette.CARD);
    Line line =
        new Line(painter)
            .add(" ", bar)
            .add("GIMLÉ", brand())
            .add(" TOP", bar.asBold())
            .add("   ", bar)
            .add(snapshot.serverAddress(), bar);
    appendConnection(line, snapshot, bar, now);
    line.add("   nodes ", bar)
        .add(String.valueOf(snapshot.nodes().size()), bar.asBold())
        .add("  instances ", bar)
        .add(String.valueOf(snapshot.instances().size()), bar.asBold())
        .add("  ", bar);
    appendHealthSplit(line, snapshot.instances(), bar);
    if (paused) {
      line.add("   PAUSED", Style.fg(Palette.WARN).on(Palette.CARD).asBold());
    }
    return line.fillTo(viewport.columns(), bar).build();
  }

  private static Style brand() {
    return Style.fg(Palette.PRIMARY).on(Palette.CARD).asBold();
  }

  private static void appendConnection(
      final Line line, final ClusterSnapshot snapshot, final Style bar, final Instant now) {
    line.add("  ", bar);
    if (snapshot.connected()) {
      line.add("●", Style.fg(Palette.OK).on(Palette.CARD)).add(" connected", bar);
      return;
    }
    String reason = snapshot.staleReason().orElse("disconnected");
    Style warn = Style.fg(Palette.WARN).on(Palette.CARD);
    line.add("●", warn);
    String age = snapshot.age(now).map(Text::age).map(value -> " " + value + " old").orElse("");
    line.add(" " + reason + age, warn);
  }

  /**
   * The ok / warn / bad instance split, the same three-way rollup the console's overview shows.
   * Written as three separately-coloured numbers rather than one string so each keeps its own
   * colour when there is colour to be had, and summing to the instance count so the line reads as a
   * whole rather than as three unrelated tallies.
   */
  private static void appendHealthSplit(
      final Line line, final List<InstanceRow> instances, final Style bar) {
    int ok = 0;
    int warn = 0;
    int bad = 0;
    for (InstanceRow instance : instances) {
      switch (StatusVariant.ofLifecycleState(instance.lifecycleState())) {
        case OK -> ok++;
        case BAD -> bad++;
        // Everything else -- STARTING, STOPPING, still INSTALLED, not yet observed at all -- is
        // counted here, so the three numbers always sum to the instance count and an operator can
        // read "settled or not" off the line without doing arithmetic.
        default -> warn++;
      }
    }
    line.add(String.valueOf(ok), Style.fg(Palette.OK).on(Palette.CARD))
        .add("/", bar)
        .add(String.valueOf(warn), Style.fg(Palette.WARN).on(Palette.CARD))
        .add("/", bar)
        .add(String.valueOf(bad), Style.fg(Palette.BAD).on(Palette.CARD));
  }

  static String clusterKeys(final Painter painter, final UiState ui, final Viewport viewport) {
    if (ui.filterEditing()) {
      Style bar = Style.fg(Palette.FOREGROUND).on(Palette.CARD);
      return new Line(painter)
          .add(" filter: ", Style.fg(Palette.PRIMARY).on(Palette.CARD))
          .add(ui.filter(), bar)
          .add("▁", Style.fg(Palette.PRIMARY).on(Palette.CARD))
          .add("   enter to apply, esc to clear", Style.fg(Palette.MUTED).on(Palette.CARD))
          .fillTo(viewport.columns(), bar)
          .build();
    }
    return keyBar(
        painter,
        viewport,
        List.of("↑↓ move", "⏎ inspect", "/ filter", "p pause", "r refresh", "? help", "q quit"));
  }

  static String instanceKeys(final Painter painter, final Viewport viewport) {
    return keyBar(
        painter,
        viewport,
        List.of("esc back", "c category", "p pause", "g/G top/bottom", "? help", "q quit"));
  }

  /** Each hint's leading key glyphs in the primary colour, its wording in the bar's own. */
  private static String keyBar(
      final Painter painter, final Viewport viewport, final List<String> hints) {
    Style bar = Style.fg(Palette.MUTED_FOREGROUND).on(Palette.CARD);
    Style key = Style.fg(Palette.PRIMARY).on(Palette.CARD).asBold();
    Line line = new Line(painter).add(" ", bar);
    for (String hint : hints) {
      int split = hint.indexOf(' ');
      line.add(hint.substring(0, split), key).add(hint.substring(split) + "  ", bar);
    }
    return line.fillTo(viewport.columns(), bar).build();
  }
}
