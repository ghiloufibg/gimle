package com.gimle.hilmir.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TopologyParserTest {

  private static Topology parse(final String yaml) {
    final InputStream in = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    return TopologyParser.parse(in);
  }

  @Test
  void parses_a_complete_topology_document() {
    final Topology topology =
        parse(
            """
            name: prod
            transport: mtls
            tls:
              materialDir: /etc/gimle/tls
            machines:
              - {name: m1, host: gimle-1.example.com}
              - {name: m2, host: gimle-2.example.com}
            runtime:
              javaExecutable: java
              classpath: /opt/gimle/lib/*
              dataRoot: /var/lib/gimle
            store:
              replicas:
                - {machine: m1, raftPort: 9080, clientPort: 9091}
                - {machine: m2}
            controlPlane:
              replicas:
                - {machine: m1, port: 8080}
            fafnir:
              keyFile: /etc/gimle/fafnir-secret.key
              replicas:
                - {machine: m1, port: 9092}
            muninn:
              replicas: []
            andvari:
              replicas: []
            agents:
              - {machine: m2, nodeId: node-a, gossipPort: 9090, labels: [ssd]}
            jvm:
              store: ["-Xmx256m"]
            """);
    assertEquals("prod", topology.name());
    assertEquals(Transport.MTLS, topology.transport());
    assertEquals(Path.of("/etc/gimle/tls"), topology.tls().orElseThrow().materialDir());
    assertEquals(
        List.of(new Machine("m1", "gimle-1.example.com"), new Machine("m2", "gimle-2.example.com")),
        topology.machines());
    assertEquals(Optional.of("java"), topology.runtime().javaExecutable());
    assertEquals(Optional.of("/opt/gimle/lib/*"), topology.runtime().classpath());
    assertEquals(Optional.of(Path.of("/var/lib/gimle")), topology.runtime().dataRoot());
    assertEquals(
        List.of(new StoreReplica("m1", 9080, 9091), new StoreReplica("m2", 9080, 9091)),
        topology.store().replicas());
    assertEquals(List.of(new ServiceReplica("m1", 8080)), topology.controlPlane().replicas());
    assertEquals(Optional.of(Path.of("/etc/gimle/fafnir-secret.key")), topology.fafnir().keyFile());
    assertEquals(List.of(new ServiceReplica("m1", 9092)), topology.fafnir().replicas());
    assertEquals(List.of(), topology.muninn().replicas());
    assertEquals(List.of(), topology.andvari().replicas());
    assertEquals(
        List.of(new AgentPlacement("m2", "node-a", 9090, List.of("ssd"))), topology.agents());
    assertEquals(List.of("-Xmx256m"), topology.jvmFlags(ProcessRole.STORE));
  }

  @Test
  void defaults_apply_when_optional_sections_are_absent() {
    final Topology topology = parse("name: bare\n");
    assertEquals(Transport.PLAINTEXT, topology.transport());
    assertTrue(topology.tls().isEmpty());
    assertEquals(List.of(), topology.machines());
    assertEquals(RuntimeSettings.EMPTY, topology.runtime());
    assertEquals(List.of(), topology.store().replicas());
    assertEquals(List.of(), topology.controlPlane().replicas());
    assertTrue(topology.fafnir().keyFile().isEmpty());
    assertEquals(List.of(), topology.fafnir().replicas());
    assertEquals(List.of(), topology.muninn().replicas());
    assertEquals(List.of(), topology.andvari().replicas());
    assertEquals(List.of(), topology.agents());
  }

  @Test
  void applies_the_documented_default_ports_when_a_replica_omits_them() {
    final Topology topology =
        parse(
            """
            name: defaults
            store:
              replicas:
                - {machine: m1}
            controlPlane:
              replicas:
                - {machine: m1}
            fafnir:
              keyFile: /key
              replicas:
                - {machine: m1}
            muninn:
              replicas:
                - {machine: m1}
            andvari:
              replicas:
                - {machine: m1}
            agents:
              - {machine: m1, nodeId: node-a}
            """);
    assertEquals(new StoreReplica("m1", 9080, 9091), topology.store().replicas().get(0));
    assertEquals(new ServiceReplica("m1", 8080), topology.controlPlane().replicas().get(0));
    assertEquals(new ServiceReplica("m1", 9092), topology.fafnir().replicas().get(0));
    assertEquals(new ServiceReplica("m1", 9093), topology.muninn().replicas().get(0));
    assertEquals(new ServiceReplica("m1", 9094), topology.andvari().replicas().get(0));
    assertEquals(9090, topology.agents().get(0).gossipPort());
  }

  @Test
  void rejects_a_missing_name() {
    final GimleManifestException e =
        assertThrows(GimleManifestException.class, () -> parse("machines: []\n"));
    assertTrue(e.getMessage().contains("name"));
  }

  @Test
  void rejects_an_unknown_transport() {
    final GimleManifestException e =
        assertThrows(GimleManifestException.class, () -> parse("name: bad\ntransport: quic\n"));
    assertTrue(e.getMessage().contains("quic"));
  }

  @Test
  void rejects_an_unknown_top_level_key() {
    final GimleManifestException e =
        assertThrows(GimleManifestException.class, () -> parse("name: bad\nsidecar: true\n"));
    assertTrue(e.getMessage().contains("sidecar"));
  }

  @Test
  void rejects_an_unknown_machine_key() {
    assertThrows(
        GimleManifestException.class,
        () -> parse("name: bad\nmachines:\n  - {name: m1, host: h1, zone: us-east}\n"));
  }

  @Test
  void rejects_an_unknown_store_replica_key() {
    assertThrows(
        GimleManifestException.class,
        () -> parse("name: bad\nstore:\n  replicas:\n    - {machine: m1, weight: 3}\n"));
  }

  @Test
  void rejects_an_unknown_fafnir_key() {
    assertThrows(GimleManifestException.class, () -> parse("name: bad\nfafnir:\n  onDisk: true\n"));
  }

  @Test
  void rejects_an_unknown_agent_key() {
    assertThrows(
        GimleManifestException.class,
        () -> parse("name: bad\nagents:\n  - {machine: m1, nodeId: n1, zone: us-east}\n"));
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
  void rejects_fafnir_replicas_without_a_key_file() {
    final GimleManifestException e =
        assertThrows(
            GimleManifestException.class,
            () -> parse("name: bad\nfafnir:\n  replicas:\n    - {machine: m1}\n"));
    assertTrue(e.getMessage().contains("keyFile"));
  }

  @Test
  void allows_fafnir_key_file_with_no_replicas() {
    final Topology topology = parse("name: ok\nfafnir:\n  keyFile: /etc/gimle/fafnir-secret.key\n");
    assertEquals(List.of(), topology.fafnir().replicas());
  }

  @Test
  void rejects_an_unknown_jvm_flags_role() {
    assertThrows(
        GimleManifestException.class, () -> parse("name: bad\njvm:\n  sidecar: [\"-Xmx1m\"]\n"));
  }

  @Test
  void rejects_a_machines_section_that_is_not_a_list() {
    assertThrows(GimleManifestException.class, () -> parse("name: bad\nmachines: everywhere\n"));
  }

  @Test
  void rejects_a_store_section_that_is_not_a_mapping() {
    assertThrows(GimleManifestException.class, () -> parse("name: bad\nstore: 3\n"));
  }

  @Test
  void rejects_a_non_numeric_port() {
    assertThrows(
        GimleManifestException.class,
        () -> parse("name: bad\nstore:\n  replicas:\n    - {machine: m1, raftPort: nine}\n"));
  }
}
