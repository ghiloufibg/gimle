package com.gimle.controlplane.schedule;

import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import java.util.List;
import java.util.Set;

/**
 * One registered node's placement-relevant state, as of its latest heartbeat: what it can run, how
 * much room it has, and whether it's already running another replica of the deployment being placed
 * (the caller -- {@code DeploymentReconciler} -- derives this last flag from the state store's
 * current assignments; the scheduler itself never reads the store directly, keeping it a pure
 * function of its inputs and testable without one).
 *
 * <p>{@code taints} is the set of tenant IDs an operator has reserved this node for, via {@code
 * StateStore#putNodeTaint} -- the Kubernetes node-taint analogue. Empty means the node is open to
 * any tenant; non-empty excludes every deployment whose {@code tenantId} isn't a member (an
 * untenanted deployment never tolerates a taint, the same way an untenanted pod never tolerates a
 * Kubernetes taint). Unlike the co-residency check this replaced, a taint is a single per-node
 * property read directly from the store, not a locally-recomputed occupancy snapshot -- so it
 * applies uniformly across every isolation tier and every workload kind, not just Tier 2/3.
 *
 * <p>{@code labels} mirrors {@code capabilities().labels()} as its own top-level accessor (rather
 * than requiring every caller to reach through {@code capabilities()}) since the scheduler's
 * required-label filter reads it on every candidate, the same way {@code taints} already gets its
 * own accessor instead of being read off the assignment set directly.
 *
 * <p>{@code cordoned} is an operator-set flag (unrelated to the latest-heartbeat capacity/labels
 * above) meaning "don't place anything new here" -- it never evicts what's already running, only
 * excludes the node from future placement, so {@code alreadyRunsThisDeployment}/{@code taints} stay
 * meaningful even for a cordoned node.
 *
 * <p>{@code residents} is what this node is currently running, supplied only by a caller that may
 * need to preempt -- every other caller leaves it empty, since nothing but {@code
 * Scheduler#preemption} ever reads it. It is deliberately not derived from {@code capacity}: free
 * capacity says how much room is left, never who is holding the rest, and preemption needs the
 * second question answered.
 *
 * <p>{@code tier2Tenants} is the set of tenant IDs currently running at least one {@code TIER_2}
 * instance on this node, read straight off the node's latest heartbeat ({@code
 * InstanceObservation#isolationTier()}/{@code #tenantId()}) -- cheap enough to populate on every
 * candidate on every reconcile tick, unlike {@code residents}, which needs a per-deployment
 * artifact resolve and so is only ever filled in after an ordinary placement has already failed.
 * {@link Scheduler#place} reads this to prefer, not require, a node with no other tenant's {@code
 * TIER_2} workload already on it. An untenanted instance never contributes to this set, since it
 * reports {@link Optional#empty()} for its own tenant.
 */
public record NodeCandidate(
    String nodeId,
    NodeCapabilities capabilities,
    ResourceUsageSnapshot capacity,
    boolean alreadyRunsThisDeployment,
    Set<String> taints,
    boolean cordoned,
    List<ResidentInstance> residents,
    Set<String> tier2Tenants) {

  public NodeCandidate {
    taints = Set.copyOf(taints);
    residents = List.copyOf(residents);
    tier2Tenants = Set.copyOf(tier2Tenants);
  }

  /**
   * Defaults {@code tier2Tenants} to empty -- what a caller with no heartbeat-derived tenant/tier
   * signal (chiefly a synthetic test candidate) needs to supply.
   */
  public NodeCandidate(
      String nodeId,
      NodeCapabilities capabilities,
      ResourceUsageSnapshot capacity,
      boolean alreadyRunsThisDeployment,
      Set<String> taints,
      boolean cordoned,
      List<ResidentInstance> residents) {
    this(
        nodeId,
        capabilities,
        capacity,
        alreadyRunsThisDeployment,
        taints,
        cordoned,
        residents,
        Set.of());
  }

  /** Defaults {@code residents} to empty -- what a caller that never preempts needs to supply. */
  public NodeCandidate(
      String nodeId,
      NodeCapabilities capabilities,
      ResourceUsageSnapshot capacity,
      boolean alreadyRunsThisDeployment,
      Set<String> taints,
      boolean cordoned) {
    this(nodeId, capabilities, capacity, alreadyRunsThisDeployment, taints, cordoned, List.of());
  }

  public NodeCandidate(
      String nodeId,
      NodeCapabilities capabilities,
      ResourceUsageSnapshot capacity,
      boolean alreadyRunsThisDeployment) {
    this(nodeId, capabilities, capacity, alreadyRunsThisDeployment, Set.of(), false);
  }

  public NodeCandidate(
      String nodeId,
      NodeCapabilities capabilities,
      ResourceUsageSnapshot capacity,
      boolean alreadyRunsThisDeployment,
      Set<String> taints) {
    this(nodeId, capabilities, capacity, alreadyRunsThisDeployment, taints, false);
  }

  Set<String> labels() {
    return capabilities.labels();
  }

  long freeMemoryBytes() {
    return capacity.totalMemoryBytes() - capacity.assignedMemoryBytes();
  }

  long freeCpuMillicores() {
    return capacity.totalCpuMillicores() - capacity.assignedCpuMillicores();
  }
}
