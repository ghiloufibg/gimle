package com.gimle.hugin.render;

/**
 * Turns a {@link Style} into the escape sequence for the attached terminal's colour depth, or into
 * nothing at all. In {@link ColorMode#NONE} this emits not a single escape byte -- the whole point
 * of that mode is that its output is safe to pipe into a file or a pager.
 */
public final class Painter {

  private final ColorMode mode;

  public Painter(final ColorMode mode) {
    this.mode = mode;
  }

  public ColorMode mode() {
    return mode;
  }

  public String paint(final String text, final Style style) {
    if (mode == ColorMode.NONE || style.isPlain() || text.isEmpty()) {
      return text;
    }
    StringBuilder sequence = new StringBuilder(Ansi.CSI);
    boolean first = true;
    if (style.bold()) {
      sequence.append('1');
      first = false;
    }
    if (style.foreground().isPresent()) {
      first = appendSeparator(sequence, first);
      appendColor(sequence, style.foreground().get(), true);
    }
    if (style.background().isPresent()) {
      appendSeparator(sequence, first);
      appendColor(sequence, style.background().get(), false);
    }
    return sequence.append('m').append(text).append(Ansi.RESET).toString();
  }

  private static boolean appendSeparator(final StringBuilder sequence, final boolean first) {
    if (!first) {
      sequence.append(';');
    }
    return false;
  }

  private void appendColor(final StringBuilder sequence, final int rgb, final boolean foreground) {
    int red = (rgb >> 16) & 0xFF;
    int green = (rgb >> 8) & 0xFF;
    int blue = rgb & 0xFF;
    sequence.append(foreground ? "38;" : "48;");
    if (mode == ColorMode.TRUECOLOR) {
      sequence.append("2;").append(red).append(';').append(green).append(';').append(blue);
    } else {
      sequence.append("5;").append(cubeIndex(red, green, blue));
    }
  }

  /**
   * The nearest entry in xterm's 256-colour palette: either the 6x6x6 colour cube (indices 16-231)
   * or the 24-step grey ramp (232-255), whichever lands closer. The grey ramp is worth the extra
   * arithmetic here -- several of the console's own tokens are near-neutral, and the cube's own
   * grey diagonal is coarse enough to tint them visibly.
   */
  private static int cubeIndex(final int red, final int green, final int blue) {
    int redComponent = cubeComponent(red);
    int greenComponent = cubeComponent(green);
    int blueComponent = cubeComponent(blue);
    int cubeCode = 16 + 36 * redComponent + 6 * greenComponent + blueComponent;
    int cubeDistance =
        distance(
            red,
            green,
            blue,
            cubeLevel(redComponent),
            cubeLevel(greenComponent),
            cubeLevel(blueComponent));

    int greyStep = Math.clamp(Math.round(((red + green + blue) / 3f - 8f) / 10f), 0, 23);
    int greyLevel = 8 + 10 * greyStep;
    int greyDistance = distance(red, green, blue, greyLevel, greyLevel, greyLevel);

    return greyDistance < cubeDistance ? 232 + greyStep : cubeCode;
  }

  private static int cubeComponent(final int value) {
    return Math.clamp(Math.round((value - 55f) / 40f), 0, 5);
  }

  private static int cubeLevel(final int component) {
    return component == 0 ? 0 : 55 + 40 * component;
  }

  private static int distance(
      final int red,
      final int green,
      final int blue,
      final int otherRed,
      final int otherGreen,
      final int otherBlue) {
    int deltaRed = red - otherRed;
    int deltaGreen = green - otherGreen;
    int deltaBlue = blue - otherBlue;
    return deltaRed * deltaRed + deltaGreen * deltaGreen + deltaBlue * deltaBlue;
  }
}
