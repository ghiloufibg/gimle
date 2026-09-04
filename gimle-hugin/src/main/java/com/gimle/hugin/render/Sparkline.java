package com.gimle.hugin.render;

import java.util.List;

/**
 * A fixed-width run of block glyphs over a series of readings. It always occupies exactly {@code
 * cells} columns -- the same posture {@link Gauge} takes and for the same reason: a series shorter
 * than the line, or present on one row and absent on the next, would otherwise shift every column
 * after it.
 *
 * <p>Heights scale against zero rather than against the series' own minimum. An operator reading a
 * request rate wants "busy or idle" at a glance, and a minimum-to-maximum scale would draw the same
 * tall peaks over a rate that never left a hundred a second as over one that spiked from nothing.
 *
 * <p>The whole run takes the caller's status colour rather than colouring each glyph by its own
 * height: normalized against its own peak every series reaches the top, so a per-glyph utilization
 * colour would paint every sparkline red at its tallest point regardless of what it measures.
 */
public final class Sparkline {

  private static final char[] GLYPHS = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};

  private Sparkline() {}

  public static void draw(
      final Line line, final List<Double> values, final int cells, final StatusVariant variant) {
    if (cells <= 0) {
      return;
    }
    List<Double> window =
        values.size() > cells ? values.subList(values.size() - cells, values.size()) : values;
    // Right-aligned, so the newest reading sits in the last column whatever the series' length and
    // the line still ends where every other row's does.
    line.pad(cells - window.size());
    if (window.isEmpty()) {
      return;
    }
    double peak =
        window.stream().mapToDouble(Double::doubleValue).filter(Double::isFinite).max().orElse(0.0);
    StringBuilder glyphs = new StringBuilder(window.size());
    for (double value : window) {
      glyphs.append(glyph(value, peak));
    }
    line.add(glyphs.toString(), Style.fg(variant));
  }

  private static char glyph(final double value, final double peak) {
    if (peak <= 0.0 || !Double.isFinite(value) || value <= 0.0) {
      return GLYPHS[0];
    }
    int index = (int) Math.round(Math.clamp(value / peak, 0.0, 1.0) * (GLYPHS.length - 1));
    return GLYPHS[Math.clamp(index, 0, GLYPHS.length - 1)];
  }
}
