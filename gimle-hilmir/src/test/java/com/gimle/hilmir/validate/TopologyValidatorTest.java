package com.gimle.hilmir.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.TopologyParser;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TopologyValidatorTest {

  /**
   * A topology with nothing at all wrong: three machines, three store replicas spread one per
   * machine (odd count, never colocated), two control-plane replicas, one single-machine fafnir,
   * and one agent per machine. Every "does not report X" test below reuses this baseline so a
   * change to one rule's fixture can't silently start tripping a different rule.
   */
  private static final String HEALTHY_TOPOLOGY =
      """
      name: healthy
      machines:
        - {name: m1, host: host1.example.com}
        - {name: m2, host: host2.example.com}
        - {name: m3, host: host3.example.com}
      store:
        replicas:
          - {machine: m1, raftPort: 9080, clientPort: 9091}
          - {machine: m2, raftPort: 9080, clientPort: 9091}
          - {machine: m3, raftPort: 9080, clientPort: 9091}
      controlPlane:
        replicas:
          - {machine: m1, port: 8080}
          - {machine: m2, port: 8080}
      fafnir:
        keyFile: /etc/gimle/fafnir-secret.key
        replicas:
          - {machine: m1, port: 9092}
      agents:
        - {machine: m1, nodeId: node-a, gossipPort: 9090}
        - {machine: m2, nodeId: node-b, gossipPort: 9090}
        - {machine: m3, nodeId: node-c, gossipPort: 9090}
      """;

  private static List<Finding> validate(final String yaml) {
    final InputStream in = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    final Topology topology = TopologyParser.parse(in);
    return TopologyValidator.validate(topology);
  }

  private static boolean has(final List<Finding> findings, final String code) {
    return findings.stream().anyMatch(f -> f.code().equals(code));
  }

  private static Finding find(final List<Finding> findings, final String code) {
    return findings.stream()
        .filter(f -> f.code().equals(code))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no finding with code " + code + " in " + findings));
  }

  @Test
  void a_healthy_multi_machine_topology_has_zero_findings() {
    assertEquals(List.of(), validate(HEALTHY_TOPOLOGY));
  }

  @Test
  void reports_no_machines_when_the_topology_declares_none() {
    assertTrue(has(validate("name: bare\n"), "NO_MACHINES"));
  }

  @Test
  void does_not_report_no_machines_when_machines_are_declared() {
    assertFalse(has(validate(HEALTHY_TOPOLOGY), "NO_MACHINES"));
  }

  @Test
  void reports_no_store_when_no_store_replicas_are_declared() {
    final Finding finding =
        find(validate("name: bare\nmachines:\n  - {name: m1, host: h1}\n"), "NO_STORE");
    assertEquals(Severity.ERROR, finding.severity());
  }

  @Test
  void does_not_report_no_store_when_a_store_replica_is_declared() {
    assertFalse(has(validate(HEALTHY_TOPOLOGY), "NO_STORE"));
  }

  @Test
  void reports_no_control_plane_when_none_are_declared() {
    final Finding finding =
        find(
            validate(
                "name: bare\nmachines:\n  - {name: m1, host: h1}\nstore:\n  replicas:\n"
                    + "    - {machine: m1}\n"),
            "NO_CONTROL_PLANE");
    assertEquals(Severity.ERROR, finding.severity());
  }

  @Test
  void does_not_report_no_control_plane_when_one_is_declared() {
    assertFalse(has(validate(HEALTHY_TOPOLOGY), "NO_CONTROL_PLANE"));
  }

  @Test
  void reports_no_fafnir_when_none_are_declared() {
    final Finding finding =
        find(
            validate(
                "name: bare\nmachines:\n  - {name: m1, host: h1}\nstore:\n  replicas:\n"
                    + "    - {machine: m1}\ncontrolPlane:\n  replicas:\n    - {machine: m1}\n"),
            "NO_FAFNIR");
    assertEquals(Severity.ERROR, finding.severity());
  }

  @Test
  void does_not_report_no_fafnir_when_one_is_declared() {
    assertFalse(has(validate(HEALTHY_TOPOLOGY), "NO_FAFNIR"));
  }

  @Test
  void reports_unknown_machine_when_a_replica_references_an_undeclared_machine() {
    final Finding finding =
        find(
            validate(
                "name: bad\nmachines:\n  - {name: m1, host: h1}\nstore:\n  replicas:\n"
                    + "    - {machine: ghost}\n"),
            "UNKNOWN_MACHINE");
    assertEquals(Severity.ERROR, finding.severity());
  }

  @Test
  void does_not_report_unknown_machine_when_every_reference_is_declared() {
    assertFalse(has(validate(HEALTHY_TOPOLOGY), "UNKNOWN_MACHINE"));
  }

  @Test
  void reports_duplicate_machine_for_two_machines_sharing_a_name() {
    assertTrue(
        has(
            validate("name: bad\nmachines:\n  - {name: m1, host: h1}\n  - {name: m1, host: h2}\n"),
            "DUPLICATE_MACHINE"));
  }

  @Test
  void does_not_report_duplicate_machine_for_distinct_names() {
    assertFalse(has(validate(HEALTHY_TOPOLOGY), "DUPLICATE_MACHINE"));
  }

  @Test
  void reports_duplicate_node_id_for_two_agents_sharing_a_node_id() {
    assertTrue(
        has(
            validate(
                "name: bad\nmachines:\n  - {name: m1, host: h1}\n  - {name: m2, host: h2}\n"
                    + "agents:\n  - {machine: m1, nodeId: n1}\n  - {machine: m2, nodeId: n1}\n"),
            "DUPLICATE_NODE_ID"));
  }

  @Test
  void does_not_report_duplicate_node_id_for_distinct_ids() {
    assertFalse(has(validate(HEALTHY_TOPOLOGY), "DUPLICATE_NODE_ID"));
  }

  @Test
  void reports_port_conflict_for_two_processes_sharing_a_port_on_one_machine() {
    assertTrue(
        has(
            validate(
                "name: bad\nmachines:\n  - {name: m1, host: h1}\ncontrolPlane:\n  replicas:\n"
                    + "    - {machine: m1, port: 8080}\nfafnir:\n  keyFile: /k\n  replicas:\n"
                    + "    - {machine: m1, port: 8080}\n"),
            "PORT_CONFLICT"));
  }

  @Test
  void does_not_report_port_conflict_when_every_port_on_a_machine_is_distinct() {
    assertFalse(has(validate(HEALTHY_TOPOLOGY), "PORT_CONFLICT"));
  }

  @Test
  void reports_replicas_colocated_as_an_error_on_a_multi_machine_topology() {
    final Finding finding =
        find(
            validate(
                "name: bad\nmachines:\n  - {name: m1, host: h1}\n  - {name: m2, host: h2}\n"
                    + "store:\n  replicas:\n    - {machine: m1, raftPort: 9080, clientPort: 9091}\n"
                    + "    - {machine: m1, raftPort: 9180, clientPort: 9191}\n"),
            "REPLICAS_COLOCATED");
    assertEquals(Severity.ERROR, finding.severity());
  }

  @Test
  void reports_replicas_colocated_as_a_warning_on_a_single_machine_topology() {
    final Finding finding =
        find(
            validate(
                "name: bad\nmachines:\n  - {name: m1, host: h1}\n"
                    + "store:\n  replicas:\n    - {machine: m1, raftPort: 9080, clientPort: 9091}\n"
                    + "    - {machine: m1, raftPort: 9180, clientPort: 9191}\n"),
            "REPLICAS_COLOCATED");
    assertEquals(Severity.WARNING, finding.severity());
  }

  @Test
  void does_not_report_replicas_colocated_when_replicas_are_spread_across_machines() {
    assertFalse(has(validate(HEALTHY_TOPOLOGY), "REPLICAS_COLOCATED"));
  }

  @Test
  void reports_agents_colocated_as_an_error_on_a_multi_machine_topology() {
    final Finding finding =
        find(
            validate(
                "name: bad\nmachines:\n  - {name: m1, host: h1}\n  - {name: m2, host: h2}\n"
                    + "agents:\n  - {machine: m1, nodeId: n1}\n  - {machine: m1, nodeId: n2}\n"),
            "AGENTS_COLOCATED");
    assertEquals(Severity.ERROR, finding.severity());
  }

  @Test
  void reports_agents_colocated_as_a_warning_on_a_single_machine_topology() {
    final Finding finding =
        find(
            validate(
                "name: bad\nmachines:\n  - {name: m1, host: h1}\n"
                    + "agents:\n  - {machine: m1, nodeId: n1}\n  - {machine: m1, nodeId: n2}\n"),
            "AGENTS_COLOCATED");
    assertEquals(Severity.WARNING, finding.severity());
  }

  @Test
  void does_not_report_agents_colocated_when_agents_are_spread_across_machines() {
    assertFalse(has(validate(HEALTHY_TOPOLOGY), "AGENTS_COLOCATED"));
  }

  @Test
  void reports_mtls_no_material_dir_when_tls_material_dir_is_unset() {
    final Finding finding =
        find(
            validate("name: bad\ntransport: mtls\nmachines:\n  - {name: m1, host: h1}\n"),
            "MTLS_NO_MATERIAL_DIR");
    assertEquals(Severity.ERROR, finding.severity());
  }

  @Test
  void does_not_report_mtls_no_material_dir_when_configured() {
    assertFalse(
        has(
            validate(
                "name: ok\ntransport: mtls\ntls:\n  materialDir: /etc/gimle/tls\n"
                    + "machines:\n  - {name: m1, host: host1.example.com}\n"),
            "MTLS_NO_MATERIAL_DIR"));
  }

  @Test
  void reports_mtls_ip_literal_host_for_an_ipv4_literal_machine_host() {
    final Finding finding =
        find(
            validate(
                "name: bad\ntransport: mtls\ntls:\n  materialDir: /etc/gimle/tls\n"
                    + "machines:\n  - {name: m1, host: 10.0.0.5}\n"),
            "MTLS_IP_LITERAL_HOST");
    assertEquals(Severity.ERROR, finding.severity());
  }

  @Test
  void does_not_report_mtls_ip_literal_host_for_a_dns_hostname() {
    assertFalse(
        has(
            validate(
                "name: ok\ntransport: mtls\ntls:\n  materialDir: /etc/gimle/tls\n"
                    + "machines:\n  - {name: m1, host: host1.example.com}\n"),
            "MTLS_IP_LITERAL_HOST"));
  }

  @Test
  void does_not_report_mtls_ip_literal_host_under_plaintext() {
    assertFalse(
        has(
            validate("name: ok\nmachines:\n  - {name: m1, host: 10.0.0.5}\n"),
            "MTLS_IP_LITERAL_HOST"));
  }

  @Test
  void reports_mtls_single_hostname_pki_on_a_multi_machine_mtls_topology() {
    final Finding finding =
        find(
            validate(
                "name: bad\ntransport: mtls\ntls:\n  materialDir: /etc/gimle/tls\n"
                    + "machines:\n  - {name: m1, host: host1.example.com}\n"
                    + "  - {name: m2, host: host2.example.com}\n"),
            "MTLS_SINGLE_HOSTNAME_PKI");
    assertEquals(Severity.WARNING, finding.severity());
  }

  @Test
  void does_not_report_mtls_single_hostname_pki_on_a_single_machine_mtls_topology() {
    assertFalse(
        has(
            validate(
                "name: ok\ntransport: mtls\ntls:\n  materialDir: /etc/gimle/tls\n"
                    + "machines:\n  - {name: m1, host: host1.example.com}\n"),
            "MTLS_SINGLE_HOSTNAME_PKI"));
  }

  @Test
  void reports_single_store_for_exactly_one_store_replica() {
    final Finding finding =
        find(
            validate(
                "name: bad\nmachines:\n  - {name: m1, host: h1}\nstore:\n  replicas:\n"
                    + "    - {machine: m1}\n"),
            "SINGLE_STORE");
    assertEquals(Severity.WARNING, finding.severity());
  }

  @Test
  void does_not_report_single_store_for_three_replicas() {
    assertFalse(has(validate(HEALTHY_TOPOLOGY), "SINGLE_STORE"));
  }

  @Test
  void reports_store_even_replicas_for_two_store_replicas() {
    final Finding finding =
        find(
            validate(
                "name: bad\nmachines:\n  - {name: m1, host: h1}\n  - {name: m2, host: h2}\n"
                    + "store:\n  replicas:\n    - {machine: m1}\n    - {machine: m2}\n"),
            "STORE_EVEN_REPLICAS");
    assertEquals(Severity.WARNING, finding.severity());
  }

  @Test
  void does_not_report_store_even_replicas_for_an_odd_replica_count() {
    assertFalse(has(validate(HEALTHY_TOPOLOGY), "STORE_EVEN_REPLICAS"));
  }

  @Test
  void reports_single_control_plane_for_exactly_one_replica() {
    final Finding finding =
        find(
            validate(
                "name: bad\nmachines:\n  - {name: m1, host: h1}\ncontrolPlane:\n  replicas:\n"
                    + "    - {machine: m1}\n"),
            "SINGLE_CONTROL_PLANE");
    assertEquals(Severity.WARNING, finding.severity());
  }

  @Test
  void does_not_report_single_control_plane_for_two_replicas() {
    assertFalse(has(validate(HEALTHY_TOPOLOGY), "SINGLE_CONTROL_PLANE"));
  }

  @Test
  void reports_no_agents_when_none_are_declared() {
    final Finding finding =
        find(validate("name: bare\nmachines:\n  - {name: m1, host: h1}\n"), "NO_AGENTS");
    assertEquals(Severity.WARNING, finding.severity());
  }

  @Test
  void does_not_report_no_agents_when_agents_are_declared() {
    assertFalse(has(validate(HEALTHY_TOPOLOGY), "NO_AGENTS"));
  }

  @Test
  void reports_fafnir_key_distribution_when_fafnir_spans_multiple_machines() {
    final Finding finding =
        find(
            validate(
                "name: bad\nmachines:\n  - {name: m1, host: h1}\n  - {name: m2, host: h2}\n"
                    + "fafnir:\n  keyFile: /k\n  replicas:\n    - {machine: m1}\n"
                    + "    - {machine: m2}\n"),
            "FAFNIR_KEY_DISTRIBUTION");
    assertEquals(Severity.WARNING, finding.severity());
  }

  @Test
  void does_not_report_fafnir_key_distribution_when_fafnir_is_single_machine() {
    assertFalse(has(validate(HEALTHY_TOPOLOGY), "FAFNIR_KEY_DISTRIBUTION"));
  }

  @Test
  void reports_every_applicable_finding_for_a_topology_that_gets_everything_wrong() {
    final List<Finding> findings =
        validate(
            """
            name: disaster
            transport: mtls
            machines:
              - {name: m1, host: 10.0.0.1}
              - {name: m1, host: 10.0.0.2}
            store:
              replicas:
                - {machine: m1, raftPort: 9080, clientPort: 9091}
                - {machine: m1, raftPort: 9080, clientPort: 9091}
                - {machine: ghost, raftPort: 9080, clientPort: 9091}
            controlPlane:
              replicas: []
            fafnir:
              replicas: []
            agents:
              - {machine: m1, nodeId: n1}
              - {machine: m1, nodeId: n1}
            """);
    assertTrue(has(findings, "MTLS_NO_MATERIAL_DIR"));
    assertTrue(has(findings, "MTLS_IP_LITERAL_HOST"));
    assertTrue(has(findings, "DUPLICATE_MACHINE"));
    assertTrue(has(findings, "UNKNOWN_MACHINE"));
    assertTrue(has(findings, "REPLICAS_COLOCATED"));
    assertTrue(has(findings, "NO_CONTROL_PLANE"));
    assertTrue(has(findings, "NO_FAFNIR"));
    assertTrue(has(findings, "DUPLICATE_NODE_ID"));
    assertTrue(has(findings, "AGENTS_COLOCATED"));
    assertTrue(has(findings, "PORT_CONFLICT"));
    assertFalse(has(findings, "NO_MACHINES"));
    assertTrue(findings.stream().anyMatch(f -> f.severity() == Severity.ERROR));
  }
}
