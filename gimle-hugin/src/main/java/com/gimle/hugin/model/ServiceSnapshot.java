package com.gimle.hugin.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
    Optional<String> staleReason)
    implements Staleable<ServiceSnapshot> {

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
  @Override
  public ServiceSnapshot stale(final String reason) {
    return new ServiceSnapshot(serverAddress, fetchedAt, services, Optional.of(reason));
  }

  /** This reading narrowed to one tenant's own Services, or unchanged when none is chosen. */
  public ServiceSnapshot scopedTo(final Optional<String> tenantId) {
    if (tenantId.isEmpty()) {
      return this;
    }
    return new ServiceSnapshot(
        serverAddress,
        fetchedAt,
        services.stream().filter(row -> tenantId.equals(row.tenantId())).toList(),
        staleReason);
  }

  public boolean connected() {
    return fetchedAt.isPresent() && staleReason.isEmpty();
  }

  public Optional<Duration> age(final Instant now) {
    return fetchedAt.map(at -> Duration.between(at, now));
  }

  /** How many Services resolve to nothing -- the number this whole view exists to make visible. */
  /**
   * The Services matching the operator's own filter, over the name, the tenant and the deployments
   * a Service fronts -- the three things they would think to type. Shared with the cluster view's
   * filter so one keystroke narrows whichever screen is open rather than being retyped per screen.
   */
  public List<ServiceRow> matching(final String filter) {
    if (filter == null || filter.isBlank()) {
      return services;
    }
    String needle = filter.toLowerCase(Locale.ROOT);
    return services.stream()
        .filter(
            service ->
                (service.name()
                        + " "
                        + service.tenantId().orElse("")
                        + " "
                        + String.join(" ", service.deploymentNames()))
                    .toLowerCase(Locale.ROOT)
                    .contains(needle))
        .toList();
  }

  public int unresolvedCount() {
    return (int) services.stream().filter(ServiceRow::unresolved).count();
  }

  /** Every live endpoint across every Service. A count nobody could read contributes nothing. */
  public int endpointCount() {
    return services.stream().mapToInt(row -> row.endpointCount().orElse(0)).sum();
  }
}
