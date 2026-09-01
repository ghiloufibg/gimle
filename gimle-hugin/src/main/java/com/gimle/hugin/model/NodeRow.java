package com.gimle.hugin.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * One node, as the cluster view draws it: the fields of {@code GET /nodes} that have a column,
 * already flattened out of that response's nested {@code capacity} object, plus the count of
 * instances currently placed here.
 */
public record NodeRow(
    String nodeId,
    boolean cordoned,
    long assignedCpuMillicores,
    long totalCpuMillicores,
    long assignedMemoryBytes,
    long totalMemoryBytes,
    int instanceCount,
    Optional<Instant> lastHeartbeatAt) {

  /**
   * Matches {@code NodesCommand}'s own threshold, which in turn matches the console's, so a node
   * that reads stale in one surface reads stale in the other two.
   */
  private static final Duration STALE_AFTER = Duration.ofSeconds(30);

  public NodeRow {
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    if (lastHeartbeatAt == null) {
      throw new IllegalArgumentException("lastHeartbeatAt must not be null; use Optional.empty()");
    }
  }

  /**
   * The single word the STATE column shows. Cordoned wins over heartbeat freshness: an operator who
   * cordoned a node wants to see that, not a heartbeat verdict that says nothing about why the
   * scheduler is avoiding it.
   */
  public String state(final Instant now) {
    if (cordoned) {
      return "CORDONED";
    }
    if (lastHeartbeatAt.isEmpty()) {
      return "UNKNOWN";
    }
    return isStale(now) ? "STALE" : "READY";
  }

  public boolean isStale(final Instant now) {
    return lastHeartbeatAt
        .map(at -> Duration.between(at, now).compareTo(STALE_AFTER) > 0)
        .orElse(true);
  }

  public Optional<Duration> heartbeatAge(final Instant now) {
    return lastHeartbeatAt.map(at -> Duration.between(at, now));
  }

  /** True when this node reported no capacity at all -- it has never heartbeated. */
  public boolean hasCapacity() {
    return totalCpuMillicores > 0 || totalMemoryBytes > 0;
  }
}
