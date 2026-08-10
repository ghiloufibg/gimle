package com.gimle.core.banner;

import java.util.Locale;
import java.util.Map;

/**
 * The single source of ANSI color truth for this process's own boot banner ({@link GimleBanner})
 * and, since {@code gimle-core}'s console log encoder now colors its own output too, every ongoing
 * log line printed to that same terminal -- one palette, one detection routine, so "logs colored
 * consistent with the banner" is a structural guarantee (both consumers read the same constants),
 * not a matter of keeping two hand-picked color sets in sync by hand.
 *
 * <p>{@code C_RED} has no banner use (the banner template never needed an error color) but exists
 * here anyway so a log encoder coloring {@code ERROR} lines draws from this same palette rather
 * than picking its own shade.
 */
public final class AnsiPalette {

  // 256-color palette matching the console UI (golden roof + neon mint).
  private static final Map<String, String> EXTENDED =
      Map.of(
          "C_GOLD", "[38;5;179m",
          "C_GOLD_B", "[1m[38;5;179m",
          "C_MINT", "[38;5;79m",
          "C_SLATE", "[38;5;66m",
          "C_RED", "[38;5;167m",
          "C_RESET", "[0m");

  // Safe fallback for terminals that only do the basic 16 colors.
  private static final Map<String, String> BASIC =
      Map.of(
          "C_GOLD", "[33m",
          "C_GOLD_B", "[1;33m",
          "C_MINT", "[36m",
          "C_SLATE", "[90m",
          "C_RED", "[31m",
          "C_RESET", "[0m");

  private static final Map<String, String> NONE_COLORS =
      Map.of("C_GOLD", "", "C_GOLD_B", "", "C_MINT", "", "C_SLATE", "", "C_RED", "", "C_RESET", "");

  public enum ColorMode {
    NONE,
    BASIC,
    EXTENDED
  }

  private AnsiPalette() {}

  public static Map<String, String> colorsFor(ColorMode mode) {
    return switch (mode) {
      case EXTENDED -> EXTENDED;
      case BASIC -> BASIC;
      case NONE -> NONE_COLORS;
    };
  }

  /**
   * Auto-detect, with explicit overrides taking precedence. {@code -Dgimle.color=always|never|auto}
   * (or the {@code NO_COLOR}/{@code FORCE_COLOR}/{@code CLICOLOR_FORCE} environment variables)
   * governs both the boot banner and every colored console log line -- one switch for "does this
   * process's own terminal output use ANSI escapes," not two independently-configured ones.
   */
  public static ColorMode detectMode() {
    String override = System.getProperty("gimle.color");
    if (override != null) {
      switch (override.toLowerCase(Locale.ROOT)) {
        case "never", "off", "false" -> {
          return ColorMode.NONE;
        }
        case "always", "on", "true" -> {
          return ColorMode.EXTENDED;
        }
        default -> {
          /* auto */
        }
      }
    }

    // https://no-color.org - any non-empty value disables color.
    String noColor = System.getenv("NO_COLOR");
    if (noColor != null && !noColor.isEmpty()) {
      return ColorMode.NONE;
    }

    boolean forced =
        notEmpty(System.getenv("FORCE_COLOR")) || notEmpty(System.getenv("CLICOLOR_FORCE"));

    // No attached console (piped output, IDE without ANSI, CI log file).
    if (!forced && System.console() == null) {
      return ColorMode.NONE;
    }

    String term = orEmpty(System.getenv("TERM")).toLowerCase(Locale.ROOT);
    if (term.equals("dumb")) {
      return ColorMode.NONE;
    }

    boolean windows =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    if (windows) {
      // Windows Terminal, ConEmu, ANSICON, Git Bash and Cygwin all handle ANSI. Legacy conhost
      // (cmd.exe / powershell.exe on Windows 10 pre-1511) does not -- stay safe and drop color.
      if (notEmpty(System.getenv("WT_SESSION"))
          || notEmpty(System.getenv("ConEmuANSI"))
          || notEmpty(System.getenv("ANSICON"))
          || term.contains("xterm")
          || !orEmpty(System.getenv("TERM_PROGRAM")).isEmpty()) {
        return ColorMode.EXTENDED;
      }
      return supportsWindowsVtProcessing() ? ColorMode.BASIC : ColorMode.NONE;
    }

    if (term.isEmpty()) {
      return forced ? ColorMode.BASIC : ColorMode.NONE;
    }
    if (term.contains("256")
        || term.contains("truecolor")
        || notEmpty(System.getenv("COLORTERM"))) {
      return ColorMode.EXTENDED;
    }
    return ColorMode.BASIC;
  }

  /**
   * Windows 10 build 10586+ enables virtual-terminal sequences in conhost. We can only sniff the OS
   * version from the JVM, so treat Windows 10/11 as VT-capable and everything older as not.
   */
  private static boolean supportsWindowsVtProcessing() {
    String v = System.getProperty("os.version", "");
    try {
      return Double.parseDouble(v.split("\\.")[0]) >= 10;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static boolean notEmpty(String s) {
    return s != null && !s.isEmpty();
  }

  private static String orEmpty(String s) {
    return s == null ? "" : s;
  }
}
