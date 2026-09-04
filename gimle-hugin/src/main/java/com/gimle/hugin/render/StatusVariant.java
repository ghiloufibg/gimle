package com.gimle.hugin.render;

/**
 * The five status colours the console's own {@code StatusBadge} defines, and the mappings onto them
 * that decide which one a value gets. Mirrored from {@code LifecycleBadge} rather than re-invented:
 * a state that reads amber in the browser has to read amber in the terminal, and a drift between
 * the two is what {@code StatusVariantTest} exists to catch.
 */
public enum StatusVariant {
  OK,
  WARN,
  BAD,
  INFO,
  MUTED;

  /** {@code LifecycleBadge}'s mapping, state for state. */
  public static StatusVariant ofLifecycleState(final String state) {
    return switch (state) {
      case "ACTIVE", "COMPLETED" -> OK;
      case "STARTING", "STOPPING" -> WARN;
      case "UNINSTALLED", "FAILED" -> BAD;
      default -> MUTED;
    };
  }

  /** A node's own single-word state, coloured on the same scale. */
  public static StatusVariant ofNodeState(final String state) {
    return switch (state) {
      case "READY" -> OK;
      case "CORDONED", "STALE" -> WARN;
      default -> MUTED;
    };
  }

  /**
   * How full a resource gauge reads. The thresholds are the terminal's own -- the console shows a
   * percentage bar rather than a coloured verdict here -- and are deliberately coarse: a gauge is
   * for noticing a node filling up, not for reading an exact number off.
   */
  public static StatusVariant ofUtilization(final double fraction) {
    if (fraction >= 0.9) {
      return BAD;
    }
    return fraction >= 0.75 ? WARN : OK;
  }

  /** A log line's level, on the same scale the log pane's other columns use. */
  public static StatusVariant ofLogLevel(final String level) {
    return switch (level) {
      case "ERROR" -> BAD;
      case "WARN" -> WARN;
      case "INFO" -> INFO;
      default -> MUTED;
    };
  }
}
