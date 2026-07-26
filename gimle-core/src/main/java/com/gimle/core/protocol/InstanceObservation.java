package com.gimle.core.protocol;

import com.gimle.core.module.ModuleId;

/**
 * One node's current view of one deployment instance it's supervising: which module it's running,
 * its lifecycle state, and liveness/readiness. {@code lifecycleState} travels as a plain {@code
 * String} (a {@code ModuleState} name), not {@code gimle-module}'s own enum -- same choice {@link
 * ControlMessage.ModuleStateChanged} already made and for the identical reason: {@code gimle-core}
 * has no dependency on {@code gimle-module}, and the control plane only needs to track/relay/diff
 * the state, not interpret it.
 *
 * <p>{@code requestRatePerSecond}/{@code queueDepth}/{@code cpuMillicoresUsed}/{@code
 * memoryBytesUsed} (Phase 4 §10) feed {@code AutoscaleReconciler} -- {@code cpuMillicoresUsed}
 * divided by the module descriptor's {@code resourceRequest.cpuMillicores()} is exactly the
 * "average observed CPU utilization" the design's autoscaling formula computes, so it travels
 * alongside the other two scaling signals here rather than being tracked separately. The
 * six-argument constructor defaults all four to {@code 0} for every call site that only ever
 * tracked lifecycle/health, not scaling metrics.
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
