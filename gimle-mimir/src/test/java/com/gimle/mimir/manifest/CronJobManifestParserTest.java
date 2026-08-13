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

class CronJobManifestParserTest {

  private static InputStream yaml(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void parses_a_minimal_manifest_defaulting_backoff_limit_and_concurrency_policy() {
    CronJobSpec spec =
        CronJobManifestParser.parse(
            yaml(
                """
                name: nightly-cleanup
                schedule: "0 2 * * *"
                jobTemplate:
                  module:
                    name: com.gimle.example.cleanup
                    version: 1.0.0
                  artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                """));

    assertEquals("nightly-cleanup", spec.name());
    assertEquals("0 2 * * *", spec.schedule());
    assertEquals(
        new ModuleId("com.gimle.example.cleanup", Version.parse("1.0.0")),
        spec.jobTemplate().moduleId());
    assertEquals("/var/gimle/artifacts/cleanup-1.0.0.jar", spec.jobTemplate().artifactPath());
    assertEquals(6, spec.jobTemplate().backoffLimit());
    assertEquals(PlacementConstraints.NONE, spec.jobTemplate().placement());
    assertTrue(spec.jobTemplate().activeDeadline().isEmpty());
    assertTrue(spec.startingDeadline().isEmpty());
    assertEquals(ConcurrencyPolicy.ALLOW, spec.concurrencyPolicy());
    assertTrue(spec.tenantId().isEmpty());
  }

  @Test
  void parses_every_optional_field_when_present() {
    CronJobSpec spec =
        CronJobManifestParser.parse(
            yaml(
                """
                name: nightly-cleanup
                schedule: "*/15 * * * *"
                jobTemplate:
                  module:
                    name: com.gimle.example.cleanup
                    version: 1.0.0
                  artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                  backoffLimit: 3
                  activeDeadlineSeconds: 600
                  placement:
                    antiAffinity: true
                    requiredLabels: [gpu]
                startingDeadlineSeconds: 300
                concurrencyPolicy: Forbid
                tenantId: acme
                """));

    assertEquals(3, spec.jobTemplate().backoffLimit());
    assertEquals(Duration.ofSeconds(600), spec.jobTemplate().activeDeadline().orElseThrow());
    assertTrue(spec.jobTemplate().placement().antiAffinityAcrossNodes());
    assertEquals(Set.of("gpu"), spec.jobTemplate().placement().requiredNodeLabels().orElseThrow());
    assertEquals(Duration.ofSeconds(300), spec.startingDeadline().orElseThrow());
    assertEquals(ConcurrencyPolicy.FORBID, spec.concurrencyPolicy());
    assertEquals("acme", spec.tenantId().orElseThrow());
  }

  @Test
  void concurrency_policy_is_case_insensitive() {
    CronJobSpec spec =
        CronJobManifestParser.parse(
            yaml(
                """
                name: nightly-cleanup
                schedule: "0 2 * * *"
                jobTemplate:
                  module:
                    name: com.gimle.example.cleanup
                    version: 1.0.0
                  artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                concurrencyPolicy: replace
                """));

    assertEquals(ConcurrencyPolicy.REPLACE, spec.concurrencyPolicy());
  }

  @Test
  void invalid_cron_schedule_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            CronJobManifestParser.parse(
                yaml(
                    """
                    name: nightly-cleanup
                    schedule: "not a cron expression"
                    jobTemplate:
                      module:
                        name: com.gimle.example.cleanup
                        version: 1.0.0
                      artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                    """)));
  }

  @Test
  void unknown_concurrency_policy_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            CronJobManifestParser.parse(
                yaml(
                    """
                    name: nightly-cleanup
                    schedule: "0 2 * * *"
                    jobTemplate:
                      module:
                        name: com.gimle.example.cleanup
                        version: 1.0.0
                      artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                    concurrencyPolicy: Sometimes
                    """)));
  }

  @Test
  void missing_schedule_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            CronJobManifestParser.parse(
                yaml(
                    """
                    name: nightly-cleanup
                    jobTemplate:
                      module:
                        name: com.gimle.example.cleanup
                        version: 1.0.0
                      artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                    """)));
  }

  @Test
  void missing_job_template_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            CronJobManifestParser.parse(
                yaml(
                    """
                    name: nightly-cleanup
                    schedule: "0 2 * * *"
                    """)));
  }

  @Test
  void missing_job_template_module_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            CronJobManifestParser.parse(
                yaml(
                    """
                    name: nightly-cleanup
                    schedule: "0 2 * * *"
                    jobTemplate:
                      artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                    """)));
  }

  @Test
  void zero_starting_deadline_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            CronJobManifestParser.parse(
                yaml(
                    """
                    name: nightly-cleanup
                    schedule: "0 2 * * *"
                    jobTemplate:
                      module:
                        name: com.gimle.example.cleanup
                        version: 1.0.0
                      artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                    startingDeadlineSeconds: 0
                    """)));
  }

  @Test
  void negative_backoff_limit_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            CronJobManifestParser.parse(
                yaml(
                    """
                    name: nightly-cleanup
                    schedule: "0 2 * * *"
                    jobTemplate:
                      module:
                        name: com.gimle.example.cleanup
                        version: 1.0.0
                      artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                      backoffLimit: -1
                    """)));
  }

  @Test
  void malformed_yaml_throws() {
    assertThrows(
        GimleManifestException.class,
        () -> CronJobManifestParser.parse(yaml("name: [unterminated")));
  }

  @Test
  void non_mapping_root_throws() {
    assertThrows(
        GimleManifestException.class,
        () -> CronJobManifestParser.parse(yaml("- just\n- a\n- list\n")));
  }
}
