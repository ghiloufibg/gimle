package com.gimle.hugin.render;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ClusterSnapshot;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.ResourceSnapshot;
import com.gimle.hugin.model.ServiceSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
      final UiState ui,
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
    appendTenantScope(line, ui, bar);
    line.add("   nodes ", bar)
        .add(String.valueOf(snapshot.nodes().size()), bar.asBold())
        .add("  instances ", bar)
        .add(String.valueOf(snapshot.instances().size()), bar.asBold())
        .add("  ", bar);
    appendHealthSplit(line, snapshot.instances(), bar);
    // Replicas the scheduler placed nowhere have no instance row, so they are absent from the
    // split above and need saying separately or they go unsaid entirely.
    int unplaced = snapshot.unplacedCount();
    if (unplaced > 0) {
      line.add("   unplaced ", bar)
          .add(String.valueOf(unplaced), Style.fg(Palette.WARN).on(Palette.CARD).asBold());
    }
    if (paused) {
      line.add("   PAUSED", Style.fg(Palette.WARN).on(Palette.CARD).asBold());
    }
    return line.fillTo(viewport.columns(), bar).build();
  }

  static String services(
      final Painter painter,
      final ServiceSnapshot snapshot,
      final UiState ui,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    Style bar = Style.fg(Palette.MUTED_FOREGROUND).on(Palette.CARD);
    Line line =
        new Line(painter)
            .add(" ", bar)
            .add("GIMLÉ", brand())
            .add(" TOP", bar.asBold())
            .add("   SERVICES", bar.asBold())
            .add("   ", bar)
            .add(snapshot.serverAddress(), bar);
    appendConnection(line, snapshot.connected(), snapshot.staleReason(), snapshot.age(now), bar);
    appendTenantScope(line, ui, bar);
    line.add("   services ", bar)
        .add(String.valueOf(snapshot.services().size()), bar.asBold())
        .add("  endpoints ", bar)
        .add(String.valueOf(snapshot.endpointCount()), bar.asBold());
    int unresolved = snapshot.unresolvedCount();
    if (unresolved > 0) {
      line.add("   ", bar)
          .add(unresolved + " unresolved", Style.fg(Palette.BAD).on(Palette.CARD).asBold());
    }
    if (paused) {
      line.add("   PAUSED", Style.fg(Palette.WARN).on(Palette.CARD).asBold());
    }
    return line.fillTo(viewport.columns(), bar).build();
  }

  static String resources(
      final Painter painter,
      final ResourceSnapshot snapshot,
      final UiState ui,
      final Viewport viewport,
      final boolean paused,
      final Instant now) {
    Style bar = Style.fg(Palette.MUTED_FOREGROUND).on(Palette.CARD);
    Line line =
        new Line(painter)
            .add(" ", bar)
            .add("GIMLÉ", brand())
            .add(" TOP", bar.asBold())
            .add("   " + snapshot.kind().key().toUpperCase(Locale.ROOT), bar.asBold())
            .add("   ", bar)
            .add(snapshot.serverAddress(), bar);
    appendConnection(line, snapshot.connected(), snapshot.staleReason(), snapshot.age(now), bar);
    appendTenantScope(line, ui, bar);
    if (snapshot.permitted()) {
      line.add("   " + snapshot.kind().label() + " ", bar)
          .add(String.valueOf(snapshot.rows().size()), bar.asBold());
    } else {
      line.add("   not permitted", Style.fg(Palette.WARN).on(Palette.CARD).asBold());
    }
    if (paused) {
      line.add("   PAUSED", Style.fg(Palette.WARN).on(Palette.CARD).asBold());
    }
    return line.fillTo(viewport.columns(), bar).build();
  }

  /**
   * The tenant in scope, said on the bar of every screen it narrows. Without it a cluster showing
   * one tenant's three instances is indistinguishable from a cluster that has only three, which is
   * the one way this feature could mislead rather than help.
   */
  private static void appendTenantScope(final Line line, final UiState ui, final Style bar) {
    ui.tenantScope()
        .ifPresent(
            tenant ->
                line.add("   tenant ", bar)
                    .add(tenant, Style.fg(Palette.PRIMARY).on(Palette.CARD).asBold()));
  }

  private static Style brand() {
    return Style.fg(Palette.PRIMARY).on(Palette.CARD).asBold();
  }

  private static void appendConnection(
      final Line line, final ClusterSnapshot snapshot, final Style bar, final Instant now) {
    appendConnection(line, snapshot.connected(), snapshot.staleReason(), snapshot.age(now), bar);
  }

  /**
   * The indicator, its wording and, when there is one, the age of the rows behind it -- written
   * against the three readings every snapshot kind exposes rather than against one snapshot type,
   * so a second view's status line cannot drift into saying "stale" differently.
   */
  private static void appendConnection(
      final Line line,
      final boolean connected,
      final Optional<String> staleReason,
      final Optional<Duration> age,
      final Style bar) {
    line.add("  ", bar);
    if (connected) {
      line.add("●", Style.fg(Palette.OK).on(Palette.CARD)).add(" connected", bar);
      return;
    }
    Style warn = Style.fg(Palette.WARN).on(Palette.CARD);
    line.add("●", warn);
    String suffix = age.map(Text::age).map(value -> " " + value + " old").orElse("");
    line.add(" " + staleReason.orElse("disconnected") + suffix, warn);
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

  /**
   * The filter prompt, which replaces whichever key bar is on screen while one is being typed --
   * the filter is one piece of state shared by every screen that has rows to narrow, so it reads
   * and behaves the same on all of them.
   */
  static String filterPrompt(final Painter painter, final UiState ui, final Viewport viewport) {
    Style bar = Style.fg(Palette.FOREGROUND).on(Palette.CARD);
    return new Line(painter)
        .add(" filter: ", Style.fg(Palette.PRIMARY).on(Palette.CARD))
        .add(ui.filter(), bar)
        .add("▁", Style.fg(Palette.PRIMARY).on(Palette.CARD))
        .add("   enter to apply, esc to clear", Style.fg(Palette.MUTED).on(Palette.CARD))
        .fillTo(viewport.columns(), bar)
        .build();
  }

  /**
   * The {@code :} prompt, which replaces the key bar while a kind is being typed -- the same shape
   * the filter prompt takes, so the one line at the bottom of the screen always means "you are
   * typing something" rather than sometimes meaning it.
   */
  static String commandPrompt(final Painter painter, final UiState ui, final Viewport viewport) {
    Style bar = Style.fg(Palette.FOREGROUND).on(Palette.CARD);
    return new Line(painter)
        .add(" :", Style.fg(Palette.PRIMARY).on(Palette.CARD))
        .add(ui.command(), bar)
        .add("▁", Style.fg(Palette.PRIMARY).on(Palette.CARD))
        .add(
            "   kind to open, enter to go, esc to cancel", Style.fg(Palette.MUTED).on(Palette.CARD))
        .fillTo(viewport.columns(), bar)
        .build();
  }

  /**
   * What a rejected {@code :} is reported with, in the same place the prompt that produced it was
   * typed. Shown on whichever screen was open when it happened, since the prompt never left that
   * screen -- an operator who mistypes must not be answered by a view silently not changing.
   */
  private static String commandError(
      final Painter painter, final String message, final Viewport viewport) {
    Style bar = Style.fg(Palette.BAD).on(Palette.CARD);
    return new Line(painter)
        .add(" " + message, bar)
        .add("   any key to dismiss", Style.fg(Palette.MUTED).on(Palette.CARD))
        .fillTo(viewport.columns(), Style.fg(Palette.MUTED_FOREGROUND).on(Palette.CARD))
        .build();
  }

  static String resourceKeys(final Painter painter, final UiState ui, final Viewport viewport) {
    if (ui.commandEditing()) {
      return commandPrompt(painter, ui, viewport);
    }
    if (ui.commandError().isPresent()) {
      return commandError(painter, ui.commandError().get(), viewport);
    }
    if (ui.filterEditing()) {
      return filterPrompt(painter, ui, viewport);
    }
    return keyBar(
        painter,
        viewport,
        List.of("esc back", ": kind", "⏎ describe", "/ filter", "p pause", "? help", "q quit"));
  }

  static String pulseKeys(final Painter painter, final UiState ui, final Viewport viewport) {
    if (ui.commandEditing()) {
      return commandPrompt(painter, ui, viewport);
    }
    return keyBar(
        painter, viewport, List.of("esc back", "p pause", "r refresh", "? help", "q quit"));
  }

  static String xrayKeys(final Painter painter, final UiState ui, final Viewport viewport) {
    if (ui.commandEditing()) {
      return commandPrompt(painter, ui, viewport);
    }
    if (ui.filterEditing()) {
      return filterPrompt(painter, ui, viewport);
    }
    return keyBar(
        painter,
        viewport,
        List.of("esc back", "↑↓ scroll", "/ filter", "p pause", "? help", "q quit"));
  }

  static String scanKeys(final Painter painter, final UiState ui, final Viewport viewport) {
    if (ui.commandEditing()) {
      return commandPrompt(painter, ui, viewport);
    }
    if (ui.commandError().isPresent()) {
      return commandError(painter, ui.commandError().get(), viewport);
    }
    if (ui.filterEditing()) {
      return filterPrompt(painter, ui, viewport);
    }
    return keyBar(
        painter,
        viewport,
        List.of("esc back", "↑↓ scroll", "/ filter", "p pause", "r rescan", "? help", "q quit"));
  }

  static String permissionKeys(final Painter painter, final UiState ui, final Viewport viewport) {
    if (ui.commandEditing()) {
      return commandPrompt(painter, ui, viewport);
    }
    if (ui.filterEditing()) {
      return filterPrompt(painter, ui, viewport);
    }
    return keyBar(
        painter,
        viewport,
        List.of("esc back", "↑↓ scroll", "/ filter", "r re-ask", "? help", "q quit"));
  }

  static String traceKeys(final Painter painter, final UiState ui, final Viewport viewport) {
    if (ui.commandEditing()) {
      return commandPrompt(painter, ui, viewport);
    }
    if (ui.filterEditing()) {
      return filterPrompt(painter, ui, viewport);
    }
    return keyBar(
        painter,
        viewport,
        List.of("esc back", "↑↓ scroll", "/ filter", "p pause", "? help", "q quit"));
  }

  static String kindsKeys(final Painter painter, final Viewport viewport) {
    return keyBar(painter, viewport, List.of("esc back", ": open one", "? help", "q quit"));
  }

  static String describeKeys(final Painter painter, final Viewport viewport) {
    return keyBar(
        painter, viewport, List.of("esc back", "↑↓ scroll", "g/G top/bottom", "? help", "q quit"));
  }

  static String clusterKeys(final Painter painter, final UiState ui, final Viewport viewport) {
    if (ui.commandEditing()) {
      return commandPrompt(painter, ui, viewport);
    }
    if (ui.commandError().isPresent()) {
      return commandError(painter, ui.commandError().get(), viewport);
    }
    if (ui.filterEditing()) {
      return filterPrompt(painter, ui, viewport);
    }
    return keyBar(
        painter,
        viewport,
        // Eighty columns is the budget, and what it buys is every key that reaches a screen an
        // operator could not otherwise find. Everything else is one "?" away: the arrow keys,
        // which nobody needs told move a cursor; "o", whose orderings the instance table's own
        // label already names beside the digits that pick one; "p"/"r", which act on the screen
        // already in front of you rather than leading anywhere; and "d" and "R", each of which
        // reaches a screen ":" already reaches by name.
        List.of(
            "⏎ open",
            "S scan",
            "s svc",
            "x tree",
            "P pulse",
            "a activity",
            ": kind",
            "/ filter",
            "q quit"));
  }

  static String activityKeys(final Painter painter, final UiState ui, final Viewport viewport) {
    if (ui.commandEditing()) {
      return commandPrompt(painter, ui, viewport);
    }
    if (ui.commandError().isPresent()) {
      return commandError(painter, ui.commandError().get(), viewport);
    }
    if (ui.filterEditing()) {
      return filterPrompt(painter, ui, viewport);
    }
    return keyBar(
        painter,
        viewport,
        List.of("esc back", "c feed", "/ filter", "m more", "p pause", "? help", "q quit"));
  }

  static String nodeKeys(final Painter painter, final Viewport viewport) {
    return keyBar(
        painter, viewport, List.of("esc back", "p pause", "r refresh", "? help", "q quit"));
  }

  static String instanceKeys(final Painter painter, final UiState ui, final Viewport viewport) {
    if (ui.filterEditing()) {
      return filterPrompt(painter, ui, viewport);
    }
    return keyBar(
        painter,
        viewport,
        List.of(
            "esc back",
            "c category",
            "/ filter",
            "w wrap",
            "t time",
            "p pause",
            "? help",
            "q quit"));
  }

  static String serviceKeys(final Painter painter, final UiState ui, final Viewport viewport) {
    if (ui.commandEditing()) {
      return commandPrompt(painter, ui, viewport);
    }
    if (ui.commandError().isPresent()) {
      return commandError(painter, ui.commandError().get(), viewport);
    }
    if (ui.filterEditing()) {
      return filterPrompt(painter, ui, viewport);
    }
    return keyBar(
        painter,
        viewport,
        List.of("esc back", "/ filter", "p pause", "r refresh", "? help", "q quit"));
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
