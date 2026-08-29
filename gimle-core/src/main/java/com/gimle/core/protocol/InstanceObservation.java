package com.gimle.core.protocol;

import com.gimle.core.module.ModuleId;
import java.util.Map;
import java.util.Optional;

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
 *
 * <p>{@code volumeUsageBytes} is the agent's last sampled on-disk size of this instance's
 * persistent volume -- 0 for the overwhelming majority of instances, which hold no volume. A soft
 * observation for operators and quota visibility, sampled on a coarse interval (not per heartbeat),
 * never an enforced ceiling.
 *
 * <p>{@code workerId} is the raw id ({@code "worker-" + pid}) the worker JVM hosting this instance
 * reported in its own {@code Hello} handshake with the agent -- {@link Optional#empty()} until that
 * handshake completes (a plain Vessel instance never sends one either, since it's an OS process,
 * not a worker JVM). Combined with the assignment's own {@code nodeId} (tracked alongside this
 * observation, not duplicated into it) as {@code nodeId:workerId}, this is the same processId shape
 * a worker's own shipped metrics/traces use -- what closes the gap where an operator had no way to
 * discover which worker to pick in the console's Metrics/Traces process picker for a given
 * instance.
 *
 * <p>{@code tenantId} is the owning deployment/job's own tenant, copied straight from the
 * assignment's {@code AssignedInstance#tenantId()} the same observation-building call site already
 * reads every other field from. Without it, a heartbeat match keyed on {@code (deploymentName,
 * instanceIndex)} alone cannot tell two different tenants' identically-named workload apart if a
 * scheduler placement (nothing prevents this once names are tenant-scoped, not globally unique)
 * ever lands both on the very same node -- every {@code *Reconciler}/{@code
 * ServiceEndpointResolver} match against this record's own {@code deploymentName}/{@code
 * instanceIndex} also checks {@code tenantId} for exactly this reason.
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
    Map<String, Integer> ports,
    long volumeUsageBytes,
    Optional<String> workerId,
    Optional<String> tenantId) {

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
    if (volumeUsageBytes < 0) {
      throw new IllegalArgumentException(
          "volumeUsageBytes must not be negative: " + volumeUsageBytes);
    }
    if (workerId == null) {
      throw new IllegalArgumentException("workerId must not be null; use Optional.empty()");
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must not be null; use Optional.empty()");
    }
    ports = Map.copyOf(ports);
  }

  /** Back-compat: defaults {@code tenantId} to {@link Optional#empty()}. */
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
      double errorRatePerSecond,
      Map<String, Integer> ports,
      long volumeUsageBytes,
      Optional<String> workerId) {
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
        ports,
        volumeUsageBytes,
        workerId,
        Optional.empty());
  }

  /** Back-compat: defaults {@code workerId} and {@code tenantId} to {@link Optional#empty()}. */
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
      double errorRatePerSecond,
      Map<String, Integer> ports,
      long volumeUsageBytes) {
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
        ports,
        volumeUsageBytes,
        Optional.empty());
  }

  /** Back-compat: defaults {@code volumeUsageBytes} to 0. */
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
      double errorRatePerSecond,
      Map<String, Integer> ports) {
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
        ports,
        0L);
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
