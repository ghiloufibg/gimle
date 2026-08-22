package com.gimle.cli;

/**
 * Byte/millicore/percentage humanization shared by {@link NodesCommand} and {@link
 * DeploymentsCommand}'s own table-output transforms -- mirrors the console's identical {@code
 * fmtBytes}/{@code fmtMillicores} helpers ({@code gimle-console/src/lib/format.ts}) so an operator
 * sees the same numbers in the same units from either surface, not a JSON blob only the console
 * knows how to render.
 */
final class ResourceFormatting {

  private static final String[] BYTE_UNITS = {"KiB", "MiB", "GiB", "TiB"};

  private ResourceFormatting() {}

  static String bytes(long value) {
    if (value < 1024) {
      return value + " B";
    }
    double v = value / 1024.0;
    int unit = 0;
    while (v >= 1024 && unit < BYTE_UNITS.length - 1) {
      v /= 1024;
      unit++;
    }
    return String.format(v >= 10 ? "%.0f %s" : "%.1f %s", v, BYTE_UNITS[unit]);
  }

  static String millicores(long value) {
    if (value >= 1000) {
      return String.format("%.2f cores", value / 1000.0);
    }
    return value + " mCPU";
  }

  /** {@code -} for a zero/unknown {@code total} rather than a division-by-zero {@code NaN%}. */
  static String percent(long used, long total) {
    if (total <= 0) {
      return "-";
    }
    return Math.round((used * 100.0) / total) + "%";
  }
}
