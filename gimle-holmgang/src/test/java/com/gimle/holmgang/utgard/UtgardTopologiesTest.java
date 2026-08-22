package com.gimle.holmgang.utgard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.TopologyParser;
import com.gimle.hilmir.validate.Finding;
import com.gimle.hilmir.validate.Severity;
import com.gimle.hilmir.validate.TopologyValidator;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Round-trips {@link UtgardTopologies}' generated YAML through {@code gimle-hilmir}'s own real
 * {@code TopologyParser}/{@code TopologyValidator} -- no Docker involved, so this stays part of the
 * module's default {@code mvn verify} even though the topologies it builds only ever get spawned
 * inside a container elsewhere in this package.
 */
class UtgardTopologiesTest {

  private static Topology parse(final String yaml) {
    return TopologyParser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void plaintext_topology_parses_and_validates_with_no_errors() {
    final String yaml =
        UtgardTopologies.plaintext(
            "utgard-boot",
            List.of("helm-1", "helm-2", "helm-3"),
            "helm-1",
            "helm-3",
            "helm-2",
            List.of("helm-3"));
    final Topology topology = parse(yaml);
    assertEquals("utgard-boot", topology.name());
    assertEquals(3, topology.machines().size());
    assertEquals(1, topology.store().replicas().size());
    assertEquals("helm-1", topology.store().replicas().get(0).machine());
    assertEquals(1, topology.agents().size());
    assertEquals("node-helm-3", topology.agents().get(0).nodeId());

    final List<Finding> findings = TopologyValidator.validate(topology);
    assertTrue(
        findings.stream().noneMatch(f -> f.severity() == Severity.ERROR),
        "expected no ERROR findings, got: " + findings);
  }

  @Test
  void mtls_topology_spanning_more_than_one_machine_parses_and_validates_with_no_errors() {
    final String yaml =
        UtgardTopologies.mtls(
            "utgard-mtls",
            List.of("helm-1", "helm-2"),
            "helm-1",
            List.of("helm-2"),
            "/opt/gimle/tls");
    final Topology topology = parse(yaml);
    assertEquals(com.gimle.hilmir.topology.Transport.MTLS, topology.transport());
    assertTrue(topology.tls().isPresent());
    assertEquals("/opt/gimle/tls", topology.tls().get().materialDir().toString());

    // A multi-machine mtls topology is real, fully-supported PKI territory now (PkiBootstrapMain
    // mints one leaf per machine hostname) -- no ERROR, and no warning about it either.
    final List<Finding> findings = TopologyValidator.validate(topology);
    assertFalse(
        findings.stream().anyMatch(f -> f.severity() == Severity.ERROR),
        "expected no ERROR findings, got: " + findings);
  }

  @Test
  void node_ids_are_derived_deterministically_from_the_machine_name() {
    assertEquals("node-helm-7", UtgardTopologies.nodeIdFor("helm-7"));
  }
}
