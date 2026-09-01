package com.gimle.hugin.model;

import java.util.Map;
import java.util.Optional;

/**
 * One line out of the log stream, in whichever of the two shapes the log readers emit: a structured
 * line with a level and a logger, or a raw one captured from a process's own stdout/stderr with
 * neither.
 */
public record LogLine(String timestamp, Optional<String> level, Optional<String> message) {

  public LogLine {
    if (timestamp == null) {
      throw new IllegalArgumentException("timestamp must not be null");
    }
    if (level == null || message == null) {
      throw new IllegalArgumentException("optional fields must not be null; use Optional.empty()");
    }
  }

  /**
   * Reads one line object. A raw capture has no level of its own, so it gets none here either --
   * the pane shows it in the muted shade rather than guessing a severity for it.
   */
  public static LogLine from(final Map<String, Object> line) {
    String timestamp = String.valueOf(line.getOrDefault("timestamp", ""));
    Object raw = line.get("raw");
    if (raw != null) {
      return new LogLine(timestamp, Optional.empty(), Optional.of(String.valueOf(raw)));
    }
    return new LogLine(timestamp, text(line.get("level")), text(line.get("message")));
  }

  /** The clock part of an ISO timestamp -- the whole date wastes a third of a narrow log pane. */
  public String clock() {
    int marker = timestamp.indexOf('T');
    if (marker < 0) {
      return timestamp;
    }
    String time = timestamp.substring(marker + 1);
    // Trim the zone suffix and sub-millisecond digits: neither survives being read at a glance.
    int dot = time.indexOf('.');
    return dot < 0 ? stripZone(time) : time.substring(0, Math.min(dot + 4, time.length()));
  }

  private static String stripZone(final String time) {
    int zone = time.indexOf('Z');
    return zone < 0 ? time : time.substring(0, zone);
  }

  private static Optional<String> text(final Object value) {
    return value instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
  }
}
