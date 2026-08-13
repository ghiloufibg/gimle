package com.gimle.mimir.manifest;

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

class DaemonSetManifestParserTest {

  private static InputStream yaml(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void parses_a_minimal_manifest_with_no_placement() {
    DaemonSetSpec spec =
        DaemonSetManifestParser.parse(
            yaml(
                """
                name: node-exporter
                module:
                  name: com.gimle.example.node-exporter
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/node-exporter-1.0.0.jar
                """));

    assertEquals("node-exporter", spec.name());
    assertEquals(
        new ModuleId("com.gimle.example.node-exporter", Version.parse("1.0.0")), spec.moduleId());
    assertEquals("/var/gimle/artifacts/node-exporter-1.0.0.jar", spec.artifactPath());
    assertEquals(PlacementConstraints.NONE, spec.placement());
    assertTrue(spec.tenantId().isEmpty());
    assertTrue(spec.artifactSha256().isEmpty());
  }

  @Test
  void parses_required_labels_and_never_sets_anti_affinity() {
    DaemonSetSpec spec =
        DaemonSetManifestParser.parse(
            yaml(
                """
                name: node-exporter
                module:
                  name: com.gimle.example.node-exporter
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/node-exporter-1.0.0.jar
                placement:
                  requiredLabels:
                    - gpu
                tenantId: acme
                """));

    assertEquals(Set.of("gpu"), spec.placement().requiredNodeLabels().orElseThrow());
    assertFalse(spec.placement().antiAffinityAcrossNodes());
    assertEquals("acme", spec.tenantId().orElseThrow());
  }

  @Test
  void placement_anti_affinity_field_is_rejected_outright() {
    assertThrows(
        GimleManifestException.class,
        () ->
            DaemonSetManifestParser.parse(
                yaml(
                    """
                    name: node-exporter
                    module:
                      name: com.gimle.example.node-exporter
                      version: 1.0.0
                    artifactPath: /var/gimle/artifacts/node-exporter-1.0.0.jar
                    placement:
                      antiAffinity: false
                    """)));
  }

  @Test
  void blank_tenant_id_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            DaemonSetManifestParser.parse(
                yaml(
                    """
                    name: node-exporter
                    module:
                      name: com.gimle.example.node-exporter
                      version: 1.0.0
                    artifactPath: /var/gimle/artifacts/node-exporter-1.0.0.jar
                    tenantId: "   "
                    """)));
  }

  @Test
  void missing_name_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            DaemonSetManifestParser.parse(
                yaml(
                    """
                    module:
                      name: com.gimle.example.node-exporter
                      version: 1.0.0
                    artifactPath: /var/gimle/artifacts/node-exporter-1.0.0.jar
                    """)));
  }

  @Test
  void missing_module_section_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            DaemonSetManifestParser.parse(
                yaml(
                    "name: node-exporter\nartifactPath:"
                        + " /var/gimle/artifacts/node-exporter-1.0.0.jar\n")));
  }

  @Test
  void missing_artifact_path_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            DaemonSetManifestParser.parse(
                yaml(
                    """
                    name: node-exporter
                    module:
                      name: com.gimle.example.node-exporter
                      version: 1.0.0
                    """)));
  }

  @Test
  void invalid_module_version_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            DaemonSetManifestParser.parse(
                yaml(
                    """
                    name: node-exporter
                    module:
                      name: com.gimle.example.node-exporter
                      version: not-a-version
                    artifactPath: /var/gimle/artifacts/node-exporter-1.0.0.jar
                    """)));
  }

  @Test
  void malformed_yaml_throws() {
    assertThrows(
        GimleManifestException.class,
        () -> DaemonSetManifestParser.parse(yaml("name: [unterminated")));
  }

  @Test
  void non_mapping_root_throws() {
    assertThrows(
        GimleManifestException.class,
        () -> DaemonSetManifestParser.parse(yaml("- just\n- a\n- list\n")));
  }
}
