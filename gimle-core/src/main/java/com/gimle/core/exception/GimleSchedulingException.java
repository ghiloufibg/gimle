package com.gimle.core.exception;

import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The scheduler cannot place a deployment's replica anywhere: no registered node has both the
 * capacity and the isolation-tier support the replica's module descriptor requires, or honoring an
 * anti-affinity constraint would leave it unplaceable. Never silently downgraded or placed in
 * violation of a constraint it was asked to honor -- same "reject, don't downgrade" framing as
 * {@link GimleIsolationException}, evaluated control-plane-side against a specific node's reported
 * capabilities instead of worker-side against the one active limiter.
 *
 * <p>Every factory here names the specific thing that blocked the placement -- which node, which
 * tenant, which label, which resource dimension and by how much -- rather than restating the policy
 * in the abstract. An operator reading one of these messages is mid-incident on a cluster they are
 * trying to scale, and the three remedies ("add capacity", "add a node supporting this tier",
 * "remove a taint/cordon/label constraint") are not interchangeable, so the message has to be
 * specific enough to tell them apart. It states only what the scheduler actually observed: a
 * capacity shortfall is reported as a shortfall, never as a guess that adding memory would fix a
 * placement a taint excluded.
 */
public class GimleSchedulingException extends RuntimeException {

  /**
   * How many candidate nodes a message enumerates before it summarizes the rest. A message is read
   * in a log line and an instance event, so an unbounded per-node listing on a large cluster would
   * bury the shortfall it exists to report.
   */
  private static final int MAX_LISTED_NODES = 5;

  /**
   * One candidate node's headroom as of its latest heartbeat, as the scheduler saw it when it gave
   * up. Free capacity is a computed difference, so it can legitimately be zero or negative (a node
   * assigned more than it reports total) -- unlike {@link ResourceSpec}, which models a manifest's
   * own always-positive declaration.
   */
  public record NodeFreeCapacity(String nodeId, long freeMemoryBytes, long freeCpuMillicores) {}

  private GimleSchedulingException(String message) {
    super(message);
  }

  /**
   * Nothing to bin-pack against at all -- distinct from every capacity/constraint failure below,
   * because no amount of manifest or constraint editing fixes it: an agent has to register first.
   */
  public static GimleSchedulingException noNodesRegistered(String deploymentName, int index) {
    return new GimleSchedulingException(
        prefix(deploymentName, index)
            + " cannot be placed: no nodes are currently registered with the control plane");
  }

  /**
   * The tier filter eliminated every registered node. Reported separately from a capacity shortfall
   * because the remedies do not overlap: enlarging the existing nodes cannot help, only a node that
   * supports the tier can, so the message says so and lists what each node does support.
   */
  public static GimleSchedulingException noNodeSupportsTier(
      String deploymentName,
      int index,
      IsolationTier tier,
      Map<String, Set<IsolationTier>> supportedTiersByNode) {
    StringBuilder detail = new StringBuilder();
    for (Map.Entry<String, Set<IsolationTier>> entry :
        new TreeMap<>(supportedTiersByNode).entrySet()) {
      appendSeparator(detail);
      detail.append(entry.getKey()).append(" supports ").append(new TreeSet<>(entry.getValue()));
    }
    return new GimleSchedulingException(
        prefix(deploymentName, index)
            + " requires isolation tier "
            + tier
            + ", which none of the "
            + supportedTiersByNode.size()
            + " registered node(s) supports -- "
            + detail
            + ". Adding capacity to these nodes cannot place it; a node supporting "
            + tier
            + " is required");
  }

  /**
   * Every node that survived tier/cordon/affinity/taint/label filtering is too full. Names the
   * dimension that actually fell short and by how much, plus each candidate's free capacity, so an
   * operator can tell "one node is 90Mi short" from "no single node has both dimensions free at
   * once" -- two situations with the same old "no node has capacity" text and very different fixes.
   */
  public static GimleSchedulingException insufficientNodeCapacity(
      String deploymentName,
      int index,
      IsolationTier tier,
      ResourceSpec resourceRequest,
      List<NodeFreeCapacity> candidates) {
    return new GimleSchedulingException(
        prefix(deploymentName, index)
            + " cannot be placed: it requests memory="
            + resourceRequest.memory()
            + " cpu="
            + resourceRequest.cpu()
            + ", and none of the "
            + candidates.size()
            + " candidate node(s) with "
            + tier
            + " support has room -- "
            + shortfall(resourceRequest, candidates)
            + "; free capacity per candidate node: "
            + describeFreeCapacity(candidates));
  }

