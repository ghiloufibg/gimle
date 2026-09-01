package com.gimle.core.logging;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side content filter applied to already-parsed log lines, alongside the timestamp cursor
 * every log reader here already understands. Deliberately one shared type rather than a per-surface
 * reimplementation: {@link LogFileReader} (a node's own local files), the agent's log HTTP surface,
 * the control plane's own platform log, and the cluster-wide shipped history all apply this exact
 * object, so an operator's {@code level}/{@code contains} query means the same thing whichever of
 * them ends up answering.
 *
 * <p>Two independent, ANDed predicates, both optional:
 *
 * <ul>
 *   <li><b>level</b> is a <i>threshold</i>, not an equality test: {@code WARN} matches {@code WARN}
 *       and {@code ERROR}, which is what an operator hunting a problem actually wants. A line
 *       carrying no {@code level} field, or one this class doesn't rank (a raw, unstructured SYSTEM
 *       capture), can't be placed against a threshold at all and therefore never satisfies one --
 *       it is excluded rather than silently admitted.
 *   <li><b>contains</b> is a plain, case-insensitive <i>substring</i> test -- never a regular
 *       expression, so an operator pasting a message fragment containing {@code (}, {@code [} or
 *       {@code .} gets a literal match instead of a syntax error or a surprising wildcard. It is
 *       tested against a line's human-readable fields only ({@code message}, {@code logger}, {@code
 *       stackTrace}, {@code raw}), not against machine identifiers like {@code nodeId} or {@code
 *       timestamp}, which would make almost any query match everything from one node.
 * </ul>
 *
 * <p>Both components are nullable, meaning "no constraint"; {@link #NONE} is the shared instance
 * for "no filtering at all".
 */
public record LogFilter(Level minLevel, String contains) {

  /** Ordered lowest-to-highest -- ordinal order is what {@link #matchesLevel} compares. */
  public enum Level {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR
  }

  public static final LogFilter NONE = new LogFilter(null, null);

  /**
   * The only fields a {@code contains} query is tested against. {@code raw} covers unstructured
   * SYSTEM capture lines, which have no {@code message} at all.
   */
  private static final List<String> SEARCHABLE_FIELDS =
      List.of("message", "logger", "stackTrace", "raw");

  public LogFilter {
    contains = contains == null || contains.isBlank() ? null : contains;
  }

  /**
   * Builds a filter from the two raw query-parameter values both HTTP log surfaces accept, either
   * of which may be {@code null} or blank ("not supplied").
   *
   * @throws IllegalArgumentException if {@code level} is supplied but names no known level -- both
   *     servers map this to a 400 with the message below, so a typo tells the operator what the
   *     accepted values are rather than silently returning an unfiltered page
   */
  public static LogFilter of(final String level, final String contains) {
    if (level == null || level.isBlank()) {
      return new LogFilter(null, contains);
    }
    try {
      return new LogFilter(Level.valueOf(level.trim().toUpperCase(Locale.ROOT)), contains);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "invalid log level: " + level + " (expected one of " + List.of(Level.values()) + ")", e);
    }
  }

  /** The same two parameter names on every log route: {@code level} and {@code contains}. */
  public static LogFilter fromQuery(final Map<String, String> query) {
    return of(query.get("level"), query.get("contains"));
  }

  public boolean isEmpty() {
    return minLevel == null && contains == null;
  }

  /** True when {@code line} should be shown -- both constraints satisfied, or none present. */
  public boolean matches(final Map<String, Object> line) {
    return matchesLevel(line) && matchesText(line);
  }

  private boolean matchesLevel(final Map<String, Object> line) {
    if (minLevel == null) {
      return true;
    }
    final Object level = line.get("level");
    if (!(level instanceof String text)) {
      return false;
    }
    try {
      return Level.valueOf(text.trim().toUpperCase(Locale.ROOT)).ordinal() >= minLevel.ordinal();
    } catch (IllegalArgumentException e) {
      // A level this class can't rank (a custom Logback level, say) is unplaceable against a
      // threshold -- excluded, the same as a line with no level at all.
      return false;
    }
  }

  private boolean matchesText(final Map<String, Object> line) {
    if (contains == null) {
      return true;
    }
    final String needle = contains.toLowerCase(Locale.ROOT);
    for (final String field : SEARCHABLE_FIELDS) {
      final Object value = line.get(field);
      if (value instanceof String text && text.toLowerCase(Locale.ROOT).contains(needle)) {
        return true;
      }
    }
    return false;
  }

  /** Human-readable rendering for "nothing matched" messages on the CLI and console. */
  public String describe() {
    if (isEmpty()) {
      return "no filter";
    }
    final StringBuilder text = new StringBuilder();
    if (minLevel != null) {
      text.append("level >= ").append(minLevel);
    }
    if (contains != null) {
      text.append(text.isEmpty() ? "" : ", ").append("containing \"").append(contains).append('"');
    }
    return text.toString();
  }
}
