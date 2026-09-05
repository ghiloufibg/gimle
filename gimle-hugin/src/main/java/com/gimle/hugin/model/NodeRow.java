package com.gimle.hugin.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    String status,
    Optional<Instant> lastHeartbeatAt,
    List<String> supportedTiers,
    List<String> labels,
    List<String> taints) {

  public NodeRow {
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    if (lastHeartbeatAt == null) {
      throw new IllegalArgumentException("lastHeartbeatAt must not be null; use Optional.empty()");
    }
    supportedTiers = List.copyOf(supportedTiers);
    labels = List.copyOf(labels);
    taints = List.copyOf(taints);
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
    return switch (status) {
      case "HEALTHY" -> "READY";
      case "PENDING" -> "PENDING";
      case "STALE" -> "STALE";
      default -> "UNKNOWN";
    };
  }

  /**
   * Whether to draw this node's heartbeat age as a warning. {@code PENDING} deliberately isn't one:
   * it says the control plane has only just started listening, which is not the node's fault and
   * would otherwise paint every node in the cluster amber after a store election.
   */
  public boolean isStale(final Instant now) {
    return "STALE".equals(status) || "UNKNOWN".equals(status);
  }

  public Optional<Duration> heartbeatAge(final Instant now) {
    return lastHeartbeatAt.map(at -> Duration.between(at, now));
  }

  /** True when this node reported no capacity at all -- it has never heartbeated. */
  public boolean hasCapacity() {
    return totalCpuMillicores > 0 || totalMemoryBytes > 0;
  }
}
