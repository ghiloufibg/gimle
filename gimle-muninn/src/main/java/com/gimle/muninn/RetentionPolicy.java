package com.gimle.muninn;

import java.nio.file.Path;

/**
 * How long each ingested signal is kept. Logs are usually the compliance- and
 * investigation-relevant record and are worth keeping longest; metrics and traces are far
 * higher-volume and lose most of their value within a short window, so a single cluster-wide number
 * forces one of the two to be wrong. Each signal therefore gets its own window, defaulting to a
 * shared one when nothing more specific is configured.
 *
 * @param defaultDays the window applied to anything not covered by a per-signal override --
 *     including day files under a subtree that is none of the three known signals, which are swept
 *     rather than kept forever
 */
record RetentionPolicy(int defaultDays, int logsDays, int metricsDays, int tracesDays) {

  private static final String DEFAULT_PROPERTY = "gimle.muninn.retentionDays";
  private static final int DEFAULT_DAYS = 30;

  RetentionPolicy {
    requireNonNegative(defaultDays, DEFAULT_PROPERTY);
    requireNonNegative(logsDays, "gimle.muninn.logs.retentionDays");
    requireNonNegative(metricsDays, "gimle.muninn.metrics.retentionDays");
    requireNonNegative(tracesDays, "gimle.muninn.traces.retentionDays");
  }

  private static void requireNonNegative(int days, String property) {
    if (days < 0) {
      throw new IllegalArgumentException(property + " must not be negative, got " + days);
    }
  }

  /** A uniform window across every signal -- what a deployment that sets no override gets. */
  static RetentionPolicy uniform(int days) {
    return new RetentionPolicy(days, days, days, days);
  }

  static RetentionPolicy fromConfig() {
    int defaultDays = Integer.getInteger(DEFAULT_PROPERTY, DEFAULT_DAYS);
    return new RetentionPolicy(
        defaultDays,
        Integer.getInteger("gimle.muninn.logs.retentionDays", defaultDays),
        Integer.getInteger("gimle.muninn.metrics.retentionDays", defaultDays),
        Integer.getInteger("gimle.muninn.traces.retentionDays", defaultDays));
  }

  /**
   * The window that applies to {@code file}, chosen by the top-level subtree it sits under -- the
   * same {@code logs/}, {@code metrics/}, {@code traces/} prefixes ingest writes to. A file
   * directly in the data root, or under any other subtree, falls back to {@link #defaultDays}.
   */
  int daysFor(Path dataRoot, Path file) {
    Path relative = dataRoot.relativize(file);
    if (relative.getNameCount() < 2) {
      return defaultDays;
    }
    return switch (relative.getName(0).toString()) {
      case "logs" -> logsDays;
      case "metrics" -> metricsDays;
      case "traces" -> tracesDays;
      default -> defaultDays;
    };
  }
}
