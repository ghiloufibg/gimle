package com.gimle.controlplane.alert;

import com.gimle.core.protocol.InstanceObservation;
import com.gimle.mimir.manifest.AlertRuleSpec;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StoreReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * against a previous reading. {@link #firing} is the one piece of state kept purely in-process, not
 * durable state -- whether a rule is currently firing, so {@link AlertNotifier#notify} is only ever
 * called on an actual transition (crossed -&gt; still crossed fires nothing, matching a real
 * alerting system's "don't page every minute for the same ongoing incident" posture), not on every
 * tick the condition still holds. A control-plane restart forgets this and may re-notify once on
 * the first tick after -- an accepted small-duplicate-notification tradeoff, the same shape {@code
 * MuninnShipper}'s own in-memory shipping cursor already accepts for a process restart.
 */
public final class AlertReconciler {

  private static final Logger log = LoggerFactory.getLogger(AlertReconciler.class);

  private final AlertRuleRegistry registry;
  private final StoreReader store;
  private final AlertNotifier notifier;
  private final Map<String, Boolean> firing = new ConcurrentHashMap<>();

  public AlertReconciler(AlertRuleRegistry registry, StoreReader store, AlertNotifier notifier) {
    this.registry = registry;
    this.store = store;
    this.notifier = notifier;
  }

  public void reconcileOnce() {
    List<AlertRuleSpec> rules = registry.list();
    Set<String> liveKeys = new HashSet<>();
    for (AlertRuleSpec rule : rules) {
      String key = ruleKey(rule);
      liveKeys.add(key);
      if (!rule.enabled()) {
        // A disabled rule never fires and never resolves -- but if it was firing when it got
        // disabled, drop its tracked state so re-enabling it later starts from "not firing" rather
        // than replaying a stale transition.
        firing.remove(key);
        continue;
      }
      try {
        evaluate(rule, key);
      } catch (RuntimeException e) {
        // One rule's failure (an unreachable webhook, a malformed metric read) must never abort
        // the rest of this tick's rules -- the next tick retries this one from a fresh reading,
        // the same level-triggered posture every other reconciler here already relies on.
        log.warn("evaluation of alert rule {} failed: {}", rule.name(), e.getMessage(), e);
      }
    }
    // A deleted rule's tracked firing state would otherwise sit in this map forever -- the exact
    // kind of unbounded per-resource growth this codebase treats as a defect elsewhere (see
    // WorkerMetrics#evict's own javadoc for the sibling case in module metrics).
    firing.keySet().retainAll(liveKeys);
  }

  private void evaluate(AlertRuleSpec rule, String key) {
    List<InstanceObservation> observations =
        observationsFor(rule.tenantId(), rule.deploymentName());
    double value = average(observations, metricExtractor(rule.metric()));
    boolean crosses = rule.crosses(value);
    boolean wasFiring = firing.getOrDefault(key, false);
    if (crosses && !wasFiring) {
      notifier.notify(new AlertNotification(rule, value, AlertNotification.State.FIRING));
      firing.put(key, true);
    } else if (!crosses && wasFiring) {
      notifier.notify(new AlertNotification(rule, value, AlertNotification.State.RESOLVED));
      firing.put(key, false);
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

  private static String ruleKey(AlertRuleSpec rule) {
    return rule.tenantId().orElse("") + '\0' + rule.name();
  }
}
