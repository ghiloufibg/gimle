package com.gimle.core.protocol;

import com.gimle.core.module.ModuleId;
import java.util.Map;

/**
 * One node's current view of one deployment instance it's supervising: which module it's running,
 * its lifecycle state, and liveness/readiness. {@code lifecycleState} travels as a plain {@code
 * String} rather than {@code gimle-module}'s own state enum, since {@code gimle-core} has no
 * dependency on {@code gimle-module} and the control plane only needs to track, relay, and diff the
 * state, not interpret it.
 *
 * <p>{@code requestRatePerSecond}/{@code errorRatePerSecond}/{@code queueDepth}/{@code
 * cpuMillicoresUsed}/{@code memoryBytesUsed} feed autoscaling decisions: {@code cpuMillicoresUsed}
 * divided by the module descriptor's requested CPU gives the observed CPU utilization used as a
 * scaling signal, alongside request rate, error rate, and queue depth. The six-argument constructor
 * defaults all five to {@code 0} for call sites that only track lifecycle/health, not scaling
 * metrics. {@code errorRatePerSecond} was added after the other four -- appended last, with the
 * pre-existing ten-argument constructor preserved as an overload, so every call site built against
 * the earlier shape keeps compiling unchanged.
 *
 * <p>{@code ports}, keyed by the {@code vessel.env} variable name each was declared under (e.g.
 * {@code "HTTP_PORT"}), carries a vessel instance's own agent-allocated or fixed port numbers --
 * empty for every non-vessel instance, and for a vessel that declares none. This is what {@code GET
 * /endpoints/{deployment}} joins against a node's own registered address to answer "where is this
 * instance actually reachable."
 */
public record InstanceObservation(
    String deploymentName,
    int instanceIndex,
    ModuleId moduleId,
    String lifecycleState,
    boolean alive,
    boolean ready,
    double requestRatePerSecond,
    int queueDepth,
    long cpuMillicoresUsed,
    long memoryBytesUsed,
    double errorRatePerSecond,
    Map<String, Integer> ports) {

  public InstanceObservation {
    if (deploymentName == null || deploymentName.isBlank()) {
      throw new IllegalArgumentException("deploymentName must not be blank");
    }
    if (instanceIndex < 0) {
      throw new IllegalArgumentException("instanceIndex must not be negative: " + instanceIndex);
    }
    if (moduleId == null) {
      throw new IllegalArgumentException("moduleId must not be null");
    }
    if (lifecycleState == null || lifecycleState.isBlank()) {
      throw new IllegalArgumentException("lifecycleState must not be blank");
    }
    if (ports == null) {
      throw new IllegalArgumentException("ports must not be null; use Map.of()");
    }
    ports = Map.copyOf(ports);
  }

  /** Back-compat: defaults {@code ports} to an empty map. */
  public InstanceObservation(
      String deploymentName,
      int instanceIndex,
      ModuleId moduleId,
      String lifecycleState,
      boolean alive,
      boolean ready,
      double requestRatePerSecond,
      int queueDepth,
      long cpuMillicoresUsed,
      long memoryBytesUsed,
      double errorRatePerSecond) {
    this(
        deploymentName,
        instanceIndex,
        moduleId,
        lifecycleState,
        alive,
        ready,
        requestRatePerSecond,
        queueDepth,
        cpuMillicoresUsed,
        memoryBytesUsed,
        errorRatePerSecond,
        Map.of());
  }

  public InstanceObservation(
      String deploymentName,
      int instanceIndex,
      ModuleId moduleId,
      String lifecycleState,
      boolean alive,
      boolean ready) {
    this(
        deploymentName, instanceIndex, moduleId, lifecycleState, alive, ready, 0.0, 0, 0L, 0L, 0.0);
  }

  public InstanceObservation(
      String deploymentName,
      int instanceIndex,
      ModuleId moduleId,
      String lifecycleState,
      boolean alive,
      boolean ready,
      double requestRatePerSecond,
      int queueDepth) {
    this(
        deploymentName,
        instanceIndex,
        moduleId,
        lifecycleState,
        alive,
        ready,
        requestRatePerSecond,
        queueDepth,
        0L,
        0L,
        0.0);
  }

  /** The pre-{@code errorRatePerSecond} full-detail shape, kept for existing call sites. */
  public InstanceObservation(
      String deploymentName,
      int instanceIndex,
      ModuleId moduleId,
      String lifecycleState,
      boolean alive,
      boolean ready,
      double requestRatePerSecond,
      int queueDepth,
      long cpuMillicoresUsed,
      long memoryBytesUsed) {
    this(
        deploymentName,
        instanceIndex,
        moduleId,
        lifecycleState,
        alive,
        ready,
        requestRatePerSecond,
        queueDepth,
        cpuMillicoresUsed,
        memoryBytesUsed,
        0.0);
  }
}
