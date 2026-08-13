package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JobManifestParserTest {

  private static InputStream yaml(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void parses_a_minimal_manifest_defaulting_backoff_limit_to_six() {
    JobSpec spec =
        JobManifestParser.parse(
            yaml(
                """
                name: nightly-cleanup
                module:
                  name: com.gimle.example.cleanup
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                """));

    assertEquals("nightly-cleanup", spec.name());
    assertEquals(
        new ModuleId("com.gimle.example.cleanup", Version.parse("1.0.0")), spec.moduleId());
    assertEquals("/var/gimle/artifacts/cleanup-1.0.0.jar", spec.artifactPath());
    assertEquals(6, spec.backoffLimit());
    assertEquals(PlacementConstraints.NONE, spec.placement());
    assertTrue(spec.activeDeadline().isEmpty());
    assertTrue(spec.tenantId().isEmpty());
    assertTrue(spec.artifactSha256().isEmpty());
  }

  @Test
  void parses_an_explicit_backoff_limit_and_active_deadline() {
    JobSpec spec =
        JobManifestParser.parse(
            yaml(
                """
                name: nightly-cleanup
                module:
                  name: com.gimle.example.cleanup
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                backoffLimit: 3
                activeDeadlineSeconds: 600
                tenantId: acme
                """));

    assertEquals(3, spec.backoffLimit());
    assertEquals(Duration.ofSeconds(600), spec.activeDeadline().orElseThrow());
    assertEquals("acme", spec.tenantId().orElseThrow());
  }

  @Test
  void parses_placement_with_anti_affinity_and_required_labels() {
    JobSpec spec =
        JobManifestParser.parse(
            yaml(
                """
                name: nightly-cleanup
                module:
                  name: com.gimle.example.cleanup
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                placement:
                  antiAffinity: true
                  requiredLabels:
                    - gpu
                """));

    assertTrue(spec.placement().antiAffinityAcrossNodes());
    assertEquals(Set.of("gpu"), spec.placement().requiredNodeLabels().orElseThrow());
  }

  @Test
  void zero_backoff_limit_is_legal() {
    JobSpec spec =
        JobManifestParser.parse(
            yaml(
                """
                name: nightly-cleanup
                module:
                  name: com.gimle.example.cleanup
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                backoffLimit: 0
                """));

    assertEquals(0, spec.backoffLimit());
  }

  @Test
  void negative_backoff_limit_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            JobManifestParser.parse(
                yaml(
                    """
                    name: nightly-cleanup
                    module:
                      name: com.gimle.example.cleanup
                      version: 1.0.0
                    artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                    backoffLimit: -1
                    """)));
  }

  @Test
  void zero_active_deadline_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            JobManifestParser.parse(
                yaml(
                    """
                    name: nightly-cleanup
                    module:
                      name: com.gimle.example.cleanup
                      version: 1.0.0
                    artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                    activeDeadlineSeconds: 0
                    """)));
  }

  @Test
  void blank_tenant_id_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            JobManifestParser.parse(
                yaml(
                    """
                    name: nightly-cleanup
                    module:
                      name: com.gimle.example.cleanup
                      version: 1.0.0
                    artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                    tenantId: "   "
                    """)));
  }

  @Test
  void missing_name_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            JobManifestParser.parse(
                yaml(
                    """
                    module:
                      name: com.gimle.example.cleanup
                      version: 1.0.0
                    artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                    """)));
  }

  @Test
  void missing_module_section_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            JobManifestParser.parse(
                yaml(
                    "name: nightly-cleanup\nartifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar\n")));
  }

  @Test
  void missing_artifact_path_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            JobManifestParser.parse(
                yaml(
                    """
                    name: nightly-cleanup
                    module:
                      name: com.gimle.example.cleanup
                      version: 1.0.0
                    """)));
  }

  @Test
  void invalid_module_version_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            JobManifestParser.parse(
                yaml(
                    """
                    name: nightly-cleanup
                    module:
                      name: com.gimle.example.cleanup
                      version: not-a-version
                    artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                    """)));
  }

  @Test
  void malformed_yaml_throws() {
    assertThrows(
        GimleManifestException.class, () -> JobManifestParser.parse(yaml("name: [unterminated")));
  }

  @Test
  void non_mapping_root_throws() {
    assertThrows(
        GimleManifestException.class, () -> JobManifestParser.parse(yaml("- just\n- a\n- list\n")));
  }
}
