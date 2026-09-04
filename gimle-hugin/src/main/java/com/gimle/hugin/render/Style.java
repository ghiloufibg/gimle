package com.gimle.hugin.render;

import java.util.Optional;

/** A foreground colour, an optional background, and whether the run is bold. */
public record Style(Optional<Integer> foreground, Optional<Integer> background, boolean bold) {

  public static final Style PLAIN = new Style(Optional.empty(), Optional.empty(), false);

  public Style {
    if (foreground == null || background == null) {
      throw new IllegalArgumentException("colours must not be null; use Optional.empty()");
    }
  }

  public static Style fg(final int rgb) {
    return new Style(Optional.of(rgb), Optional.empty(), false);
  }

  public static Style fg(final StatusVariant variant) {
    return fg(Palette.of(variant));
  }

  public Style on(final int rgb) {
    return new Style(foreground, Optional.of(rgb), bold);
  }

  /**
   * A bold variant of this style. Named apart from the {@code bold()} component accessor, which a
   * record reserves.
   */
  public Style asBold() {
    return new Style(foreground, background, true);
  }

  public boolean isPlain() {
    return foreground.isEmpty() && background.isEmpty() && !bold;
  }
}
