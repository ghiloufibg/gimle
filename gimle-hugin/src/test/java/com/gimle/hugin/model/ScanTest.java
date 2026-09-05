package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * What the scan reports, and -- more importantly -- what it refuses to report.
 *
 * <p>Every case here is about a finding being right rather than merely present: a scan that cries
 * wolf is ignored, and one that comes back clean because it never looked is worse than no scan.
 */
class ScanTest {

  private static final Instant NOW = Instant.parse("2026-09-01T14:02:43Z");

  @Test
  void a_workload_with_replicas_the_scheduler_placed_nowhere_is_reported_as_an_error() {
    List<ScanFinding> findings =
        scan(
            cluster(List.of(freshNode("node-alpha")), List.of(), List.of(workload("api", 4, 2, 2))),
            services());

    ScanFinding finding = only(findings, "workloads");
    assertEquals(ScanFinding.Severity.ERROR, finding.severity());
    assertTrue(finding.detail().contains("2 of 4"), finding.detail());
  }

  @Test
  void a_workload_scaled_to_zero_is_a_note_and_not_a_fault() {
    // Deliberate, and indistinguishable at a glance from a workload whose replicas all died.
    List<ScanFinding> findings =
        scan(
            cluster(List.of(freshNode("node-alpha")), List.of(), List.of(workload("api", 0, 0, 0))),
            services());

    assertEquals(ScanFinding.Severity.NOTE, only(findings, "workloads").severity());
  }

  @Test
  void a_node_whose_agent_has_stopped_reporting_outranks_everything_it_claims_to_run() {
    List<ScanFinding> findings =
        scan(
            cluster(
                List.of(staleNode("node-alpha")),
                List.of(instance("api", 0, "ACTIVE", true, true)),
                List.of(workload("api", 1, 1, 0))),
            services());

    ScanFinding finding = only(findings, "nodes");
    assertEquals(ScanFinding.Severity.ERROR, finding.severity());
    assertTrue(finding.detail().contains("no heartbeat"), finding.detail());
  }

  @Test
  void a_cordoned_node_is_a_note_because_somebody_meant_it() {
    List<ScanFinding> findings =
        scan(cluster(List.of(cordonedNode("node-alpha")), List.of(), List.of()), services());

    assertEquals(ScanFinding.Severity.NOTE, only(findings, "nodes").severity());
  }

  @Test
  void a_node_almost_fully_committed_is_warned_about_before_a_placement_actually_fails() {
    // By the time it fails the finding is a workload's unplaced replicas, which no longer says
    // which machine ran out.
    NodeRow crowded =
        new NodeRow(
            "node-alpha",
            false,
            3800L,
            4000L,
            1_000L,
            8_000L,
            9,
            "HEALTHY",
            Optional.of(NOW),
            List.of(),
            List.of(),
            List.of());

    List<ScanFinding> findings = scan(cluster(List.of(crowded), List.of(), List.of()), services());

    ScanFinding finding = only(findings, "nodes");
    assertEquals(ScanFinding.Severity.WARNING, finding.severity());
    assertTrue(finding.detail().contains("95%"), finding.detail());
  }

  @Test
  void an_instance_failing_liveness_is_an_error_and_one_merely_not_ready_is_a_warning() {
    List<ScanFinding> findings =
        scan(
            cluster(
                List.of(freshNode("node-alpha")),
                List.of(
                    instance("api", 0, "ACTIVE", false, false),
                    instance("api", 1, "ACTIVE", true, false)),
                List.of()),
            services());

    assertEquals(
        List.of(ScanFinding.Severity.ERROR, ScanFinding.Severity.WARNING),
        findings.stream().map(ScanFinding::severity).toList());
  }

  @Test
  void an_instance_still_starting_is_not_reported_for_being_unready() {
    // It is unready by design, and reporting it would fill the screen with findings that resolve
    // themselves before anyone could act on one.
    List<ScanFinding> findings =
        scan(
            cluster(
                List.of(freshNode("node-alpha")),
                List.of(instance("api", 0, "STARTING", true, false)),
                List.of()),
            services());

    assertTrue(findings.isEmpty(), findings.toString());
  }

