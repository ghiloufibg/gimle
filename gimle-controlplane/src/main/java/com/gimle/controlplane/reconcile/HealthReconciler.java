package com.gimle.controlplane.reconcile;

import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.restart.RestartTracker;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches each {@link InstanceObservation}'s {@code alive} flag and {@code lifecycleState}. An
 * instance its own node still reports, but as unhealthy, is exactly the "missing/unhealthy ->
 * reschedule" trigger {@link DeploymentReconciler} already handles generically once this reconciler
 * removes its assignment; this class's distinct job is the {@link RestartTracker}-shaped backoff
 * gating *when* that removal happens, per deployment-instance-index, so a replica that fails
 * immediately after every reschedule (a bad artifact, not a transient node problem) doesn't get
 * rescheduled in a tight loop across every node in the cluster -- the same shape of problem
 * module-level and worker-level restart already solved, recurring one tier up.
 *
 * <p>Readiness ({@code ready=false}) never triggers a reschedule here: readiness failures only flip
 * tracked readiness state, never restart anything.
 *
 * <p>Backoff bookkeeping (the {@link RestartTracker}'s attempt count/window, plus this reconciler's
 * own {@code pendingRetry}/{@code permanentlyFailed} flags) is persisted through {@link
 * StoreReader}/{@link MutationSink} as a {@link ReconcilerInstanceState}, not held in a local map
 * -- a reconciler-leader failover reconstructs this class fresh, and without persistence it would
 * silently forget every in-progress backoff, re-granting a full restart budget to an
 * already-flapping instance. Each read-modify-write starts from the currently persisted record so a
 * write here never clobbers {@code ReplicaCountReconciler}'s own {@code
 * firstSeenMissingAtEpochMilli} field in the same consolidated record.
 */
public final class HealthReconciler {

  private static final Logger log = LoggerFactory.getLogger(HealthReconciler.class);

  private final StoreReader store;
  private final Duration initialDelay;
  private final double multiplier;
  private final Duration cap;
  private final int maxAttemptsPerWindow;
  private final Duration window;
  private final MutationSink mutations;
  private final Clock clock;

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public HealthReconciler(StateStore store) {
    // Deliberately looser than module-level (100ms/5s cap) or worker-level (1s/30s cap) restart
    // backoff -- rescheduling to a different node is a heavier operation than either, and this
    // tier is already crossing the same network hop the heartbeat/dark-node timing reasons about.
    this(store, Duration.ofSeconds(2), 2.0, Duration.ofMinutes(1), 5, Duration.ofMinutes(15));
  }

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public HealthReconciler(
      StateStore store,
      Duration initialDelay,
      double multiplier,
      Duration cap,
      int maxAttemptsPerWindow,
      Duration window) {
    this(
        store,
        initialDelay,
        multiplier,
        cap,
        maxAttemptsPerWindow,
        window,
        mutation -> mutation.applyTo(store));
  }

  public HealthReconciler(
      StoreReader store,
      Duration initialDelay,
      double multiplier,
      Duration cap,
      int maxAttemptsPerWindow,
      Duration window,
      MutationSink mutations) {
    this(
        store,
        initialDelay,
        multiplier,
        cap,
        maxAttemptsPerWindow,
        window,
        mutations,
        Clock.systemUTC());
  }

  /**
   * Every backoff deadline this reconciler compares against is measured from a single {@code now}
   * read at the top of {@link #reconcileOnce}, so supplying that read is all a test needs to
   * exercise the real production backoff schedule without waiting for it -- see {@code TestClock}
   * in {@code gimle-core}'s test-jar.
   */
  public HealthReconciler(
      StoreReader store,
      Duration initialDelay,
      double multiplier,
      Duration cap,
      int maxAttemptsPerWindow,
      Duration window,
      MutationSink mutations,
      Clock clock) {
    this.store = store;
    this.initialDelay = initialDelay;
    this.multiplier = multiplier;
    this.cap = cap;
    this.maxAttemptsPerWindow = maxAttemptsPerWindow;
    this.window = window;
    this.mutations = mutations;
    this.clock = clock;
  }

  public void reconcileOnce() {
    Instant now = clock.instant();
    for (InstanceAssignment assignment : store.listAssignments()) {
      try {
        reconcileAssignment(assignment, now);
      } catch (RuntimeException e) {
        // One assignment's failure (e.g. a GimleRaftException from mutations.propose during a
        // store leader-election gap) must never abort the rest of this tick's assignments -- the
        // next tick retries this one from the same full snapshot.
        log.warn(
            "health reconcile of {} instance {} failed: {}",
            assignment.deploymentName(),
            assignment.instanceIndex(),
            e.getMessage(),
            e);
      }
    }
  }

  private void reconcileAssignment(InstanceAssignment assignment, Instant now) {
    ReconcilerInstanceState persisted = currentState(assignment);
    if (persisted.permanentlyFailed()) {
      return;
    }
    Optional<InstanceObservation> observation = findObservation(assignment);
    if (observation.isEmpty()) {
      return; // ReplicaCountReconciler's concern, not this one
    }
    if (isHealthy(observation.get())) {
      recordHealthy(assignment, persisted);
      return;
    }
    handleUnhealthy(assignment, persisted, now);
  }

