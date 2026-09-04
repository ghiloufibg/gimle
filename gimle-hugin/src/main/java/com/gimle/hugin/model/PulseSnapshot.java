package com.gimle.hugin.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The control plane's own account of itself, alongside its per-deployment traffic rollup.
 *
 * <p>Separate from {@link ClusterSnapshot} because it answers a different question: that one is
 * what the cluster is running, this is how the process serving those answers is doing. A control
 * plane that is up but has lost its store still lists deployments from nothing and would look fine
 * in the other reading.
 */
public record PulseSnapshot(
    String serverAddress,
    Optional<Instant> fetchedAt,
    String status,
    Optional<String> reason,
    long uptimeSeconds,
    String transportProtocol,
    int storeTenantCount,
    List<DeploymentTraffic> traffic,
    Optional<String> staleReason)
    implements Staleable<PulseSnapshot> {

  /** One deployment's measured traffic, as the control plane's own rollup reports it. */
  public record DeploymentTraffic(
      Optional<String> tenantId,
      String deploymentName,
      int instanceCount,
      double requestRatePerSecond,
      double errorRatePerSecond) {}

  public PulseSnapshot {
    if (serverAddress == null || serverAddress.isBlank()) {
      throw new IllegalArgumentException("serverAddress must not be blank");
    }
    if (fetchedAt == null || reason == null || staleReason == null) {
      throw new IllegalArgumentException("optional fields must not be null; use Optional.empty()");
    }
    traffic = List.copyOf(traffic);
  }

  public static PulseSnapshot connecting(final String serverAddress) {
    return new PulseSnapshot(
        serverAddress,
        Optional.empty(),
        "UNKNOWN",
        Optional.empty(),
        0L,
        "",
        0,
        List.of(),
        Optional.of("connecting"));
  }

  /**
   * A control plane that did not answer at all. Distinct from one that answered {@code DOWN}: the
   * second is a process reporting on itself, the first is no process reporting anything, and an
   * operator reading a health screen needs to know which of those they are looking at.
   */
  public static PulseSnapshot unreachable(final String serverAddress, final String why) {
    return new PulseSnapshot(
        serverAddress,
        Optional.of(Instant.now()),
        "UNREACHABLE",
        Optional.of(why),
        0L,
        "",
        0,
        List.of(),
        Optional.empty());
  }

  @Override
  public PulseSnapshot stale(final String reason) {
    return new PulseSnapshot(
        serverAddress,
        fetchedAt,
        status,
        this.reason,
        uptimeSeconds,
        transportProtocol,
        storeTenantCount,
        traffic,
        Optional.of(reason));
  }

  public boolean connected() {
    return fetchedAt.isPresent() && staleReason.isEmpty();
  }

  public Optional<Duration> age(final Instant now) {
    return fetchedAt.map(at -> Duration.between(at, now));
  }

  public boolean healthy() {
    return "UP".equals(status);
  }

  /** The busiest deployments first: on a screen with room for a few, those are the few. */
  public List<DeploymentTraffic> busiestFirst() {
    return traffic.stream()
        .sorted(
            Comparator.comparingDouble(DeploymentTraffic::requestRatePerSecond)
                .reversed()
                .thenComparing(DeploymentTraffic::deploymentName))
        .toList();
  }

  /** Deployments currently reporting errors, which is what this screen is opened to find. */
  public List<DeploymentTraffic> erroring() {
    return traffic.stream()
        .filter(row -> row.errorRatePerSecond() > 0)
        .sorted(
            Comparator.comparingDouble(DeploymentTraffic::errorRatePerSecond)
                .reversed()
                .thenComparing(DeploymentTraffic::deploymentName))
        .toList();
  }
}
