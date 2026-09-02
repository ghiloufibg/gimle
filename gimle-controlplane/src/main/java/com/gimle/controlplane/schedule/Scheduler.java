package com.gimle.controlplane.schedule;

import com.gimle.core.exception.GimleSchedulingException;
import com.gimle.core.exception.GimleSchedulingException.NodeFreeCapacity;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Bin-packing across registered nodes' latest heartbeat capacity: first-fit-decreasing over free
 * {@code (memory, cpu)}, filtered by isolation-tier support, an operator's node-cordon flag, node
 * taints/tenant tolerations, and, if requested, anti-affinity against nodes already running another
 * replica of the same deployment. A pure function of its inputs -- it never reads the state store
 * itself, so it's testable with synthetic candidates and doesn't need a real module artifact to
 * resolve a tier/resource request against.
 *
 * <p>Cordoning is deliberately just an exclusion filter, evaluated right after the tier filter and
 * before every other constraint: it never evicts an instance already running on a cordoned node,
 * only keeps new placements off it. Preemption is out of scope -- cordoning is a binary "don't
 * schedule here" flag, nothing more.
 *
 * <p>{@link #eligibleNodes} is the same five-step eligibility filter {@link #place} applies,
 * extracted so a caller that wants "every survivor" rather than "one pick" -- {@code
 * DaemonSetReconciler}, which places on every eligible node rather than bin-packing a single
 * replica onto one -- can reuse it directly. {@code place} is unchanged in behavior: it still
 * throws its own specific {@link GimleSchedulingException} per filter stage that eliminates every
 * remaining candidate; {@code eligibleNodes} never throws, since an empty result (no eligible node
 * at all, or not yet) is an entirely ordinary outcome for a DaemonSet reconcile tick, not an error
 * condition the way it is for a single replica that must land somewhere.
 *
 * <p>The {@code stickyNodeId}-accepting {@link #place} overload is {@code StatefulSetReconciler}'s
 * sticky-placement primitive: once a {@code StatefulSet} index's local-disk volume exists on a
 * node, every later placement attempt for that index must land back on that exact node or not at
 * all, never a different one. See that overload's own javadoc for which filters still apply and
 * which are skipped.
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
   * {@code tenantId} is checked against every candidate's node taints (an operator's per-node
   * tenant reservation via {@code StateStore#putNodeTaint}, the Kubernetes taint/toleration
   * analogue) unconditionally across every isolation tier: a candidate tainted for one or more
   * tenants is excluded unless {@code tenantId} is present and tolerates (is a member of) that
   * taint set, the same "reject, don't silently violate" posture anti-affinity already uses above.
   * An untainted node (the common case) admits any tenant, including an untenanted deployment.
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
    return place(
        deploymentName,
        instanceIndex,
        tier,
        resourceRequest,
        antiAffinityAcrossNodes,
        tenantId,
        requiredNodeLabels,
        Optional.empty(),
        candidates);
  }

  /**
   * {@code stickyNodeId} is a {@code StatefulSet} index's sticky node binding: when present, this
   * collapses the entire eligibility/bin-packing chain above to "is {@code stickyNodeId} itself
   * still eligible? Y/N" -- tier, cordon, node taints, and required labels are all still checked
   * (they're properties of the node itself), but {@code antiAffinityAcrossNodes} and bin-packing
   * candidate selection are both skipped entirely: there is only ever one candidate under
   * consideration, never a choice among several, so "exclude nodes already running this deployment"
   * and "prefer the roomiest node" have nothing to apply to. Never falls back to a different node
   * if the sticky one fails eligibility -- see {@link
   * GimleSchedulingException#stickyNodeUnavailable} for why that's the deliberate behavior, not a
   * gap.
   */
  public String place(
      String deploymentName,
      int instanceIndex,
      IsolationTier tier,
      ResourceSpec resourceRequest,
      boolean antiAffinityAcrossNodes,
      Optional<String> tenantId,
      Set<String> requiredNodeLabels,
      Optional<String> stickyNodeId,
      List<NodeCandidate> candidates) {
    if (stickyNodeId.isPresent()) {
      return placeSticky(
          deploymentName,
          instanceIndex,
          tier,
          resourceRequest,
          tenantId,
          requiredNodeLabels,
          stickyNodeId.get(),
          candidates);
    }
    if (candidates.isEmpty()) {
      throw GimleSchedulingException.noNodesRegistered(deploymentName, instanceIndex);
    }

    // Tier is the one filter whose failure no capacity change can fix, so it reports itself rather
    // than falling through to the capacity shortfall below with an empty candidate set.
    List<NodeCandidate> tierEligible = filterByTier(tier, candidates);
    if (tierEligible.isEmpty()) {
      throw GimleSchedulingException.noNodeSupportsTier(
          deploymentName, instanceIndex, tier, supportedTiersByNode(candidates));
    }

    List<NodeCandidate> uncordonedEligible = filterByCordon(tierEligible);
    if (uncordonedEligible.isEmpty()) {
      throw GimleSchedulingException.nodeCordoned(
          deploymentName, instanceIndex, nodeIdsOf(tierEligible));
    }

    List<NodeCandidate> affinityEligible =
        filterByAntiAffinity(uncordonedEligible, antiAffinityAcrossNodes);
    if (antiAffinityAcrossNodes && affinityEligible.isEmpty()) {
      throw GimleSchedulingException.antiAffinityViolated(deploymentName, instanceIndex);
    }

    List<NodeCandidate> taintEligible = filterByTaint(affinityEligible, tenantId);
    if (taintEligible.isEmpty()) {
      throw GimleSchedulingException.nodeTaintsExcludeTenant(
          deploymentName, instanceIndex, conflictingTaintsByNode(affinityEligible, tenantId));
    }

    List<NodeCandidate> labelEligible = filterByLabels(taintEligible, requiredNodeLabels);
    if (labelEligible.isEmpty()) {
      throw GimleSchedulingException.requiredLabelsUnsatisfied(
          deploymentName, instanceIndex, requiredNodeLabels, labelsByNode(taintEligible));
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
                GimleSchedulingException.insufficientNodeCapacity(
                    deploymentName,
                    instanceIndex,
                    tier,
                    resourceRequest,
                    freeCapacityOf(labelEligible)));
  }

  /**
   * One node's instances that could be evicted to make room for a workload the cluster currently
   * has nowhere to put, or empty when no eviction would help. A pure decision -- it evicts nothing
   * itself and mutates nothing; the caller turns the answer into {@code RemoveAssignment}s.
   *
   * <p>Only ever consulted after {@link #place} has already failed for capacity, which is what
   * makes priority a last resort rather than a scheduling preference: while any node has room, a
   * workload lands there and nothing is disturbed, no matter how high its priority or how low its
   * neighbours'. Raising a priority buys the ability to make room, not a better address.
   *
   * <p>Eviction is level-triggered like everything else here: this returns victims, the caller
   * removes their assignments, and the ordinary {@link #place} on a later tick then succeeds
   * against the freed capacity. Nothing tries to hold the freed room for the workload that earned
   * it -- if something else takes it first, this simply runs again next tick. That is a deliberate
   * trade against carrying a reservation through the store, and it is why a preempting workload can
   * occasionally need more than one round to land.
   */
  public Optional<Preemption> preemption(
      IsolationTier tier,
      ResourceSpec resourceRequest,
      int priority,
      boolean antiAffinityAcrossNodes,
      Optional<String> tenantId,
      Set<String> requiredNodeLabels,
      List<NodeCandidate> candidates) {
    long requiredMemory = resourceRequest.memoryBytes();
    long requiredCpu = resourceRequest.cpuMillicores();
    return eligibleNodes(
            tier, antiAffinityAcrossNodes, tenantId, requiredNodeLabels, false, candidates)
        .stream()
        .map(candidate -> victimsOn(candidate, priority, requiredMemory, requiredCpu))
        .flatMap(Optional::stream)
        // Fewest evictions first, then least capacity taken back: disturbing two instances to seat
        // one is worse than disturbing one, and between equal counts the smaller disruption wins.
        .min(
            Comparator.comparingInt((Preemption p) -> p.victims().size())
                .thenComparingLong(Preemption::reclaimedMemoryBytes))
        .map(Optional::of)
        .orElse(Optional.empty());
  }

  /**
   * The cheapest set of strictly-lower-priority instances on one node that together free enough for
   * the request, or empty when even evicting all of them would not. Victims are taken
   * lowest-priority first, and within one priority the largest first -- so the fewest instances are
   * disturbed, and the ones disturbed are the ones the cluster was told matter least.
   *
   * <p>An instance at the same priority is never a victim. Equal priority means the cluster was
   * given no basis to prefer one over the other, and evicting a peer to seat a peer would let two
   * equal workloads displace each other indefinitely.
   */
  private static Optional<Preemption> victimsOn(
      NodeCandidate candidate, int priority, long requiredMemory, long requiredCpu) {
    long freeMemory = candidate.freeMemoryBytes();
    long freeCpu = candidate.freeCpuMillicores();
    if (freeMemory >= requiredMemory && freeCpu >= requiredCpu) {
      // Nothing to preempt: this node already fits the request. Reached when place() failed for a
      // reason other than this node's capacity -- a concurrent placement, or a stale snapshot.
      return Optional.of(new Preemption(candidate.nodeId(), List.of()));
    }
    List<ResidentInstance> ordered =
        candidate.residents().stream()
            .filter(resident -> resident.priority() < priority)
            .sorted(
                Comparator.comparingInt(ResidentInstance::priority)
                    .thenComparing(
                        Comparator.comparingLong((ResidentInstance r) -> r.request().memoryBytes())
                            .reversed()))
            .toList();
    List<ResidentInstance> victims = new ArrayList<>();
    for (ResidentInstance resident : ordered) {
      if (freeMemory >= requiredMemory && freeCpu >= requiredCpu) {
        break;
      }
      victims.add(resident);
      freeMemory += resident.request().memoryBytes();
      freeCpu += resident.request().cpuMillicores();
    }
    if (freeMemory < requiredMemory || freeCpu < requiredCpu) {
      return Optional.empty();
    }
    return Optional.of(new Preemption(candidate.nodeId(), victims));
  }

  /**
   * Where a preempting workload would land and what would have to be evicted first. An empty {@code
   * victims} list means the node already had room after all -- valid, and distinct from "no
   * preemption is possible", which is an empty {@link Optional} instead.
   */
  public record Preemption(String nodeId, List<ResidentInstance> victims) {

    public Preemption {
      victims = List.copyOf(victims);
    }

    long reclaimedMemoryBytes() {
      return victims.stream().mapToLong(victim -> victim.request().memoryBytes()).sum();
    }
  }

  /**
   * The same five-step eligibility filter {@link #place} applies (tier, cordon, anti-affinity, node
   * taints, required labels), minus its final bin-packing pick -- every surviving candidate is
   * returned, never just one. Never throws: an empty result is an ordinary "no node is eligible
   * right now" outcome for a caller like {@code DaemonSetReconciler} that places on every survivor
   * rather than needing exactly one.
   *
   * <p>{@code tolerateAllTaints} skips the taint-filter stage entirely when {@code true} -- the
   * only caller that ever passes {@code true} is {@code DaemonSetReconciler}, for a DaemonSet whose
   * own manifest opted into {@code tolerateAllTaints}, mirroring Kubernetes' notion of a DaemonSet
   * needing to cover every node including ones reserved for a tenant. Every other caller passes
   * {@code false}, leaving today's strict "an operator-tainted node stays excluded unless the
   * caller's own tenantId matches" behavior unchanged.
   */
  public List<NodeCandidate> eligibleNodes(
      IsolationTier tier,
      boolean antiAffinityAcrossNodes,
      Optional<String> tenantId,
      Set<String> requiredNodeLabels,
      boolean tolerateAllTaints,
      List<NodeCandidate> candidates) {
    List<NodeCandidate> tierEligible = filterByTier(tier, candidates);
    List<NodeCandidate> uncordonedEligible = filterByCordon(tierEligible);
    List<NodeCandidate> affinityEligible =
        filterByAntiAffinity(uncordonedEligible, antiAffinityAcrossNodes);
    List<NodeCandidate> taintEligible =
        tolerateAllTaints ? affinityEligible : filterByTaint(affinityEligible, tenantId);
    return filterByLabels(taintEligible, requiredNodeLabels);
  }

  private String placeSticky(
      String deploymentName,
      int instanceIndex,
      IsolationTier tier,
      ResourceSpec resourceRequest,
      Optional<String> tenantId,
      Set<String> requiredNodeLabels,
      String stickyNodeId,
      List<NodeCandidate> candidates) {
    Optional<NodeCandidate> sticky =
        candidates.stream().filter(c -> c.nodeId().equals(stickyNodeId)).findFirst();
    if (sticky.isEmpty()) {
      throw GimleSchedulingException.stickyNodeUnavailable(
          deploymentName, instanceIndex, stickyNodeId, "it is not a registered, live node");
    }
    NodeCandidate node = sticky.get();
    Optional<String> ineligible =
        stickyIneligibilityReason(node, tier, resourceRequest, tenantId, requiredNodeLabels);
    if (ineligible.isPresent()) {
      throw GimleSchedulingException.stickyNodeUnavailable(
          deploymentName, instanceIndex, stickyNodeId, ineligible.get());
    }
    return node.nodeId();
  }

  /**
   * The one concrete check the sticky node fails, in the same filter order {@link #place} applies.
   * Only the first is reported: a sticky replica has exactly one node it may ever land on, so the
   * operator's next action is to fix that node, and the first blocker is the one to fix first.
   */
  private static Optional<String> stickyIneligibilityReason(
      NodeCandidate node,
      IsolationTier tier,
      ResourceSpec resourceRequest,
      Optional<String> tenantId,
      Set<String> requiredNodeLabels) {
    if (!node.capabilities().supportedTiers().contains(tier)) {
      return Optional.of(
          "it does not support isolation tier "
              + tier
              + " (it supports "
              + new TreeSet<>(node.capabilities().supportedTiers())
              + ")");
    }
    if (node.cordoned()) {
      return Optional.of("it is cordoned");
    }
    if (!node.taints().isEmpty() && tenantId.filter(node.taints()::contains).isEmpty()) {
      return Optional.of(
          "it is tainted for tenant(s) " + String.join(", ", new TreeSet<>(node.taints())));
    }
    if (!node.labels().containsAll(requiredNodeLabels)) {
      Set<String> missing = new TreeSet<>(requiredNodeLabels);
      missing.removeAll(node.labels());
      return Optional.of("it is missing required node label(s) " + missing);
    }
    long shortMemory = resourceRequest.memoryBytes() - node.freeMemoryBytes();
    long shortCpu = resourceRequest.cpuMillicores() - node.freeCpuMillicores();
    if (shortMemory > 0 || shortCpu > 0) {
      return Optional.of(
          "it has memory="
              + ResourceSpec.formatMemory(node.freeMemoryBytes())
              + " cpu="
              + ResourceSpec.formatCpu(node.freeCpuMillicores())
              + " free against a request of memory="
              + resourceRequest.memory()
              + " cpu="
              + resourceRequest.cpu()
              + (shortMemory > 0
                  ? ", short by " + ResourceSpec.formatMemory(shortMemory) + " memory"
                  : "")
              + (shortCpu > 0 ? ", short by " + ResourceSpec.formatCpu(shortCpu) + " cpu" : ""));
    }
    return Optional.empty();
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

  private static List<NodeCandidate> filterByTaint(
      List<NodeCandidate> candidates, Optional<String> tenantId) {
    return candidates.stream()
        .filter(
            c ->
                c.taints().isEmpty()
                    || (tenantId.isPresent() && c.taints().contains(tenantId.get())))
        .toList();
  }

  /**
   * The taint rejection's own explanatory detail: every candidate {@link #filterByTaint} would
   * exclude for {@code tenantId} (i.e. tainted for a tenant this deployment doesn't tolerate),
   * mapped to exactly which tenant(s) it's tainted for -- named specifics for {@link
   * GimleSchedulingException#nodeTaintsExcludeTenant}'s message, not just "some node conflicts."
   */
  private static Map<String, Set<String>> conflictingTaintsByNode(
      List<NodeCandidate> candidates, Optional<String> tenantId) {
    Map<String, Set<String>> conflicts = new LinkedHashMap<>();
    for (NodeCandidate candidate : candidates) {
      boolean tolerates = tenantId.isPresent() && candidate.taints().contains(tenantId.get());
      if (!candidate.taints().isEmpty() && !tolerates) {
        conflicts.put(candidate.nodeId(), candidate.taints());
      }
    }
    return conflicts;
  }

  private static List<NodeCandidate> filterByLabels(
      List<NodeCandidate> candidates, Set<String> requiredNodeLabels) {
    if (requiredNodeLabels.isEmpty()) {
      return candidates;
    }
    return candidates.stream().filter(c -> c.labels().containsAll(requiredNodeLabels)).toList();
  }

  private static Set<String> nodeIdsOf(List<NodeCandidate> candidates) {
    return candidates.stream().map(NodeCandidate::nodeId).collect(Collectors.toSet());
  }

  private static Map<String, Set<IsolationTier>> supportedTiersByNode(
      List<NodeCandidate> candidates) {
    Map<String, Set<IsolationTier>> byNode = new LinkedHashMap<>();
    for (NodeCandidate candidate : candidates) {
      byNode.put(candidate.nodeId(), candidate.capabilities().supportedTiers());
    }
    return byNode;
  }

  private static Map<String, Set<String>> labelsByNode(List<NodeCandidate> candidates) {
    Map<String, Set<String>> byNode = new LinkedHashMap<>();
    for (NodeCandidate candidate : candidates) {
      byNode.put(candidate.nodeId(), candidate.labels());
    }
    return byNode;
  }

  private static List<NodeFreeCapacity> freeCapacityOf(List<NodeCandidate> candidates) {
    return candidates.stream()
        .map(c -> new NodeFreeCapacity(c.nodeId(), c.freeMemoryBytes(), c.freeCpuMillicores()))
        .toList();
  }
}
