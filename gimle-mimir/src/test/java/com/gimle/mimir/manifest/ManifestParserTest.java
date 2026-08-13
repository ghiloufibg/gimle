package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.core.exception.GimleManifestException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Covers only the {@code kind:} dispatch itself -- field-level parsing for each kind's own shape is
 * covered by {@link DeploymentManifestParserTest} and {@link JobManifestParserTest} against their
 * own {@code parseRoot}, which this class delegates to without re-testing.
 */
class ManifestParserTest {

  private static InputStream yaml(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void kind_deployment_dispatches_to_deployment_manifest_parser() {
    WorkloadSpec spec =
        ManifestParser.parse(
            yaml(
                """
                kind: Deployment
                name: orders-service
                module:
                  name: com.gimle.example.orders
                  version: 1.2.0
                artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
                replicas: 3
                """));

    DeploymentSpec deployment = assertInstanceOf(DeploymentSpec.class, spec);
    assertEquals("orders-service", deployment.name());
  }

  @Test
  void kind_job_dispatches_to_job_manifest_parser() {
    WorkloadSpec spec =
        ManifestParser.parse(
            yaml(
                """
                kind: Job
                name: nightly-cleanup
                module:
                  name: com.gimle.example.cleanup
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                """));

    JobSpec job = assertInstanceOf(JobSpec.class, spec);
    assertEquals("nightly-cleanup", job.name());
  }

  @Test
  void kind_cronjob_dispatches_to_cronjob_manifest_parser() {
    WorkloadSpec spec =
        ManifestParser.parse(
            yaml(
                """
                kind: CronJob
                name: nightly-cleanup
                schedule: "0 2 * * *"
                jobTemplate:
                  module:
                    name: com.gimle.example.cleanup
                    version: 1.0.0
                  artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                """));

    CronJobSpec cronJob = assertInstanceOf(CronJobSpec.class, spec);
    assertEquals("nightly-cleanup", cronJob.name());
  }

  @Test
  void kind_daemonset_dispatches_to_daemonset_manifest_parser() {
    WorkloadSpec spec =
        ManifestParser.parse(
            yaml(
                """
                kind: DaemonSet
                name: node-exporter
                module:
                  name: com.gimle.example.node-exporter
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/node-exporter-1.0.0.jar
                """));

    DaemonSetSpec daemonSet = assertInstanceOf(DaemonSetSpec.class, spec);
    assertEquals("node-exporter", daemonSet.name());
  }

  @Test
  void kind_statefulset_dispatches_to_statefulset_manifest_parser() {
    WorkloadSpec spec =
        ManifestParser.parse(
            yaml(
                """
                kind: StatefulSet
                name: orders-statefulset
                module:
                  name: com.gimle.example.orders
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
                replicas: 3
                """));

    StatefulSetSpec statefulSet = assertInstanceOf(StatefulSetSpec.class, spec);
    assertEquals("orders-statefulset", statefulSet.name());
  }

  @Test
  void missing_kind_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ManifestParser.parse(
                yaml(
                    """
                    name: orders-service
                    module:
                      name: com.gimle.example.orders
                      version: 1.2.0
                    artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
                    replicas: 3
                    """)));
  }

  @Test
  void unrecognized_kind_throws() {
    // Every workload kind (Deployment/Job/CronJob/DaemonSet/StatefulSet) is dispatched above --
    // "Frobnicate" is a deliberately never-real kind name for this case,
    // rather than reusing a real one that would go stale silently the moment it's implemented.
    assertThrows(
        GimleManifestException.class,
        () ->
            ManifestParser.parse(
                yaml(
                    """
                    kind: Frobnicate
                    name: nightly-cleanup
                    """)));
  }

  @Test
  void blank_kind_throws() {
    assertThrows(GimleManifestException.class, () -> ManifestParser.parse(yaml("kind: \"  \"\n")));
  }

  @Test
  void malformed_yaml_throws() {
    assertThrows(
        GimleManifestException.class, () -> ManifestParser.parse(yaml("kind: [unterminated")));
  }

  @Test
  void non_mapping_root_throws() {
    assertThrows(
        GimleManifestException.class, () -> ManifestParser.parse(yaml("- just\n- a\n- list\n")));
  }
}
