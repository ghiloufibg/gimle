package com.gimle.hugin.render;

import com.gimle.hugin.model.ResourceColumn;
import java.util.Arrays;
import java.util.List;

/**
 * Column widths for the resource browser, computed from the kind's own columns rather than fixed
 * per table.
 *
 * <p>The other tables here each know their columns at compile time and can size them by hand. This
 * one cannot: a custom kind's columns are whatever its definition declared, so the widths have to
 * come from the declared weights and the terminal's width alone. The header and every row read them
 * from one place, so they agree by construction.
 */
public record ResourceLayout(List<Integer> widths, int gap) {

  /** Below this the table gives up one cell of gap rather than one of every column. */
  private static final int COMPACT_BELOW = 100;

  /** Narrow enough to be useless on its own, but a column at zero would misalign the row. */
  private static final int MIN_CELL = 4;

  public ResourceLayout {
    widths = List.copyOf(widths);
  }

  public static ResourceLayout forWidth(final List<ResourceColumn> columns, final int terminal) {
    int count = columns.size();
    int gap = terminal < COMPACT_BELOW ? 1 : 2;
    int available = Math.max(count * MIN_CELL, terminal - gap * (count - 1));
    int totalWeight = columns.stream().mapToInt(ResourceColumn::weight).sum();

    int[] widths = new int[count];
    int assigned = 0;
    for (int index = 0; index < count; index++) {
      widths[index] = Math.max(MIN_CELL, available * columns.get(index).weight() / totalWeight);
      assigned += widths[index];
    }
    // Rounding up to the floor above can push the total past the terminal; give the cells back
    // from whichever column is currently widest, so the loss lands on the column best able to
    // absorb it rather than always on the last one.
    while (assigned > available) {
      int widest = widestAbove(widths);
      if (widest < 0) {
        break;
      }
      widths[widest]--;
      assigned--;
    }
    // Whatever integer division left over goes to the last column, so the table reaches the edge
    // instead of stopping a few cells short of it.
    if (assigned < available) {
      widths[count - 1] += available - assigned;
    }
    return new ResourceLayout(Arrays.stream(widths).boxed().toList(), gap);
  }

  private static int widestAbove(final int[] widths) {
    int widest = -1;
    for (int index = 0; index < widths.length; index++) {
      if (widths[index] > MIN_CELL && (widest < 0 || widths[index] > widths[widest])) {
        widest = index;
      }
    }
    return widest;
  }

  public int width(final int column) {
    return widths.get(column);
  }
}
