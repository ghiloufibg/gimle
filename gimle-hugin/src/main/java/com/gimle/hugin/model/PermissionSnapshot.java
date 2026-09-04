package com.gimle.hugin.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What this caller may do, kind by kind and verb by verb, as the control plane itself answers it.
 *
 * <p>Answered rather than computed: the roles and bindings behind the decision are already
 * browsable as tables, and reading them back into a verdict here would be a second implementation
 * of the authorizer that could disagree with the real one. Every cell in this grid is the control
 * plane's own answer to the one question that matters.
 *
 * <p>{@code principal} is whoever it answered as, which is not always who asked: over a plaintext
 * transport there is no client certificate to identify anyone, so the control plane answers as an
 * anonymous caller and allows everything. That grid is about the transport, not about any
 * particular identity's grants, and {@link #anonymous()} is what lets the screen say so instead of
 * presenting it as an account's own permissions.
 */
public record PermissionSnapshot(
    String serverAddress,
    Optional<Instant> fetchedAt,
    String principal,
    Optional<String> tenantId,
    List<String> verbs,
    List<PermissionRow> rows,
    boolean readable,
    Optional<String> staleReason)
    implements Staleable<PermissionSnapshot> {

  /** How the control plane names a caller it could not identify. */
  private static final String ANONYMOUS = "anonymous";

  public PermissionSnapshot {
    if (serverAddress == null || serverAddress.isBlank()) {
      throw new IllegalArgumentException("serverAddress must not be blank");
    }
    if (principal == null || principal.isBlank()) {
      throw new IllegalArgumentException("principal must not be blank");
    }
    if (fetchedAt == null || tenantId == null || staleReason == null) {
      throw new IllegalArgumentException("optional fields must not be null; use Optional.empty()");
    }
    verbs = List.copyOf(verbs);
    rows = List.copyOf(rows);
  }

  public static PermissionSnapshot connecting(final String serverAddress) {
    return new PermissionSnapshot(
        serverAddress,
        Optional.empty(),
        "—",
        Optional.empty(),
        List.of(),
        List.of(),
        true,
        Optional.of("connecting"));
  }

  /**
   * The control plane would not say what this caller may do. Reported as a state rather than an
   * empty grid, which would read as "you may do nothing" -- a different and much more specific
   * claim than "nobody would tell me".
   */
  public static PermissionSnapshot unreadable(final String serverAddress, final String why) {
    return new PermissionSnapshot(
        serverAddress,
        Optional.of(Instant.now()),
        "—",
        Optional.empty(),
        List.of(),
        List.of(),
        false,
        Optional.of(why));
  }

  @Override
  public PermissionSnapshot stale(final String reason) {
    return new PermissionSnapshot(
        serverAddress, fetchedAt, principal, tenantId, verbs, rows, readable, Optional.of(reason));
  }

  public boolean connected() {
    return fetchedAt.isPresent() && staleReason.isEmpty();
  }

  public Optional<Duration> age(final Instant now) {
    return fetchedAt.map(at -> Duration.between(at, now));
  }

  /**
   * Whether the grid describes an unidentified caller. True means the transport carried no client
   * certificate, so every cell says yes and none of it is about anyone's grants.
   */
  public boolean anonymous() {
    return ANONYMOUS.equals(principal);
  }

  /** The rows matching the operator's filter, over the kind and the verbs actually granted. */
  public List<PermissionRow> matching(final String filter) {
    if (filter == null || filter.isBlank()) {
      return rows;
    }
    String needle = filter.toLowerCase(Locale.ROOT);
    return rows.stream().filter(row -> row.searchText().contains(needle)).toList();
  }

  /** How many kinds this caller may touch at all -- the one-line summary of the whole grid. */
  public long allowedKindCount() {
    return rows.stream().filter(PermissionRow::anyAllowed).count();
  }

  /** Cells the control plane never answered, which is what makes a partial read visible. */
  public long unansweredCount() {
    return rows.stream()
        .mapToLong(row -> verbs.stream().filter(verb -> row.allowed(verb).isEmpty()).count())
        .sum();
  }
}
