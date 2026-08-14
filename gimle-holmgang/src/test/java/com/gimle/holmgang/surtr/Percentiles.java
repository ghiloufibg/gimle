package com.gimle.holmgang.surtr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A percentile summary over a sample of latencies (milliseconds). Uses the nearest-rank method on a
 * sorted copy -- simple, dependency-free, and exact enough for the coarse regression alarms Surtr's
 * gates are (not a statistics library's interpolated estimate).
 */
public record Percentiles(long count, long p50, long p95, long p99, long max) {

  public static Percentiles of(final List<Long> samples) {
    if (samples.isEmpty()) {
      return new Percentiles(0, 0, 0, 0, 0);
    }
    final List<Long> sorted = new ArrayList<>(samples);
    Collections.sort(sorted);
    return new Percentiles(
        sorted.size(),
        nearestRank(sorted, 50),
        nearestRank(sorted, 95),
        nearestRank(sorted, 99),
        sorted.get(sorted.size() - 1));
  }

  private static long nearestRank(final List<Long> sorted, final int percentile) {
    // Rank = ceil(p/100 * N), 1-based, clamped into the sample -- the standard nearest-rank index.
    final int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
    final int index = Math.min(Math.max(rank - 1, 0), sorted.size() - 1);
    return sorted.get(index);
  }
}
