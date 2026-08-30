package com.gimle.controlplane.reconcile;

import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.mimir.manifest.DeploymentSpec;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *
 * <p>Releasing a stale assignment is throttled per deployment against that deployment's own {@link
 * com.gimle.mimir.manifest.DisruptionBudget#maxUnavailable}, the same budget {@code
 * DeploymentReconciler#handleRollingUpdate} already enforces for voluntary rolling-update
 * migrations -- without it, one dead node hosting several replicas of the same anti-affinity-less
 * deployment (cross-node anti-affinity is opt-in, see {@code PlacementConstraints}) could release
 * every one of them in a single tick, silently exceeding an operator's configured availability
 * floor. {@link #evictionBudgetRemaining} measures "already unavailable" as {@code replicas} minus
 * how many of indices {@code [0, replicas)} currently have *any* assignment at all -- deliberately
 * counting migrations {@code handleRollingUpdate} itself already has in flight too, since both
 * paths draw down the same operator-facing ceiling on how many replicas may be missing at once,
 * regardless of which reconciler caused the gap. Only indices actually due for release this tick
 * (grace period already elapsed) compete for the remaining budget, lowest index first -- a deferred
 * eviction leaves its grace-period timer exactly as elapsed as it already is, so it's retried (and
 * wins budget) on the very next tick rather than waiting out a fresh grace period. A deployment
 * with no budget configured (the default, or no {@link DeploymentSpec} found at all -- e.g. one
 * already deleted by some other path) evicts at unthrottled full speed, exactly this reconciler's
 * behavior before this budget existed.
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
    // Grouped so eviction throttling below is computed once per deployment, against every one of
    // its assignments together, rather than per assignment in isolation -- a LinkedHashMap keeps
    // deployments in a stable, first-seen order rather than a hash-dependent one, though the
    // per-deployment index ordering below is what actually matters for determinism.
    Map<DeploymentIdentity, List<InstanceAssignment>> byDeployment = new LinkedHashMap<>();
    for (InstanceAssignment assignment : store.listAssignments()) {
      currentKeys.add(
          key(assignment.tenantId(), assignment.deploymentName(), assignment.instanceIndex()));
      byDeployment
          .computeIfAbsent(DeploymentIdentity.of(assignment), unused -> new ArrayList<>())
          .add(assignment);
    }
    for (Map.Entry<DeploymentIdentity, List<InstanceAssignment>> entry : byDeployment.entrySet()) {
      try {
        reconcileDeployment(entry.getKey(), entry.getValue(), now);
      } catch (RuntimeException e) {
        // One deployment's failure (e.g. a GimleRaftException from mutations.propose during a
        // store leader-election gap) must never abort the rest of this tick's deployments -- the
        // next tick retries this one from the same full snapshot.
        log.warn(
            "replica count reconcile of deployment {} failed: {}",
            entry.getKey().deploymentName(),
            e.getMessage(),
            e);
      }
    }
    // Assignments removed by some other path (deletion, scale-down) shouldn't leave orphaned
    // grace-period bookkeeping behind.
    for (ReconcilerInstanceState state : store.listReconcilerInstanceStates()) {
      if (state.firstSeenMissingAtEpochMilli() != ReconcilerInstanceState.ABSENT
          && !currentKeys.contains(
              key(state.tenantId(), state.deploymentName(), state.instanceIndex()))) {
        try {
          save(withFirstSeenMissing(state, ReconcilerInstanceState.ABSENT));
        } catch (RuntimeException e) {
          log.warn(
              "clearing orphaned grace-period bookkeeping for {} instance {} failed: {}",
              state.deploymentName(),
              state.instanceIndex(),
              e.getMessage(),
              e);
        }
      }
    }
  }

  /**
   * Processes every assignment for one deployment, capping how many of them may actually be
   * released this tick at {@link #evictionBudgetRemaining} -- lowest instance index first, matching
   * {@code DeploymentReconciler#handleRollingUpdate}'s own "lowest-indexed mismatches first"
   * determinism. An assignment whose eviction the budget defers still has {@link
   * #reconcileAssignment} run against it (so its own confirmed/grace-period bookkeeping stays
   * current), it just isn't allowed to actually release this tick.
   */
  private void reconcileDeployment(
      DeploymentIdentity identity, List<InstanceAssignment> assignments, Instant now) {
    int budgetRemaining = evictionBudgetRemaining(identity, assignments);
    List<InstanceAssignment> byIndex =
        assignments.stream()
            .sorted(Comparator.comparingInt(InstanceAssignment::instanceIndex))
            .toList();
    int evicted = 0;
    for (InstanceAssignment assignment : byIndex) {
      try {
        if (reconcileAssignment(assignment, now, evicted < budgetRemaining)) {
          evicted++;
        }
      } catch (RuntimeException e) {
        // One assignment's failure must never abort the rest of this deployment's (or tick's)
        // assignments -- the next tick retries this one from the same full snapshot.
        log.warn(
            "replica count reconcile of {} instance {} failed: {}",
            assignment.deploymentName(),
            assignment.instanceIndex(),
            e.getMessage(),
            e);
      }
    }
  }

  /**
   * How many more of {@code identity}'s assignments may be released this tick without pushing its
   * total unavailable-replica count past its effective {@code DisruptionBudget#maxUnavailable}. See
   * this class's own javadoc for why "already unavailable" counts every missing index regardless of
   * cause, not just other node-death evictions.
   */
  private int evictionBudgetRemaining(
      DeploymentIdentity identity, List<InstanceAssignment> assignments) {
    Optional<DeploymentSpec> spec =
        store.getDeployment(identity.tenantId(), identity.deploymentName());
    if (spec.isEmpty()) {
      // Nothing to throttle against -- either untenanted desired state was never a Deployment to
      // begin with, or it has already been deleted (DeploymentReconciler's own stale-assignment
      // sweep will clean these up regardless of what this reconciler does with them).
      return Integer.MAX_VALUE;
    }
    int maxUnavailable = spec.get().effectiveDisruptionBudget().maxUnavailable();
    int replicas =
        store
            .getEffectiveReplicas(identity.tenantId(), identity.deploymentName())
            .orElse(spec.get().replicas());
    long assignedWithinReplicaRange =
        assignments.stream().filter(a -> a.instanceIndex() < replicas).count();
    long alreadyUnavailable = Math.max(0, replicas - assignedWithinReplicaRange);
    return (int) Math.max(0, maxUnavailable - alreadyUnavailable);
  }

  /**
   * @return {@code true} if this assignment was actually released this call (its eviction consumed
   *     one unit of this tick's disruption budget); {@code false} if it's confirmed healthy, still
   *     within its grace period, or due for release but deferred by the budget.
   */
  private boolean reconcileAssignment(
      InstanceAssignment assignment, Instant now, boolean evictionAllowed) {
    ReconcilerInstanceState persisted = currentState(assignment);
    if (isConfirmedByItsNode(assignment, now)) {
      clearFirstSeenMissing(persisted);
      return false;
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
        < 0) {
      return false;
    }
    if (!evictionAllowed) {
      // Past its grace period and otherwise due for release, but this deployment's disruption
      // budget is already spent this tick -- leave the timer exactly as elapsed as it already is,
      // so the next tick re-evaluates it as still due and it competes for budget again, rather
      // than restarting a fresh grace period it already served.
      log.debug(
          "deployment {} instance {} on node {} is due for release but the deployment's"
              + " disruption budget is exhausted this tick; deferring to a later tick",
          assignment.deploymentName(),
          assignment.instanceIndex(),
          assignment.nodeId());
      return false;
    }
    log.warn(
        "deployment {} instance {} on node {} is no longer confirmed by a heartbeat; releasing"
            + " its assignment for re-placement",
        assignment.deploymentName(),
        assignment.instanceIndex(),
        assignment.nodeId());
    // One batch: the release and its grace-period bookkeeping clear commit atomically, so a
    // crash between them can no longer leave a released assignment with a stale timer behind.
    mutations.proposeAll(
        List.of(
            new StateMutation.RemoveAssignment(
                assignment.tenantId(), assignment.deploymentName(), assignment.instanceIndex()),
            saveMutation(withFirstSeenMissing(persisted, ReconcilerInstanceState.ABSENT))));
    return true;
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
      // tenantId included in the match -- see HealthReconciler's own findIn for why.
      if (observation.deploymentName().equals(assignment.deploymentName())
          && observation.instanceIndex() == assignment.instanceIndex()
          && observation.tenantId().equals(assignment.tenantId())) {
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
        firstSeenMissingAtEpochMilli,
        state.tenantId());
  }

  private void save(ReconcilerInstanceState state) {
    mutations.propose(saveMutation(state));
  }

  private static StateMutation saveMutation(ReconcilerInstanceState state) {
    if (state.isEmpty()) {
      return new StateMutation.RemoveReconcilerInstanceState(
          state.tenantId(), state.deploymentName(), state.instanceIndex());
    }
    return new StateMutation.PutReconcilerInstanceState(state);
  }

  private ReconcilerInstanceState currentState(InstanceAssignment assignment) {
    return store
        .getReconcilerInstanceState(
            assignment.tenantId(), assignment.deploymentName(), assignment.instanceIndex())
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
        ReconcilerInstanceState.ABSENT,
        assignment.tenantId());
  }

  private static String key(Optional<String> tenantId, String deploymentName, int instanceIndex) {
    return tenantId.orElse("") + '\0' + deploymentName + "#" + instanceIndex;
  }

  /** Tenant-scoped deployment identity: what {@link #evictionBudgetRemaining} throttles against. */
  private record DeploymentIdentity(Optional<String> tenantId, String deploymentName) {
    static DeploymentIdentity of(InstanceAssignment assignment) {
      return new DeploymentIdentity(assignment.tenantId(), assignment.deploymentName());
    }
  }
}
