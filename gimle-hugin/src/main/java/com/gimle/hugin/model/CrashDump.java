package com.gimle.hugin.model;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

/**
 * One {@code hs_err_pid*.log} the node kept beside a worker JVM that died on it. The JVM writes
 * these itself on a native crash, outside the logging pipeline entirely, so they are a directory
 * listing rather than anything the log tail would ever carry.
 */
public record CrashDump(String name, long sizeBytes, Optional<Instant> lastModified) {

  public CrashDump {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (lastModified == null) {
      throw new IllegalArgumentException("lastModified must not be null; use Optional.empty()");
    }
  }

  /** Reads one listing entry, or nothing at all for an entry this build cannot make sense of. */
  public static Optional<CrashDump> from(final Map<String, Object> entry) {
    Object name = entry.get("name");
    if (!(name instanceof String text) || text.isBlank()) {
      return Optional.empty();
    }
    long size = entry.get("sizeBytes") instanceof Number number ? number.longValue() : 0L;
    return Optional.of(new CrashDump(text, size, instant(entry.get("lastModified"))));
  }

  private static Optional<Instant> instant(final Object value) {
    if (!(value instanceof String text) || text.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.parse(text));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
