package com.gimle.hugin.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One immutable read of every Service and the endpoints each currently resolves to, published by
 * {@link ServicePoller} and read by the renderer as a pure value -- the same discipline {@link
 * ClusterSnapshot} follows, for the same reason: the render loop must never wait on HTTP.
 *
 * <p>{@code fetchedAt} empty means no read has ever succeeded. {@code staleReason} present means
 * the most recent one failed and these rows are the last good ones: a Service table that blanks the
 * moment a request times out is worse than one that says how old it is.
 */
public record ServiceSnapshot(
    String serverAddress,
    Optional<Instant> fetchedAt,
    List<ServiceRow> services,
    Optional<String> staleReason) {

  public ServiceSnapshot {
    if (serverAddress == null || serverAddress.isBlank()) {
      throw new IllegalArgumentException("serverAddress must not be blank");
    }
    if (fetchedAt == null || staleReason == null) {
      throw new IllegalArgumentException("optional fields must not be null; use Optional.empty()");
    }
    services = List.copyOf(services);
  }

  /** The starting state: connected to nothing yet, showing nothing. */
  public static ServiceSnapshot connecting(final String serverAddress) {
    return new ServiceSnapshot(
        serverAddress, Optional.empty(), List.of(), Optional.of("connecting"));
  }

  /** These rows, re-labelled as the last good data behind a now-failing read. */
  public ServiceSnapshot stale(final String reason) {
    return new ServiceSnapshot(serverAddress, fetchedAt, services, Optional.of(reason));
  }

  public boolean connected() {
    return fetchedAt.isPresent() && staleReason.isEmpty();
  }

  public Optional<Duration> age(final Instant now) {
    return fetchedAt.map(at -> Duration.between(at, now));
  }

  /** How many Services resolve to nothing -- the number this whole view exists to make visible. */
  public int unresolvedCount() {
    return (int) services.stream().filter(ServiceRow::unresolved).count();
  }

  /** Every live endpoint across every Service. A count nobody could read contributes nothing. */
  public int endpointCount() {
    return services.stream().mapToInt(row -> row.endpointCount().orElse(0)).sum();
  }
}
