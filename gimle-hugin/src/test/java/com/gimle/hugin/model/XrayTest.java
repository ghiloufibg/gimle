package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Joining Services to the instances behind them, and naming the gaps between the two. */
class XrayTest {

  @Test
  void a_service_is_followed_by_the_deployments_it_fronts_and_their_instances() {
    List<XrayRow> rows =
        Xray.rows(
            services(service("checkout-svc", "acme", 2, "checkout-api")),
            cluster(
                List.of(workload("checkout-api", "acme", 2)),
                List.of(instance("checkout-api", "acme", 0), instance("checkout-api", "acme", 1))),
            "");

    assertEquals(
        List.of(
            XrayRow.Kind.SERVICE,
            XrayRow.Kind.DEPLOYMENT,
            XrayRow.Kind.INSTANCE,
            XrayRow.Kind.INSTANCE),
        rows.stream().map(XrayRow::kind).toList());
    assertEquals(List.of(0, 1, 2, 2), rows.stream().map(XrayRow::depth).toList());
    assertEquals("checkout-api/0", rows.get(2).label());
  }

  @Test
  void a_service_naming_a_workload_the_cluster_has_never_heard_of_reads_as_not_found() {
    // The whole reason to look here: this Service looks fine in the services table, and the
    // deployment it names is absent from the instance table rather than wrong in it.
    List<XrayRow> rows =
        Xray.rows(
            services(service("checkout-svc", "acme", 0, "typo-api")),
            cluster(List.of(), List.of()),
            "");

    XrayRow deployment =
        rows.stream()
            .filter(row -> row.kind() == XrayRow.Kind.DEPLOYMENT)
            .findFirst()
            .orElseThrow();
    assertEquals("NOT FOUND", deployment.state());
  }

  @Test
  void a_workload_that_exists_but_runs_nothing_is_a_different_finding_from_one_that_does_not() {
    // A scaled-to-zero workload and a Service pointed at a name that does not exist are two
    // different mistakes, and telling them apart is most of the value of looking.
    List<XrayRow> rows =
        Xray.rows(
            services(service("checkout-svc", "acme", 0, "checkout-api")),
            cluster(List.of(workload("checkout-api", "acme", 3)), List.of()),
            "");

    XrayRow deployment =
        rows.stream()
            .filter(row -> row.kind() == XrayRow.Kind.DEPLOYMENT)
            .findFirst()
            .orElseThrow();
    assertEquals("NOT RUNNING", deployment.state());
    assertTrue(deployment.detail().contains("0 of 3"), deployment.detail());
  }

  @Test
  void a_workload_no_service_fronts_gets_its_own_heading_rather_than_being_left_out() {
    List<XrayRow> rows =
        Xray.rows(
            services(service("checkout-svc", "acme", 1, "checkout-api")),
            cluster(
                List.of(workload("checkout-api", "acme", 1), workload("batch-worker", "acme", 1)),
                List.of(instance("checkout-api", "acme", 0), instance("batch-worker", "acme", 0))),
            "");

    assertTrue(rows.stream().anyMatch(row -> row.kind() == XrayRow.Kind.UNFRONTED));
    int heading = indexOfKind(rows, XrayRow.Kind.UNFRONTED);
    assertTrue(
        rows.subList(heading, rows.size()).stream()
            .anyMatch(row -> row.label().equals("batch-worker")),
        rows.toString());
    assertFalse(
        rows.subList(heading, rows.size()).stream()
            .anyMatch(row -> row.label().equals("checkout-api")),
        "a fronted workload is not also listed as unfronted");
  }

  @Test
  void a_service_and_a_workload_in_different_tenants_are_never_joined_to_each_other() {
    // Two tenants running an identically-named deployment is exactly the case a name-only join
    // would report as one thing fronting the other's instances.
    List<XrayRow> rows =
        Xray.rows(
            services(service("checkout-svc", "acme", 1, "checkout-api")),
            cluster(
                List.of(workload("checkout-api", "beta", 1)),
                List.of(instance("checkout-api", "beta", 0))),
            "");

    XrayRow deployment =
        rows.stream()
            .filter(row -> row.kind() == XrayRow.Kind.DEPLOYMENT)
            .findFirst()
            .orElseThrow();
    assertEquals("NOT FOUND", deployment.state(), rows.toString());
  }

  @Test
  void filtering_keeps_a_matched_row_together_with_the_rows_it_hangs_from() {
    // A tree filtered to bare matches loses the only thing it was drawn for.
    List<XrayRow> rows =
        Xray.rows(
            services(service("checkout-svc", "acme", 1, "checkout-api")),
            cluster(
                List.of(workload("checkout-api", "acme", 2)),
                List.of(instance("checkout-api", "acme", 0), instance("checkout-api", "acme", 1))),
            "checkout-api/1");

    assertEquals(
        List.of(XrayRow.Kind.SERVICE, XrayRow.Kind.DEPLOYMENT, XrayRow.Kind.INSTANCE),
        rows.stream().map(XrayRow::kind).toList());
    assertEquals("checkout-api/1", rows.getLast().label());
  }

  @Test
  void a_cluster_with_no_services_at_all_reports_every_workload_as_fronted_by_none() {
    List<XrayRow> rows =
        Xray.rows(services(), cluster(List.of(workload("checkout-api", "acme", 1)), List.of()), "");

    assertEquals(XrayRow.Kind.UNFRONTED, rows.getFirst().kind());
  }

  private static int indexOfKind(final List<XrayRow> rows, final XrayRow.Kind kind) {
    for (int index = 0; index < rows.size(); index++) {
      if (rows.get(index).kind() == kind) {
        return index;
      }
    }
    throw new AssertionError("no row of kind " + kind + " in " + rows);
  }

  private static ServiceSnapshot services(final ServiceRow... rows) {
    return new ServiceSnapshot(
        "localhost:8080", Optional.of(Instant.EPOCH), List.of(rows), Optional.empty());
  }

  private static ServiceRow service(
      final String name, final String tenant, final int endpoints, final String... deployments) {
    return new ServiceRow(
        name,
        Optional.of(tenant),
        List.of(deployments),
        8080,
        OptionalInt.empty(),
        Optional.empty(),
        "TCP",
        OptionalInt.of(endpoints));
  }

  private static ClusterSnapshot cluster(
      final List<WorkloadRow> workloads, final List<InstanceRow> instances) {
    return new ClusterSnapshot(
        "localhost:8080",
        Optional.of(Instant.EPOCH),
        List.of(),
        instances,
        workloads,
        Optional.empty());
  }

  private static WorkloadRow workload(final String name, final String tenant, final int desired) {
    return new WorkloadRow(
        WorkloadKind.DEPLOYMENT,
        Optional.of(tenant),
        name,
        desired,
        0,
        0,
        false,
        false,
        Optional.empty());
  }

  private static InstanceRow instance(final String name, final String tenant, final int index) {
    return new InstanceRow(
        new InstanceKey(Optional.of(tenant), name, index),
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
        java.util.Map.of(),
        0L);
  }
}
