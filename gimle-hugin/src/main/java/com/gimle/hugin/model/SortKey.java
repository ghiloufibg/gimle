package com.gimle.hugin.model;

import java.util.Comparator;
import java.util.Optional;

/**
 * How the instance table is ordered. Every metric key sorts descending, because the reason to sort
 * by a metric at all is to put the worst instance on the first row rather than to page looking for
 * it; name sorts ascending, which is what makes it a stable reading rather than a ranking.
 *
 * <p>Every comparator ends in the same name-then-index tiebreak, so instances with equal readings
 * -- the ordinary case on an idle cluster, where every rate is zero -- keep one fixed order instead
 * of shuffling between two polls that measured the same thing.
 */
public enum SortKey {
  NAME("name", byName()),
  STATE("state", Comparator.comparing(InstanceRow::lifecycleState).thenComparing(byName())),
  REQUEST_RATE("req/s", descending(InstanceRow::requestRatePerSecond)),
  ERROR_RATE("err/s", descending(InstanceRow::errorRatePerSecond)),
  QUEUE("queue", descending(row -> (double) row.queueDepth())),
  MEMORY("mem", descending(row -> (double) row.memoryBytesUsed())),
  CPU("cpu", descending(row -> (double) row.cpuMillicoresUsed()));

  private final String label;
  private final Comparator<InstanceRow> comparator;

  SortKey(final String label, final Comparator<InstanceRow> comparator) {
    this.label = label;
    this.comparator = comparator;
  }

  public String label() {
    return label;
  }

  public Comparator<InstanceRow> comparator() {
    return comparator;
  }

  /**
   * The key at {@code position}, counting from one, or empty when nothing is there. What lets a
   * column be picked outright instead of cycled to: on a table of seven orderings, reaching the
   * last one by repeating a key is six presses and a wrong guess away.
   */
  public static Optional<SortKey> at(final int position) {
    SortKey[] keys = values();
    return position >= 1 && position <= keys.length
        ? Optional.of(keys[position - 1])
        : Optional.empty();
  }

  /** How many orderings there are, so a caller can say which keys pick one. */
  public static int count() {
    return values().length;
  }

  /** The next key in declaration order, wrapping -- what one repeated keypress cycles through. */
  public SortKey next() {
    SortKey[] keys = values();
    return keys[(ordinal() + 1) % keys.length];
  }

  private static Comparator<InstanceRow> byName() {
    return Comparator.comparing(InstanceRow::deploymentName)
        .thenComparingInt(InstanceRow::instanceIndex);
  }

  private static Comparator<InstanceRow> descending(
      final java.util.function.ToDoubleFunction<InstanceRow> measure) {
    return Comparator.comparingDouble(measure).reversed().thenComparing(byName());
  }
}
