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
 *       ${C_RESET}} -- resolved via {@link AnsiPalette}, the same palette {@code
 *       com.gimle.core.logging.TextLogEncoder} colors every ongoing log line with, or to {@code ""}
 *       when colors are off.
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
 * <p>Colors can be forced with {@code -Dgimle.color=always|never|auto} or the {@code
 * NO_COLOR}/{@code FORCE_COLOR}/{@code CLICOLOR_FORCE} environment variables (see {@link
 * AnsiPalette#detectMode()}) -- the same switch the console log encoder reads, so a process's
 * terminal output is either colored everywhere or nowhere, never inconsistently. The whole banner
 * can additionally be suppressed with {@code -Dgimle.banner.enabled=false} -- {@code gimle-agent}
 * sets this unconditionally on every worker JVM it spawns (see {@code
 * AgentMain#buildWorkerCommand}), since a worker starts once per module instance rather than once
 * per node/replica lifecycle and a banner on every spawn would just be log noise at scale; every
 * other process (control plane, agent, store, Fafnir) leaves it on.
 */
public final class GimleBanner {

  private GimleBanner() {}

  /**
   * No-ops when {@code -Dgimle.banner.enabled=false} -- see the class javadoc.
   *
   * <p>Writes raw UTF-8 bytes rather than {@code out.println(String)}, deliberately: a plain {@code
   * println} encodes through {@code out}'s own default charset, which for {@code System.out}
   * follows the JVM's {@code stdout.encoding} -- left at the platform's native encoding on every
   * process this project spawns, never overridden to UTF-8. On a non-UTF-8-locale host that
   * silently corrupts any non-ASCII {@code app.name} (e.g. "Gimlé") into an invalid byte, while
   * every other line in the same log file is unconditionally UTF-8 (see {@code
   * TextLogEncoder}/{@code JsonLogEncoder}) -- since {@code ProcessBuilder.Redirect} merges stdout
   * and Logback's own output into one physical file, that mismatch made the file as a whole not
   * valid UTF-8. Encoding explicitly here, matching Logback's own posture, is what keeps every line
   * in it in one consistent, locale-independent encoding.
   */
  public static void print(PrintStream out, Map<String, String> values) {
    if (!enabled()) {
      return;
    }
    byte[] bytes = (render(values) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    out.write(bytes, 0, bytes.length);
    out.flush();
  }

  public static String render(Map<String, String> values) {
    boolean unicode = supportsUnicode();

    String template = load(unicode ? "banner.txt" : "banner-ascii.txt");
    if (template == null) {
      return "";
    }

    Map<String, String> vars = new HashMap<>(AnsiPalette.colorsFor(AnsiPalette.detectMode()));
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
}