  @Test
  void an_instance_no_node_has_reported_on_is_reported_as_such_rather_than_as_unhealthy() {
    List<ScanFinding> findings =
        scan(
            cluster(
                List.of(freshNode("node-alpha")),
                List.of(
                    new InstanceRow(
                        new InstanceKey(Optional.empty(), "api", 0),
                        WorkloadKind.DEPLOYMENT,
                        "node-alpha",
                        false,
                        "INSTALLED",
                        false,
                        false,
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
                        0L)),
                List.of()),
            services());

    ScanFinding finding = only(findings, "instances");
    assertEquals(ScanFinding.Severity.WARNING, finding.severity());
    assertTrue(finding.detail().contains("reported nothing about it yet"), finding.detail());
  }

  @Test
  void a_service_resolving_to_nothing_and_one_fronting_a_workload_that_does_not_exist_differ() {
    // Two different mistakes, fixed in two different places.
    ServiceSnapshot services =
        new ServiceSnapshot(
            "localhost:8080",
            Optional.of(NOW),
            List.of(service("checkout-svc", List.of("ghost"), OptionalInt.of(0))),
            Optional.empty());

    List<ScanFinding> findings =
        scan(cluster(List.of(freshNode("node-alpha")), List.of(), List.of()), services);

    assertEquals(2, findings.size(), findings.toString());
    assertTrue(
        findings.stream().anyMatch(f -> f.detail().contains("resolves to no endpoint")),
        findings.toString());
    assertTrue(
        findings.stream().anyMatch(f -> f.detail().contains("not a workload this cluster has")),
        findings.toString());
  }

  @Test
  void a_service_whose_endpoints_could_not_be_read_is_never_reported_as_resolving_to_nothing() {
    ServiceSnapshot services =
        new ServiceSnapshot(
            "localhost:8080",
            Optional.of(NOW),
            List.of(service("checkout-svc", List.of("api"), OptionalInt.empty())),
            Optional.empty());

    ScanFinding finding =
        only(
            scan(
                cluster(
                    List.of(freshNode("node-alpha")), List.of(), List.of(workload("api", 1, 1, 0))),
                services),
            "services");

    assertEquals(ScanFinding.Severity.WARNING, finding.severity());
    assertTrue(finding.detail().contains("could not be read"), finding.detail());
  }

  @Test
  void a_services_read_that_never_landed_is_reported_rather_than_silently_skipped() {
    // A clean scan that never ran half its checks is worse than no scan at all.
    List<ScanFinding> findings =
        scan(
            cluster(List.of(freshNode("node-alpha")), List.of(), List.of()),
            ServiceSnapshot.connecting("localhost:8080"));

    ScanFinding finding = only(findings, "services");
    assertTrue(finding.detail().contains("nothing about Services was checked"), finding.detail());
  }

  @Test
  void a_healthy_cluster_produces_nothing_at_all() {
    List<ScanFinding> findings =
        scan(
            cluster(
                List.of(freshNode("node-alpha")),
                List.of(instance("api", 0, "ACTIVE", true, true)),
                List.of(workload("api", 1, 1, 0))),
            services(service("api-svc", List.of("api"), OptionalInt.of(1))));

    assertTrue(findings.isEmpty(), findings.toString());
  }

  @Test
  void errors_come_before_warnings_and_warnings_before_notes() {
    List<ScanFinding> findings =
        scan(
            cluster(
                List.of(cordonedNode("node-beta"), freshNode("node-alpha")),
                List.of(instance("api", 0, "ACTIVE", true, false)),
                List.of(workload("api", 2, 1, 1))),
            services());

    assertEquals(
        List.of(
            ScanFinding.Severity.ERROR, ScanFinding.Severity.WARNING, ScanFinding.Severity.NOTE),
        findings.stream().map(ScanFinding::severity).toList());
  }

  @Test
  void the_filter_narrows_by_subject_kind_and_wording_alike() {
    ClusterSnapshot cluster =
        cluster(
            List.of(cordonedNode("node-beta"), freshNode("node-alpha")),
            List.of(),
            List.of(workload("checkout-api", 2, 1, 1)));

    assertEquals(1, Scan.findings(cluster, services(), NOW, "checkout").size());
    assertEquals(1, Scan.findings(cluster, services(), NOW, "node-beta").size());
    assertEquals(2, Scan.findings(cluster, services(), NOW, "").size());
  }

  @Test
  void a_tenants_own_name_is_part_of_a_subject_because_two_tenants_may_share_one() {
    WorkloadRow tenanted =
        new WorkloadRow(
            WorkloadKind.DEPLOYMENT,
            Optional.of("acme"),
            "api",
            2,
            1,
            1,
            false,
            false,
            Optional.empty());

    ScanFinding finding =
        only(
            scan(
                cluster(List.of(freshNode("node-alpha")), List.of(), List.of(tenanted)),
                services()),
            "workloads");

    assertEquals("acme/api", finding.subject());
  }

