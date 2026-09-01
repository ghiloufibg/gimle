package com.gimle.hugin.model;

import java.util.Locale;
import java.util.Optional;

/**
 * One placed instance, as the cluster view draws it. An instance the scheduler has placed but whose
 * node has not yet reported an observation for carries {@code observed = false}: its lifecycle and
 * metric columns have no value at all, which the renderer shows as an em dash rather than as a zero
 * that would read like a real measurement.
 */
public record InstanceRow(
    InstanceKey key,
    String nodeId,
    boolean observed,
    String lifecycleState,
    boolean alive,
    boolean ready,
    double requestRatePerSecond,
    double errorRatePerSecond,
    int queueDepth,
    long memoryBytesUsed,
    long cpuMillicoresUsed,
    Optional<String> moduleCoordinate,
    Optional<String> workerId) {

  public InstanceRow {
    if (key == null) {
      throw new IllegalArgumentException("key must not be null");
    }
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    if (lifecycleState == null || lifecycleState.isBlank()) {
      throw new IllegalArgumentException("lifecycleState must not be blank");
    }
    if (moduleCoordinate == null || workerId == null) {
      throw new IllegalArgumentException("optional fields must not be null; use Optional.empty()");
    }
  }

  public String deploymentName() {
    return key.deploymentName();
  }

  public int instanceIndex() {
    return key.instanceIndex();
  }

  public Optional<String> tenantId() {
    return key.tenantId();
  }

  /** The text a filter is matched against: everything an operator would think to type. */
  public String searchText() {
    return (deploymentName()
            + " "
            + instanceIndex()
            + " "
            + nodeId
            + " "
            + lifecycleState
            + " "
            + tenantId().orElse("")
            + " "
            + moduleCoordinate().orElse(""))
        .toLowerCase(Locale.ROOT);
  }
}
