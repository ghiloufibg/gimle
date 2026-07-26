package com.gimle.controlplane.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeploymentManifestParserTest {

  private static InputStream yaml(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void parses_a_minimal_manifest_with_no_placement_section() {
    DeploymentSpec spec =
        DeploymentManifestParser.parse(
            yaml(
                """
                name: orders-service
                module:
                  name: com.gimle.example.orders
                  version: 1.2.0
                artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
                replicas: 3
                """));

    assertEquals("orders-service", spec.name());
    assertEquals(new ModuleId("com.gimle.example.orders", Version.parse("1.2.0")), spec.moduleId());
    assertEquals("/var/gimle/artifacts/orders-1.2.0.jar", spec.artifactPath());
    assertEquals(3, spec.replicas());
    assertEquals(PlacementConstraints.NONE, spec.placement());
  }

  @Test
  void parses_placement_with_anti_affinity_and_required_labels() {
    DeploymentSpec spec =
        DeploymentManifestParser.parse(
            yaml(
                """
                name: orders-service
                module:
                  name: com.gimle.example.orders
                  version: 1.2.0
                artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
                replicas: 2
                placement:
                  antiAffinity: true
                  requiredLabels:
                    - zone-a
                    - gpu
                """));

    assertTrue(spec.placement().antiAffinityAcrossNodes());
    assertEquals(Set.of("zone-a", "gpu"), spec.placement().requiredNodeLabels().orElseThrow());
  }

  @Test
  void placement_anti_affinity_defaults_to_false_when_omitted() {
    DeploymentSpec spec =
        DeploymentManifestParser.parse(
            yaml(
                """
                name: orders-service
                module:
                  name: com.gimle.example.orders
                  version: 1.2.0
                artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
                replicas: 1
                placement:
                  requiredLabels: [zone-a]
                """));

    assertFalse(spec.placement().antiAffinityAcrossNodes());
  }

  @Test
  void replicas_of_zero_is_legal() {
    DeploymentSpec spec =
        DeploymentManifestParser.parse(
            yaml(
                """
                name: orders-service
                module:
                  name: com.gimle.example.orders
                  version: 1.2.0
                artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
                replicas: 0
                """));

    assertEquals(0, spec.replicas());
  }

  @Test
  void missing_name_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            DeploymentManifestParser.parse(
                yaml(
                    """
                    module:
                      name: com.gimle.example.orders
                      version: 1.2.0
                    artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
                    replicas: 1
                    """)));
  }

  @Test
  void missing_module_section_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            DeploymentManifestParser.parse(
                yaml(
                    "name: orders-service\nartifactPath: /var/gimle/artifacts/orders-1.2.0.jar\nreplicas: 1\n")));
  }

  @Test
  void missing_artifact_path_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            DeploymentManifestParser.parse(
                yaml(
                    """
                    name: orders-service
                    module:
                      name: com.gimle.example.orders
                      version: 1.2.0
                    replicas: 1
                    """)));
  }

  @Test
  void missing_replicas_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            DeploymentManifestParser.parse(
                yaml(
                    """
                    name: orders-service
                    module:
                      name: com.gimle.example.orders
                      version: 1.2.0
                    artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
                    """)));
  }

  @Test
  void negative_replicas_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            DeploymentManifestParser.parse(
                yaml(
                    """
                    name: orders-service
                    module:
                      name: com.gimle.example.orders
                      version: 1.2.0
                    artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
                    replicas: -1
                    """)));
  }

  @Test
  void invalid_module_version_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            DeploymentManifestParser.parse(
                yaml(
                    """
                    name: orders-service
                    module:
                      name: com.gimle.example.orders
                      version: not-a-version
                    artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
                    replicas: 1
                    """)));
  }

  @Test
  void malformed_yaml_throws() {
    assertThrows(
        GimleManifestException.class,
        () -> DeploymentManifestParser.parse(yaml("name: [unterminated")));
  }

  @Test
  void non_mapping_root_throws() {
    assertThrows(
        GimleManifestException.class,
        () -> DeploymentManifestParser.parse(yaml("- just\n- a\n- list\n")));
  }

  @Test
  void non_boolean_anti_affinity_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            DeploymentManifestParser.parse(
                yaml(
                    """
                    name: orders-service
                    module:
                      name: com.gimle.example.orders
                      version: 1.2.0
                    artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
                    replicas: 1
                    placement:
                      antiAffinity: yes-please
                    """)));
  }
}
