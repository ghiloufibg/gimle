package com.gimle.hugin.render;

/**
 * The terminal's current size, as the renderers see it. A viewport is passed in rather than read
 * from the terminal inside a renderer, which is what lets a test render the same snapshot at 80 and
 * at 200 columns and compare.
 */
public record Viewport(int columns, int rows) {

  /** What a terminal that reports no size at all is treated as. */
  public static final Viewport DEFAULT = new Viewport(100, 30);

  private static final int MIN_COLUMNS = 60;
  private static final int MIN_ROWS = 10;

  public Viewport {
    columns = Math.max(MIN_COLUMNS, columns);
    rows = Math.max(MIN_ROWS, rows);
  }

  /** A reported size of zero in either dimension means "unknown", not "nothing fits". */
  public static Viewport of(final int columns, final int rows) {
    return columns <= 0 || rows <= 0 ? DEFAULT : new Viewport(columns, rows);
  }
}
