package com.gimle.core.protocol;

import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ResourceSpec;
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
 * scaling signal, alongside request rate, error rate, and queue depth. All five default to {@code
 * 0} via {@link #builder}, for the call sites that only track lifecycle/health and never see a
 * metrics report.
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
 *
 * <p>{@code isolationTier} is the declared value from the running module's own descriptor. {@code
 * resourceLimit} is the real ceiling this instance's worker JVM was actually spawned under -- a
 * denominator for {@code cpuMillicoresUsed}/{@code memoryBytesUsed}, since a usage figure with no
 * ceiling beside it is a number, not a judgement. Both are {@link Optional#empty()} for a vessel
 * instance, which is an OS process with no module descriptor behind it at all.
 *
 * <p>{@code resourceLimit} is deliberately not always the module's own declared {@code
 * resources.limit}. At {@link IsolationTier#TIER_2} one instance owns its worker JVM, so its
 * declared limit is that instance's own enforced {@code -Xmx} ceiling and the two coincide. At
 * {@link IsolationTier#TIER_1} several instances share one worker JVM sized by the node's own
 * shared-worker budget, not by any one instance's manifest -- so {@code resourceLimit} there
 * carries that shared worker's real spawned size instead, which is the number a used/limit reader
 * actually needs. A module's own declared TIER_1 request/limit remains what admission and
 * scheduling use; it is simply not what this field reports.
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
    Optional<String> tenantId,
    Optional<IsolationTier> isolationTier,
    Optional<ResourceSpec> resourceLimit) {

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
    if (isolationTier == null) {
      throw new IllegalArgumentException("isolationTier must not be null; use Optional.empty()");
    }
    if (resourceLimit == null) {
      throw new IllegalArgumentException("resourceLimit must not be null; use Optional.empty()");
    }
    ports = Map.copyOf(ports);
  }

  /**
   * Starts an observation from the five identifying facts plus liveness -- everything a node can
   * always report about an instance it supervises. Every remaining field is a measurement or a
   * declaration that may genuinely be unavailable (a node that has had no metrics report yet, a
   * vessel with no module descriptor), so each defaults to zero/empty and is set only when known.
   * Required-in-the-factory rather than a builder setter: an observation naming no instance is not
   * a thing that should be constructible.
   */
  public static Builder builder(
      final String deploymentName,
      final int instanceIndex,
      final ModuleId moduleId,
      final String lifecycleState,
      final boolean alive,
      final boolean ready) {
    return new Builder(deploymentName, instanceIndex, moduleId, lifecycleState, alive, ready);
  }

  /** Mutable while assembling one {@link InstanceObservation}; the record it builds is not. */
  public static final class Builder {

    private final String deploymentName;
    private final int instanceIndex;
    private final ModuleId moduleId;
    private final String lifecycleState;
    private final boolean alive;
    private final boolean ready;

    private double requestRatePerSecond;
    private int queueDepth;
    private long cpuMillicoresUsed;
    private long memoryBytesUsed;
    private double errorRatePerSecond;
    private Map<String, Integer> ports = Map.of();
    private long volumeUsageBytes;
    private Optional<String> workerId = Optional.empty();
    private Optional<String> tenantId = Optional.empty();
    private Optional<IsolationTier> isolationTier = Optional.empty();
    private Optional<ResourceSpec> resourceLimit = Optional.empty();

    private Builder(
        final String deploymentName,
        final int instanceIndex,
        final ModuleId moduleId,
        final String lifecycleState,
        final boolean alive,
        final boolean ready) {
      this.deploymentName = deploymentName;
      this.instanceIndex = instanceIndex;
      this.moduleId = moduleId;
      this.lifecycleState = lifecycleState;
      this.alive = alive;
      this.ready = ready;
    }

    /** The four autoscaling signals, which a worker reports together in one metrics tick. */
    public Builder load(
        final double requestRatePerSecond,
        final double errorRatePerSecond,
        final int queueDepth,
        final long cpuMillicoresUsed,
        final long memoryBytesUsed) {
      this.requestRatePerSecond = requestRatePerSecond;
      this.errorRatePerSecond = errorRatePerSecond;
      this.queueDepth = queueDepth;
      this.cpuMillicoresUsed = cpuMillicoresUsed;
      this.memoryBytesUsed = memoryBytesUsed;
      return this;
    }

    public Builder requestRatePerSecond(final double requestRatePerSecond) {
      this.requestRatePerSecond = requestRatePerSecond;
      return this;
    }

    public Builder errorRatePerSecond(final double errorRatePerSecond) {
      this.errorRatePerSecond = errorRatePerSecond;
      return this;
    }

    public Builder queueDepth(final int queueDepth) {
      this.queueDepth = queueDepth;
      return this;
    }

    public Builder cpuMillicoresUsed(final long cpuMillicoresUsed) {
      this.cpuMillicoresUsed = cpuMillicoresUsed;
      return this;
    }

    public Builder memoryBytesUsed(final long memoryBytesUsed) {
      this.memoryBytesUsed = memoryBytesUsed;
      return this;
    }

    public Builder ports(final Map<String, Integer> ports) {
      this.ports = ports;
      return this;
    }

    public Builder volumeUsageBytes(final long volumeUsageBytes) {
      this.volumeUsageBytes = volumeUsageBytes;
      return this;
    }

    public Builder workerId(final Optional<String> workerId) {
      this.workerId = workerId;
      return this;
    }

    public Builder tenantId(final Optional<String> tenantId) {
      this.tenantId = tenantId;
      return this;
    }

    public Builder isolationTier(final Optional<IsolationTier> isolationTier) {
      this.isolationTier = isolationTier;
      return this;
    }

    public Builder resourceLimit(final Optional<ResourceSpec> resourceLimit) {
      this.resourceLimit = resourceLimit;
      return this;
    }

    /**
     * The tier and ceiling a module instance was admitted under, taken together because they are
     * only meaningful read against each other -- a limit says something different at TIER_1, where
     * the worker JVM is shared, than at TIER_2, where the instance owns it.
     */
    public Builder declaredResources(
        final IsolationTier isolationTier, final ResourceSpec resourceLimit) {
      this.isolationTier = Optional.of(isolationTier);
      this.resourceLimit = Optional.of(resourceLimit);
      return this;
    }

    public InstanceObservation build() {
      return new InstanceObservation(
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
          tenantId,
          isolationTier,
          resourceLimit);
    }
  }
}