  @Test
  void a_quota_and_a_limit_range_verdict_are_reported_separately_from_the_shortfall() {
    WorkloadRow violating =
        new WorkloadRow(
            WorkloadKind.DEPLOYMENT,
            Optional.empty(),
            "api",
            1,
            1,
            0,
            true,
            true,
            Optional.of("memory above the maximum"));

    List<ScanFinding> findings =
        scan(cluster(List.of(freshNode("node-alpha")), List.of(), List.of(violating)), services());

    assertEquals(2, findings.size(), findings.toString());
    assertTrue(findings.stream().allMatch(f -> f.severity() == ScanFinding.Severity.WARNING));
    assertTrue(
        findings.stream().anyMatch(f -> f.detail().contains("memory above the maximum")),
        findings.toString());
  }

  @Test
  void a_service_and_a_workload_in_different_tenants_are_never_matched_to_each_other() {
    ServiceSnapshot services =
        new ServiceSnapshot(
            "localhost:8080",
            Optional.of(NOW),
            List.of(
                new ServiceRow(
                    "api-svc",
                    Optional.of("acme"),
                    List.of("api"),
                    8080,
                    OptionalInt.empty(),
                    Optional.empty(),
                    "TCP",
                    OptionalInt.of(1))),
            Optional.empty());

    // The workload named "api" exists, but in no tenant at all -- so this Service fronts nothing.
    List<ScanFinding> findings =
        scan(
            cluster(List.of(freshNode("node-alpha")), List.of(), List.of(workload("api", 1, 1, 0))),
            services);

    assertFalse(findings.isEmpty());
    assertTrue(
        findings.getFirst().detail().contains("not a workload this cluster has"),
        findings.toString());
  }

  private static List<ScanFinding> scan(
      final ClusterSnapshot cluster, final ServiceSnapshot services) {
    return Scan.findings(cluster, services, NOW, "");
  }

  private static ScanFinding only(final List<ScanFinding> findings, final String group) {
    List<ScanFinding> inGroup =
        findings.stream().filter(finding -> finding.group().equals(group)).toList();
    assertEquals(1, inGroup.size(), findings.toString());
    return inGroup.getFirst();
  }

  private static ClusterSnapshot cluster(
      final List<NodeRow> nodes,
      final List<InstanceRow> instances,
      final List<WorkloadRow> workloads) {
    return new ClusterSnapshot(
        "localhost:8080", Optional.of(NOW), nodes, instances, workloads, Optional.empty());
  }

  private static ServiceSnapshot services(final ServiceRow... rows) {
    return new ServiceSnapshot("localhost:8080", Optional.of(NOW), List.of(rows), Optional.empty());
  }

  private static ServiceRow service(
      final String name, final List<String> deployments, final OptionalInt endpoints) {
    return new ServiceRow(
        name,
        Optional.empty(),
        deployments,
        8080,
        OptionalInt.empty(),
        Optional.empty(),
        "TCP",
        endpoints);
  }

  private static NodeRow freshNode(final String nodeId) {
    return node(nodeId, false, "HEALTHY", Optional.of(NOW));
  }

  private static NodeRow staleNode(final String nodeId) {
    return node(nodeId, false, "STALE", Optional.of(NOW.minusSeconds(300)));
  }

  private static NodeRow cordonedNode(final String nodeId) {
    return node(nodeId, true, "HEALTHY", Optional.of(NOW));
  }

  private static NodeRow node(
      final String nodeId,
      final boolean cordoned,
      final String status,
      final Optional<Instant> heartbeat) {
    return new NodeRow(
        nodeId, cordoned, 100L, 4000L, 100L, 8000L, 1, status, heartbeat, List.of(), List.of(),
        List.of());
  }

  private static InstanceRow instance(
      final String name,
      final int index,
      final String state,
      final boolean alive,
      final boolean ready) {
    return new InstanceRow(
        new InstanceKey(Optional.empty(), name, index),
        WorkloadKind.DEPLOYMENT,
        "node-alpha",
        true,
        state,
        alive,
        ready,
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

  private static WorkloadRow workload(
      final String name, final int desired, final int placed, final int unplaced) {
    return new WorkloadRow(
        WorkloadKind.DEPLOYMENT,
        Optional.empty(),
        name,
        desired,
        placed,
        unplaced,
        false,
        false,
        Optional.empty());
  }
}
