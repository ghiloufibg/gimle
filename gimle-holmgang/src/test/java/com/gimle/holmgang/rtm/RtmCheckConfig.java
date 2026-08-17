package com.gimle.holmgang.rtm;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The one place the RTM coverage gate's own configuration is decided, mirroring {@link
 * com.gimle.holmgang.WorkDirs}'s style: {@code -Dgimle.holmgang.rtmCheck.enabled=true|false}
 * (default {@code true} -- release-readiness is opt-out, not opt-in), an optional {@code
 * -Dgimle.holmgang.rtmCheck.file=<path>} override for where {@code rtm.json} is read from (default
 * {@code ../rtm.json}, relative to this module's own working directory when Maven runs it -- the
 * repo root, one level up from {@code gimle-holmgang} itself -- the structured JSON source of truth
 * {@code RTM.md} is itself rendered from, not the Markdown file), and {@code
 * -Dgimle.holmgang.rtmCheck.exclude=GIMLE-012,GIMLE-018} for known/accepted gaps to whitelist
 * without disabling the whole check.
 */
public final class RtmCheckConfig {

  private static final String ENABLED_PROPERTY = "gimle.holmgang.rtmCheck.enabled";
  private static final String FILE_PROPERTY = "gimle.holmgang.rtmCheck.file";
  private static final String EXCLUDE_PROPERTY = "gimle.holmgang.rtmCheck.exclude";
  private static final Path DEFAULT_RTM_FILE = Path.of("..", "rtm.json");

  private RtmCheckConfig() {}

  public static boolean enabled() {
    return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
  }

  public static Path rtmFile() {
    final String override = System.getProperty(FILE_PROPERTY);
    return (override == null || override.isBlank()) ? DEFAULT_RTM_FILE : Path.of(override);
  }

  /** Requirement IDs (e.g. {@code GIMLE-012}) to whitelist, normalized to upper case. */
  public static Set<String> excludedIds() {
    final String raw = System.getProperty(EXCLUDE_PROPERTY, "");
    final Set<String> ids = new LinkedHashSet<>();
    for (final String token : raw.split(",")) {
      final String trimmed = token.strip();
      if (!trimmed.isEmpty()) {
        ids.add(trimmed.toUpperCase(Locale.ROOT));
      }
    }
    return ids;
  }
}