  public static GimleSchedulingException antiAffinityViolated(String deploymentName, int index) {
    return new GimleSchedulingException(
        prefix(deploymentName, index)
            + " cannot be placed without violating its anti-affinity constraint (every node with"
            + " capacity already runs another replica of this deployment)");
  }

  /**
   * A replica couldn't be placed because every otherwise-eligible node is tainted for a tenant this
   * deployment doesn't tolerate -- the Kubernetes node-taint/toleration analogue: an operator's
   * per-node reservation, checked unconditionally across every isolation tier and workload kind
   * (unlike the co-residency check this replaced, which only applied to Tier 2/3). Deliberately a
   * hard policy, not a soft preference: this is never relaxed to let placement succeed anyway, so
   * an operator who hits it needs to know exactly which node(s) and taint(s) are blocking, not just
   * that placement failed -- {@code conflictingTaintsByNode} is every otherwise-eligible node this
   * replica could not use, mapped to the tenant(s) it's tainted for, so the message names the
   * specific conflict rather than describing the policy in the abstract.
   */
  public static GimleSchedulingException nodeTaintsExcludeTenant(
      String deploymentName, int index, Map<String, Set<String>> conflictingTaintsByNode) {
    StringBuilder detail = new StringBuilder();
    // TreeMap/TreeSet: deterministic message text regardless of the caller's own iteration order,
    // so this exception's message (and the durable event a caller may derive from it) doesn't
    // flap between otherwise-identical retries.
    for (Map.Entry<String, Set<String>> entry : new TreeMap<>(conflictingTaintsByNode).entrySet()) {
      appendSeparator(detail);
      detail
          .append(entry.getKey())
          .append(" is tainted for tenant(s) ")
          .append(String.join(", ", new TreeSet<>(entry.getValue())));
    }
    return new GimleSchedulingException(
        prefix(deploymentName, index)
            + " cannot be placed: every otherwise-eligible node is tainted for a"
            + " different tenant -- "
            + detail
            + ". Untaint one of them for this deployment's tenant, or register an untainted node");
  }

  /**
   * The manifest's {@code placement.requiredLabels} names at least one label no eligible node
   * carries -- same "reject, don't silently ignore the constraint" posture as anti-affinity and
   * tenant isolation above, and the same named specifics: which labels were required, and what each
   * candidate actually carries.
   */
  public static GimleSchedulingException requiredLabelsUnsatisfied(
      String deploymentName,
      int index,
      Set<String> requiredLabels,
      Map<String, Set<String>> labelsByNode) {
    StringBuilder detail = new StringBuilder();
    for (Map.Entry<String, Set<String>> entry : new TreeMap<>(labelsByNode).entrySet()) {
      appendSeparator(detail);
      detail.append(entry.getKey()).append(" carries ").append(new TreeSet<>(entry.getValue()));
    }
    return new GimleSchedulingException(
        prefix(deploymentName, index)
            + " requires node labels "
            + new TreeSet<>(requiredLabels)
            + ", which no otherwise-eligible node carries -- "
            + detail
            + ". Label a node accordingly, or relax placement.requiredLabels");
  }

  /**
   * Every node supporting this replica's isolation tier has been cordoned by an operator --
   * cordoning never evicts what's already running, it only excludes a node from future placement,
   * so this fires only when cordoning would leave an otherwise-nonempty candidate set empty. Free
   * capacity is deliberately not mentioned: the cordon filter runs before any capacity comparison,
   * so the scheduler never learned whether these nodes had room.
   */
  public static GimleSchedulingException nodeCordoned(
      String deploymentName, int index, Set<String> cordonedNodeIds) {
    return new GimleSchedulingException(
        prefix(deploymentName, index)
            + " cannot be placed because every node with tier support is cordoned -- "
            + String.join(", ", new TreeSet<>(cordonedNodeIds))
            + ". Uncordon one of them, or register another node");
  }

