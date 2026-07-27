package com.gimle.core.protocol;

import com.gimle.core.module.ModuleId;

/**
 * One node's current view of one deployment instance it's supervising: which module it's running,
 * its lifecycle state, and liveness/readiness. {@code lifecycleState} travels as a plain {@code
 * String} rather than {@code gimle-module}'s own state enum, since {@code gimle-core} has no
 * dependency on {@code gimle-module} and the control plane only needs to track, relay, and diff the
 * state, not interpret it.
 *
 * <p>{@code requestRatePerSecond}/{@code queueDepth}/{@code cpuMillicoresUsed}/{@code
 * memoryBytesUsed} feed autoscaling decisions: {@code cpuMillicoresUsed} divided by the module
 * descriptor's requested CPU gives the observed CPU utilization used as a scaling signal, alongside
 * request rate and queue depth. The six-argument constructor defaults all four to {@code 0} for
 * call sites that only track lifecycle/health, not scaling metrics.
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
    long memoryBytesUsed) {

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
  }

  public InstanceObservation(
      String deploymentName,
      int instanceIndex,
      ModuleId moduleId,
      String lifecycleState,
      boolean alive,
      boolean ready) {
    this(deploymentName, instanceIndex, moduleId, lifecycleState, alive, ready, 0.0, 0, 0L, 0L);
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
        0L);
  }
}
