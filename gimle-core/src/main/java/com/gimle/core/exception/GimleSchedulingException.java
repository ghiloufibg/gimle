package com.gimle.core.exception;

import com.gimle.core.module.IsolationTier;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The scheduler cannot place a deployment's replica anywhere: no registered node has both the
 * capacity and the isolation-tier support the replica's module descriptor requires, or honoring an
 * anti-affinity constraint would leave it unplaceable. Never silently downgraded or placed in
 * violation of a constraint it was asked to honor — same "reject, don't downgrade" framing as
 * {@link GimleIsolationException}, evaluated control-plane-side against a specific node's reported
 * capabilities instead of worker-side against the one active limiter.
 */
public class GimleSchedulingException extends RuntimeException {

  private GimleSchedulingException(String message) {
    super(message);
  }

  public static GimleSchedulingException noFeasiblePlacement(
      String deploymentName, int instanceIndex, IsolationTier tier) {
    return new GimleSchedulingException(
        "deployment "
            + deploymentName
            + " instance "
            + instanceIndex
            + " requires isolation tier "
            + tier
            + " but no registered node has both the capacity and tier support to place it");
  }

  public static GimleSchedulingException antiAffinityViolated(
      String deploymentName, int instanceIndex) {
    return new GimleSchedulingException(
        "deployment "
            + deploymentName
            + " instance "
            + instanceIndex
            + " cannot be placed without violating its anti-affinity constraint (every node with"
            + " capacity already runs another replica of this deployment)");
  }

  /**
   * A Tier 2/3 replica for a tenant couldn't be placed without co-residing on a node already
   * running a different tenant's instance -- the node-level segregation enforced for tiers with a
   * real process/kernel isolation boundary. Deliberately a hard policy, not a soft preference: this
   * is never relaxed to let placement succeed anyway, so an operator who hits it needs to know
   * exactly which node(s) and tenant(s) are blocking, not just that placement failed -- {@code
   * conflictingTenantsByNode} is every otherwise-eligible node this replica could not use, mapped
   * to the other tenant(s) already resident there, so the message names the specific conflict
   * rather than describing the policy in the abstract.
   */
  public static GimleSchedulingException tenantIsolationViolated(
      String deploymentName, int instanceIndex, Map<String, Set<String>> conflictingTenantsByNode) {
    StringBuilder detail = new StringBuilder();
    // TreeMap/TreeSet: deterministic message text regardless of the caller's own iteration order,
    // so this exception's message (and the durable event a caller may derive from it) doesn't
    // flap between otherwise-identical retries.
    for (Map.Entry<String, Set<String>> entry :
        new TreeMap<>(conflictingTenantsByNode).entrySet()) {
      if (!detail.isEmpty()) {
        detail.append("; ");
      }
      detail
          .append(entry.getKey())
          .append(" already hosts tenant(s) ")
          .append(String.join(", ", new TreeSet<>(entry.getValue())));
    }
    return new GimleSchedulingException(
        "deployment "
            + deploymentName
            + " instance "
            + instanceIndex
            + " cannot be placed: tenant isolation forbids co-residing with a different tenant's"
            + " instance, and every node with capacity and tier support already hosts one -- "
            + detail);
  }

  /**
   * The manifest's {@code placement.requiredLabels} names at least one label no eligible node
   * carries -- same "reject, don't silently ignore the constraint" posture as anti-affinity and
   * tenant isolation above.
   */
  public static GimleSchedulingException requiredLabelsUnsatisfied(
      String deploymentName, int instanceIndex) {
    return new GimleSchedulingException(
        "deployment "
            + deploymentName
            + " instance "
            + instanceIndex
            + " requires node labels that no node with capacity and tier support carries");
  }

  /**
   * Every node with capacity and tier support for this replica has been cordoned by an operator --
   * cordoning never evicts what's already running, it only excludes a node from future placement,
   * so this fires only when cordoning would leave an otherwise-nonempty candidate set empty.
   */
  public static GimleSchedulingException nodeCordoned(String deploymentName, int instanceIndex) {
    return new GimleSchedulingException(
        "deployment "
            + deploymentName
            + " instance "
            + instanceIndex
            + " cannot be placed because every node with capacity and tier support is cordoned");
  }

  /**
   * A sticky-placed replica (a {@code StatefulSet} index whose local disk volume already exists on
   * one specific node) cannot be placed because that node is no longer eligible: gone, cordoned, or
   * out of capacity/tier support. Deliberately never falls back to a different node -- the whole
   * point of sticky placement is that the data doesn't move, so this replica stays unplaced (and
   * this exception keeps firing every retry tick) until the sticky node itself becomes eligible
   * again, exactly matching the documented "data does not survive node loss" contract rather than
   * silently relocating and orphaning the volume.
   */
  public static GimleSchedulingException stickyNodeUnavailable(
      String deploymentName, int instanceIndex, String nodeId) {
    return new GimleSchedulingException(
        "deployment "
            + deploymentName
            + " instance "
            + instanceIndex
            + " is sticky-bound to node "
            + nodeId
            + ", which is no longer eligible (gone, cordoned, or out of capacity/tier support) --"
            + " it will not be rescheduled elsewhere");
  }
}
