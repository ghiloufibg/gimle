package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.vessel.VesselSpec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StatefulSetManifestParserTest {

  private static InputStream yaml(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void parses_a_minimal_manifest() {
    StatefulSetSpec spec =
        StatefulSetManifestParser.parse(
            yaml(
                """
                name: orders-statefulset
                module:
                  name: com.gimle.example.orders
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
                replicas: 3
                """));

    assertEquals("orders-statefulset", spec.name());
    assertEquals(new ModuleId("com.gimle.example.orders", Version.parse("1.0.0")), spec.moduleId());
    assertEquals("/var/gimle/artifacts/orders-1.0.0.jar", spec.artifactPath());
    assertEquals(3, spec.replicas());
    assertEquals(PlacementConstraints.NONE, spec.placement());
    assertTrue(spec.tenantId().isEmpty());
    assertTrue(spec.artifactSha256().isEmpty());
  }

  @Test
  void parses_placement_with_anti_affinity_and_required_labels() {
    // Unlike DaemonSet, antiAffinity is a perfectly ordinary field here -- spreading replicas
    // across distinct nodes is meaningful for a StatefulSet the same way it is for a Deployment.
    StatefulSetSpec spec =
        StatefulSetManifestParser.parse(
            yaml(
                """
                name: orders-statefulset
                module:
                  name: com.gimle.example.orders
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
                replicas: 3
                placement:
                  antiAffinity: true
                  requiredLabels:
                    - ssd
                """));

    assertTrue(spec.placement().antiAffinityAcrossNodes());
    assertEquals(Set.of("ssd"), spec.placement().requiredNodeLabels().orElseThrow());
  }

  @Test
  void zero_replicas_is_legal() {
    StatefulSetSpec spec =
        StatefulSetManifestParser.parse(
            yaml(
                """
                name: orders-statefulset
                module:
                  name: com.gimle.example.orders
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
                replicas: 0
                """));

    assertEquals(0, spec.replicas());
  }

  @Test
  void negative_replicas_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            StatefulSetManifestParser.parse(
                yaml(
                    """
                    name: orders-statefulset
                    module:
                      name: com.gimle.example.orders
                      version: 1.0.0
                    artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
                    replicas: -1
                    """)));
  }

  @Test
  void missing_replicas_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            StatefulSetManifestParser.parse(
                yaml(
                    """
                    name: orders-statefulset
                    module:
                      name: com.gimle.example.orders
                      version: 1.0.0
                    artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
                    """)));
  }

  @Test
  void blank_tenant_id_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            StatefulSetManifestParser.parse(
                yaml(
                    """
                    name: orders-statefulset
                    module:
                      name: com.gimle.example.orders
                      version: 1.0.0
                    artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
                    replicas: 3
                    tenantId: "   "
                    """)));
  }

  @Test
  void missing_name_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            StatefulSetManifestParser.parse(
                yaml(
                    """
                    module:
                      name: com.gimle.example.orders
                      version: 1.0.0
                    artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
                    replicas: 3
                    """)));
  }

  @Test
  void missing_module_section_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            StatefulSetManifestParser.parse(
                yaml(
                    "name: orders-statefulset\nartifactPath:"
                        + " /var/gimle/artifacts/orders-1.0.0.jar\nreplicas: 3\n")));
  }

  @Test
  void missing_artifact_path_parses_as_a_registry_coordinate() {
    var spec =
        StatefulSetManifestParser.parse(
            yaml(
                """
                    name: orders-statefulset
                    module:
                      name: com.gimle.example.orders
                      version: 1.0.0
                    replicas: 3
                    """));

    assertTrue(ArtifactReference.isRegistryCoordinate(spec.artifactPath()));
  }

  @Test
  void explicitly_blank_artifact_path_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            StatefulSetManifestParser.parse(
                yaml(
                    """
                    name: orders-statefulset
                    module:
                      name: com.gimle.example.orders
                      version: 1.0.0
                    replicas: 3
                    artifactPath: ""
                    """)));
  }

  @Test
  void invalid_module_version_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            StatefulSetManifestParser.parse(
                yaml(
                    """
                    name: orders-statefulset
                    module:
                      name: com.gimle.example.orders
                      version: not-a-version
                    artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
                    replicas: 3
                    """)));
  }

  @Test
  void malformed_yaml_throws() {
    assertThrows(
        GimleManifestException.class,
        () -> StatefulSetManifestParser.parse(yaml("name: [unterminated")));
  }

  @Test
  void non_mapping_root_throws() {
    assertThrows(
        GimleManifestException.class,
        () -> StatefulSetManifestParser.parse(yaml("- just\n- a\n- list\n")));
  }

  @Test
  void parses_a_vessel_block() {
    StatefulSetSpec spec =
        StatefulSetManifestParser.parse(
            yaml(
                """
                name: cache-nodes
                module:
                  name: com.acme.cache-node
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/cache-node-1.0.0.jar
                replicas: 3
                vessel:
                  env:
                    GOSSIP_PORT: {port: dynamic}
                  probes:
                    readiness: {tcp: true}
                  resources:
                    request: {memory: 256Mi, cpu: 100m}
                    limit: {memory: 512Mi, cpu: 500m}
                """));

    VesselSpec vessel = spec.vessel().orElseThrow();
    assertEquals(Optional.of("GOSSIP_PORT"), vessel.firstDeclaredPortName());
    assertEquals("256Mi", vessel.resourceRequest().memory());
  }

  @Test
  void absent_vessel_block_parses_as_module_hosted() {
    StatefulSetSpec spec =
        StatefulSetManifestParser.parse(
            yaml(
                """
                name: orders-statefulset
                module:
                  name: com.gimle.example.orders
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
                replicas: 1
                """));

    assertTrue(spec.vessel().isEmpty());
  }
}
