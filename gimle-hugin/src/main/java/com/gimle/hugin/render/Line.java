package com.gimle.hugin.render;

/**
 * One rendered line, built left to right. Every append tracks the visible width alongside the
 * emitted text, so {@link #padTo} lands a column at the same place whether the cells before it were
 * coloured or not -- the one piece of bookkeeping that keeps a coloured table aligned.
 */
public final class Line {

  private final Painter painter;
  private final StringBuilder text = new StringBuilder();
  private int width;

  public Line(final Painter painter) {
    this.painter = painter;
  }

  public Line add(final String value) {
    return add(value, Style.PLAIN);
  }

  public Line add(final String value, final Style style) {
    text.append(painter.paint(value, style));
    width += value.length();
    return this;
  }

  /**
   * Adds {@code value} truncated to {@code cells} (with a trailing ellipsis when it doesn't fit),
   * then pads with spaces to exactly that many cells. A long deployment name shortens rather than
   * wrapping onto a line of its own and pushing every row below it out of place.
   */
  public Line cell(final String value, final int cells, final Style style) {
    if (cells <= 0) {
      return this;
    }
    String fitted = Text.truncate(value, cells);
    add(fitted, style);
    return pad(cells - fitted.length());
  }

  public Line cell(final String value, final int cells) {
    return cell(value, cells, Style.PLAIN);
  }

  /** Right-aligns {@code value} within {@code cells}, the shape every numeric column wants. */
  public Line rightCell(final String value, final int cells, final Style style) {
    if (cells <= 0) {
      return this;
    }
    String fitted = Text.truncate(value, cells);
    return pad(cells - fitted.length()).add(fitted, style);
  }

  public Line pad(final int cells) {
    if (cells > 0) {
      text.append(" ".repeat(cells));
      width += cells;
    }
    return this;
  }

  /** Pads with spaces until the visible width reaches {@code column}. */
  public Line padTo(final int column) {
    return pad(column - width);
  }

  /**
   * Pads to {@code column} with {@code style} applied to the padding itself -- what makes a status
   * bar's background run the full width of the terminal rather than stopping after its text.
   */
  public Line fillTo(final int column, final Style style) {
    int missing = column - width;
    return missing > 0 ? add(" ".repeat(missing), style) : this;
  }

  public int width() {
    return width;
  }

  public String build() {
    return text.toString();
  }
}
