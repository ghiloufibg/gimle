package com.gimle.controlplane.schedule;

import com.gimle.core.exception.GimleSchedulingException;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Bin-packing across registered nodes' latest heartbeat capacity: first-fit-decreasing over free
 * {@code (memory, cpu)}, filtered by isolation-tier support, an operator's node-cordon flag, and,
 * if requested, anti-affinity against nodes already running another replica of the same deployment.
 * A pure function of its inputs -- it never reads the state store itself, so it's testable with
 * synthetic candidates and doesn't need a real module artifact to resolve a tier/resource request
 * against.
 *
 * <p>Cordoning is deliberately just an exclusion filter, evaluated right after the tier filter and
 * before every other constraint: it never evicts an instance already running on a cordoned node,
 * only keeps new placements off it. Preemption and taint/toleration-style soft constraints are out
 * of scope -- this is a binary "don't schedule here" flag, nothing more.
 *
 * <p>{@link #eligibleNodes} (priority-3 design doc §4b) is the same five-step eligibility filter
 * {@link #place} applies, extracted so a caller that wants "every survivor" rather than "one pick"
 * -- {@code DaemonSetReconciler}, which places on every eligible node rather than bin-packing a
 * single replica onto one -- can reuse it directly. {@code place} is unchanged in behavior: it
 * still throws its own specific {@link GimleSchedulingException} per filter stage that eliminates
 * every remaining candidate; {@code eligibleNodes} never throws, since an empty result (no eligible
 * node at all, or not yet) is an entirely ordinary outcome for a DaemonSet reconcile tick, not an
 * error condition the way it is for a single replica that must land somewhere.
 */
public final class Scheduler {

  /**
   * Chooses a node for one replica, returning its id. Anti-affinity is enforced by strict
   * exclusion, not a soft preference: if honoring it would leave no candidate, placement fails
   * outright rather than silently violating the constraint it was asked to honor.
   */
  public String place(
      String deploymentName,
      int instanceIndex,
      IsolationTier tier,
      ResourceSpec resourceRequest,
      boolean antiAffinityAcrossNodes,
      List<NodeCandidate> candidates) {
    return place(
        deploymentName,
        instanceIndex,
        tier,
        resourceRequest,
        antiAffinityAcrossNodes,
        Optional.empty(),
        candidates);
  }

  /**
   * {@code tenantId} enforces node-level tenant segregation for {@code TIER_2}/{@code TIER_3}
   * placements only: a candidate already hosting a *different* tenant's instance is excluded
   * outright, the same "reject, don't silently violate" posture anti-affinity already uses above.
   * Absent, or for {@code TIER_1}, this filter is a no-op -- Tier 1 density packing across separate
   * deployments isn't implemented anywhere in this codebase today, so there is nothing for a
   * same-node Tier 1 exclusion to protect against yet.
   */
  public String place(
      String deploymentName,
      int instanceIndex,
      IsolationTier tier,
      ResourceSpec resourceRequest,
      boolean antiAffinityAcrossNodes,
      Optional<String> tenantId,
      List<NodeCandidate> candidates) {
    return place(
        deploymentName,
        instanceIndex,
        tier,
        resourceRequest,
        antiAffinityAcrossNodes,
        tenantId,
        Set.of(),
        candidates);
  }

  /**
   * {@code requiredNodeLabels} is the manifest's {@code placement.requiredLabels}, matched by exact
   * set membership against each candidate's {@code NodeCapabilities.labels()} -- a candidate
   * missing even one required label is excluded outright, same "reject, don't downgrade" posture as
   * every other constraint here. Empty is a no-op, matching an unset manifest field.
   */
  public String place(
      String deploymentName,
      int instanceIndex,
      IsolationTier tier,
      ResourceSpec resourceRequest,
      boolean antiAffinityAcrossNodes,
      Optional<String> tenantId,
      Set<String> requiredNodeLabels,
      List<NodeCandidate> candidates) {
    List<NodeCandidate> tierEligible = filterByTier(tier, candidates);

    List<NodeCandidate> uncordonedEligible = filterByCordon(tierEligible);
    if (uncordonedEligible.isEmpty() && !tierEligible.isEmpty()) {
      throw GimleSchedulingException.nodeCordoned(deploymentName, instanceIndex);
    }

    List<NodeCandidate> affinityEligible =
        filterByAntiAffinity(uncordonedEligible, antiAffinityAcrossNodes);
    if (antiAffinityAcrossNodes && affinityEligible.isEmpty() && !uncordonedEligible.isEmpty()) {
      throw GimleSchedulingException.antiAffinityViolated(deploymentName, instanceIndex);
    }

    List<NodeCandidate> tenantEligible = filterByTenant(affinityEligible, tier, tenantId);
    if (enforcesTenantIsolation(tier, tenantId)
        && tenantEligible.isEmpty()
        && !affinityEligible.isEmpty()) {
      throw GimleSchedulingException.tenantIsolationViolated(deploymentName, instanceIndex);
    }

    List<NodeCandidate> labelEligible = filterByLabels(tenantEligible, requiredNodeLabels);
    if (!requiredNodeLabels.isEmpty() && labelEligible.isEmpty() && !tenantEligible.isEmpty()) {
      throw GimleSchedulingException.requiredLabelsUnsatisfied(deploymentName, instanceIndex);
    }

    long requiredMemory = resourceRequest.memoryBytes();
    long requiredCpu = resourceRequest.cpuMillicores();

    return labelEligible.stream()
        .sorted(
            Comparator.comparingLong(NodeCandidate::freeMemoryBytes)
                .thenComparingLong(NodeCandidate::freeCpuMillicores)
                .reversed())
        .filter(c -> c.freeMemoryBytes() >= requiredMemory && c.freeCpuMillicores() >= requiredCpu)
        .findFirst()
        .map(NodeCandidate::nodeId)
        .orElseThrow(
            () ->
                GimleSchedulingException.noFeasiblePlacement(deploymentName, instanceIndex, tier));
  }

  /**
   * The same five-step eligibility filter {@link #place} applies (tier, cordon, anti-affinity,
   * tenant isolation, required labels), minus its final bin-packing pick -- every surviving
   * candidate is returned, never just one. Never throws: an empty result is an ordinary "no node is
   * eligible right now" outcome for a caller like {@code DaemonSetReconciler} that places on every
   * survivor rather than needing exactly one.
   */
  public List<NodeCandidate> eligibleNodes(
      IsolationTier tier,
      boolean antiAffinityAcrossNodes,
      Optional<String> tenantId,
      Set<String> requiredNodeLabels,
      List<NodeCandidate> candidates) {
    List<NodeCandidate> tierEligible = filterByTier(tier, candidates);
    List<NodeCandidate> uncordonedEligible = filterByCordon(tierEligible);
    List<NodeCandidate> affinityEligible =
        filterByAntiAffinity(uncordonedEligible, antiAffinityAcrossNodes);
    List<NodeCandidate> tenantEligible = filterByTenant(affinityEligible, tier, tenantId);
    return filterByLabels(tenantEligible, requiredNodeLabels);
  }

  private static List<NodeCandidate> filterByTier(
      IsolationTier tier, List<NodeCandidate> candidates) {
    return candidates.stream()
        .filter(c -> c.capabilities().supportedTiers().contains(tier))
        .toList();
  }

  private static List<NodeCandidate> filterByCordon(List<NodeCandidate> candidates) {
    return candidates.stream().filter(c -> !c.cordoned()).toList();
  }

  private static List<NodeCandidate> filterByAntiAffinity(
      List<NodeCandidate> candidates, boolean antiAffinityAcrossNodes) {
    if (!antiAffinityAcrossNodes) {
      return candidates;
    }
    return candidates.stream().filter(c -> !c.alreadyRunsThisDeployment()).toList();
  }

  private static boolean enforcesTenantIsolation(IsolationTier tier, Optional<String> tenantId) {
    return tenantId.isPresent() && (tier == IsolationTier.TIER_2 || tier == IsolationTier.TIER_3);
  }

  private static List<NodeCandidate> filterByTenant(
      List<NodeCandidate> candidates, IsolationTier tier, Optional<String> tenantId) {
    if (!enforcesTenantIsolation(tier, tenantId)) {
      return candidates;
    }
    String thisTenant = tenantId.get();
    return candidates.stream()
        .filter(c -> c.tenantsPresent().stream().allMatch(thisTenant::equals))
        .toList();
  }

  private static List<NodeCandidate> filterByLabels(
      List<NodeCandidate> candidates, Set<String> requiredNodeLabels) {
    if (requiredNodeLabels.isEmpty()) {
      return candidates;
    }
    return candidates.stream().filter(c -> c.labels().containsAll(requiredNodeLabels)).toList();
  }
}
