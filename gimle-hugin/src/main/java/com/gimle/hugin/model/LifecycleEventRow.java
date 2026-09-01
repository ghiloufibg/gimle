package com.gimle.hugin.model;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** One entry from an instance's own lifecycle timeline, as {@code GET /events} returns it. */
public record LifecycleEventRow(
    Instant occurredAt, String kind, String message, Optional<String> causeSummary) {

  public LifecycleEventRow {
    if (occurredAt == null) {
      throw new IllegalArgumentException("occurredAt must not be null");
    }
    if (kind == null || kind.isBlank()) {
      throw new IllegalArgumentException("kind must not be blank");
    }
    if (message == null || causeSummary == null) {
      throw new IllegalArgumentException("message/causeSummary must not be null");
    }
  }

  public static LifecycleEventRow from(final Map<String, Object> event) {
    long epochMilli = event.get("occurredAtEpochMilli") instanceof Number n ? n.longValue() : 0L;
    Object cause = event.get("causeSummary");
    return new LifecycleEventRow(
        Instant.ofEpochMilli(epochMilli),
        String.valueOf(event.getOrDefault("kind", "UNKNOWN")),
        String.valueOf(event.getOrDefault("message", "")),
        cause instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty());
  }
}
