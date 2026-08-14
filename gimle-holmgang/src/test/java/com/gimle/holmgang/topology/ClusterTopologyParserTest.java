package com.gimle.holmgang.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClusterTopologyParserTest {

  private static ClusterSpec parse(final String yaml) {
    final InputStream in = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    return ClusterTopologyParser.parse(in);
  }

  @Test
  void parses_a_complete_topology_document() {
    final ClusterSpec spec =
        parse(
            """
            name: full
            transport: plaintext
            store:
              replicas: 3
            controlPlane:
              replicas: 2
            fafnir:
              replicas: 2
            muninn:
              enabled: true
            nodes:
              - id: node-a
                labels: [gpu, ssd]
              - id: node-b
            faults:
              proxied: true
            jvm:
              store: ["-Xmx256m"]
              worker: ["-Xmx128m"]
            seed:
              accounts:
                - username: operator
                  password: secret
              tenants:
                - id: acme
                  quota:
                    maxMemoryBytes: 2147483648
                    maxCpuMillicores: 4000
                    maxInstances: 8
            """);
    assertEquals("full", spec.name());
    assertEquals(Transport.PLAINTEXT, spec.transport());
    assertEquals(3, spec.storeReplicas());
    assertEquals(2, spec.controlPlaneReplicas());
    assertEquals(2, spec.fafnirReplicas());
    assertTrue(spec.muninnEnabled());
    assertEquals(
        List.of(new NodeSpec("node-a", List.of("gpu", "ssd")), new NodeSpec("node-b", List.of())),
        spec.nodes());
    assertTrue(spec.faultsProxied());
    assertEquals(List.of("-Xmx256m"), spec.jvmFlags(ProcessRole.STORE));
    assertEquals(List.of("-Xmx128m"), spec.jvmFlags(ProcessRole.WORKER));
    assertEquals(List.of(), spec.jvmFlags(ProcessRole.AGENT));
    assertEquals(List.of(new AccountSeed("operator", "secret")), spec.seed().accounts());
    assertEquals(
        List.of(new TenantSeed("acme", QuotaSpec.of(2_147_483_648L, 4000, 8))),
        spec.seed().tenants());
  }

  @Test
  void defaults_apply_when_optional_sections_are_absent() {
    final ClusterSpec spec = parse("name: bare\n");
    assertEquals(Transport.PLAINTEXT, spec.transport());
    assertEquals(1, spec.storeReplicas());
    assertEquals(1, spec.controlPlaneReplicas());
    assertEquals(1, spec.fafnirReplicas());
    assertFalse(spec.muninnEnabled());
    assertEquals(List.of(), spec.nodes());
    assertFalse(spec.faultsProxied());
    assertEquals(SeedSpec.EMPTY, spec.seed());
  }

  @Test
  void parses_the_node_count_shorthand_into_sequential_ids() {
    final ClusterSpec spec =
        parse(
            """
            name: shorthand
            nodes:
              count: 3
            """);
    assertEquals(
        List.of(
            new NodeSpec("node-1", List.of()),
            new NodeSpec("node-2", List.of()),
            new NodeSpec("node-3", List.of())),
        spec.nodes());
  }

  @Test
  void rejects_a_missing_name() {
    final GimleManifestException e =
        assertThrows(GimleManifestException.class, () -> parse("store:\n  replicas: 1\n"));
    assertTrue(e.getMessage().contains("name"));
  }

  @Test
  void rejects_zero_store_replicas() {
    assertThrows(GimleManifestException.class, () -> parse("name: bad\nstore:\n  replicas: 0\n"));
  }

  @Test
  void rejects_an_unknown_transport() {
    final GimleManifestException e =
        assertThrows(GimleManifestException.class, () -> parse("name: bad\ntransport: quic\n"));
    assertTrue(e.getMessage().contains("quic"));
  }

  @Test
  void rejects_duplicate_node_ids() {
    final GimleManifestException e =
        assertThrows(
            GimleManifestException.class,
            () ->
                parse(
                    """
                    name: bad
                    nodes:
                      - id: node-a
                      - id: node-a
                    """));
    assertTrue(e.getMessage().contains("node-a"));
  }

  @Test
  void rejects_a_nodes_section_that_is_neither_count_nor_list() {
    assertThrows(GimleManifestException.class, () -> parse("name: bad\nnodes: everywhere\n"));
  }

  @Test
  void rejects_an_unknown_jvm_flags_role() {
    assertThrows(
        GimleManifestException.class, () -> parse("name: bad\njvm:\n  sidecar: [\"-Xmx1m\"]\n"));
  }

  @Test
  void rejects_a_non_mapping_root() {
    assertThrows(GimleManifestException.class, () -> parse("- just\n- a\n- list\n"));
  }

  @Test
  void rejects_malformed_yaml() {
    assertThrows(GimleManifestException.class, () -> parse("name: [unclosed\n"));
  }

  @Test
  void rejects_a_seed_tenant_without_a_quota() {
    assertThrows(
        GimleManifestException.class,
        () ->
            parse(
                """
                name: bad
                seed:
                  tenants:
                    - id: acme
                """));
  }

  @Test
  void loads_the_committed_topology_resources() {
    final ClusterSpec minimal = ClusterTopologyParser.fromClasspath("topologies/minimal.yaml");
    assertEquals("minimal", minimal.name());
    assertEquals(1, minimal.storeReplicas());
    assertEquals(1, minimal.nodes().size());
    final ClusterSpec ha = ClusterTopologyParser.fromClasspath("topologies/ha-plaintext.yaml");
    assertEquals("ha-plaintext", ha.name());
    assertEquals(3, ha.storeReplicas());
    assertEquals(2, ha.controlPlaneReplicas());
    assertTrue(ha.muninnEnabled());
  }

  @Test
  void rejects_a_missing_classpath_resource() {
    assertThrows(
        GimleManifestException.class,
        () -> ClusterTopologyParser.fromClasspath("topologies/no-such-topology.yaml"));
  }
}
