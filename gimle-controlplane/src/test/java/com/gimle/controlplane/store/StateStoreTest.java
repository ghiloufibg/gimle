package com.gimle.controlplane.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.manifest.DeploymentSpec;
import com.gimle.controlplane.manifest.PlacementConstraints;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

class StateStoreTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static final ModuleId ORDERS =
      new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));

  private static DeploymentSpec sample_deployment(String name, int replicas) {
    return new DeploymentSpec(
        name, ORDERS, "/var/gimle/artifacts/orders-1.0.0.jar", replicas, PlacementConstraints.NONE);
  }

  @Test
  void a_fresh_store_creates_its_directory_layout() {
    Path root = tempDir.resolve("fresh");
    new StateStore(root);

    assertTrue(Files.isDirectory(root.resolve("deployments")));
    assertTrue(Files.isDirectory(root.resolve("assignments")));
    assertTrue(Files.isDirectory(root.resolve("nodes")));
  }

  @Test
  void deployment_round_trips_through_a_fresh_store_instance() {
    Path root = tempDir.resolve("deployment-roundtrip");
    StateStore store = new StateStore(root);
    DeploymentSpec spec = sample_deployment("orders-service", 3);

    store.put_deployment(spec);
    assertEquals(Optional.of(spec), store.get_deployment("orders-service"));

    StateStore reloaded = new StateStore(root);
    assertEquals(Optional.of(spec), reloaded.get_deployment("orders-service"));
    assertEquals(List.of(spec), reloaded.list_deployments());
  }

  @Test
  void removed_deployment_is_gone_after_reload() {
    Path root = tempDir.resolve("deployment-remove");
    StateStore store = new StateStore(root);
    store.put_deployment(sample_deployment("orders-service", 1));
    store.remove_deployment("orders-service");

    assertTrue(store.get_deployment("orders-service").isEmpty());
    StateStore reloaded = new StateStore(root);
    assertTrue(reloaded.get_deployment("orders-service").isEmpty());
  }

  @Test
  void assignment_round_trips_and_is_scoped_to_its_deployment() {
    Path root = tempDir.resolve("assignment-roundtrip");
    StateStore store = new StateStore(root);
    InstanceAssignment a0 = new InstanceAssignment("orders-service", 0, "node-a");
    InstanceAssignment a1 = new InstanceAssignment("orders-service", 1, "node-b");
    InstanceAssignment other = new InstanceAssignment("catalog-service", 0, "node-a");

    store.put_assignment(a0);
    store.put_assignment(a1);
    store.put_assignment(other);

    assertEquals(Set.of(a0, a1), Set.copyOf(store.list_assignments_for("orders-service")));

    StateStore reloaded = new StateStore(root);
    assertEquals(Set.of(a0, a1, other), Set.copyOf(reloaded.list_assignments()));

    reloaded.remove_assignment("orders-service", 0);
    assertEquals(List.of(a1), reloaded.list_assignments_for("orders-service"));
    StateStore reloadedAgain = new StateStore(root);
    assertEquals(List.of(a1), reloadedAgain.list_assignments_for("orders-service"));
  }

  @Test
  void node_registration_round_trips() {
    Path root = tempDir.resolve("registration-roundtrip");
    StateStore store = new StateStore(root);
    NodeRegistration registration =
        new NodeRegistration(
            "node-a", new NodeCapabilities(Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2)));

    store.put_node_registration(registration);
    assertEquals(Optional.of(registration), store.get_node_registration("node-a"));

    StateStore reloaded = new StateStore(root);
    assertEquals(Optional.of(registration), reloaded.get_node_registration("node-a"));
  }

  @Test
  void node_heartbeat_round_trips_with_instance_observations() {
    Path root = tempDir.resolve("heartbeat-roundtrip");
    StateStore store = new StateStore(root);
    NodeHeartbeat heartbeat =
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1_000_000L, 400_000L, 4000L, 1000L),
            List.of(new InstanceObservation("orders-service", 0, ORDERS, "ACTIVE", true, true)));

    store.put_node_heartbeat(heartbeat);
    ObservedHeartbeat observed = store.get_node_heartbeat("node-a").orElseThrow();
    assertEquals(heartbeat, observed.heartbeat());

    StateStore reloaded = new StateStore(root);
    ObservedHeartbeat reloadedObserved = reloaded.get_node_heartbeat("node-a").orElseThrow();
    assertEquals(heartbeat, reloadedObserved.heartbeat());
    assertEquals(observed.receivedAt(), reloadedObserved.receivedAt());
  }

  @Test
  void unknown_resources_return_empty_or_empty_collections() {
    Path root = tempDir.resolve("empty-store");
    StateStore store = new StateStore(root);

    assertTrue(store.get_deployment("nope").isEmpty());
    assertTrue(store.list_deployments().isEmpty());
    assertTrue(store.list_assignments().isEmpty());
    assertTrue(store.get_node_registration("nope").isEmpty());
    assertTrue(store.get_node_heartbeat("nope").isEmpty());
  }

  @Test
  void a_leftover_tmp_file_from_an_interrupted_write_is_never_read_back() {
    // Simulates a crash between writing the temp file and the atomic move: the reload must see
    // only the last successfully-moved file, never a partially-written .tmp.
    Path root = tempDir.resolve("crash-mid-write");
    StateStore store = new StateStore(root);
    store.put_deployment(sample_deployment("orders-service", 1));

    Path danglingTmp = root.resolve("deployments").resolve("orders-service.yaml.tmp");
    try {
      Files.writeString(danglingTmp, "not: [valid, deployment, content");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    StateStore reloaded = new StateStore(root);
    assertEquals(
        Optional.of(sample_deployment("orders-service", 1)),
        reloaded.get_deployment("orders-service"));
    assertFalse(reloaded.list_deployments().isEmpty());
  }
}
