package com.gimle.core.banner;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Renders each process's startup banner ({@code banner.txt} / {@code banner-ascii.txt}, both
 * bundled here in {@code gimle-core} so every {@code *Main} class can share one copy rather than
 * duplicating the template or this rendering logic five times). Printed directly to a {@link
 * PrintStream}, deliberately bypassing SLF4J/Logback entirely: the banner needs raw control over
 * its own ANSI escapes and line breaks, and Logback's console pattern would prefix every line with
 * a timestamp/level/logger-name block that destroys the ASCII-art layout -- the same reasoning
 * Spring Boot's own banner printer uses.
 *
 * <p>Placeholders in the template:
 *
 * <ul>
 *   <li>{@code ${app.name}} {@code ${app.description}} {@code ${app.version}} -- supplied by the
 *       caller, one literal name/description per process role plus {@link GimleVersion#current()}
 *       for the version.
 *   <li>{@code ${C_GOLD}} {@code ${C_GOLD_B}} {@code ${C_MINT}} {@code ${C_SLATE}} {@code
 *       ${C_RESET}} -- resolved here to ANSI escapes, or to {@code ""} when colors are off.
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * GimleBanner.print(System.out, Map.of(
 *     "app.name",        "Gimlé Control Plane",
 *     "app.description", "API server, scheduler, reconcilers",
 *     "app.version",     GimleVersion.current()));
 * }</pre>
 *
 * <p>Colors can be forced with {@code -Dgimle.banner.color=always|never|auto} or the {@code
 * NO_COLOR}/{@code FORCE_COLOR}/{@code CLICOLOR_FORCE} environment variables. The whole banner can
 * be suppressed with {@code -Dgimle.banner.enabled=false} -- {@code gimle-agent} sets this
 * unconditionally on every worker JVM it spawns (see {@code AgentMain#buildWorkerCommand}), since a
 * worker starts once per module instance rather than once per node/replica lifecycle and a banner
 * on every spawn would just be log noise at scale; every other process (control plane, agent,
 * store, Fafnir) leaves it on.
 */
public final class GimleBanner {

  // 256-color palette matching the console UI (golden roof + neon mint).
  private static final Map<String, String> ANSI =
      Map.of(
          "C_GOLD", "[38;5;179m",
          "C_GOLD_B", "[1m[38;5;179m",
          "C_MINT", "[38;5;79m",
          "C_SLATE", "[38;5;66m",
          "C_RESET", "[0m");

  // Safe fallback for terminals that only do the basic 16 colors.
  private static final Map<String, String> ANSI_BASIC =
      Map.of(
          "C_GOLD", "[33m",
          "C_GOLD_B", "[1;33m",
          "C_MINT", "[36m",
          "C_SLATE", "[90m",
          "C_RESET", "[0m");

  private static final Map<String, String> NO_ANSI =
      Map.of("C_GOLD", "", "C_GOLD_B", "", "C_MINT", "", "C_SLATE", "", "C_RESET", "");

  public enum ColorMode {
    NONE,
    BASIC,
    EXTENDED
  }

  private GimleBanner() {}

  /** No-ops when {@code -Dgimle.banner.enabled=false} -- see the class javadoc. */
  public static void print(PrintStream out, Map<String, String> values) {
    if (!enabled()) {
      return;
    }
    out.println(render(values));
    out.flush();
  }

  public static String render(Map<String, String> values) {
    ColorMode mode = detectColorMode();
    boolean unicode = supportsUnicode();

    String template = load(unicode ? "banner.txt" : "banner-ascii.txt");
    if (template == null) {
      return "";
    }

    Map<String, String> vars = new HashMap<>();
    vars.putAll(
        switch (mode) {
          case EXTENDED -> ANSI;
          case BASIC -> ANSI_BASIC;
          case NONE -> NO_ANSI;
        });
    vars.put("app.name", "Application");
    vars.put("app.description", "");
    vars.put("app.version", "0.0.0");
    if (values != null) {
      vars.putAll(values);
    }

    String rendered = template;
    for (Map.Entry<String, String> e : vars.entrySet()) {
      rendered = rendered.replace("${" + e.getKey() + "}", e.getValue());
    }
    // Drop any placeholder the caller did not supply.
    return rendered.replaceAll("\\$\\{[^}]*}", "");
  }

  private static boolean enabled() {
    return !"false".equalsIgnoreCase(System.getProperty("gimle.banner.enabled", "true"));
  }

  /** Auto-detect, with explicit overrides taking precedence. */
  public static ColorMode detectColorMode() {
    String override = System.getProperty("gimle.banner.color");
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

  /** Box-drawing glyphs need a UTF-8 capable console. */
  private static boolean supportsUnicode() {
    String enc =
        System.getProperty("stdout.encoding", System.getProperty("file.encoding", ""))
            .toUpperCase(Locale.ROOT);
    return enc.contains("UTF");
  }

  private static String load(String resource) {
    try (InputStream in = GimleBanner.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        return null;
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
  }

  private static boolean notEmpty(String s) {
    return s != null && !s.isEmpty();
  }

  private static String orEmpty(String s) {
    return s == null ? "" : s;
  }
}
