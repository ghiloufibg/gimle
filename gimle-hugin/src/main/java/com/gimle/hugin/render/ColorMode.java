package com.gimle.hugin.render;

import java.util.Locale;

/** How much colour the attached terminal can be given. */
public enum ColorMode {

  /** 24-bit SGR: the console's token values, exactly. */
  TRUECOLOR,

  /** The 256-colour cube: each token approximated once, at startup. */
  ANSI256,

  /** No escape sequences at all. Every state still reads as text; nothing is colour-only. */
  NONE;

  /**
   * Picks a mode from the environment the way every well-behaved terminal program does: {@code
   * NO_COLOR} (any non-empty value) and a non-TTY both mean no colour at all, {@code COLORTERM}
   * naming truecolor means 24-bit, and everything else settles for the 256-colour cube.
   */
  public static ColorMode detect(
      final boolean interactive, final String noColor, final String colorTerm, final String term) {
    if (!interactive || (noColor != null && !noColor.isEmpty())) {
      return NONE;
    }
    if (term != null && term.toLowerCase(Locale.ROOT).equals("dumb")) {
      return NONE;
    }
    if (colorTerm != null) {
      String lower = colorTerm.toLowerCase(Locale.ROOT);
      if (lower.contains("truecolor") || lower.contains("24bit")) {
        return TRUECOLOR;
      }
    }
    return ANSI256;
  }
}
