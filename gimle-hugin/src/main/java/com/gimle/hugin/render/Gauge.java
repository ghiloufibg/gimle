package com.gimle.hugin.render;

/**
 * A fixed-width bar: filled cells in the utilization's own status colour, the remainder in muted
 * shade characters. Both halves are always drawn, so the bar occupies the same columns whatever it
 * reads -- an empty gauge that collapsed to nothing would shift every column after it.
 */
public final class Gauge {

  private static final char FILLED = '▇';
  private static final char EMPTY = '░';

  private Gauge() {}

  public static void draw(final Line line, final double fraction, final int cells) {
    if (cells <= 0) {
      return;
    }
    int filled = (int) Math.round(Math.clamp(fraction, 0.0, 1.0) * cells);
    // A non-zero reading always shows at least one filled cell: rounding a small-but-real
    // utilization down to an empty bar would say "idle" about a node that isn't.
    if (filled == 0 && fraction > 0.0) {
      filled = 1;
    }
    line.add(
        String.valueOf(FILLED).repeat(filled), Style.fg(StatusVariant.ofUtilization(fraction)));
    line.add(String.valueOf(EMPTY).repeat(cells - filled), Style.fg(Palette.MUTED));
  }
}
