package com.gimle.hugin.render;

import java.time.Duration;
import java.util.Locale;

/**
 * Formatting for the values the tables show. Every one of these is chosen to fit a fixed column at
 * a glance rather than to be exact: a memory reading of {@code 142Mi} says what an operator needs
 * to know, and the byte count it came from does not.
 */
public final class Text {

  /** What a column shows when there is no value at all -- absent, as opposed to zero. */
  public static final String ABSENT = "—";

  private Text() {}

  /** Truncates to {@code cells}, marking the cut with an ellipsis when there is room for one. */
  public static String truncate(final String value, final int cells) {
    if (cells <= 0) {
      return "";
    }
    if (value.length() <= cells) {
      return value;
    }
    return cells == 1 ? "…" : value.substring(0, cells - 1) + "…";
  }

  /** Binary units, the same scale the CLI's own {@code ResourceFormatting} prints. */
  public static String bytes(final long value) {
    if (value <= 0) {
      return "0";
    }
    if (value < 1024L) {
      return value + "B";
    }
    if (value < 1024L * 1024L) {
      return Math.round(value / 1024.0) + "Ki";
    }
    if (value < 1024L * 1024L * 1024L) {
      return Math.round(value / (1024.0 * 1024.0)) + "Mi";
    }
    return String.format(Locale.ROOT, "%.1fGi", value / (1024.0 * 1024.0 * 1024.0));
  }

  /** Gibibytes with one decimal, for a gauge's "used of total" label. */
  public static String gibibytes(final long value) {
    return String.format(Locale.ROOT, "%.1f", value / (1024.0 * 1024.0 * 1024.0));
  }

  public static String millicores(final long value) {
    return value + "m";
  }

  public static String rate(final double value) {
    return String.format(Locale.ROOT, "%.1f", value);
  }

  /**
   * A duration as the compact age a status line shows: seconds up to a minute, then minutes, then
   * hours, then days. Never more than one unit -- this is a freshness cue, not a stopwatch.
   */
  public static String age(final Duration duration) {
    long seconds = Math.max(0, duration.toSeconds());
    if (seconds < 60) {
      return seconds + "s";
    }
    if (seconds < 3600) {
      return seconds / 60 + "m";
    }
    if (seconds < 86_400) {
      return seconds / 3600 + "h";
    }
    return seconds / 86_400 + "d";
  }

  /** {@code fraction} as a percentage of a whole, clamped, for a gauge. */
  public static double fraction(final long used, final long total) {
    if (total <= 0) {
      return 0.0;
    }
    return Math.clamp((double) used / total, 0.0, 1.0);
  }
}
