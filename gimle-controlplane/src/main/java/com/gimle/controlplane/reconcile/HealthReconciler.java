package com.gimle.controlplane.reconcile;

import com.gimle.controlplane.store.InstanceAssignment;
import com.gimle.controlplane.store.ObservedHeartbeat;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.restart.RestartTracker;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches each {@link InstanceObservation}'s {@code alive} flag and {@code lifecycleState} (design
 * §7). An instance its own node still reports, but as unhealthy, is exactly the "missing/unhealthy
 * -> reschedule" trigger {@link DeploymentReconciler} already handles generically once this
 * reconciler removes its assignment; this class's distinct job is the {@link RestartTracker}-shaped
 * backoff gating *when* that removal happens, per deployment-instance-index, so a replica that
 * fails immediately after every reschedule (a bad artifact, not a transient node problem) doesn't
 * get rescheduled in a tight loop across every node in the cluster -- the same shape of problem
 * module-level and worker-level restart already solved, recurring one tier up.
 *
 * <p>Readiness ({@code ready=false}) never triggers a reschedule here, consistent with Phase 2's
 * own rule that readiness failures only flip tracked readiness state, never restart anything.
 */
public final class HealthReconciler {

  private static final Logger log = LoggerFactory.getLogger(HealthReconciler.class);

  private final StateStore store;
  private final Duration initialDelay;
  private final double multiplier;
  private final Duration cap;
  private final int maxAttemptsPerWindow;
  private final Duration window;
  private final Map<String, RestartTracker> restartTrackers = new ConcurrentHashMap<>();
  private final Set<String> pendingRetry = ConcurrentHashMap.newKeySet();
  private final Set<String> permanentlyFailed = ConcurrentHashMap.newKeySet();

  public HealthReconciler(StateStore store) {
    // Deliberately looser than either Phase 2 tier (module-level: 100ms/5s cap; worker-level:
    // 1s/30s cap) -- rescheduling to a different node is a heavier operation than either, and
    // this tier is already crossing the same network hop the heartbeat/dark-node timing (design
    // §11.3) reasons about.
    this(store, Duration.ofSeconds(2), 2.0, Duration.ofMinutes(1), 5, Duration.ofMinutes(15));
  }

  public HealthReconciler(
      StateStore store,
      Duration initialDelay,
      double multiplier,
      Duration cap,
      int maxAttemptsPerWindow,
      Duration window) {
    this.store = store;
    this.initialDelay = initialDelay;
    this.multiplier = multiplier;
    this.cap = cap;
    this.maxAttemptsPerWindow = maxAttemptsPerWindow;
    this.window = window;
  }

  public void reconcile_once() {
    Instant now = Instant.now();
    for (InstanceAssignment assignment : store.list_assignments()) {
      String key = key(assignment);
      if (permanentlyFailed.contains(key)) {
        continue;
      }
      Optional<InstanceObservation> observation = find_observation(assignment);
      if (observation.isEmpty()) {
        continue; // ReplicaCountReconciler's concern, not this one
      }
      if (is_healthy(observation.get())) {
        Optional.ofNullable(restartTrackers.get(key)).ifPresent(RestartTracker::record_success);
        pendingRetry.remove(key);
        continue;
      }
      handle_unhealthy(assignment, key, now);
    }
  }

  private void handle_unhealthy(InstanceAssignment assignment, String key, Instant now) {
    RestartTracker tracker = restartTrackers.computeIfAbsent(key, k -> new_tracker());
    if (!pendingRetry.contains(key)) {
      if (!tracker.record_failure_and_check_should_retry(now)) {
        log.error(
            "deployment {} instance {} exhausted its restart budget; giving up on rescheduling it",
            assignment.deploymentName(),
            assignment.instanceIndex());
        permanentlyFailed.add(key);
        return;
      }
      pendingRetry.add(key);
    }

    Duration delay = tracker.delay_until_next_attempt(now);
    if (delay.compareTo(Duration.ZERO) <= 0) {
      store.remove_assignment(assignment.deploymentName(), assignment.instanceIndex());
      pendingRetry.remove(key);
    }
  }

  private Optional<InstanceObservation> find_observation(InstanceAssignment assignment) {
    return store
        .get_node_heartbeat(assignment.nodeId())
        .map(ObservedHeartbeat::heartbeat)
        .flatMap(heartbeat -> find_in(heartbeat, assignment));
  }

  private static Optional<InstanceObservation> find_in(
      NodeHeartbeat heartbeat, InstanceAssignment assignment) {
    for (InstanceObservation observation : heartbeat.instances()) {
      if (observation.deploymentName().equals(assignment.deploymentName())
          && observation.instanceIndex() == assignment.instanceIndex()) {
        return Optional.of(observation);
      }
    }
    return Optional.empty();
  }

  private static boolean is_healthy(InstanceObservation observation) {
    return observation.alive() && !"FAILED".equals(observation.lifecycleState());
  }

  private static String key(InstanceAssignment assignment) {
    return assignment.deploymentName() + "#" + assignment.instanceIndex();
  }

  private RestartTracker new_tracker() {
    return new RestartTracker(initialDelay, multiplier, cap, maxAttemptsPerWindow, window);
  }
}