  /**
   * A sticky-placed replica (a {@code StatefulSet} index whose local disk volume already exists on
   * one specific node) cannot be placed because that node is no longer eligible. Deliberately never
   * falls back to a different node -- the whole point of sticky placement is that the data doesn't
   * move, so this replica stays unplaced (and this exception keeps firing every retry tick) until
   * the sticky node itself becomes eligible again, exactly matching the documented "data does not
   * survive node loss" contract rather than silently relocating and orphaning the volume. {@code
   * reason} is the one concrete check that failed, since "make that exact node eligible again" is
   * the only available remedy and an operator needs to know which property to change.
   */
  public static GimleSchedulingException stickyNodeUnavailable(
      String deploymentName, int index, String nodeId, String reason) {
    return new GimleSchedulingException(
        prefix(deploymentName, index)
            + " is sticky-bound to node "
            + nodeId
            + ", which is not currently eligible: "
            + reason
            + " -- it will not be rescheduled elsewhere");
  }

  private static String prefix(String deploymentName, int index) {
    return "deployment " + deploymentName + " instance " + index;
  }

  private static void appendSeparator(StringBuilder detail) {
    if (!detail.isEmpty()) {
      detail.append("; ");
    }
  }

  /**
   * Which dimension actually fell short, and by how much against the roomiest candidate. Both
   * dimensions can individually fit somewhere and still leave the replica unplaceable when no
   * single node has both free at once -- that case is reported as itself rather than as a
   * fabricated shortfall on either dimension.
   */
  private static String shortfall(ResourceSpec request, List<NodeFreeCapacity> candidates) {
    Optional<NodeFreeCapacity> roomiestMemory =
        candidates.stream().max(Comparator.comparingLong(NodeFreeCapacity::freeMemoryBytes));
    Optional<NodeFreeCapacity> roomiestCpu =
        candidates.stream().max(Comparator.comparingLong(NodeFreeCapacity::freeCpuMillicores));
    if (roomiestMemory.isEmpty() || roomiestCpu.isEmpty()) {
      return "no candidate node remained after filtering";
    }
    long memoryShort = request.memoryBytes() - roomiestMemory.get().freeMemoryBytes();
    long cpuShort = request.cpuMillicores() - roomiestCpu.get().freeCpuMillicores();
    StringBuilder detail = new StringBuilder();
    if (memoryShort > 0) {
      detail
          .append("memory is short by ")
          .append(ResourceSpec.formatMemory(memoryShort))
          .append(" (the most any candidate has free is ")
          .append(ResourceSpec.formatMemory(roomiestMemory.get().freeMemoryBytes()))
          .append(", on ")
          .append(roomiestMemory.get().nodeId())
          .append(")");
    }
    if (cpuShort > 0) {
      if (!detail.isEmpty()) {
        detail.append(" and ");
      }
      detail
          .append("cpu is short by ")
          .append(ResourceSpec.formatCpu(cpuShort))
          .append(" (the most any candidate has free is ")
          .append(ResourceSpec.formatCpu(roomiestCpu.get().freeCpuMillicores()))
          .append(", on ")
          .append(roomiestCpu.get().nodeId())
          .append(")");
    }
    if (detail.isEmpty()) {
      return "no single node has both memory and cpu free at once (the most memory free is "
          + ResourceSpec.formatMemory(roomiestMemory.get().freeMemoryBytes())
          + " on "
          + roomiestMemory.get().nodeId()
          + ", the most cpu free is "
          + ResourceSpec.formatCpu(roomiestCpu.get().freeCpuMillicores())
          + " on "
          + roomiestCpu.get().nodeId()
          + ")";
    }
    return detail.toString();
  }

  private static String describeFreeCapacity(List<NodeFreeCapacity> candidates) {
    List<NodeFreeCapacity> ordered =
        candidates.stream().sorted(Comparator.comparing(NodeFreeCapacity::nodeId)).toList();
    StringBuilder detail = new StringBuilder();
    for (NodeFreeCapacity candidate :
        ordered.subList(0, Math.min(MAX_LISTED_NODES, ordered.size()))) {
      appendSeparator(detail);
      detail
          .append(candidate.nodeId())
          .append(" memory=")
          .append(ResourceSpec.formatMemory(candidate.freeMemoryBytes()))
          .append(" cpu=")
          .append(ResourceSpec.formatCpu(candidate.freeCpuMillicores()));
    }
    if (ordered.size() > MAX_LISTED_NODES) {
      detail.append("; and ").append(ordered.size() - MAX_LISTED_NODES).append(" more");
    }
    return detail.toString();
  }
}
