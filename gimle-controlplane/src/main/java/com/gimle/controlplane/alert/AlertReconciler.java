package com.gimle.controlplane.alert;

import com.gimle.core.protocol.InstanceObservation;
import com.gimle.mimir.manifest.AlertRuleSpec;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StoreReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates every enabled {@link AlertRuleSpec} on a tick, level-triggered like every other
 * reconciler in this codebase: each tick recomputes the current value of {@code rule.metric()} from
 * scratch off the live store snapshot (the same request-rate/error-rate/queue-depth/CPU/memory
 * signals {@code ApiServer#handleMetrics} already averages across a deployment's own instances for
 * the console's Metrics screen, reused here rather than reimplemented differently), never diffing
 * against a previous reading. Whether a rule is currently firing is durable state, read and written
 * through {@link #registry} rather than kept in this reconciler's own process (see {@code
 * StateStore#putAlertFiringState}'s own javadoc) -- {@link AlertNotifier#notify} is only ever
 * called on an actual transition (crossed -&gt; still crossed proposes nothing, matching a real
 * alerting system's "don't page every minute for the same ongoing incident" posture), not on every
 * tick the condition still holds, and a control-plane restart or failover onto another replica
 * reads the same last-known verdict rather than forgetting it. A disabled rule is simply never
 * evaluated; its last-known verdict is left untouched rather than reset, so re-enabling it resumes
 * from the true prior state instead of an artificially cleared one -- the durable equivalent of
 * every other level-triggered reconciler here just not looking at a resource it has nothing to do
 * for.
 */
public final class AlertReconciler {

  private static final Logger log = LoggerFactory.getLogger(AlertReconciler.class);

  private final AlertRuleRegistry registry;
  private final StoreReader store;
  private final AlertNotifier notifier;

  /**
   * Which rules have already been reported as watching a deployment nothing is reporting for.
   * Purely log de-duplication -- a tick evaluates every rule from the live snapshot regardless of
   * what is in here, so this never influences a verdict; without it, a rule pointed at a misspelled
   * or undeployed name would repeat the same warning on every tick forever, and with nothing at all
   * an operator would see a rule sit at "never evaluated" with no explanation anywhere.
   */
  private final Set<String> reportedAsUnmatched = ConcurrentHashMap.newKeySet();

  public AlertReconciler(AlertRuleRegistry registry, StoreReader store, AlertNotifier notifier) {
    this.registry = registry;
    this.store = store;
    this.notifier = notifier;
  }

  public void reconcileOnce() {
    for (AlertRuleSpec rule : registry.list()) {
      if (!rule.enabled()) {
        continue;
      }
      try {
        evaluate(rule);
      } catch (RuntimeException e) {
        // One rule's failure (an unreachable webhook, a malformed metric read) must never abort
        // the rest of this tick's rules -- the next tick retries this one from a fresh reading,
        // the same level-triggered posture every other reconciler here already relies on.
        log.warn("evaluation of alert rule {} failed: {}", rule.name(), e.getMessage(), e);
      }
    }
  }

  private void evaluate(AlertRuleSpec rule) {
    List<InstanceObservation> observations =
        observationsFor(rule.tenantId(), rule.deploymentName());
    reportUnmatchedDeployment(rule, observations.isEmpty());
    double value = average(observations, metricExtractor(rule.metric()));
    boolean crosses = rule.crosses(value);
    boolean wasFiring = registry.getFiringState(rule.tenantId(), rule.name()).orElse(false);
    if (crosses && !wasFiring) {
      notifier.notify(new AlertNotification(rule, value, AlertNotification.State.FIRING));
      registry.putFiringState(rule.tenantId(), rule.name(), true);
    } else if (!crosses && wasFiring) {
      notifier.notify(new AlertNotification(rule, value, AlertNotification.State.RESOLVED));
      registry.putFiringState(rule.tenantId(), rule.name(), false);
    }
  }

  /**
   * Says out loud, once, that a rule is being evaluated against no live reading at all -- the
   * deployment it names does not exist under its tenant, has no placed instance, or has not
   * heartbeated one yet. Every such rule still evaluates against an average of zero, which for a
   * {@code GREATER_THAN} rule silently never crosses; this is the only place that difference
   * between "healthy" and "watching nothing" is visible to an operator.
   */
  private void reportUnmatchedDeployment(AlertRuleSpec rule, boolean unmatched) {
    String key = rule.tenantId().orElse("") + "/" + rule.name();
    if (!unmatched) {
      reportedAsUnmatched.remove(key);
      return;
    }
    if (reportedAsUnmatched.add(key)) {
      log.warn(
          "alert rule '{}' watches deployment '{}' in tenant '{}', which currently reports no"
              + " instance metrics -- the rule evaluates against a value of 0 until one does",
          rule.name(),
          rule.deploymentName(),
          rule.tenantId().orElse("(untenanted)"));
    }
  }

  private List<InstanceObservation> observationsFor(
      Optional<String> tenantId, String deploymentName) {
    List<InstanceObservation> observations = new ArrayList<>();
    for (InstanceAssignment assignment : store.listAssignmentsFor(tenantId, deploymentName)) {
      findObservation(assignment).ifPresent(observations::add);
    }
    return observations;
  }

  private Optional<InstanceObservation> findObservation(InstanceAssignment assignment) {
    return findObservation(
        assignment.nodeId(),
        obs ->
            obs.deploymentName().equals(assignment.deploymentName())
                && obs.instanceIndex() == assignment.instanceIndex()
                && obs.tenantId().equals(assignment.tenantId()));
  }

  private Optional<InstanceObservation> findObservation(
      String nodeId, Predicate<InstanceObservation> matches) {
    return store
        .getNodeHeartbeat(nodeId)
        .map(ObservedHeartbeat::heartbeat)
        .flatMap(heartbeat -> heartbeat.instances().stream().filter(matches).findFirst());
  }

  private static ToDoubleFunction<InstanceObservation> metricExtractor(
      AlertRuleSpec.Metric metric) {
    return switch (metric) {
      case REQUEST_RATE_PER_SECOND -> InstanceObservation::requestRatePerSecond;
      case ERROR_RATE_PER_SECOND -> InstanceObservation::errorRatePerSecond;
      case QUEUE_DEPTH -> obs -> obs.queueDepth();
      case CPU_MILLICORES_USED -> obs -> obs.cpuMillicoresUsed();
      case MEMORY_BYTES_USED -> obs -> obs.memoryBytesUsed();
    };
  }

  private static double average(
      List<InstanceObservation> observations, ToDoubleFunction<InstanceObservation> extractor) {
    if (observations.isEmpty()) {
      return 0.0;
    }
    return observations.stream().mapToDouble(extractor).average().orElse(0.0);
  }
}
