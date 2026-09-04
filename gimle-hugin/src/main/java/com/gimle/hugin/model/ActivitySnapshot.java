package com.gimle.hugin.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** The activity feed as one immutable reading, in the same shape the other snapshots take. */
public record ActivitySnapshot(
    String serverAddress,
    Optional<Instant> fetchedAt,
    List<FeedRow> events,
    FeedMode mode,
    boolean permitted,
    Optional<String> nextCursor,
    Optional<String> staleReason)
    implements Staleable<ActivitySnapshot> {

  public ActivitySnapshot {
    if (serverAddress == null || serverAddress.isBlank()) {
      throw new IllegalArgumentException("serverAddress must not be blank");
    }
    if (fetchedAt == null || staleReason == null || nextCursor == null) {
      throw new IllegalArgumentException("optional fields must not be null; use Optional.empty()");
    }
    events = List.copyOf(events);
  }

  /** The verdicts worth counting, across all three feeds. */
  private static final Set<String> NOTABLE =
      Set.of("DENIED", "REJECTED", "TRANSITION_FAILED", "FIRING");

  public static ActivitySnapshot connecting(final String serverAddress, final FeedMode mode) {
    return new ActivitySnapshot(
        serverAddress,
        Optional.empty(),
        List.of(),
        mode,
        true,
        Optional.empty(),
        Optional.of("connecting"));
  }

  /**
   * Each feed here is gated on a permission of its own, so a caller whose certificate does not
   * carry it is told exactly that rather than shown an empty feed -- which would read as a quiet
   * cluster.
   */
  public static ActivitySnapshot forbidden(final String serverAddress, final FeedMode mode) {
    return new ActivitySnapshot(
        serverAddress,
        Optional.of(Instant.now()),
        List.of(),
        mode,
        false,
        Optional.empty(),
        Optional.empty());
  }

  @Override
  public ActivitySnapshot stale(final String reason) {
    return new ActivitySnapshot(
        serverAddress, fetchedAt, events, mode, permitted, nextCursor, Optional.of(reason));
  }

  /** Whether the trail has pages this snapshot has not asked for yet. */
  public boolean hasMore() {
    return nextCursor.isPresent();
  }

  public boolean connected() {
    return fetchedAt.isPresent() && staleReason.isEmpty();
  }

  public Optional<Duration> age(final Instant now) {
    return fetchedAt.map(at -> Duration.between(at, now));
  }

  /**
   * How many rows are the ones an operator opened this feed to find: a decision refused, a
   * transition that failed, a rule currently firing. Counted on the status line so the answer is
   * legible without reading every row.
   */
  public long notableCount() {
    return events.stream().filter(row -> NOTABLE.contains(row.verdict())).count();
  }

  public List<FeedRow> matching(final String filter) {
    if (filter == null || filter.isBlank()) {
      return events;
    }
    String needle = filter.toLowerCase(Locale.ROOT);
    return events.stream().filter(row -> row.searchText().contains(needle)).toList();
  }
}
