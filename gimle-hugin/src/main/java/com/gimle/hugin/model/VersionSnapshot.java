package com.gimle.hugin.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Every revision one config key, ConfigMap, secret or SecretMap has had, newest first.
 *
 * <p>{@code available} false means the kind keeps no history at all, which is a different answer
 * from a history nobody could read and a different one again from a resource that has only ever
 * been written once. All three would otherwise arrive as an empty list.
 */
public record VersionSnapshot(
    String serverAddress,
    Optional<Instant> fetchedAt,
    String subject,
    List<VersionRow> rows,
    boolean available,
    Optional<String> staleReason)
    implements Staleable<VersionSnapshot> {

  public VersionSnapshot {
    if (serverAddress == null || serverAddress.isBlank()) {
      throw new IllegalArgumentException("serverAddress must not be blank");
    }
    if (subject == null || subject.isBlank()) {
      throw new IllegalArgumentException("subject must not be blank");
    }
    if (fetchedAt == null || staleReason == null) {
      throw new IllegalArgumentException("optional fields must not be null; use Optional.empty()");
    }
    rows = List.copyOf(rows);
  }

  public static VersionSnapshot connecting(final String serverAddress, final String subject) {
    return new VersionSnapshot(
        serverAddress, Optional.empty(), subject, List.of(), true, Optional.of("connecting"));
  }

  /** A kind whose ledger does not exist, as distinct from one whose ledger is empty. */
  public static VersionSnapshot unavailable(
      final String serverAddress, final String subject, final String why) {
    return new VersionSnapshot(
        serverAddress, Optional.of(Instant.now()), subject, List.of(), false, Optional.of(why));
  }

  @Override
  public VersionSnapshot stale(final String reason) {
    return new VersionSnapshot(
        serverAddress, fetchedAt, subject, rows, available, Optional.of(reason));
  }

  public boolean connected() {
    return fetchedAt.isPresent() && staleReason.isEmpty();
  }

  public Optional<Duration> age(final Instant now) {
    return fetchedAt.map(at -> Duration.between(at, now));
  }

  public List<VersionRow> matching(final String filter) {
    if (filter == null || filter.isBlank()) {
      return rows;
    }
    String needle = filter.toLowerCase(Locale.ROOT);
    return rows.stream().filter(row -> row.searchText().contains(needle)).toList();
  }

  /** The revision currently in effect, which is the one every other row is a predecessor of. */
  public Optional<VersionRow> current() {
    return rows.stream().findFirst();
  }
}