  private void recordHealthy(InstanceAssignment assignment, ReconcilerInstanceState persisted) {
    if (persisted.attemptsInWindow() == 0
        && persisted.windowStartEpochMilli() == ReconcilerInstanceState.ABSENT
        && !persisted.pendingRetry()) {
      return; // nothing to reset -- avoid an unnecessary write on every healthy tick
    }
    save(
        new ReconcilerInstanceState(
            assignment.deploymentName(),
            assignment.instanceIndex(),
            0,
            ReconcilerInstanceState.ABSENT,
            ReconcilerInstanceState.ABSENT,
            false,
            persisted.permanentlyFailed(),
            persisted.firstSeenMissingAtEpochMilli(),
            persisted.firstContinuousReadyAtEpochMilli(),
            assignment.tenantId()));
  }

  private void handleUnhealthy(
      InstanceAssignment assignment, ReconcilerInstanceState persisted, Instant now) {
    RestartTracker tracker = restoreTracker(persisted);
    boolean pendingRetry = persisted.pendingRetry();
    if (!pendingRetry) {
      if (!tracker.recordFailureAndCheckShouldRetry(now)) {
        log.error(
            "deployment {} instance {} exhausted its restart budget; giving up on rescheduling it",
            assignment.deploymentName(),
            assignment.instanceIndex());
        // A durable timeline entry, not only this platform-log line -- "gave up" is otherwise
        // invisible to gimle events, and DeploymentReconciler's own rolling/surge budget tracking
        // reads this same permanentlyFailed flag to stop this index from wedging a whole
        // deployment's future rollouts forever.
        mutations.propose(
            new StateMutation.AppendInstanceEvent(
                assignment.tenantId(),
                new InstanceEvent(
                    UUID.randomUUID().toString(),
                    assignment.deploymentName(),
                    assignment.instanceIndex(),
                    InstanceEventKind.TRANSITION_FAILED,
                    "exhausted its restart budget; giving up on rescheduling it",
                    Optional.empty(),
                    clock.millis())));
        persistTracker(assignment, persisted, tracker, false, true);
        return;
      }
      pendingRetry = true;
    }

    Duration delay = tracker.delayUntilNextAttempt(now);
    if (delay.compareTo(Duration.ZERO) <= 0) {
      // One batch: the reschedule and its backoff bookkeeping commit atomically, so a crash
      // between them can no longer remove the assignment while leaving pendingRetry set.
      mutations.proposeAll(
          List.of(
              new StateMutation.RemoveAssignment(
                  assignment.tenantId(), assignment.deploymentName(), assignment.instanceIndex()),
              saveMutation(trackerState(assignment, persisted, tracker, false, false))));
      return;
    }
    persistTracker(assignment, persisted, tracker, true, false);
  }

  private RestartTracker restoreTracker(ReconcilerInstanceState persisted) {
    if (persisted.windowStartEpochMilli() == ReconcilerInstanceState.ABSENT) {
      return newTracker();
    }
    Instant nextAllowedAttempt =
        persisted.nextAllowedAttemptEpochMilli() == ReconcilerInstanceState.ABSENT
            ? Instant.EPOCH
            : Instant.ofEpochMilli(persisted.nextAllowedAttemptEpochMilli());
    return RestartTracker.restore(
        initialDelay,
        multiplier,
        cap,
        maxAttemptsPerWindow,
        window,
        persisted.attemptsInWindow(),
        Instant.ofEpochMilli(persisted.windowStartEpochMilli()),
        nextAllowedAttempt);
  }

  private void persistTracker(
      InstanceAssignment assignment,
      ReconcilerInstanceState previous,
      RestartTracker tracker,
      boolean pendingRetry,
      boolean permanentlyFailed) {
    save(trackerState(assignment, previous, tracker, pendingRetry, permanentlyFailed));
  }

  private static ReconcilerInstanceState trackerState(
      InstanceAssignment assignment,
      ReconcilerInstanceState previous,
      RestartTracker tracker,
      boolean pendingRetry,
      boolean permanentlyFailed) {
    return new ReconcilerInstanceState(
        assignment.deploymentName(),
        assignment.instanceIndex(),
        tracker.attemptsInWindow(),
        tracker.windowStart().map(Instant::toEpochMilli).orElse(ReconcilerInstanceState.ABSENT),
        tracker.nextAllowedAttempt().equals(Instant.EPOCH)
            ? ReconcilerInstanceState.ABSENT
            : tracker.nextAllowedAttempt().toEpochMilli(),
        pendingRetry,
        permanentlyFailed,
        previous.firstSeenMissingAtEpochMilli(),
        previous.firstContinuousReadyAtEpochMilli(),
        assignment.tenantId());
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
        ReconcilerInstanceState.ABSENT,
        assignment.tenantId());
  }

  private Optional<InstanceObservation> findObservation(InstanceAssignment assignment) {
    return store
        .getNodeHeartbeat(assignment.nodeId())
        .map(ObservedHeartbeat::heartbeat)
        .flatMap(heartbeat -> findIn(heartbeat, assignment));
  }

  private static Optional<InstanceObservation> findIn(
      NodeHeartbeat heartbeat, InstanceAssignment assignment) {
    for (InstanceObservation observation : heartbeat.instances()) {
      // tenantId included in the match, not just (deploymentName, instanceIndex) -- see
      // InstanceObservation's own javadoc for why: two different tenants' identically-named
      // deployment can otherwise land the same instanceIndex on the very same node.
      if (observation.deploymentName().equals(assignment.deploymentName())
          && observation.instanceIndex() == assignment.instanceIndex()
          && observation.tenantId().equals(assignment.tenantId())) {
        return Optional.of(observation);
      }
    }
    return Optional.empty();
  }

  private static boolean isHealthy(InstanceObservation observation) {
    return observation.alive() && !"FAILED".equals(observation.lifecycleState());
  }

  private RestartTracker newTracker() {
    return new RestartTracker(initialDelay, multiplier, cap, maxAttemptsPerWindow, window);
  }
}
