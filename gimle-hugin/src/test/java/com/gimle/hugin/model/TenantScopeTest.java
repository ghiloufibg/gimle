package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * Narrowing each reading to one tenant, the way a namespace narrows a cluster.
 *
 * <p>Every case here is about what stays visible rather than what disappears: a scope that hides
 * the wrong thing is worse than no scope at all, because the screen still looks complete.
 */
class TenantScopeTest {

  @Test
  void a_scoped_cluster_keeps_only_that_tenants_instances_and_workloads() {
    ClusterSnapshot scoped = cluster().scopedTo(Optional.of("acme"));

    assertEquals(
        List.of("checkout-api"),
        scoped.instances().stream().map(InstanceRow::deploymentName).toList());
    assertEquals(
        List.of("checkout-api"), scoped.workloads().stream().map(WorkloadRow::name).toList());
  }

  @Test
  void nodes_are_never_narrowed_because_a_node_belongs_to_the_cluster_and_not_to_a_tenant() {
    // Hiding the machine a tenant's instances run on answers "where is this running" with silence.
    assertEquals(2, cluster().scopedTo(Optional.of("acme")).nodes().size());
  }

  @Test
  void choosing_no_tenant_leaves_the_reading_exactly_as_it_came() {
    ClusterSnapshot full = cluster();

    assertEquals(full, full.scopedTo(Optional.empty()));
  }

  @Test
  void a_tenant_with_nothing_in_it_reads_as_empty_rather_than_falling_back_to_everything() {
    // A scope that silently widened when it matched nothing would be the one truly misleading
    // outcome: the screen would look like that tenant owned the whole cluster.
    ClusterSnapshot scoped = cluster().scopedTo(Optional.of("nobody"));

    assertTrue(scoped.instances().isEmpty());
    assertTrue(scoped.workloads().isEmpty());
  }

  @Test
  void the_untenanted_namespace_is_a_scope_of_its_own_and_not_a_wildcard() {
    ClusterSnapshot mixed =
        new ClusterSnapshot(
            "localhost:8080",
            Optional.of(Instant.EPOCH),
            List.of(),
            List.of(
                instance("shared-api", Optional.empty()),
                instance("checkout-api", Optional.of("acme"))),
            List.of(),
            Optional.empty());

    assertEquals(2, mixed.scopedTo(Optional.empty()).instances().size());
    assertEquals(1, mixed.scopedTo(Optional.of("acme")).instances().size());
  }

  @Test
  void a_scoped_services_reading_keeps_only_that_tenants_services() {
    ServiceSnapshot services =
        new ServiceSnapshot(
            "localhost:8080",
            Optional.of(Instant.EPOCH),
            List.of(service("checkout-svc", "acme"), service("billing-svc", "beta")),
            Optional.empty());

    assertEquals(
        List.of("checkout-svc"),
        services.scopedTo(Optional.of("acme")).services().stream().map(ServiceRow::name).toList());
  }

  @Test
  void a_kind_whose_rows_carry_no_tenant_is_cluster_wide_and_survives_being_scoped() {
    // Roles and accounts belong to the cluster; narrowing them to a tenant would report that none
    // of them exist, which is a different and much more alarming claim.
    ResourceKind roles =
        ResourceKind.builtIns().stream()
            .filter(kind -> kind.key().equals("roles"))
            .findFirst()
            .orElseThrow();
    ResourceSnapshot snapshot = resources(roles, resourceRow("admin"));

    assertEquals(1, snapshot.scopedTo(Optional.of("acme")).rows().size());
  }

  @Test
  void a_kind_whose_rows_do_carry_a_tenant_is_narrowed_by_it() {
    ResourceKind tenantScoped =
        ResourceKind.builtIns().stream()
            .filter(kind -> kind.key().equals("networkpolicies"))
            .findFirst()
            .orElseThrow();
    ResourceSnapshot snapshot =
        resources(
            tenantScoped, resourceRow("allow-acme", "acme"), resourceRow("allow-beta", "beta"));

    assertEquals(
        List.of("allow-acme"),
        snapshot.scopedTo(Optional.of("acme")).rows().stream().map(ResourceRow::name).toList());
  }

  private static ClusterSnapshot cluster() {
    return new ClusterSnapshot(
        "localhost:8080",
        Optional.of(Instant.EPOCH),
        List.of(node("node-alpha"), node("node-beta")),
        List.of(
            instance("checkout-api", Optional.of("acme")),
            instance("billing-api", Optional.of("beta"))),
        List.of(workload("checkout-api", "acme"), workload("billing-api", "beta")),
        Optional.empty());
  }

  private static NodeRow node(final String nodeId) {
    return new NodeRow(
        nodeId,
        false,
        0L,
        0L,
        0L,
        0L,
        0,
        "HEALTHY",
        Optional.of(Instant.EPOCH),
        List.of(),
        List.of(),
        List.of());
  }

  private static InstanceRow instance(final String name, final Optional<String> tenant) {
    return new InstanceRow(
        new InstanceKey(tenant, name, 0),
        WorkloadKind.DEPLOYMENT,
        "node-alpha",
        true,
        "ACTIVE",
        true,
        true,
        0.0,
        0.0,
        0,
        0L,
        0L,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Map.of(),
        0L);
  }

  private static WorkloadRow workload(final String name, final String tenant) {
    return new WorkloadRow(
        WorkloadKind.DEPLOYMENT,
        Optional.of(tenant),
        name,
        1,
        1,
        0,
        false,
        false,
        Optional.empty());
  }

  private static ServiceRow service(final String name, final String tenant) {
    return new ServiceRow(
        name,
        Optional.of(tenant),
        List.of(),
        8080,
        OptionalInt.empty(),
        Optional.empty(),
        "TCP",
        OptionalInt.of(1));
  }

  private static ResourceSnapshot resources(final ResourceKind kind, final ResourceRow... rows) {
    return new ResourceSnapshot(
        "localhost:8080", Optional.of(Instant.EPOCH), kind, List.of(rows), true, Optional.empty());
  }

  private static ResourceRow resourceRow(final String name) {
    return new ResourceRow(name, Optional.empty(), List.of(name), Map.of("name", name));
  }

  private static ResourceRow resourceRow(final String name, final String tenant) {
    return new ResourceRow(name, Optional.of(tenant), List.of(name, tenant), Map.of("name", name));
  }
}
