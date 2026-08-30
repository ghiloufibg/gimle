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
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.time.TestClock;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.NetworkPolicySpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.manifest.StatefulSetSpec;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StateStoreTest {

  private static final ModuleId ORDERS =
      new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));

  private static DeploymentSpec sampleDeployment(String name, int replicas) {
    return new DeploymentSpec(
        name, ORDERS, "/var/gimle/artifacts/orders-1.0.0.jar", replicas, PlacementConstraints.NONE);
  }

  private static DaemonSetSpec sampleDaemonSet(String name) {
    return new DaemonSetSpec(
        name,
        ORDERS,
        "/var/gimle/artifacts/orders-1.0.0.jar",
        PlacementConstraints.NONE,
        Optional.empty(),
        Optional.empty());
  }

  private static StatefulSetSpec sampleStatefulSet(String name, int replicas) {
    return new StatefulSetSpec(
        name,
        ORDERS,
        "/var/gimle/artifacts/orders-1.0.0.jar",
        replicas,
        PlacementConstraints.NONE,
        Optional.empty(),
        Optional.empty());
  }

  @Test
  void deployment_round_trips_through_a_snapshot_into_a_fresh_store() {
    StateStore store = new StateStore();
    DeploymentSpec spec = sampleDeployment("orders-service", 3);

    store.putDeployment(spec);
    assertEquals(Optional.of(spec), store.getDeployment(Optional.empty(), "orders-service"));

    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertEquals(Optional.of(spec), reloaded.getDeployment(Optional.empty(), "orders-service"));
    assertEquals(List.of(spec), reloaded.listDeployments());
  }

  @Test
  void deployment_artifact_sha256_round_trips_through_a_snapshot_into_a_fresh_store() {
    StateStore store = new StateStore();
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
    assertEquals(Optional.of(spec), store.getDeployment(Optional.empty(), "orders-service"));

    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertEquals(
        Optional.of("a".repeat(64)),
        reloaded.getDeployment(Optional.empty(), "orders-service").orElseThrow().artifactSha256());
  }

  @Test
  void removed_deployment_is_gone_after_snapshot_restore() {
    StateStore store = new StateStore();
    store.putDeployment(sampleDeployment("orders-service", 1));
    store.removeDeployment(Optional.empty(), "orders-service");

    assertTrue(store.getDeployment(Optional.empty(), "orders-service").isEmpty());
    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertTrue(reloaded.getDeployment(Optional.empty(), "orders-service").isEmpty());
  }

  @Test
  void service_round_trips_through_a_snapshot_into_a_fresh_store() {
    StateStore store = new StateStore();
    ServiceSpec spec =
        new ServiceSpec("orders", Optional.of("tenant-1"), Set.of("orders-service"), 8080, 9090);

    store.putService(spec);
    assertEquals(Optional.of(spec), store.getService(Optional.of("tenant-1"), "orders"));

    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertEquals(Optional.of(spec), reloaded.getService(Optional.of("tenant-1"), "orders"));
    assertEquals(List.of(spec), reloaded.listServices());
  }

  @Test
  void removed_service_is_gone_after_snapshot_restore() {
    StateStore store = new StateStore();
    store.putService(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));
    store.removeService(Optional.empty(), "orders");

    assertTrue(store.getService(Optional.empty(), "orders").isEmpty());
    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertTrue(reloaded.getService(Optional.empty(), "orders").isEmpty());
    assertTrue(reloaded.listServices().isEmpty());
  }

  /**
   * The core cross-tenant isolation guarantee: two tenants sharing a bare Deployment name must
   * never collide, overwrite each other's spec, or become visible to each other -- the whole point
   * of the store's own {@code (tenantId, name)} compound key, not just an incidental side effect.
   */
  @Test
  void two_tenants_with_an_identically_named_deployment_never_collide() {
    StateStore store = new StateStore();
    DeploymentSpec tenantASpec =
        new DeploymentSpec(
            "orders-service",
            ORDERS,
            "/var/gimle/artifacts/tenant-a-orders-1.0.0.jar",
            3,
            PlacementConstraints.NONE,
            Optional.empty(),
            Optional.of("tenant-a"));
    DeploymentSpec tenantBSpec =
        new DeploymentSpec(
            "orders-service",
            ORDERS,
            "/var/gimle/artifacts/tenant-b-orders-2.0.0.jar",
            5,
            PlacementConstraints.NONE,
            Optional.empty(),
            Optional.of("tenant-b"));

    store.putDeployment(tenantASpec);
    store.putDeployment(tenantBSpec);

    assertEquals(
        Optional.of(tenantASpec), store.getDeployment(Optional.of("tenant-a"), "orders-service"));
    assertEquals(
        Optional.of(tenantBSpec), store.getDeployment(Optional.of("tenant-b"), "orders-service"));
    assertTrue(store.getDeployment(Optional.empty(), "orders-service").isEmpty());
    assertEquals(Set.of(tenantASpec, tenantBSpec), Set.copyOf(store.listDeployments()));

    // Removing one tenant's copy must not touch the other's, and both must still be distinct
    // after a snapshot round-trip -- not merely distinct in the live map.
    store.removeDeployment(Optional.of("tenant-a"), "orders-service");
    assertTrue(store.getDeployment(Optional.of("tenant-a"), "orders-service").isEmpty());
    assertEquals(
        Optional.of(tenantBSpec), store.getDeployment(Optional.of("tenant-b"), "orders-service"));

    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertTrue(reloaded.getDeployment(Optional.of("tenant-a"), "orders-service").isEmpty());
    assertEquals(
        Optional.of(tenantBSpec),
        reloaded.getDeployment(Optional.of("tenant-b"), "orders-service"));
  }

  /**
   * The Service analogue of {@link
   * #two_tenants_with_an_identically_named_deployment_never_collide}.
   */
  @Test
  void two_tenants_with_an_identically_named_service_never_collide() {
    StateStore store = new StateStore();
    ServiceSpec tenantASpec =
        new ServiceSpec("web", Optional.of("tenant-a"), Set.of("orders-service"), 8080);
    ServiceSpec tenantBSpec =
        new ServiceSpec("web", Optional.of("tenant-b"), Set.of("billing-service"), 9090);

    store.putService(tenantASpec);
    store.putService(tenantBSpec);

    assertEquals(Optional.of(tenantASpec), store.getService(Optional.of("tenant-a"), "web"));
    assertEquals(Optional.of(tenantBSpec), store.getService(Optional.of("tenant-b"), "web"));
    assertTrue(store.getService(Optional.empty(), "web").isEmpty());

    store.removeService(Optional.of("tenant-a"), "web");
    assertTrue(store.getService(Optional.of("tenant-a"), "web").isEmpty());
    assertEquals(Optional.of(tenantBSpec), store.getService(Optional.of("tenant-b"), "web"));
  }

  /**
   * The NetworkPolicy analogue of {@link
   * #two_tenants_with_an_identically_named_deployment_never_collide} -- structurally guaranteed
   * here since {@link NetworkPolicySpec#tenantId} is mandatory rather than optional, but asserted
   * explicitly so the guarantee is verified for every resource kind {@code scopedKey} covers, not
   * just the two already under test.
   */
  @Test
  void two_tenants_with_an_identically_named_network_policy_never_collide() {
    StateStore store = new StateStore();
    NetworkPolicySpec tenantASpec =
        new NetworkPolicySpec(
            "default-deny", "tenant-a", Optional.of(Set.of("orders-service")), Set.of("tenant-x"));
    NetworkPolicySpec tenantBSpec =
        new NetworkPolicySpec(
            "default-deny", "tenant-b", Optional.of(Set.of("billing-service")), Set.of("tenant-y"));

    store.putNetworkPolicy(tenantASpec);
    store.putNetworkPolicy(tenantBSpec);

    assertEquals(Optional.of(tenantASpec), store.getNetworkPolicy("tenant-a", "default-deny"));
    assertEquals(Optional.of(tenantBSpec), store.getNetworkPolicy("tenant-b", "default-deny"));

    store.removeNetworkPolicy("tenant-a", "default-deny");
    assertTrue(store.getNetworkPolicy("tenant-a", "default-deny").isEmpty());
    assertEquals(Optional.of(tenantBSpec), store.getNetworkPolicy("tenant-b", "default-deny"));
  }

  @Test
  void certificate_revocations_round_trip_through_a_snapshot_and_clear_on_unrevoke() {
    StateStore store = new StateStore();
    store.putCertificateRevocation("0a1b2c", true);
    assertTrue(store.isCertificateRevoked("0a1b2c"));
    assertEquals(Set.of("0a1b2c"), store.listRevokedCertificateSerials());

    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertTrue(reloaded.isCertificateRevoked("0a1b2c"));

    reloaded.putCertificateRevocation("0a1b2c", false);
    assertTrue(!reloaded.isCertificateRevoked("0a1b2c"));
    assertTrue(reloaded.listRevokedCertificateSerials().isEmpty());
  }

  @Test
  void network_policy_round_trips_through_a_snapshot_into_a_fresh_store() {
    StateStore store = new StateStore();
    NetworkPolicySpec spec =
        new NetworkPolicySpec(
            "orders-policy", "tenant-1", Optional.of(Set.of("orders-service")), Set.of("tenant-2"));

    store.putNetworkPolicy(spec);
    assertEquals(Optional.of(spec), store.getNetworkPolicy("tenant-1", "orders-policy"));

    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertEquals(Optional.of(spec), reloaded.getNetworkPolicy("tenant-1", "orders-policy"));
    assertEquals(List.of(spec), reloaded.listNetworkPolicies());
  }

  @Test
  void removed_network_policy_is_gone_after_snapshot_restore() {
    StateStore store = new StateStore();
    store.putNetworkPolicy(new NetworkPolicySpec("orders-policy", "tenant-1", Set.of("tenant-2")));
    store.removeNetworkPolicy("tenant-1", "orders-policy");

    assertTrue(store.getNetworkPolicy("tenant-1", "orders-policy").isEmpty());
    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertTrue(reloaded.getNetworkPolicy("tenant-1", "orders-policy").isEmpty());
    assertTrue(reloaded.listNetworkPolicies().isEmpty());
  }

  @Test
  void assignment_round_trips_and_is_scoped_to_its_deployment() {
    StateStore store = new StateStore();
    InstanceAssignment a0 = new InstanceAssignment("orders-service", 0, "node-a");
    InstanceAssignment a1 = new InstanceAssignment("orders-service", 1, "node-b");
    InstanceAssignment other = new InstanceAssignment("catalog-service", 0, "node-a");

    store.putAssignment(a0);
    store.putAssignment(a1);
    store.putAssignment(other);

    assertEquals(
        Set.of(a0, a1), Set.copyOf(store.listAssignmentsFor(Optional.empty(), "orders-service")));

    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertEquals(Set.of(a0, a1, other), Set.copyOf(reloaded.listAssignments()));

    reloaded.removeAssignment(Optional.empty(), "orders-service", 0);
    assertEquals(List.of(a1), reloaded.listAssignmentsFor(Optional.empty(), "orders-service"));
    StateStore reloadedAgain = new StateStore();
    reloadedAgain.restoreFromSnapshot(reloaded.snapshot());
    assertEquals(List.of(a1), reloadedAgain.listAssignmentsFor(Optional.empty(), "orders-service"));
  }

  @Test
  void node_registration_round_trips() {
    StateStore store = new StateStore();
    NodeRegistration registration =
        new NodeRegistration(
            "node-a", new NodeCapabilities(Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2)));

    store.putNodeRegistration(registration);
    assertEquals(Optional.of(registration), store.getNodeRegistration("node-a"));

    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertEquals(Optional.of(registration), reloaded.getNodeRegistration("node-a"));
  }

  @Test
  void node_heartbeat_round_trips_and_is_deliberately_absent_from_snapshots() {
    StateStore store = new StateStore();
    NodeHeartbeat heartbeat =
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(1_000_000L, 400_000L, 4000L, 1000L),
            List.of(new InstanceObservation("orders-service", 0, ORDERS, "ACTIVE", true, true)));

    store.putNodeHeartbeat(heartbeat);
    ObservedHeartbeat observed = store.getNodeHeartbeat("node-a").orElseThrow();
    assertEquals(heartbeat, observed.heartbeat());

    // Heartbeats never enter the replicated log, so a snapshot deliberately never carries them --
    // only the current leader's own store ever holds one.
    StateStore restored = new StateStore();
    restored.restoreFromSnapshot(store.snapshot());
    assertTrue(restored.getNodeHeartbeat("node-a").isEmpty());
  }

  @Test
  void reconciler_instance_state_round_trips_through_a_snapshot_into_a_fresh_store() {
    StateStore store = new StateStore();
    ReconcilerInstanceState state =
        new ReconcilerInstanceState("orders-service", 0, 2, 100L, 200L, true, false, 300L);

    store.putReconcilerInstanceState(state);
    assertEquals(
        Optional.of(state),
        store.getReconcilerInstanceState(Optional.empty(), "orders-service", 0));
    assertEquals(List.of(state), store.listReconcilerInstanceStates());

    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertEquals(
        Optional.of(state),
        reloaded.getReconcilerInstanceState(Optional.empty(), "orders-service", 0));
  }

  @Test
  void removed_reconciler_instance_state_is_gone_after_snapshot_restore() {
    StateStore store = new StateStore();
    store.putReconcilerInstanceState(
        new ReconcilerInstanceState("orders-service", 0, 1, 100L, 200L, true, false, -1L));

    store.removeReconcilerInstanceState(Optional.empty(), "orders-service", 0);
    assertTrue(store.getReconcilerInstanceState(Optional.empty(), "orders-service", 0).isEmpty());

    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertTrue(
        reloaded.getReconcilerInstanceState(Optional.empty(), "orders-service", 0).isEmpty());
  }

  @Test
  void a_snapshot_carries_reconciler_instance_state_and_restores_it() {
    StateStore store = new StateStore();
    ReconcilerInstanceState state =
        new ReconcilerInstanceState("orders-service", 0, 2, 100L, 200L, true, false, -1L);
    store.putReconcilerInstanceState(state);

    StateSnapshot snapshot = store.snapshot();
    assertEquals(List.of(state), snapshot.reconcilerInstanceStates());

    StateStore target = new StateStore();
    target.restoreFromSnapshot(snapshot);

    assertEquals(
        Optional.of(state),
        target.getReconcilerInstanceState(Optional.empty(), "orders-service", 0));
  }

  @Test
  void node_cordon_round_trips_through_a_snapshot_into_a_fresh_store() {
    StateStore store = new StateStore();

    store.putNodeCordon("node-1", true);
    assertTrue(store.isNodeCordoned("node-1"));

    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertTrue(reloaded.isNodeCordoned("node-1"));
  }

  @Test
  void uncordoning_a_node_clears_it_and_is_gone_after_snapshot_restore() {
    StateStore store = new StateStore();
    store.putNodeCordon("node-1", true);

    store.putNodeCordon("node-1", false);
    assertFalse(store.isNodeCordoned("node-1"));

    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertFalse(reloaded.isNodeCordoned("node-1"));
  }

  @Test
  void an_unknown_node_is_reported_as_not_cordoned() {
    StateStore store = new StateStore();

    assertFalse(store.isNodeCordoned("node-never-seen"));
  }

  @Test
  void a_snapshot_carries_node_cordons_and_restores_them() {
    StateStore store = new StateStore();
    store.putNodeCordon("node-1", true);

    StateSnapshot snapshot = store.snapshot();
    assertEquals(Set.of("node-1"), snapshot.cordonedNodes());

    StateStore target = new StateStore();
    target.restoreFromSnapshot(snapshot);

    assertTrue(target.isNodeCordoned("node-1"));
  }

  @Test
  void instance_events_round_trip_newest_first_through_a_snapshot_into_a_fresh_store() {
    StateStore store = new StateStore();
    InstanceEvent first =
        new InstanceEvent(
            "evt-1", "orders-service", 0, InstanceEventKind.INSTALLED, "module installed", 1_000L);
    InstanceEvent second =
        new InstanceEvent(
            "evt-2", "orders-service", 0, InstanceEventKind.RESOLVED, "module resolved", 2_000L);

    store.putInstanceEvent(Optional.empty(), first);
    store.putInstanceEvent(Optional.empty(), second);

    assertEquals(
        List.of(second, first), store.listInstanceEvents(Optional.empty(), "orders-service", 0));

    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertEquals(
        List.of(second, first), reloaded.listInstanceEvents(Optional.empty(), "orders-service", 0));
  }

  @Test
  void an_instance_events_transition_failed_cause_summary_round_trips() {
    StateStore store = new StateStore();
    InstanceEvent event =
        new InstanceEvent(
            "evt-1",
            "orders-service",
            0,
            InstanceEventKind.TRANSITION_FAILED,
            "transition ACTIVE -> STOPPING failed",
            Optional.of("java.lang.IllegalStateException: boom"),
            3_000L);

    store.putInstanceEvent(Optional.empty(), event);

    assertEquals(List.of(event), store.listInstanceEvents(Optional.empty(), "orders-service", 0));
    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertEquals(
        List.of(event), reloaded.listInstanceEvents(Optional.empty(), "orders-service", 0));
  }

  @Test
  void instance_events_beyond_the_retention_cap_prune_the_oldest_first() {
    StateStore store = new StateStore();
    // 51 events, one over the 50-per-instance retention cap.
    for (int i = 0; i < 51; i++) {
      store.putInstanceEvent(
          Optional.empty(),
          new InstanceEvent(
              "evt-" + i,
              "orders-service",
              0,
              InstanceEventKind.ACTIVE,
              "module active",
              1_000L + i));
    }

    List<InstanceEvent> events = store.listInstanceEvents(Optional.empty(), "orders-service", 0);
    assertEquals(50, events.size());
    // Newest-first: the very first event (evt-0) was pruned, evt-50 is now first.
    assertEquals("evt-50", events.get(0).id());
    assertEquals("evt-1", events.get(events.size() - 1).id());

    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertEquals(50, reloaded.listInstanceEvents(Optional.empty(), "orders-service", 0).size());
  }

  @Test
  void an_unknown_instance_has_no_events() {
    StateStore store = new StateStore();

    assertTrue(store.listInstanceEvents(Optional.empty(), "never-deployed", 0).isEmpty());
  }

  @Test
  void a_snapshot_carries_instance_events_and_restores_them() {
    StateStore store = new StateStore();
    InstanceEvent event =
        new InstanceEvent(
            "evt-1", "orders-service", 0, InstanceEventKind.ACTIVE, "module active", 1_000L);
    store.putInstanceEvent(Optional.empty(), event);

    StateSnapshot snapshot = store.snapshot();
    assertEquals(
        List.of(event), snapshot.instanceEvents().values().stream().flatMap(List::stream).toList());

    StateStore target = new StateStore();
    target.restoreFromSnapshot(snapshot);

    assertEquals(List.of(event), target.listInstanceEvents(Optional.empty(), "orders-service", 0));
  }

  private static AuditEvent auditEvent(
      String id,
      String principal,
      String resourceKind,
      String tenantId,
      long occurredAtEpochMilli) {
    return new AuditEvent(
        id,
        principal,
        Set.of("gimle:operators"),
        resourceKind,
        "WRITE",
        Optional.of(tenantId),
        Optional.of("target-" + id),
        true,
        occurredAtEpochMilli);
  }

  @Test
  void audit_events_round_trip_newest_first_through_a_snapshot_into_a_fresh_store() {
    StateStore store = new StateStore();
    AuditEvent first = auditEvent("audit-1", "alice", "DEPLOYMENT", "tenant-1", 1_000L);
    AuditEvent second = auditEvent("audit-2", "bob", "TENANT", "tenant-1", 2_000L);

    store.putAuditEvent(first);
    store.putAuditEvent(second);

    assertEquals(
        List.of(second, first),
        store.listAuditEvents(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));

    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertEquals(
        List.of(second, first),
        reloaded.listAuditEvents(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
  }

  @Test
  void audit_events_beyond_the_retention_cap_prune_the_oldest_first() {
    StateStore store = new StateStore();
    // One over the cluster-wide retention cap.
    for (int i = 0; i < StateStore.MAX_AUDIT_EVENTS + 1; i++) {
      store.putAuditEvent(auditEvent("audit-" + i, "alice", "DEPLOYMENT", "tenant-1", 1_000L + i));
    }

    List<AuditEvent> events =
        store.listAuditEvents(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    assertEquals(StateStore.MAX_AUDIT_EVENTS, events.size());
    // Newest-first: the very first event (audit-0) was pruned, the last-written one is now first.
    assertEquals("audit-" + StateStore.MAX_AUDIT_EVENTS, events.get(0).id());
    assertEquals("audit-1", events.get(events.size() - 1).id());
  }

  @Test
  void audit_events_filter_by_principal_resource_kind_tenant_and_since_independently() {
    StateStore store = new StateStore();
    store.putAuditEvent(auditEvent("audit-1", "alice", "DEPLOYMENT", "tenant-1", 1_000L));
    store.putAuditEvent(auditEvent("audit-2", "bob", "TENANT", "tenant-1", 2_000L));
    store.putAuditEvent(auditEvent("audit-3", "alice", "SECRET", "tenant-2", 3_000L));

    assertEquals(
        List.of("audit-3", "audit-1"),
        ids(
            store.listAuditEvents(
                Optional.of("alice"), Optional.empty(), Optional.empty(), Optional.empty())));
    assertEquals(
        List.of("audit-2"),
        ids(
            store.listAuditEvents(
                Optional.empty(), Optional.of("TENANT"), Optional.empty(), Optional.empty())));
    assertEquals(
        List.of("audit-2", "audit-1"),
        ids(
            store.listAuditEvents(
                Optional.empty(), Optional.empty(), Optional.of("tenant-1"), Optional.empty())));
    assertEquals(
        List.of("audit-3", "audit-2"),
        ids(
            store.listAuditEvents(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(2_000L))));
    assertEquals(
        List.of("audit-3"),
        ids(
            store.listAuditEvents(
                Optional.of("alice"),
                Optional.empty(),
                Optional.of("tenant-2"),
                Optional.empty())));
    assertTrue(
        store
            .listAuditEvents(
                Optional.of("nobody"), Optional.empty(), Optional.empty(), Optional.empty())
            .isEmpty());
  }

  private static List<String> ids(List<AuditEvent> events) {
    return events.stream().map(AuditEvent::id).toList();
  }

  @Test
  void an_empty_store_has_no_audit_events() {
    StateStore store = new StateStore();

    assertTrue(
        store
            .listAuditEvents(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())
            .isEmpty());
  }

  @Test
  void a_snapshot_carries_audit_events_and_restores_them() {
    StateStore store = new StateStore();
    AuditEvent event = auditEvent("audit-1", "alice", "DEPLOYMENT", "tenant-1", 1_000L);
    store.putAuditEvent(event);

    StateSnapshot snapshot = store.snapshot();
    assertEquals(List.of(event), snapshot.auditEvents());

    StateStore target = new StateStore();
    target.restoreFromSnapshot(snapshot);

    assertEquals(
        List.of(event),
        target.listAuditEvents(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
  }

  @Test
  void concurrent_audit_event_appends_never_exceed_the_cap_or_lose_or_duplicate_an_event()
      throws InterruptedException {
    StateStore store = new StateStore();
    int threadCount = 16;
    int perThread = 25;
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch go = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threadCount);
    try {
      for (int t = 0; t < threadCount; t++) {
        int threadIndex = t;
        pool.submit(
            () -> {
              ready.countDown();
              try {
                go.await();
                for (int i = 0; i < perThread; i++) {
                  store.putAuditEvent(
                      auditEvent(
                          "audit-" + threadIndex + "-" + i,
                          "alice",
                          "DEPLOYMENT",
                          "tenant-1",
                          System.nanoTime()));
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      go.countDown();
      assertTrue(done.await(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdown();
    }

    List<AuditEvent> events =
        store.listAuditEvents(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    int totalWritten = threadCount * perThread;
    assertEquals(Math.min(totalWritten, StateStore.MAX_AUDIT_EVENTS), events.size());
    assertEquals(events.size(), Set.copyOf(ids(events)).size(), "no duplicate or lost event ids");
  }

  @Test
  void unknown_resources_return_empty_or_empty_collections() {
    StateStore store = new StateStore();

    assertTrue(store.getDeployment(Optional.empty(), "nope").isEmpty());
    assertTrue(store.listDeployments().isEmpty());
    assertTrue(store.listAssignments().isEmpty());
    assertTrue(store.getNodeRegistration("nope").isEmpty());
    assertTrue(store.getNodeHeartbeat("nope").isEmpty());
  }

  @Test
  void role_role_binding_and_account_round_trip_through_a_snapshot_into_a_fresh_store() {
    StateStore store = new StateStore();
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

    // Account's generated equals() compares passwordHash by array reference, not content -- so
    // this round trip compares fields individually (assertArrayEquals on the byte[]) rather than
    // the whole record, the same posture ConfigEntry-bearing round trips elsewhere take.
    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertEquals(List.of(role), reloaded.listRoles());
    assertEquals(List.of(binding), reloaded.listRoleBindings());
    assertEquals(1, reloaded.listAccounts().size());
    assertEquals("admin", reloaded.listAccounts().get(0).username());
    assertArrayEquals(account.passwordHash(), reloaded.listAccounts().get(0).passwordHash());

    reloaded.removeRole("viewer");
    reloaded.removeRoleBinding("b1");
    reloaded.removeAccount("admin");
    StateStore reloadedAgain = new StateStore();
    reloadedAgain.restoreFromSnapshot(reloaded.snapshot());
    assertTrue(reloadedAgain.listRoles().isEmpty());
    assertTrue(reloadedAgain.listRoleBindings().isEmpty());
    assertTrue(reloadedAgain.listAccounts().isEmpty());
  }

  @Test
  void remove_role_bindings_for_role_removes_only_the_bindings_naming_that_role() {
    StateStore store = new StateStore();
    RoleBinding toViewer1 = new RoleBinding("b1", RoleBinding.userSubject("alice"), "viewer");
    RoleBinding toViewer2 = new RoleBinding("b2", RoleBinding.groupSubject("finance"), "viewer");
    RoleBinding toEditor = new RoleBinding("b3", RoleBinding.userSubject("bob"), "editor");
    store.putRoleBinding(toViewer1);
    store.putRoleBinding(toViewer2);
    store.putRoleBinding(toEditor);

    List<RoleBinding> removed = store.removeRoleBindingsForRole("viewer");

    assertEquals(Set.of(toViewer1, toViewer2), Set.copyOf(removed));
    assertEquals(List.of(toEditor), store.listRoleBindings());
    // A second call against a name with nothing left to cascade is a no-op, not an error.
    assertTrue(store.removeRoleBindingsForRole("viewer").isEmpty());
  }

  // ---- leases: non-replicated coordination state backing the reconciler-leader election ----

  @Test
  void a_free_lease_is_granted_to_the_first_caller() {
    StateStore store = new StateStore();

    LeaseGrant grant =
        store.tryAcquireOrRenewLease("reconciler-leader", "node-a:8080", Duration.ofSeconds(10));

    assertTrue(grant.granted());
    assertEquals("node-a:8080", grant.holderId());
    assertEquals(Optional.of("node-a:8080"), store.getLeaseHolder("reconciler-leader"));
  }

  @Test
  void the_current_holder_can_renew_its_own_lease() {
    StateStore store = new StateStore();
    store.tryAcquireOrRenewLease("reconciler-leader", "node-a:8080", Duration.ofSeconds(10));

    LeaseGrant renewal =
        store.tryAcquireOrRenewLease("reconciler-leader", "node-a:8080", Duration.ofSeconds(10));

    assertTrue(renewal.granted());
    assertEquals("node-a:8080", renewal.holderId());
  }

  @Test
  void a_different_holder_is_denied_while_the_lease_is_still_valid() {
    StateStore store = new StateStore();
    store.tryAcquireOrRenewLease("reconciler-leader", "node-a:8080", Duration.ofSeconds(10));

    LeaseGrant denied =
        store.tryAcquireOrRenewLease("reconciler-leader", "node-b:8081", Duration.ofSeconds(10));

    assertFalse(denied.granted());
    assertEquals("node-a:8080", denied.holderId(), "denial reports the current holder");
    assertEquals(Optional.of("node-a:8080"), store.getLeaseHolder("reconciler-leader"));
  }

  @Test
  void a_different_holder_is_granted_once_the_lease_has_expired(TestClock clock) {
    // The real 10-second TTL ControlPlaneMain uses, not a 1ms stand-in raced by a sleep -- and
    // asserted on both sides of the expiry, which a sleeping test cannot do.
    StateStore store = new StateStore(clock);
    Duration ttl = Duration.ofSeconds(10);
    store.tryAcquireOrRenewLease("reconciler-leader", "node-a:8080", ttl);

    clock.advance(ttl);
    assertFalse(
        store.tryAcquireOrRenewLease("reconciler-leader", "node-b:8081", ttl).granted(),
        "a lease is held right up to its expiry instant, not until just before it");

    clock.advance(Duration.ofMillis(1));
    LeaseGrant grant = store.tryAcquireOrRenewLease("reconciler-leader", "node-b:8081", ttl);

    assertTrue(grant.granted());
    assertEquals("node-b:8081", grant.holderId());
  }

  @Test
  void releasing_a_lease_the_caller_holds_frees_it_immediately() {
    StateStore store = new StateStore();
    store.tryAcquireOrRenewLease("reconciler-leader", "node-a:8080", Duration.ofSeconds(30));

    store.releaseLease("reconciler-leader", "node-a:8080");

    assertEquals(Optional.empty(), store.getLeaseHolder("reconciler-leader"));
    LeaseGrant grant =
        store.tryAcquireOrRenewLease("reconciler-leader", "node-b:8081", Duration.ofSeconds(10));
    assertTrue(grant.granted());
  }

  @Test
  void releasing_a_lease_the_caller_does_not_hold_is_a_no_op() {
    StateStore store = new StateStore();
    store.tryAcquireOrRenewLease("reconciler-leader", "node-a:8080", Duration.ofSeconds(30));

    store.releaseLease("reconciler-leader", "node-b:8081");

    assertEquals(Optional.of("node-a:8080"), store.getLeaseHolder("reconciler-leader"));
  }

  @Test
  void a_controller_revision_round_trips_through_a_snapshot_into_a_fresh_store() {
    StateStore store = new StateStore();
    ControllerRevision revision =
        new ControllerRevision(
            "Deployment",
            "orders-service",
            1,
            sampleDeployment("orders-service", 3),
            1_000L,
            OptionalInt.empty());

    store.putControllerRevision(revision);
    assertEquals(
        Optional.of(revision),
        store.getControllerRevision("Deployment", Optional.empty(), "orders-service", 1));
    assertEquals(
        List.of(revision),
        store.listControllerRevisions("Deployment", Optional.empty(), "orders-service"));

    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertEquals(
        Optional.of(revision),
        reloaded.getControllerRevision("Deployment", Optional.empty(), "orders-service", 1));
    assertEquals(
        List.of(revision),
        reloaded.listControllerRevisions("Deployment", Optional.empty(), "orders-service"));
  }

  @Test
  void controller_revisions_are_listed_newest_first() {
    StateStore store = new StateStore();
    ControllerRevision first =
        new ControllerRevision(
            "Deployment",
            "orders-service",
            1,
            sampleDeployment("orders-service", 1),
            1_000L,
            OptionalInt.empty());
    ControllerRevision second =
        new ControllerRevision(
            "Deployment",
            "orders-service",
            2,
            sampleDeployment("orders-service", 2),
            2_000L,
            OptionalInt.empty());

    store.putControllerRevision(first);
    store.putControllerRevision(second);

    assertEquals(
        List.of(second, first),
        store.listControllerRevisions("Deployment", Optional.empty(), "orders-service"));
  }

  @Test
  void a_rollback_revision_records_which_revision_it_restored() {
    StateStore store = new StateStore();
    store.putControllerRevision(
        new ControllerRevision(
            "Deployment",
            "orders-service",
            1,
            sampleDeployment("orders-service", 1),
            1_000L,
            OptionalInt.empty()));
    ControllerRevision rollback =
        new ControllerRevision(
            "Deployment",
            "orders-service",
            2,
            sampleDeployment("orders-service", 1),
            2_000L,
            OptionalInt.of(1));

    store.putControllerRevision(rollback);

    assertEquals(
        OptionalInt.of(1),
        store
            .getControllerRevision("Deployment", Optional.empty(), "orders-service", 2)
            .orElseThrow()
            .rollbackOfRevision());
  }

  @Test
  void controller_revisions_beyond_the_retention_cap_prune_the_oldest_first() {
    StateStore store = new StateStore();
    // 11 revisions, one over the 10-per-workload retention cap.
    for (int i = 1; i <= 11; i++) {
      store.putControllerRevision(
          new ControllerRevision(
              "Deployment",
              "orders-service",
              i,
              sampleDeployment("orders-service", i),
              1_000L + i,
              OptionalInt.empty()));
    }

    List<ControllerRevision> revisions =
        store.listControllerRevisions("Deployment", Optional.empty(), "orders-service");
    assertEquals(10, revisions.size());
    // Newest-first: revision 1 was pruned, revision 11 is now first.
    assertEquals(11, revisions.get(0).revision());
    assertEquals(2, revisions.get(revisions.size() - 1).revision());

    StateStore reloaded = new StateStore();
    reloaded.restoreFromSnapshot(store.snapshot());
    assertEquals(
        10,
        reloaded.listControllerRevisions("Deployment", Optional.empty(), "orders-service").size());
  }

  @Test
  void deployment_and_statefulset_revision_history_do_not_collide_on_a_shared_name() {
    StateStore store = new StateStore();
    store.putControllerRevision(
        new ControllerRevision(
            "Deployment",
            "shared-name",
            1,
            sampleDeployment("shared-name", 1),
            1_000L,
            OptionalInt.empty()));

    assertTrue(
        store.listControllerRevisions("StatefulSet", Optional.empty(), "shared-name").isEmpty());
    assertEquals(
        1, store.listControllerRevisions("Deployment", Optional.empty(), "shared-name").size());
  }

  @Test
  void an_unknown_workload_has_no_revision_history() {
    StateStore store = new StateStore();

    assertTrue(
        store.listControllerRevisions("Deployment", Optional.empty(), "never-deployed").isEmpty());
    assertTrue(
        store.getControllerRevision("Deployment", Optional.empty(), "never-deployed", 1).isEmpty());
  }

  @Test
  void removing_a_deployment_clears_its_controller_revision_history() {
    StateStore store = new StateStore();
    store.putControllerRevision(
        new ControllerRevision(
            "Deployment",
            "orders-service",
            1,
            sampleDeployment("orders-service", 1),
            1_000L,
            OptionalInt.empty()));

    store.removeDeployment(Optional.empty(), "orders-service");

    assertTrue(
        store.listControllerRevisions("Deployment", Optional.empty(), "orders-service").isEmpty());
  }

  @Test
  void recreating_a_deployment_under_the_same_name_after_delete_starts_revision_history_fresh() {
    StateStore store = new StateStore();
    store.putControllerRevision(
        new ControllerRevision(
            "Deployment",
            "orders-service",
            1,
            sampleDeployment("orders-service", 1),
            1_000L,
            OptionalInt.empty()));
    store.removeDeployment(Optional.empty(), "orders-service");

    // A brand-new Deployment reusing the same name -- its own first revision is numbered 1 again,
    // not a continuation of the deleted Deployment's history.
    ControllerRevision freshFirstRevision =
        new ControllerRevision(
            "Deployment",
            "orders-service",
            1,
            sampleDeployment("orders-service", 5),
            2_000L,
            OptionalInt.empty());
    store.putControllerRevision(freshFirstRevision);

    assertEquals(
        List.of(freshFirstRevision),
        store.listControllerRevisions("Deployment", Optional.empty(), "orders-service"));
  }

  @Test
  void removing_a_daemonset_clears_its_controller_revision_history() {
    StateStore store = new StateStore();
    store.putControllerRevision(
        new ControllerRevision(
            "DaemonSet",
            "orders-agent",
            1,
            sampleDaemonSet("orders-agent"),
            1_000L,
            OptionalInt.empty()));

    store.removeDaemonSetSpec(Optional.empty(), "orders-agent");

    assertTrue(
        store.listControllerRevisions("DaemonSet", Optional.empty(), "orders-agent").isEmpty());
  }

  @Test
  void removing_a_statefulset_clears_its_controller_revision_history() {
    StateStore store = new StateStore();
    store.putControllerRevision(
        new ControllerRevision(
            "StatefulSet",
            "orders-db",
            1,
            sampleStatefulSet("orders-db", 3),
            1_000L,
            OptionalInt.empty()));

    store.removeStatefulSetSpec(Optional.empty(), "orders-db");

    assertTrue(
        store.listControllerRevisions("StatefulSet", Optional.empty(), "orders-db").isEmpty());
  }

  @Test
  void a_snapshot_carries_controller_revisions_and_restores_them() {
    StateStore store = new StateStore();
    ControllerRevision revision =
        new ControllerRevision(
            "Deployment",
            "orders-service",
            1,
            sampleDeployment("orders-service", 1),
            1_000L,
            OptionalInt.empty());
    store.putControllerRevision(revision);

    StateSnapshot snapshot = store.snapshot();
    assertEquals(List.of(revision), snapshot.controllerRevisions());

    StateStore target = new StateStore();
    target.restoreFromSnapshot(snapshot);

    assertEquals(
        List.of(revision),
        target.listControllerRevisions("Deployment", Optional.empty(), "orders-service"));
  }
}
