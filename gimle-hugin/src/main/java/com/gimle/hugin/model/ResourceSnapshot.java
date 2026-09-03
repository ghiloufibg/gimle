package com.gimle.hugin.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One immutable read of a single kind's collection, in the same shape every other snapshot here
 * takes so it can ride the same poller.
 *
 * <p>{@code permitted} false is the one failure worth reporting as a state rather than retrying:
 * each collection route is gated on its own resource permission, and a caller whose certificate
 * lacks it must be told that instead of shown an empty table -- an empty table reads as "this
 * cluster has no tenants", which is a different and much more alarming claim.
 */
public record ResourceSnapshot(
    String serverAddress,
    Optional<Instant> fetchedAt,
    ResourceKind kind,
    List<ResourceRow> rows,
    boolean permitted,
    Optional<String> staleReason)
    implements Staleable<ResourceSnapshot> {

  public ResourceSnapshot {
    if (serverAddress == null || serverAddress.isBlank()) {
      throw new IllegalArgumentException("serverAddress must not be blank");
    }
    if (kind == null) {
      throw new IllegalArgumentException("kind must not be null");
    }
    if (fetchedAt == null || staleReason == null) {
      throw new IllegalArgumentException("optional fields must not be null; use Optional.empty()");
    }
    rows = List.copyOf(rows);
  }

  public static ResourceSnapshot connecting(final String serverAddress, final ResourceKind kind) {
    return new ResourceSnapshot(
        serverAddress, Optional.empty(), kind, List.of(), true, Optional.of("connecting"));
  }

  public static ResourceSnapshot forbidden(final String serverAddress, final ResourceKind kind) {
    return new ResourceSnapshot(
        serverAddress, Optional.of(Instant.now()), kind, List.of(), false, Optional.empty());
  }

  @Override
  public ResourceSnapshot stale(final String reason) {
    return new ResourceSnapshot(
        serverAddress, fetchedAt, kind, rows, permitted, Optional.of(reason));
  }

  public boolean connected() {
    return fetchedAt.isPresent() && staleReason.isEmpty();
  }

  public Optional<Duration> age(final Instant now) {
    return fetchedAt.map(at -> Duration.between(at, now));
  }

  /** Shared with every other screen's filter, so one keystroke narrows whichever is open. */
  public List<ResourceRow> matching(final String filter) {
    if (filter == null || filter.isBlank()) {
      return rows;
    }
    String needle = filter.toLowerCase(Locale.ROOT);
    return rows.stream().filter(row -> row.searchText().contains(needle)).toList();
  }

  /** The row the cursor names, if it is still in the list this snapshot carries. */
  public Optional<ResourceRow> find(final String name) {
    return rows.stream().filter(row -> row.name().equals(name)).findFirst();
  }
}
