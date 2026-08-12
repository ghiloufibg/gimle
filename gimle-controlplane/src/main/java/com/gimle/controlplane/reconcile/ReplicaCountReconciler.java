package com.gimle.controlplane.reconcile;

import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.ReconcilerInstanceState;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StoreReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
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
 *
 * <p>The grace-period timer is persisted through {@link StoreReader}/{@link MutationSink} as part
 * of {@link ReconcilerInstanceState} rather than held in a local map -- a reconciler-leader
 * failover reconstructs this class fresh, and without persistence it would forget how long an
 * instance had already been missing, restarting the grace period and delaying a legitimate
 * reschedule. Each write starts from the currently persisted record so it never clobbers {@link
 * HealthReconciler}'s own fields in the same consolidated record.
 */
public final class ReplicaCountReconciler {

  private static final Logger log = LoggerFactory.getLogger(ReplicaCountReconciler.class);

  private final StoreReader store;
  private final Duration nodeDarkTimeout;
  private final Duration placementGracePeriod;
  private final MutationSink mutations;
  private final Clock clock;

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public ReplicaCountReconciler(StateStore store, Duration nodeDarkTimeout) {
    this(store, nodeDarkTimeout, nodeDarkTimeout);
  }

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public ReplicaCountReconciler(
      StateStore store, Duration nodeDarkTimeout, Duration placementGracePeriod) {
    this(store, nodeDarkTimeout, placementGracePeriod, mutation -> mutation.applyTo(store));
  }

  /**
   * Test-only convenience, with an injectable clock: every duration this reconciler compares
   * against ({@code nodeDarkTimeout}, {@code placementGracePeriod}) is measured from a single
   * {@code now} read at the top of {@link #reconcileOnce}, so supplying that read is all a test
   * needs to exercise the real production timeouts without waiting for them -- see {@code
   * TestClock} in {@code gimle-core}'s test-jar.
   */
  public ReplicaCountReconciler(
      StateStore store, Duration nodeDarkTimeout, Duration placementGracePeriod, Clock clock) {
    this(store, nodeDarkTimeout, placementGracePeriod, mutation -> mutation.applyTo(store), clock);
  }

  public ReplicaCountReconciler(
      StoreReader store,
      Duration nodeDarkTimeout,
      Duration placementGracePeriod,
      MutationSink mutations) {
    this(store, nodeDarkTimeout, placementGracePeriod, mutations, Clock.systemUTC());
  }

  public ReplicaCountReconciler(
      StoreReader store,
      Duration nodeDarkTimeout,
      Duration placementGracePeriod,
      MutationSink mutations,
      Clock clock) {
    this.store = store;
    this.nodeDarkTimeout = nodeDarkTimeout;
    this.placementGracePeriod = placementGracePeriod;
    this.mutations = mutations;
    this.clock = clock;
  }

  public void reconcileOnce() {
    Instant now = clock.instant();
    Set<String> currentKeys = new HashSet<>();
    for (InstanceAssignment assignment : store.listAssignments()) {
      currentKeys.add(key(assignment.deploymentName(), assignment.instanceIndex()));
      ReconcilerInstanceState persisted = currentState(assignment);
      if (isConfirmedByItsNode(assignment, now)) {
        clearFirstSeenMissing(persisted);
        continue;
      }
      long firstMissing = persisted.firstSeenMissingAtEpochMilli();
      if (firstMissing == ReconcilerInstanceState.ABSENT) {
        firstMissing = now.toEpochMilli();
        // Track locally rather than re-reading the store after this write: store reads may hit a
        // different, possibly-lagging replica than the leader mutations.propose just committed to
        // (StoreReader's own javadoc -- reads stay loose, no linearizability requirement).
        persisted = withFirstSeenMissing(persisted, firstMissing);
        save(persisted);
      }
      if (Duration.between(Instant.ofEpochMilli(firstMissing), now).compareTo(placementGracePeriod)
          >= 0) {
        log.warn(
            "deployment {} instance {} on node {} is no longer confirmed by a heartbeat; releasing"
                + " its assignment for re-placement",
            assignment.deploymentName(),
            assignment.instanceIndex(),
            assignment.nodeId());
        mutations.propose(
            new StateMutation.RemoveAssignment(
                assignment.deploymentName(), assignment.instanceIndex()));
        clearFirstSeenMissing(persisted);
      }
    }
    // Assignments removed by some other path (deletion, scale-down) shouldn't leave orphaned
    // grace-period bookkeeping behind.
    for (ReconcilerInstanceState state : store.listReconcilerInstanceStates()) {
      if (state.firstSeenMissingAtEpochMilli() != ReconcilerInstanceState.ABSENT
          && !currentKeys.contains(key(state.deploymentName(), state.instanceIndex()))) {
        save(withFirstSeenMissing(state, ReconcilerInstanceState.ABSENT));
      }
    }
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

  private void clearFirstSeenMissing(ReconcilerInstanceState persisted) {
    if (persisted.firstSeenMissingAtEpochMilli() == ReconcilerInstanceState.ABSENT) {
      return;
    }
    save(withFirstSeenMissing(persisted, ReconcilerInstanceState.ABSENT));
  }

  private static ReconcilerInstanceState withFirstSeenMissing(
      ReconcilerInstanceState state, long firstSeenMissingAtEpochMilli) {
    return new ReconcilerInstanceState(
        state.deploymentName(),
        state.instanceIndex(),
        state.attemptsInWindow(),
        state.windowStartEpochMilli(),
        state.nextAllowedAttemptEpochMilli(),
        state.pendingRetry(),
        state.permanentlyFailed(),
        firstSeenMissingAtEpochMilli);
  }

  private void save(ReconcilerInstanceState state) {
    if (state.isEmpty()) {
      mutations.propose(
          new StateMutation.RemoveReconcilerInstanceState(
              state.deploymentName(), state.instanceIndex()));
    } else {
      mutations.propose(new StateMutation.PutReconcilerInstanceState(state));
    }
  }

  private ReconcilerInstanceState currentState(InstanceAssignment assignment) {
    return store
        .getReconcilerInstanceState(assignment.deploymentName(), assignment.instanceIndex())
        .orElseGet(() -> emptyState(assignment));
  }

  private static ReconcilerInstanceState emptyState(InstanceAssignment assignment) {
    return new ReconcilerInstanceState(
        assignment.deploymentName(),
        assignment.instanceIndex(),
        0,
        ReconcilerInstanceState.ABSENT,
        ReconcilerInstanceState.ABSENT,
        false,
        false,
        ReconcilerInstanceState.ABSENT);
  }

  private static String key(String deploymentName, int instanceIndex) {
    return deploymentName + "#" + instanceIndex;
  }
}
