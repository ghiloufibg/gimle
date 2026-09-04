package com.gimle.hugin.render;

import com.gimle.hugin.UiState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The bar across the top of every screen, built once here rather than at each screen.
 *
 * <p>What a screen supplies is what it knows -- its name, what it is about, whether its own read is
 * current, its own counts. What it never chooses is the order those appear in, the spacing between
 * them, or how any of them is coloured: a bar assembled at ten call sites drifts, and a status line
 * that reads differently depending on which screen you are on is worse than a plain one, because
 * the difference looks like it means something.
 *
 * <p>The order is fixed and reads left to right as the question an operator is asking: what am I
 * looking at, what is it about, is it current, what is it narrowed to, what does it add up to, and
 * finally what is wrong with it -- the last two positions being the ones the eye goes to.
 *
 * <p>The cluster view alone carries no screen name. It is the one you return to with {@code esc}
 * rather than one you go to, and at eighty columns the ten cells a name would cost are cells its
 * own instance-health counts need.
 */
public final class TitleBar {

  /** One piece of a stat's value, and the colour it carries if it means something on its own. */
  private record Part(String text, Optional<StatusVariant> variant) {}

  /**
   * A named number: how many of something this screen is showing. Its value is a list of parts
   * rather than a string so that a value made of several counts -- the instance health split --
   * keeps a colour per count while still being assembled here with every other stat.
   */
  private record Stat(String label, List<Part> parts) {}

  /** A word standing on its own, in the colour of what it means -- a state, or a fault. */
  private record Badge(String text, StatusVariant variant) {}

  private final Painter painter;
  private final String screen;
  private final List<Stat> stats = new ArrayList<>();
  private final List<Badge> badges = new ArrayList<>();

  private Optional<String> subject = Optional.empty();
  private Optional<Boolean> connected = Optional.empty();
  private Optional<String> staleReason = Optional.empty();
  private Optional<Duration> age = Optional.empty();
  private Optional<String> scope = Optional.empty();
  private boolean paused;

  private TitleBar(final Painter painter, final String screen) {
    this.painter = painter;
    this.screen = screen;
  }

  /** A bar for a named screen. The name is drawn in upper case whatever case it arrives in. */
  public static TitleBar of(final Painter painter, final String screen) {
    return new TitleBar(painter, screen);
  }

  /** A bar for the cluster view, which names no screen -- see this class's own note on why. */
  public static TitleBar unnamed(final Painter painter) {
    return new TitleBar(painter, "");
  }

  /** What this screen is about: an address, a resource, an instance. */
  public TitleBar subject(final String value) {
    subject = Optional.ofNullable(value).filter(text -> !text.isBlank());
    return this;
  }

  /**
   * Whether the rows below are current, and how old they are if not. Written against the three
   * readings every snapshot exposes rather than against any one snapshot type, so no screen can
   * drift into saying "stale" its own way.
   */
  public TitleBar connection(
      final boolean isConnected, final Optional<String> reason, final Optional<Duration> howOld) {
    connected = Optional.of(isConnected);
    staleReason = reason;
    age = howOld;
    return this;
  }

  /** The same, for a snapshot that reports its own age against a clock. */
  public TitleBar connection(
      final boolean isConnected,
      final Optional<String> reason,
      final Optional<Instant> fetchedAt,
      final Instant now) {
    return connection(isConnected, reason, fetchedAt.map(at -> Duration.between(at, now)));
  }

  public TitleBar stat(final String label, final long value) {
    return stat(label, String.valueOf(value));
  }

  public TitleBar stat(final String label, final String value) {
    stats.add(new Stat(label, List.of(new Part(value, Optional.empty()))));
    return this;
  }

  /**
   * The ok / warn / bad instance split, the same three-way rollup the console's overview shows.
   * Three separately-coloured numbers rather than one string, so each keeps its own meaning where
   * there is colour to be had -- and unlabelled, because they read as the breakdown of the count
   * they follow and a label would cost cells the bar has better uses for.
   */
  public TitleBar health(final int ok, final int warn, final int bad) {
    stats.add(
        new Stat(
            "",
            List.of(
                new Part(String.valueOf(ok), Optional.of(StatusVariant.OK)),
                new Part("/", Optional.empty()),
                new Part(String.valueOf(warn), Optional.of(StatusVariant.WARN)),
                new Part("/", Optional.empty()),
                new Part(String.valueOf(bad), Optional.of(StatusVariant.BAD)))));
    return this;
  }

  /**
   * One word about the screen as a whole, drawn after the counts where the eye lands and coloured
   * by what it means -- a node's own state as readily as a count of what is broken.
   */
  public TitleBar badge(final String text, final StatusVariant variant) {
    badges.add(new Badge(text, variant));
    return this;
  }

  /**
   * The tenant every screen is currently narrowed to, said on every bar it narrows. Without it a
   * cluster showing one tenant's three instances is indistinguishable from a cluster that has only
   * three, which is the one way the scope could mislead rather than help.
   */
  public TitleBar scope(final UiState ui) {
    scope = ui.tenantScope();
    return this;
  }

  /** A tenant this screen is about in its own right, shown the same way a scope is. */
  public TitleBar tenant(final Optional<String> tenantId) {
    scope = tenantId;
    return this;
  }

  public TitleBar paused(final boolean isPaused) {
    paused = isPaused;
    return this;
  }

  public String build(final Viewport viewport) {
    Style bar = Style.fg(Palette.MUTED_FOREGROUND).on(Palette.CARD);
    Line line =
        new Line(painter)
            .add(" ", bar)
            .add("GIMLÉ", Style.fg(Palette.PRIMARY).on(Palette.CARD).asBold())
            .add(" TOP", bar.asBold());
    if (!screen.isBlank()) {
      line.add("   " + screen.toUpperCase(Locale.ROOT), bar.asBold());
    }
    subject.ifPresent(value -> line.add("   ", bar).add(value, bar));
    connected.ifPresent(value -> appendConnection(line, value, bar));
    scope.ifPresent(
        tenant ->
            line.add("   tenant ", bar)
                .add(tenant, Style.fg(Palette.PRIMARY).on(Palette.CARD).asBold()));
    for (Stat stat : stats) {
      line.add(stat.label().isBlank() ? "  " : "   " + stat.label() + " ", bar);
      for (Part part : stat.parts()) {
        line.add(
            part.text(),
            part.variant().map(variant -> Style.fg(variant).on(Palette.CARD)).orElse(bar.asBold()));
      }
    }
    for (Badge badge : badges) {
      line.add("   ", bar).add(badge.text(), Style.fg(badge.variant()).on(Palette.CARD).asBold());
    }
    if (paused) {
      // Always last: it is a property of the screen rather than of anything on it, and an operator
      // scanning for why nothing is moving looks at the end of the line.
      line.add("   PAUSED", Style.fg(Palette.WARN).on(Palette.CARD).asBold());
    }
    return line.fillTo(viewport.columns(), bar).build();
  }

  /**
   * The indicator carries no information the words beside it don't -- "connected" and "stale 8s"
   * read the same with colour switched off, which is the whole no-colour contract.
   */
  private void appendConnection(final Line line, final boolean isConnected, final Style bar) {
    line.add("  ", bar);
    if (isConnected) {
      line.add("●", Style.fg(Palette.OK).on(Palette.CARD)).add(" connected", bar);
      return;
    }
    Style warn = Style.fg(Palette.WARN).on(Palette.CARD);
    String suffix = age.map(Text::age).map(value -> " " + value + " old").orElse("");
    line.add("●", warn).add(" " + staleReason.orElse("disconnected") + suffix, warn);
  }
}
