package com.gimle.controlplane.reconcile;

import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StoreReader;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cross-checks assignments against the *observed* instance list from the latest heartbeats: an
 * assignment whose node has gone dark (no heartbeat within {@code nodeDarkTimeout}), or whose
 * latest heartbeat simply doesn't mention the instance at all, is removed once that's been true for
 * longer than {@code placementGracePeriod} -- {@link DeploymentReconciler}'s next tick sees the gap
 * and re-places it, the same code path a fresh assignment takes. This is the machine-level tier of
 * the spec's self-healing escalation ladder: a node going dark turns into replacement placement on
 * a different node, which module-level and worker-level restart couldn't reach on their own.
 *
 * <p>The grace period exists because a brand-new assignment is, by construction, never mentioned in
 * *any* heartbeat sent before the owning agent has even fetched it -- an agent polls assignments on
 * its own tick cadence, then still has to spawn the worker JVM and drive it through install/
 * resolve/start before it can report the instance at all. Removing on the very first "not mentioned
 * yet" observation would treat that normal startup latency as a failure and undo the placement
 * before the agent had a chance to act on it, a real bug this reconciler had until the grace period
 * was added: watch it happen by dropping the period to zero.
 *
 * <p>Deliberately not this reconciler's job: an instance the node *does* still report, but as
 * unhealthy ({@code alive=false} or a {@code FAILED} lifecycle state) -- that's {@link
 * HealthReconciler}'s distinct concern, gated by its own backoff so a persistently-failing replica
 * isn't rescheduled in a tight loop.
 */
public final class ReplicaCountReconciler {

  private static final Logger log = LoggerFactory.getLogger(ReplicaCountReconciler.class);

  private final StoreReader store;
  private final Duration nodeDarkTimeout;
  private final Duration placementGracePeriod;
  private final Map<String, Instant> firstSeenMissingAt = new ConcurrentHashMap<>();
  private final MutationSink mutations;

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public ReplicaCountReconciler(StateStore store, Duration nodeDarkTimeout) {
    this(store, nodeDarkTimeout, nodeDarkTimeout);
  }

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public ReplicaCountReconciler(
      StateStore store, Duration nodeDarkTimeout, Duration placementGracePeriod) {
    this(store, nodeDarkTimeout, placementGracePeriod, mutation -> mutation.applyTo(store));
  }

  public ReplicaCountReconciler(
      StoreReader store,
      Duration nodeDarkTimeout,
      Duration placementGracePeriod,
      MutationSink mutations) {
    this.store = store;
    this.nodeDarkTimeout = nodeDarkTimeout;
    this.placementGracePeriod = placementGracePeriod;
    this.mutations = mutations;
  }

  public void reconcileOnce() {
    Instant now = Instant.now();
    Set<String> currentKeys = ConcurrentHashMap.newKeySet();
    for (InstanceAssignment assignment : store.listAssignments()) {
      String key = key(assignment);
      currentKeys.add(key);
      if (isConfirmedByItsNode(assignment, now)) {
        firstSeenMissingAt.remove(key);
        continue;
      }
      Instant firstMissing = firstSeenMissingAt.computeIfAbsent(key, k -> now);
      if (Duration.between(firstMissing, now).compareTo(placementGracePeriod) >= 0) {
        log.warn(
            "deployment {} instance {} on node {} is no longer confirmed by a heartbeat; releasing"
                + " its assignment for re-placement",
            assignment.deploymentName(),
            assignment.instanceIndex(),
            assignment.nodeId());
        mutations.propose(
            new StateMutation.RemoveAssignment(
                assignment.deploymentName(), assignment.instanceIndex()));
        firstSeenMissingAt.remove(key);
      }
    }
    // Assignments removed by some other path (deletion, scale-down) shouldn't leave orphaned
    // grace-period bookkeeping behind.
    firstSeenMissingAt.keySet().retainAll(currentKeys);
  }

  private boolean isConfirmedByItsNode(InstanceAssignment assignment, Instant now) {
    return store
        .getNodeHeartbeat(assignment.nodeId())
        .filter(observed -> !nodeIsDark(observed, now))
        .map(ObservedHeartbeat::heartbeat)
        .map(heartbeat -> mentions(heartbeat, assignment))
        .orElse(false);
  }

  private boolean nodeIsDark(ObservedHeartbeat observed, Instant now) {
    return Duration.between(observed.receivedAt(), now).compareTo(nodeDarkTimeout) > 0;
  }

  private static boolean mentions(NodeHeartbeat heartbeat, InstanceAssignment assignment) {
    for (InstanceObservation observation : heartbeat.instances()) {
      if (observation.deploymentName().equals(assignment.deploymentName())
          && observation.instanceIndex() == assignment.instanceIndex()) {
        return true;
      }
    }
    return false;
  }

  private static String key(InstanceAssignment assignment) {
    return assignment.deploymentName() + "#" + assignment.instanceIndex();
  }
}
