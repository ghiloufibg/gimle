package com.gimle.controlplane.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.mimir.manifest.AlertRuleSpec;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.StateStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The alerting primitive's core evaluation logic: a rule fires the first tick its metric crosses
 * threshold, never re-fires on a later tick while still crossed, and resolves exactly once when the
 * reading moves back to the safe side -- proved against a real {@link StateStore} (not a mock),
 * mirroring {@code AutoscaleReconcilerTest}'s own "real observations, real assignments" fixture
 * style for the identical {@code InstanceObservation} signal set.
 */
class AlertReconcilerTest {

  private static final ModuleId MODULE_ID =
      new ModuleId("com.gimle.example.checkout", Version.parse("1.0.0"));

  private static class RecordingNotifier implements AlertNotifier {
    final List<AlertNotification> notifications = new ArrayList<>();

    @Override
    public void notify(AlertNotification notification) {
      notifications.add(notification);
    }
  }

  private static void oneInstanceReporting(
      StateStore store, String deploymentName, double errorRatePerSecond) {
    store.putAssignment(new InstanceAssignment(deploymentName, 0, "node-a", MODULE_ID, ""));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(500L * 1024 * 1024, 0, 4000, 0),
            List.of(
                InstanceObservation.builder(deploymentName, 0, MODULE_ID, "ACTIVE", true, true)
                    .load(0.0, errorRatePerSecond, 0, 0L, 0L)
                    .build())));
  }

  private static AlertRuleSpec errorRateRule(double threshold) {
    return new AlertRuleSpec(
        "high-error-rate",
        Optional.empty(),
        "checkout-service",
        AlertRuleSpec.Metric.ERROR_RATE_PER_SECOND,
        AlertRuleSpec.Comparator.GREATER_THAN,
        threshold,
        "https://hooks.example.com/alerts");
  }

  @Test
  void fires_once_the_metric_crosses_threshold() {
    StateStore store = new StateStore();
    AlertRuleRegistry registry = new AlertRuleRegistry(store);
    registry.put(errorRateRule(5.0));
    oneInstanceReporting(store, "checkout-service", 8.0);
    RecordingNotifier notifier = new RecordingNotifier();
    AlertReconciler reconciler = new AlertReconciler(registry, store, notifier);

    reconciler.reconcileOnce();

    assertEquals(1, notifier.notifications.size());
    AlertNotification n = notifier.notifications.get(0);
    assertEquals(AlertNotification.State.FIRING, n.state());
    assertEquals(8.0, n.observedValue());
  }

  @Test
  void does_not_fire_when_the_metric_stays_under_threshold() {
    StateStore store = new StateStore();
    AlertRuleRegistry registry = new AlertRuleRegistry(store);
    registry.put(errorRateRule(5.0));
    oneInstanceReporting(store, "checkout-service", 2.0);
    RecordingNotifier notifier = new RecordingNotifier();
    AlertReconciler reconciler = new AlertReconciler(registry, store, notifier);

    reconciler.reconcileOnce();

    assertTrue(notifier.notifications.isEmpty());
  }

  @Test
  void does_not_re_fire_on_a_later_tick_while_still_crossed() {
    StateStore store = new StateStore();
    AlertRuleRegistry registry = new AlertRuleRegistry(store);
    registry.put(errorRateRule(5.0));
    oneInstanceReporting(store, "checkout-service", 8.0);
    RecordingNotifier notifier = new RecordingNotifier();
    AlertReconciler reconciler = new AlertReconciler(registry, store, notifier);

    reconciler.reconcileOnce();
    reconciler.reconcileOnce();
    reconciler.reconcileOnce();

    assertEquals(1, notifier.notifications.size(), "only the first crossing should notify");
  }

  @Test
  void resolves_exactly_once_when_the_reading_moves_back_under_threshold() {
    StateStore store = new StateStore();
    AlertRuleRegistry registry = new AlertRuleRegistry(store);
    registry.put(errorRateRule(5.0));
    oneInstanceReporting(store, "checkout-service", 8.0);
    RecordingNotifier notifier = new RecordingNotifier();
    AlertReconciler reconciler = new AlertReconciler(registry, store, notifier);
    reconciler.reconcileOnce(); // fires

    oneInstanceReporting(store, "checkout-service", 1.0);
    reconciler.reconcileOnce(); // resolves
    reconciler.reconcileOnce(); // stays resolved, no duplicate notification

    assertEquals(2, notifier.notifications.size());
    assertEquals(AlertNotification.State.FIRING, notifier.notifications.get(0).state());
    assertEquals(AlertNotification.State.RESOLVED, notifier.notifications.get(1).state());
    assertEquals(1.0, notifier.notifications.get(1).observedValue());
  }

  @Test
  void a_disabled_rule_never_fires() {
    StateStore store = new StateStore();
    AlertRuleRegistry registry = new AlertRuleRegistry(store);
    registry.put(
        new AlertRuleSpec(
            "high-error-rate",
            Optional.empty(),
            "checkout-service",
            AlertRuleSpec.Metric.ERROR_RATE_PER_SECOND,
            AlertRuleSpec.Comparator.GREATER_THAN,
            5.0,
            "https://hooks.example.com/alerts",
            false));
    oneInstanceReporting(store, "checkout-service", 8.0);
    RecordingNotifier notifier = new RecordingNotifier();
    AlertReconciler reconciler = new AlertReconciler(registry, store, notifier);

    reconciler.reconcileOnce();

    assertTrue(notifier.notifications.isEmpty());
  }

  @Test
  void an_instance_with_no_observation_yet_contributes_nothing_rather_than_dragging_the_average() {
    // No node heartbeat at all -- the exact "not yet observed" gap ApiServer#handleMetrics'
    // own average() helper degrades gracefully from, mirrored here.
    StateStore store = new StateStore();
    AlertRuleRegistry registry = new AlertRuleRegistry(store);
    registry.put(errorRateRule(5.0));
    store.putAssignment(new InstanceAssignment("checkout-service", 0, "node-a", MODULE_ID, ""));
    RecordingNotifier notifier = new RecordingNotifier();
    AlertReconciler reconciler = new AlertReconciler(registry, store, notifier);

    reconciler.reconcileOnce();

    assertTrue(notifier.notifications.isEmpty());
  }

  @Test
  void a_deleted_rules_firing_state_does_not_leak_forever() {
    StateStore store = new StateStore();
    AlertRuleRegistry registry = new AlertRuleRegistry(store);
    registry.put(errorRateRule(5.0));
    oneInstanceReporting(store, "checkout-service", 8.0);
    RecordingNotifier notifier = new RecordingNotifier();
    AlertReconciler reconciler = new AlertReconciler(registry, store, notifier);
    reconciler.reconcileOnce(); // fires, tracked as firing internally

    registry.remove(Optional.empty(), "high-error-rate");
    reconciler.reconcileOnce(); // prunes the now-deleted rule's tracked state

    // Re-creating the same-named rule afterward starts fresh (not "already firing"), proving the
    // prior tracked state was actually dropped, not merely orphaned in the map.
    registry.put(errorRateRule(5.0));
    reconciler.reconcileOnce();

    assertEquals(2, notifier.notifications.size(), "recreated rule should fire again, not skip");
    assertEquals(AlertNotification.State.FIRING, notifier.notifications.get(1).state());
  }
}
