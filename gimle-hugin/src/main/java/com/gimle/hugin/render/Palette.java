package com.gimle.hugin.render;

/**
 * The console's own dark-theme tokens, converted from OKLCH to sRGB once and frozen here as
 * constants -- terminals speak sRGB, and a token that drifts between the two surfaces would make
 * the same state read differently depending on which one an operator happened to be looking at.
 *
 * <p>Only the dark theme ships: terminals are dark, and the console's dark theme is already tuned
 * as an instrument panel rather than as an inversion of its light one.
 */
public final class Palette {

  /** {@code --status-ok}: ACTIVE, COMPLETED, healthy headroom. */
  public static final int OK = 0x4AE2AC;

  /** {@code --status-warn}: STARTING, STOPPING, cordoned, stale heartbeat. */
  public static final int WARN = 0xFDBA2F;

  /** {@code --status-bad}: FAILED, UNINSTALLED, a non-zero error rate. */
  public static final int BAD = 0xFE6270;

  /** {@code --status-info}: INFO log lines, neutral events. */
  public static final int INFO = 0x5DCBD1;

  /** {@code --status-muted}: INSTALLED, RESOLVED, absent values. */
  public static final int MUTED = 0x748389;

  /** {@code --primary}: key hints, the cursor, gauge fill. */
  public static final int PRIMARY = 0x39D7B0;

  /** {@code --hud}: section labels, the console's own {@code hud-label} role. */
  public static final int HUD = 0x62B7A1;

  /** {@code --foreground}: body text. */
  public static final int FOREGROUND = 0xBEF3D3;

  /** {@code --muted-foreground}: column headers, timestamps. */
  public static final int MUTED_FOREGROUND = 0x85AFA1;

  /** {@code --card}: the status and key bars. */
  public static final int CARD = 0x0E232F;

  /** The selected row's background -- {@code --card} lifted enough to read as a cursor. */
  public static final int SELECTION = 0x123040;

  private Palette() {}

  /** The colour a status variant carries. */
  public static int of(final StatusVariant variant) {
    return switch (variant) {
      case OK -> OK;
      case WARN -> WARN;
      case BAD -> BAD;
      case INFO -> INFO;
      case MUTED -> MUTED;
    };
  }
}
