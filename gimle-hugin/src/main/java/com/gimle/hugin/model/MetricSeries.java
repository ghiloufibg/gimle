package com.gimle.hugin.model;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One meter's readings, oldest first. A worker ships its whole registry on an interval, so a series
 * is a run of periodic snapshots rather than a continuous signal -- consecutive samples can be
 * minutes apart, which is why a rate is derived from each pair's own elapsed time rather than from
 * an assumed cadence.
 */
public record MetricSeries(String name, List<MetricSample> samples) {

  public MetricSeries {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    samples = List.copyOf(samples);
  }

  public boolean isEmpty() {
    return samples.isEmpty();
  }

  /** The readings as shipped -- already the value a gauge means. */
  public List<Double> values() {
    return samples.stream().map(MetricSample::value).toList();
  }

  /** The newest reading, or {@code 0} for a series with none. */
  public double latest() {
    return samples.isEmpty() ? 0.0 : samples.getLast().value();
  }

  /**
   * Per-second change between consecutive readings -- what a cumulative counter has to become
   * before it means anything drawn. One point shorter than the series itself, and empty for a
   * single reading, since a rate needs two. A negative delta is a counter that restarted with its
   * worker JVM rather than a negative rate, so it reads as zero.
   */
  public List<Double> ratesPerSecond() {
    List<Double> rates = new ArrayList<>();
    for (int index = 1; index < samples.size(); index++) {
      MetricSample previous = samples.get(index - 1);
      MetricSample current = samples.get(index);
      double seconds = Duration.between(previous.timestamp(), current.timestamp()).toNanos() / 1e9;
      if (seconds <= 0.0) {
        continue;
      }
      rates.add(Math.max(0.0, (current.value() - previous.value()) / seconds));
    }
    return List.copyOf(rates);
  }
}
