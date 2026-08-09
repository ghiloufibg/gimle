package com.gimle.mimir.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.Permission;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

  private static DeploymentSpec sampleDeployment(String name, int replicas) {
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
    DeploymentSpec spec = sampleDeployment("orders-service", 3);

    store.putDeployment(spec);
    assertEquals(Optional.of(spec), store.getDeployment("orders-service"));

    StateStore reloaded = new StateStore(root);
    assertEquals(Optional.of(spec), reloaded.getDeployment("orders-service"));
    assertEquals(List.of(spec), reloaded.listDeployments());
  }

  @Test
  void deployment_artifact_sha256_round_trips_through_a_fresh_store_instance() {
    Path root = tempDir.resolve("deployment-sha256-roundtrip");
    StateStore store = new StateStore(root);
    DeploymentSpec spec =
        new DeploymentSpec(
            "orders-service",
            ORDERS,
            "/var/gimle/artifacts/orders-1.0.0.jar",
            3,
            PlacementConstraints.NONE,
            Optional.empty(),
            Optional.empty(),
            Optional.of("a".repeat(64)));

    store.putDeployment(spec);
    assertEquals(Optional.of(spec), store.getDeployment("orders-service"));

    StateStore reloaded = new StateStore(root);
    assertEquals(
        Optional.of("a".repeat(64)),
        reloaded.getDeployment("orders-service").orElseThrow().artifactSha256());
  }

  @Test
  void removed_deployment_is_gone_after_reload() {
    Path root = tempDir.resolve("deployment-remove");
    StateStore store = new StateStore(root);
    store.putDeployment(sampleDeployment("orders-service", 1));
    store.removeDeployment("orders-service");

    assertTrue(store.getDeployment("orders-service").isEmpty());
    StateStore reloaded = new StateStore(root);
    assertTrue(reloaded.getDeployment("orders-service").isEmpty());
  }

  @Test
  void assignment_round_trips_and_is_scoped_to_its_deployment() {
    Path root = tempDir.resolve("assignment-roundtrip");
    StateStore store = new StateStore(root);
    InstanceAssignment a0 = new InstanceAssignment("orders-service", 0, "node-a");
    InstanceAssignment a1 = new InstanceAssignment("orders-service", 1, "node-b");
    InstanceAssignment other = new InstanceAssignment("catalog-service", 0, "node-a");

    store.putAssignment(a0);
    store.putAssignment(a1);
    store.putAssignment(other);

    assertEquals(Set.of(a0, a1), Set.copyOf(store.listAssignmentsFor("orders-service")));

    StateStore reloaded = new StateStore(root);
    assertEquals(Set.of(a0, a1, other), Set.copyOf(reloaded.listAssignments()));

    reloaded.removeAssignment("orders-service", 0);
    assertEquals(List.of(a1), reloaded.listAssignmentsFor("orders-service"));
    StateStore reloadedAgain = new StateStore(root);
    assertEquals(List.of(a1), reloadedAgain.listAssignmentsFor("orders-service"));
  }

  @Test
  void node_registration_round_trips() {
    Path root = tempDir.resolve("registration-roundtrip");
    StateStore store = new StateStore(root);
    NodeRegistration registration =
        new NodeRegistration(
            "node-a", new NodeCapabilities(Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2)));

    store.putNodeRegistration(registration);
    assertEquals(Optional.of(registration), store.getNodeRegistration("node-a"));

    StateStore reloaded = new StateStore(root);
    assertEquals(Optional.of(registration), reloaded.getNodeRegistration("node-a"));
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

    store.putNodeHeartbeat(heartbeat);
    ObservedHeartbeat observed = store.getNodeHeartbeat("node-a").orElseThrow();
    assertEquals(heartbeat, observed.heartbeat());

    StateStore reloaded = new StateStore(root);
    ObservedHeartbeat reloadedObserved = reloaded.getNodeHeartbeat("node-a").orElseThrow();
    assertEquals(heartbeat, reloadedObserved.heartbeat());
    assertEquals(observed.receivedAt(), reloadedObserved.receivedAt());
  }

  @Test
  void reconciler_instance_state_round_trips_through_a_fresh_store_instance() {
    Path root = tempDir.resolve("reconciler-state-roundtrip");
    StateStore store = new StateStore(root);
    ReconcilerInstanceState state =
        new ReconcilerInstanceState("orders-service", 0, 2, 100L, 200L, true, false, 300L);

    store.putReconcilerInstanceState(state);
    assertEquals(Optional.of(state), store.getReconcilerInstanceState("orders-service", 0));
    assertEquals(List.of(state), store.listReconcilerInstanceStates());

    StateStore reloaded = new StateStore(root);
    assertEquals(Optional.of(state), reloaded.getReconcilerInstanceState("orders-service", 0));
  }

  @Test
  void removed_reconciler_instance_state_is_gone_after_reload() {
    Path root = tempDir.resolve("reconciler-state-removed");
    StateStore store = new StateStore(root);
    store.putReconcilerInstanceState(
        new ReconcilerInstanceState("orders-service", 0, 1, 100L, 200L, true, false, -1L));

    store.removeReconcilerInstanceState("orders-service", 0);
    assertTrue(store.getReconcilerInstanceState("orders-service", 0).isEmpty());

    StateStore reloaded = new StateStore(root);
    assertTrue(reloaded.getReconcilerInstanceState("orders-service", 0).isEmpty());
  }

  @Test
  void a_snapshot_carries_reconciler_instance_state_and_restores_it() {
    Path root = tempDir.resolve("snapshot-reconciler-state");
    StateStore store = new StateStore(root);
    ReconcilerInstanceState state =
        new ReconcilerInstanceState("orders-service", 0, 2, 100L, 200L, true, false, -1L);
    store.putReconcilerInstanceState(state);

    StateSnapshot snapshot = store.snapshot();
    assertEquals(List.of(state), snapshot.reconcilerInstanceStates());

    StateStore target = new StateStore(tempDir.resolve("snapshot-reconciler-state-target"));
    target.restoreFromSnapshot(snapshot);

    assertEquals(Optional.of(state), target.getReconcilerInstanceState("orders-service", 0));
  }

  @Test
  void node_cordon_round_trips_through_a_fresh_store_instance() {
    Path root = tempDir.resolve("cordon-roundtrip");
    StateStore store = new StateStore(root);

    store.putNodeCordon("node-1", true);
    assertTrue(store.isNodeCordoned("node-1"));

    StateStore reloaded = new StateStore(root);
    assertTrue(reloaded.isNodeCordoned("node-1"));
  }

  @Test
  void uncordoning_a_node_clears_it_and_is_gone_after_reload() {
    Path root = tempDir.resolve("cordon-cleared");
    StateStore store = new StateStore(root);
    store.putNodeCordon("node-1", true);

    store.putNodeCordon("node-1", false);
    assertFalse(store.isNodeCordoned("node-1"));

    StateStore reloaded = new StateStore(root);
    assertFalse(reloaded.isNodeCordoned("node-1"));
  }

  @Test
  void an_unknown_node_is_reported_as_not_cordoned() {
    Path root = tempDir.resolve("cordon-unknown");
    StateStore store = new StateStore(root);

    assertFalse(store.isNodeCordoned("node-never-seen"));
  }

  @Test
  void a_snapshot_carries_node_cordons_and_restores_them() {
    Path root = tempDir.resolve("snapshot-cordon");
    StateStore store = new StateStore(root);
    store.putNodeCordon("node-1", true);

    StateSnapshot snapshot = store.snapshot();
    assertEquals(Set.of("node-1"), snapshot.cordonedNodes());

    StateStore target = new StateStore(tempDir.resolve("snapshot-cordon-target"));
    target.restoreFromSnapshot(snapshot);

    assertTrue(target.isNodeCordoned("node-1"));
  }

  @Test
  void instance_events_round_trip_newest_first_through_a_fresh_store_instance() {
    Path root = tempDir.resolve("events-roundtrip");
    StateStore store = new StateStore(root);
    InstanceEvent first =
        new InstanceEvent(
            "evt-1", "orders-service", 0, InstanceEventKind.INSTALLED, "module installed", 1_000L);
    InstanceEvent second =
        new InstanceEvent(
            "evt-2", "orders-service", 0, InstanceEventKind.RESOLVED, "module resolved", 2_000L);

    store.putInstanceEvent(first);
    store.putInstanceEvent(second);

    assertEquals(List.of(second, first), store.listInstanceEvents("orders-service", 0));

    StateStore reloaded = new StateStore(root);
    assertEquals(List.of(second, first), reloaded.listInstanceEvents("orders-service", 0));
  }

  @Test
  void an_instance_events_transition_failed_cause_summary_round_trips() {
    Path root = tempDir.resolve("events-cause-summary");
    StateStore store = new StateStore(root);
    InstanceEvent event =
        new InstanceEvent(
            "evt-1",
            "orders-service",
            0,
            InstanceEventKind.TRANSITION_FAILED,
            "transition ACTIVE -> STOPPING failed",
            Optional.of("java.lang.IllegalStateException: boom"),
            3_000L);

    store.putInstanceEvent(event);

    assertEquals(List.of(event), store.listInstanceEvents("orders-service", 0));
    StateStore reloaded = new StateStore(root);
    assertEquals(List.of(event), reloaded.listInstanceEvents("orders-service", 0));
  }

  @Test
  void instance_events_beyond_the_retention_cap_prune_the_oldest_first() {
    Path root = tempDir.resolve("events-retention");
    StateStore store = new StateStore(root);
    // 51 events, one over the 50-per-instance retention cap.
    for (int i = 0; i < 51; i++) {
      store.putInstanceEvent(
          new InstanceEvent(
              "evt-" + i,
              "orders-service",
              0,
              InstanceEventKind.ACTIVE,
              "module active",
              1_000L + i));
    }

    List<InstanceEvent> events = store.listInstanceEvents("orders-service", 0);
    assertEquals(50, events.size());
    // Newest-first: the very first event (evt-0) was pruned, evt-50 is now first.
    assertEquals("evt-50", events.get(0).id());
    assertEquals("evt-1", events.get(events.size() - 1).id());

    StateStore reloaded = new StateStore(root);
    assertEquals(50, reloaded.listInstanceEvents("orders-service", 0).size());
  }

  @Test
  void an_unknown_instance_has_no_events() {
    Path root = tempDir.resolve("events-unknown");
    StateStore store = new StateStore(root);

    assertTrue(store.listInstanceEvents("never-deployed", 0).isEmpty());
  }

  @Test
  void a_snapshot_carries_instance_events_and_restores_them() {
    Path root = tempDir.resolve("snapshot-events");
    StateStore store = new StateStore(root);
    InstanceEvent event =
        new InstanceEvent(
            "evt-1", "orders-service", 0, InstanceEventKind.ACTIVE, "module active", 1_000L);
    store.putInstanceEvent(event);

    StateSnapshot snapshot = store.snapshot();
    assertEquals(List.of(event), snapshot.instanceEvents());

    StateStore target = new StateStore(tempDir.resolve("snapshot-events-target"));
    target.restoreFromSnapshot(snapshot);

    assertEquals(List.of(event), target.listInstanceEvents("orders-service", 0));
  }

  @Test
  void unknown_resources_return_empty_or_empty_collections() {
    Path root = tempDir.resolve("empty-store");
    StateStore store = new StateStore(root);

    assertTrue(store.getDeployment("nope").isEmpty());
    assertTrue(store.listDeployments().isEmpty());
    assertTrue(store.listAssignments().isEmpty());
    assertTrue(store.getNodeRegistration("nope").isEmpty());
    assertTrue(store.getNodeHeartbeat("nope").isEmpty());
  }

  @Test
  void role_role_binding_and_account_round_trip_through_a_fresh_store_instance() {
    Path root = tempDir.resolve("authz-roundtrip");
    StateStore store = new StateStore(root);
    Role role =
        new Role(
            "viewer",
            Set.of(
                Permission.unscoped(ResourceKind.DEPLOYMENT, Verb.READ),
                Permission.scoped(ResourceKind.CONFIG, Verb.READ, "acme")));
    RoleBinding binding = new RoleBinding("b1", RoleBinding.userSubject("alice"), "viewer");
    Account account = new Account("admin", new byte[] {1, 2, 3, 4});

    store.putRole(role);
    store.putRoleBinding(binding);
    store.putAccount(account);

    assertEquals(Optional.of(role), store.getRole("viewer"));
    assertEquals(Optional.of(binding), store.getRoleBinding("b1"));
    assertEquals(Optional.of(account), store.getAccount("admin"));

    // Account's generated equals() compares passwordHash by array reference, not content -- a
    // freshly reloaded Account is a distinct object with a distinct (if equal-content) array, the
    // same reason ConfigEntry-bearing round trips elsewhere in this codebase compare fields
    // individually (assertArrayEquals on the byte[]) rather than the whole record.
    StateStore reloaded = new StateStore(root);
    assertEquals(List.of(role), reloaded.listRoles());
    assertEquals(List.of(binding), reloaded.listRoleBindings());
    assertEquals(1, reloaded.listAccounts().size());
    assertEquals("admin", reloaded.listAccounts().get(0).username());
    assertArrayEquals(account.passwordHash(), reloaded.listAccounts().get(0).passwordHash());

    reloaded.removeRole("viewer");
    reloaded.removeRoleBinding("b1");
    reloaded.removeAccount("admin");
    StateStore reloadedAgain = new StateStore(root);
    assertTrue(reloadedAgain.listRoles().isEmpty());
    assertTrue(reloadedAgain.listRoleBindings().isEmpty());
    assertTrue(reloadedAgain.listAccounts().isEmpty());
  }

  @Test
  void a_leftover_tmp_file_from_an_interrupted_write_is_never_read_back() {
    // Simulates a crash between writing the temp file and the atomic move: the reload must see
    // only the last successfully-moved file, never a partially-written .tmp.
    Path root = tempDir.resolve("crash-mid-write");
    StateStore store = new StateStore(root);
    store.putDeployment(sampleDeployment("orders-service", 1));

    Path danglingTmp = root.resolve("deployments").resolve("orders-service.yaml.tmp");
    try {
      Files.writeString(danglingTmp, "not: [valid, deployment, content");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    StateStore reloaded = new StateStore(root);
    assertEquals(
        Optional.of(sampleDeployment("orders-service", 1)),
        reloaded.getDeployment("orders-service"));
    assertFalse(reloaded.listDeployments().isEmpty());
  }

  // ---- leases: non-replicated coordination state backing the reconciler-leader election ----

  @Test
  void a_free_lease_is_granted_to_the_first_caller() {
    StateStore store = new StateStore(tempDir.resolve("lease-free"));

    LeaseGrant grant =
        store.tryAcquireOrRenewLease("reconciler-leader", "node-a:8080", Duration.ofSeconds(10));

    assertTrue(grant.granted());
    assertEquals("node-a:8080", grant.holderId());
    assertEquals(Optional.of("node-a:8080"), store.getLeaseHolder("reconciler-leader"));
  }

  @Test
  void the_current_holder_can_renew_its_own_lease() {
    StateStore store = new StateStore(tempDir.resolve("lease-renew"));
    store.tryAcquireOrRenewLease("reconciler-leader", "node-a:8080", Duration.ofSeconds(10));

    LeaseGrant renewal =
        store.tryAcquireOrRenewLease("reconciler-leader", "node-a:8080", Duration.ofSeconds(10));

    assertTrue(renewal.granted());
    assertEquals("node-a:8080", renewal.holderId());
  }

  @Test
  void a_different_holder_is_denied_while_the_lease_is_still_valid() {
    StateStore store = new StateStore(tempDir.resolve("lease-contended"));
    store.tryAcquireOrRenewLease("reconciler-leader", "node-a:8080", Duration.ofSeconds(10));

    LeaseGrant denied =
        store.tryAcquireOrRenewLease("reconciler-leader", "node-b:8081", Duration.ofSeconds(10));

    assertFalse(denied.granted());
    assertEquals("node-a:8080", denied.holderId(), "denial reports the current holder");
    assertEquals(Optional.of("node-a:8080"), store.getLeaseHolder("reconciler-leader"));
  }

  @Test
  void a_different_holder_is_granted_once_the_lease_has_expired() throws InterruptedException {
    StateStore store = new StateStore(tempDir.resolve("lease-expired"));
    store.tryAcquireOrRenewLease("reconciler-leader", "node-a:8080", Duration.ofMillis(1));
    Thread.sleep(50); // comfortably past the 1ms TTL

    LeaseGrant grant =
        store.tryAcquireOrRenewLease("reconciler-leader", "node-b:8081", Duration.ofSeconds(10));

    assertTrue(grant.granted());
    assertEquals("node-b:8081", grant.holderId());
  }

  @Test
  void releasing_a_lease_the_caller_holds_frees_it_immediately() {
    StateStore store = new StateStore(tempDir.resolve("lease-release"));
    store.tryAcquireOrRenewLease("reconciler-leader", "node-a:8080", Duration.ofSeconds(30));

    store.releaseLease("reconciler-leader", "node-a:8080");

    assertEquals(Optional.empty(), store.getLeaseHolder("reconciler-leader"));
    LeaseGrant grant =
        store.tryAcquireOrRenewLease("reconciler-leader", "node-b:8081", Duration.ofSeconds(10));
    assertTrue(grant.granted());
  }

  @Test
  void releasing_a_lease_the_caller_does_not_hold_is_a_no_op() {
    StateStore store = new StateStore(tempDir.resolve("lease-release-wrong-holder"));
    store.tryAcquireOrRenewLease("reconciler-leader", "node-a:8080", Duration.ofSeconds(30));

    store.releaseLease("reconciler-leader", "node-b:8081");

    assertEquals(Optional.of("node-a:8080"), store.getLeaseHolder("reconciler-leader"));
  }
}
