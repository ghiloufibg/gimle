package com.gimle.hugin.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The activity feed as one immutable reading, in the same shape the other snapshots take. */
public record ActivitySnapshot(
    String serverAddress,
    Optional<Instant> fetchedAt,
    List<ActivityRow> events,
    boolean permitted,
    Optional<String> staleReason) {

  public ActivitySnapshot {
    if (serverAddress == null || serverAddress.isBlank()) {
      throw new IllegalArgumentException("serverAddress must not be blank");
    }
    if (fetchedAt == null || staleReason == null) {
      throw new IllegalArgumentException("optional fields must not be null; use Optional.empty()");
    }
    events = List.copyOf(events);
  }

  public static ActivitySnapshot connecting(final String serverAddress) {
    return new ActivitySnapshot(
        serverAddress, Optional.empty(), List.of(), true, Optional.of("connecting"));
  }

  /**
   * The audit trail is the one read in this view gated on a permission of its own, so a caller
   * whose certificate does not carry it is told exactly that rather than shown an empty feed --
   * which would read as a quiet cluster.
   */
  public static ActivitySnapshot forbidden(final String serverAddress) {
    return new ActivitySnapshot(
        serverAddress, Optional.of(Instant.now()), List.of(), false, Optional.empty());
  }

  public ActivitySnapshot stale(final String reason) {
    return new ActivitySnapshot(serverAddress, fetchedAt, events, permitted, Optional.of(reason));
  }

  public boolean connected() {
    return fetchedAt.isPresent() && staleReason.isEmpty();
  }

  public Optional<Duration> age(final Instant now) {
    return fetchedAt.map(at -> Duration.between(at, now));
  }

  /** Refusals are what an operator scans this feed for, so they are counted on the status line. */
  public long refusedCount() {
    return events.stream().filter(event -> !"APPLIED".equals(event.verdict())).count();
  }

  public List<ActivityRow> matching(final String filter) {
    if (filter == null || filter.isBlank()) {
      return events;
    }
    String needle = filter.toLowerCase(Locale.ROOT);
    return events.stream()
        .filter(
            event ->
                (event.principal() + " " + event.target() + " " + event.verb())
                    .toLowerCase(Locale.ROOT)
                    .contains(needle))
        .toList();
  }
}
