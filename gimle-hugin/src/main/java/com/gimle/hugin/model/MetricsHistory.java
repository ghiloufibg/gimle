package com.gimle.hugin.model;

import java.util.Map;
import java.util.Optional;

/**
 * A process's shipped meter history, one series per meter name. Empty is the ordinary case, not a
 * failure: shipping to Muninn is optional, so a cluster configured without it has no history at all
 * and every pane drawn from this simply has nothing to draw.
 */
public record MetricsHistory(Map<String, MetricSeries> series) {

  public static final MetricsHistory EMPTY = new MetricsHistory(Map.of());

  public MetricsHistory {
    series = Map.copyOf(series);
  }

  public boolean isEmpty() {
    return series.isEmpty();
  }

  /** The series for {@code meterName}, absent when nothing under that name was ever shipped. */
  public Optional<MetricSeries> series(final String meterName) {
    return Optional.ofNullable(series.get(meterName));
  }
}
