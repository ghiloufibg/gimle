package com.gimle.holmgang.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.core.exception.GimleManifestException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClusterTopologyDslTest {

  @Test
  void the_builder_produces_the_same_spec_as_the_equivalent_yaml() {
    final ClusterSpec fromDsl =
        ClusterTopology.named("ha")
            .transport(Transport.PLAINTEXT)
            .stores(3)
            .controlPlanes(2)
            .fafnirs(2)
            .muninn()
            .nodes(2)
            .jvmFlags(ProcessRole.STORE, "-Xmx256m")
            .seedTenant("acme", QuotaSpec.of(2_147_483_648L, 4000, 8))
            .build();
    final ClusterSpec fromYaml =
        ClusterTopologyParser.parse(
            new ByteArrayInputStream(
                """
                name: ha
                store:
                  replicas: 3
                controlPlane:
                  replicas: 2
                fafnir:
                  replicas: 2
                muninn:
                  enabled: true
                nodes:
                  count: 2
                jvm:
                  store: ["-Xmx256m"]
                seed:
                  tenants:
                    - id: acme
                      quota:
                        maxMemoryBytes: 2147483648
                        maxCpuMillicores: 4000
                        maxInstances: 8
                """
                    .getBytes(StandardCharsets.UTF_8)));
    assertEquals(fromYaml, fromDsl);
  }

  @Test
  void explicit_nodes_carry_their_labels() {
    final ClusterSpec spec =
        ClusterTopology.named("labeled").node("gpu-node", "gpu", "ssd").build();
    assertEquals(List.of(new NodeSpec("gpu-node", List.of("gpu", "ssd"))), spec.nodes());
  }

  @Test
  void the_builder_rejects_zero_control_planes() {
    assertThrows(
        GimleManifestException.class, () -> ClusterTopology.named("bad").controlPlanes(0).build());
  }

  @Test
  void the_builder_rejects_duplicate_node_ids() {
    assertThrows(
        GimleManifestException.class,
        () -> ClusterTopology.named("bad").node("node-a").node("node-a").build());
  }
}
