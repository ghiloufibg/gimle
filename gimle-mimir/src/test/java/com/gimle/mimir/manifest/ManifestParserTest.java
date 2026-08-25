package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ArtifactReference;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code kind:} dispatch and the {@code apiVersion:} resolution layered on top of it --
 * field-level parsing for each kind's own shape is covered by {@link DeploymentManifestParserTest}
 * and {@link JobManifestParserTest} against their own {@code parseRoot}, which this class delegates
 * to without re-testing.
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
                    """))
            .spec();

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
                    """))
            .spec();

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
                    """))
            .spec();

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
                    """))
            .spec();

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
                    """))
            .spec();

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

  @Test
  void an_explicit_v1alpha1_parses_identically_to_an_unversioned_manifest() {
    String body =
        """
        name: orders-service
        module:
          name: com.gimle.example.orders
          version: 1.2.0
        artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
        replicas: 3
        """;

    ParsedManifest unversioned = ManifestParser.parse(yaml("kind: Deployment\n" + body));
    ParsedManifest explicit =
        ManifestParser.parse(yaml("apiVersion: v1alpha1\nkind: Deployment\n" + body));

    assertEquals(unversioned.spec(), explicit.spec());
    assertEquals(unversioned.warnings(), explicit.warnings());
  }

  @Test
  void a_v1alpha1_local_artifact_path_yields_a_deprecation_warning() {
    ParsedManifest parsed =
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

    assertEquals(1, parsed.warnings().size());
    assertTrue(parsed.warnings().get(0).contains("deprecated"), parsed.warnings().get(0));
  }

  @Test
  void a_coordinate_only_manifest_yields_no_warnings() {
    ParsedManifest parsed =
        ManifestParser.parse(
            yaml(
                """
                kind: Deployment
                name: orders-service
                module:
                  name: com.gimle.example.orders
                  version: 1.2.0
                replicas: 3
                """));

    assertEquals(0, parsed.warnings().size());
  }

  @Test
  void a_v1_coordinate_only_manifest_parses_to_the_registry_reference() {
    ParsedManifest parsed =
        ManifestParser.parse(
            yaml(
                """
                apiVersion: v1
                kind: Deployment
                name: orders-service
                module:
                  name: com.gimle.example.orders
                  version: 1.2.0
                replicas: 3
                """));

    DeploymentSpec deployment = assertInstanceOf(DeploymentSpec.class, parsed.spec());
    assertTrue(ArtifactReference.isRegistryCoordinate(deployment.artifactPath()));
    assertEquals(0, parsed.warnings().size());
  }

  @Test
  void a_v1_manifest_declaring_artifact_path_is_rejected() {
    GimleManifestException failure =
        assertThrows(
            GimleManifestException.class,
            () ->
                ManifestParser.parse(
                    yaml(
                        """
                        apiVersion: v1
                        kind: Deployment
                        name: orders-service
                        module:
                          name: com.gimle.example.orders
                          version: 1.2.0
                        artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
                        replicas: 3
                        """)));
    assertTrue(
        failure.getMessage().contains("not accepted in apiVersion v1"), failure.getMessage());
  }

  @Test
  void a_v1_manifest_with_a_blank_artifact_path_is_rejected_on_presence_alone() {
    GimleManifestException failure =
        assertThrows(
            GimleManifestException.class,
            () ->
                ManifestParser.parse(
                    yaml(
                        """
                        apiVersion: v1
                        kind: Deployment
                        name: orders-service
                        module:
                          name: com.gimle.example.orders
                          version: 1.2.0
                        artifactPath: ""
                        replicas: 3
                        """)));
    assertTrue(
        failure.getMessage().contains("not accepted in apiVersion v1"), failure.getMessage());
  }

  @Test
  void every_other_workload_kind_rejects_artifact_path_under_v1_too() {
    // Job/DaemonSet/StatefulSet share optionalArtifactPath with Deployment, but each kind's own
    // parseRoot must actually thread the resolved version through -- a kind passing a hardcoded
    // alpha would pass the Deployment-only test above and still be wrong.
    String job =
        """
        apiVersion: v1
        kind: Job
        name: nightly-cleanup
        module:
          name: com.gimle.example.cleanup
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
        """;
    String daemonSet =
        """
        apiVersion: v1
        kind: DaemonSet
        name: node-exporter
        module:
          name: com.gimle.example.node-exporter
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/node-exporter-1.0.0.jar
        """;
    String statefulSet =
        """
        apiVersion: v1
        kind: StatefulSet
        name: orders-statefulset
        module:
          name: com.gimle.example.orders
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
        replicas: 3
        """;
    for (String manifest : java.util.List.of(job, daemonSet, statefulSet)) {
      GimleManifestException failure =
          assertThrows(
              GimleManifestException.class, () -> ManifestParser.parse(yaml(manifest)), manifest);
      assertTrue(
          failure.getMessage().contains("not accepted in apiVersion v1"), failure.getMessage());
    }
  }

  @Test
  void a_v1_cronjob_job_template_declaring_artifact_path_is_rejected() {
    GimleManifestException failure =
        assertThrows(
            GimleManifestException.class,
            () ->
                ManifestParser.parse(
                    yaml(
                        """
                        apiVersion: v1
                        kind: CronJob
                        name: nightly-cleanup
                        schedule: "0 2 * * *"
                        jobTemplate:
                          module:
                            name: com.gimle.example.cleanup
                            version: 1.0.0
                          artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
                        """)));
    assertTrue(
        failure.getMessage().contains("not accepted in apiVersion v1"), failure.getMessage());
  }

  @Test
  void an_unknown_api_version_is_rejected_naming_the_supported_set() {
    GimleManifestException failure =
        assertThrows(
            GimleManifestException.class,
            () ->
                ManifestParser.parse(
                    yaml(
                        """
                        apiVersion: v3
                        kind: Deployment
                        name: orders-service
                        module:
                          name: com.gimle.example.orders
                          version: 1.2.0
                        replicas: 3
                        """)));
    assertTrue(failure.getMessage().contains("unsupported apiVersion 'v3'"), failure.getMessage());
    assertTrue(failure.getMessage().contains("v1alpha1 (default when omitted)"));
    assertTrue(failure.getMessage().contains("kind Deployment"), failure.getMessage());
  }

  @Test
  void api_version_matching_is_exact_and_case_sensitive() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ManifestParser.parse(
                yaml(
                    """
                    apiVersion: V1
                    kind: Deployment
                    name: orders-service
                    module:
                      name: com.gimle.example.orders
                      version: 1.2.0
                    replicas: 3
                    """)));
  }

  @Test
  void a_blank_api_version_throws_rather_than_defaulting() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ManifestParser.parse(
                yaml(
                    """
                    apiVersion: "  "
                    kind: Deployment
                    name: orders-service
                    module:
                      name: com.gimle.example.orders
                      version: 1.2.0
                    replicas: 3
                    """)));
  }

  @Test
  void a_non_string_api_version_throws() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ManifestParser.parse(
                yaml(
                    """
                    apiVersion: 1
                    kind: Deployment
                    name: orders-service
                    module:
                      name: com.gimle.example.orders
                      version: 1.2.0
                    replicas: 3
                    """)));
  }
}
