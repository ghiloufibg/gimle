package com.gimle.controlplane.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.service.ServiceEndpoint;
import com.gimle.controlplane.service.ServiceRegistry;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.StateStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Level-triggered like {@code DeploymentReconcilerTest}'s own suite: every test here reconciles a
 * store snapshot built from scratch, not a sequence of incremental edits, proving {@link
 * ServiceReconciler#reconcileOnce} converges to the same result regardless of what state the
 * snapshot started in.
 */
class ServiceReconcilerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static final ModuleId MODULE_ID = new ModuleId("com.acme.orders", Version.parse("1.0.0"));

  private static void registerNode(StateStore store, String nodeId, String apiAddress) {
    store.putNodeRegistration(
        new NodeRegistration(nodeId, new NodeCapabilities(Set.of()), Optional.of(apiAddress)));
  }

  private static void putHeartbeat(
      StateStore store,
      String nodeId,
      String deploymentName,
      int instanceIndex,
      boolean ready,
      Map<String, Integer> ports) {
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            nodeId,
            new ResourceUsageSnapshot(0, 0, 0, 0),
            List.of(
                new InstanceObservation(
                    deploymentName,
                    instanceIndex,
                    MODULE_ID,
                    "ACTIVE",
                    true,
                    ready,
                    0.0,
                    0,
                    0L,
                    0L,
                    0.0,
                    ports))));
  }

  @Test
  void an_empty_store_converges_to_an_empty_endpoint_list() {
    StateStore store = new StateStore();
    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));

    new ServiceReconciler(registry, store).reconcileOnce();

    assertTrue(
        registry.getEndpoints(Optional.empty(), "orders").isEmpty(),
        "a Service whose backing deployment has no assignments yet must converge to no endpoints,"
            + " not fail");
  }

  @Test
  void a_store_with_active_and_ready_instances_already_present_converges_to_their_endpoints() {
    StateStore store = new StateStore();
    registerNode(store, "node-a", "10.0.0.5:9101");
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a", MODULE_ID, ""));
    putHeartbeat(store, "node-a", "orders-service", 0, true, Map.of("HTTP_PORT", 51234));

    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));

    new ServiceReconciler(registry, store).reconcileOnce();

    assertEquals(
        List.of(new ServiceEndpoint("10.0.0.5", 51234, Optional.of("node-a"))),
        registry.getEndpoints(Optional.empty(), "orders"));
  }

  @Test
  void an_instance_reported_alive_but_not_ready_yet_contributes_no_endpoint() {
    StateStore store = new StateStore();
    registerNode(store, "node-a", "10.0.0.5:9101");
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a", MODULE_ID, ""));
    putHeartbeat(store, "node-a", "orders-service", 0, false, Map.of("HTTP_PORT", 51234));

    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));

    new ServiceReconciler(registry, store).reconcileOnce();

    assertTrue(registry.getEndpoints(Optional.empty(), "orders").isEmpty());
  }

  @Test
  void an_instance_with_no_heartbeat_yet_contributes_no_endpoint() {
    StateStore store = new StateStore();
    registerNode(store, "node-a", "10.0.0.5:9101");
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a", MODULE_ID, ""));
    // no heartbeat ever recorded for node-a

    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));

    new ServiceReconciler(registry, store).reconcileOnce();

    assertTrue(registry.getEndpoints(Optional.empty(), "orders").isEmpty());
  }

  /** deploymentNames is a real selector across multiple workload names, not just a single one. */
  @Test
  void endpoints_are_aggregated_across_every_deployment_name_in_the_selector() {
    StateStore store = new StateStore();
    registerNode(store, "node-a", "10.0.0.5:9101");
    registerNode(store, "node-b", "10.0.0.6:9101");
    store.putAssignment(new InstanceAssignment("orders-v1", 0, "node-a", MODULE_ID, ""));
    store.putAssignment(new InstanceAssignment("orders-v2", 0, "node-b", MODULE_ID, ""));
    putHeartbeat(store, "node-a", "orders-v1", 0, true, Map.of("HTTP_PORT", 51234));
    putHeartbeat(store, "node-b", "orders-v2", 0, true, Map.of("HTTP_PORT", 51235));

    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(
        new ServiceSpec("orders", Optional.empty(), Set.of("orders-v1", "orders-v2"), 8080));

    new ServiceReconciler(registry, store).reconcileOnce();

    assertEquals(
        Set.of(
            new ServiceEndpoint("10.0.0.5", 51234, Optional.of("node-a")),
            new ServiceEndpoint("10.0.0.6", 51235, Optional.of("node-b"))),
        Set.copyOf(registry.getEndpoints(Optional.empty(), "orders")));
  }

  @Test
  void one_services_failure_does_not_prevent_another_from_reconciling() {
    StateStore store = new StateStore();
    registerNode(store, "node-a", "10.0.0.5:9101");
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a", MODULE_ID, ""));
    putHeartbeat(store, "node-a", "orders-service", 0, true, Map.of("HTTP_PORT", 51234));

    ServiceRegistry registry = new ServiceRegistry(store);
    // "unreachable" resolves to no assignments at all -- not a failure, just an empty result --
    // reconciled alongside a Service that does have a healthy backing instance, in the same tick.
    registry.put(new ServiceSpec("unreachable", Optional.empty(), Set.of("nothing-here"), 9090));
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));

    new ServiceReconciler(registry, store).reconcileOnce();

    assertTrue(registry.getEndpoints(Optional.empty(), "unreachable").isEmpty());
    assertEquals(
        List.of(new ServiceEndpoint("10.0.0.5", 51234, Optional.of("node-a"))),
        registry.getEndpoints(Optional.empty(), "orders"));
  }

  @Test
  void reconciling_again_after_a_backing_instance_goes_away_clears_its_endpoint() {
    StateStore store = new StateStore();
    registerNode(store, "node-a", "10.0.0.5:9101");
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a", MODULE_ID, ""));
    putHeartbeat(store, "node-a", "orders-service", 0, true, Map.of("HTTP_PORT", 51234));

    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));
    ServiceReconciler reconciler = new ServiceReconciler(registry, store);
    reconciler.reconcileOnce();
    assertEquals(1, registry.getEndpoints(Optional.empty(), "orders").size());

    store.removeAssignment(Optional.empty(), "orders-service", 0);
    reconciler.reconcileOnce();

    assertTrue(
        registry.getEndpoints(Optional.empty(), "orders").isEmpty(),
        "a level-triggered reconcile must fully replace the prior endpoint set, not merge into it");
  }

  /**
   * The declared targetPort is what resolution keys on, not "whatever single port is reported": an
   * instance reporting several ports used to contribute nothing at all.
   */
  @Test
  void a_declared_target_port_selects_that_port_out_of_a_multi_port_instance() {
    StateStore store = new StateStore();
    registerNode(store, "node-a", "10.0.0.5:9101");
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a", MODULE_ID, ""));
    putHeartbeat(
        store,
        "node-a",
        "orders-service",
        0,
        true,
        Map.of("HTTP_PORT", 51234, "ADMIN_PORT", 51235));

    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(
        new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080, 51235));

    new ServiceReconciler(registry, store).reconcileOnce();

    assertEquals(
        List.of(new ServiceEndpoint("10.0.0.5", 51235, Optional.of("node-a"))),
        registry.getEndpoints(Optional.empty(), "orders"));
  }

  @Test
  void an_instance_not_reporting_the_declared_target_port_contributes_no_endpoint() {
    StateStore store = new StateStore();
    registerNode(store, "node-a", "10.0.0.5:9101");
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a", MODULE_ID, ""));
    putHeartbeat(store, "node-a", "orders-service", 0, true, Map.of("HTTP_PORT", 51234));

    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080, 9090));

    new ServiceReconciler(registry, store).reconcileOnce();

    assertTrue(
        registry.getEndpoints(Optional.empty(), "orders").isEmpty(),
        "a wrong port is worse than no endpoint -- the instance must be left out entirely");
  }

  @Test
  void with_no_target_port_declared_a_single_reported_port_still_resolves() {
    StateStore store = new StateStore();
    registerNode(store, "node-a", "10.0.0.5:9101");
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a", MODULE_ID, ""));
    putHeartbeat(store, "node-a", "orders-service", 0, true, Map.of("HTTP_PORT", 51234));

    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));

    new ServiceReconciler(registry, store).reconcileOnce();

    assertEquals(
        List.of(new ServiceEndpoint("10.0.0.5", 51234, Optional.of("node-a"))),
        registry.getEndpoints(Optional.empty(), "orders"));
  }

  @Test
  void with_no_target_port_declared_several_reported_ports_stay_ambiguous() {
    StateStore store = new StateStore();
    registerNode(store, "node-a", "10.0.0.5:9101");
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a", MODULE_ID, ""));
    putHeartbeat(
        store,
        "node-a",
        "orders-service",
        0,
        true,
        Map.of("HTTP_PORT", 51234, "ADMIN_PORT", 51235));

    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));

    new ServiceReconciler(registry, store).reconcileOnce();

    assertTrue(registry.getEndpoints(Optional.empty(), "orders").isEmpty());
  }

  /**
   * Convergence from the awkward direction: the store already holds an instance whose reported
   * ports changed under a Service that never changed, and one tick from that arbitrary starting
   * state lands on the right answer with no memory of the previous one.
   */
  @Test
  void a_target_port_appearing_and_disappearing_converges_both_ways_from_any_starting_state() {
    StateStore store = new StateStore();
    registerNode(store, "node-a", "10.0.0.5:9101");
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a", MODULE_ID, ""));
    putHeartbeat(store, "node-a", "orders-service", 0, true, Map.of("HTTP_PORT", 51234));

    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080, 9090));
    ServiceReconciler reconciler = new ServiceReconciler(registry, store);
    reconciler.reconcileOnce();
    assertTrue(registry.getEndpoints(Optional.empty(), "orders").isEmpty());

    putHeartbeat(store, "node-a", "orders-service", 0, true, Map.of("HTTP_PORT", 9090));
    reconciler.reconcileOnce();
    assertEquals(
        List.of(new ServiceEndpoint("10.0.0.5", 9090, Optional.of("node-a"))),
        registry.getEndpoints(Optional.empty(), "orders"));

    putHeartbeat(store, "node-a", "orders-service", 0, true, Map.of("HTTP_PORT", 51234));
    reconciler.reconcileOnce();
    assertTrue(
        registry.getEndpoints(Optional.empty(), "orders").isEmpty(),
        "a level-triggered tick must drop an endpoint whose port no longer matches");
  }

  /**
   * The exclusion bookkeeping the reconciler keeps for log dedup must never leak into the endpoint
   * set: repeated ticks over an unchanged store keep producing the same answer.
   */
  @Test
  void repeated_ticks_over_an_unchanged_store_keep_producing_the_same_endpoints() {
    StateStore store = new StateStore();
    registerNode(store, "node-a", "10.0.0.5:9101");
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a", MODULE_ID, ""));
    store.putAssignment(new InstanceAssignment("orders-service", 1, "node-a", MODULE_ID, ""));
    putHeartbeat(store, "node-a", "orders-service", 0, true, Map.of("HTTP_PORT", 51234));

    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(
        new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080, 51234));
    ServiceReconciler reconciler = new ServiceReconciler(registry, store);

    for (int tick = 0; tick < 3; tick++) {
      reconciler.reconcileOnce();
      assertEquals(
          List.of(new ServiceEndpoint("10.0.0.5", 51234, Optional.of("node-a"))),
          registry.getEndpoints(Optional.empty(), "orders"),
          "tick " + tick);
    }
  }

  @Test
  void an_external_name_service_with_no_target_port_resolves_on_its_own_port() {
    StateStore store = new StateStore();
    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(
        new ServiceSpec(
            "billing",
            Optional.empty(),
            Set.of(),
            443,
            OptionalInt.empty(),
            false,
            Optional.of("billing.example.com")));

    new ServiceReconciler(registry, store).reconcileOnce();

    assertEquals(
        List.of(new ServiceEndpoint("billing.example.com", 443)),
        registry.getEndpoints(Optional.empty(), "billing"));
  }
}
