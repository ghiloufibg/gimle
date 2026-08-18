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
    StateStore store = new StateStore(tempDir.resolve("store-empty"));
    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));

    new ServiceReconciler(registry, store).reconcileOnce();

    assertTrue(
        registry.getEndpoints("orders").isEmpty(),
        "a Service whose backing deployment has no assignments yet must converge to no endpoints,"
            + " not fail");
  }

  @Test
  void a_store_with_active_and_ready_instances_already_present_converges_to_their_endpoints() {
    StateStore store = new StateStore(tempDir.resolve("store-active"));
    registerNode(store, "node-a", "10.0.0.5:9101");
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a", MODULE_ID, ""));
    putHeartbeat(store, "node-a", "orders-service", 0, true, Map.of("HTTP_PORT", 51234));

    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080, 8080));

    new ServiceReconciler(registry, store).reconcileOnce();

    assertEquals(List.of(new ServiceEndpoint("10.0.0.5", 51234)), registry.getEndpoints("orders"));
  }

  @Test
  void an_instance_reported_alive_but_not_ready_yet_contributes_no_endpoint() {
    StateStore store = new StateStore(tempDir.resolve("store-not-ready"));
    registerNode(store, "node-a", "10.0.0.5:9101");
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a", MODULE_ID, ""));
    putHeartbeat(store, "node-a", "orders-service", 0, false, Map.of("HTTP_PORT", 51234));

    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));

    new ServiceReconciler(registry, store).reconcileOnce();

    assertTrue(registry.getEndpoints("orders").isEmpty());
  }

  @Test
  void an_instance_with_no_heartbeat_yet_contributes_no_endpoint() {
    StateStore store = new StateStore(tempDir.resolve("store-no-heartbeat"));
    registerNode(store, "node-a", "10.0.0.5:9101");
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a", MODULE_ID, ""));
    // no heartbeat ever recorded for node-a

    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));

    new ServiceReconciler(registry, store).reconcileOnce();

    assertTrue(registry.getEndpoints("orders").isEmpty());
  }

  /** deploymentNames is a real selector across multiple workload names, not just a single one. */
  @Test
  void endpoints_are_aggregated_across_every_deployment_name_in_the_selector() {
    StateStore store = new StateStore(tempDir.resolve("store-multi-deployment"));
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
        Set.of(new ServiceEndpoint("10.0.0.5", 51234), new ServiceEndpoint("10.0.0.6", 51235)),
        Set.copyOf(registry.getEndpoints("orders")));
  }

  @Test
  void one_services_failure_does_not_prevent_another_from_reconciling() {
    StateStore store = new StateStore(tempDir.resolve("store-mixed"));
    registerNode(store, "node-a", "10.0.0.5:9101");
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a", MODULE_ID, ""));
    putHeartbeat(store, "node-a", "orders-service", 0, true, Map.of("HTTP_PORT", 51234));

    ServiceRegistry registry = new ServiceRegistry(store);
    // "unreachable" resolves to no assignments at all -- not a failure, just an empty result --
    // reconciled alongside a Service that does have a healthy backing instance, in the same tick.
    registry.put(new ServiceSpec("unreachable", Optional.empty(), Set.of("nothing-here"), 9090));
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));

    new ServiceReconciler(registry, store).reconcileOnce();

    assertTrue(registry.getEndpoints("unreachable").isEmpty());
    assertEquals(List.of(new ServiceEndpoint("10.0.0.5", 51234)), registry.getEndpoints("orders"));
  }

  @Test
  void reconciling_again_after_a_backing_instance_goes_away_clears_its_endpoint() {
    StateStore store = new StateStore(tempDir.resolve("store-churn"));
    registerNode(store, "node-a", "10.0.0.5:9101");
    store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a", MODULE_ID, ""));
    putHeartbeat(store, "node-a", "orders-service", 0, true, Map.of("HTTP_PORT", 51234));

    ServiceRegistry registry = new ServiceRegistry(store);
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));
    ServiceReconciler reconciler = new ServiceReconciler(registry, store);
    reconciler.reconcileOnce();
    assertEquals(1, registry.getEndpoints("orders").size());

    store.removeAssignment("orders-service", 0);
    reconciler.reconcileOnce();

    assertTrue(
        registry.getEndpoints("orders").isEmpty(),
        "a level-triggered reconcile must fully replace the prior endpoint set, not merge into it");
  }
}
