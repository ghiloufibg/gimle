package com.gimle.hugin.model;

import java.time.Instant;

/** One shipped reading of one meter: when the snapshot was taken, and what the meter read. */
public record MetricSample(Instant timestamp, double value) {

  public MetricSample {
    if (timestamp == null) {
      throw new IllegalArgumentException("timestamp must not be null");
    }
  }
}
