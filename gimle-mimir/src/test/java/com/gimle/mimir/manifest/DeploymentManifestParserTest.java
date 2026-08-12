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
    assertTrue(spec.artifactSha256().isEmpty());
  }

  @Test
  void parses_an_artifact_sha256_when_present() {
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
                artifactSha256: %s
                """
                    .formatted("a".repeat(64))));

    assertEquals(Set.of(), spec.placement().requiredNodeLabels().orElse(Set.of()));
    assertEquals("a".repeat(64), spec.artifactSha256().orElseThrow());
  }

  @Test
  void blank_artifact_sha256_throws() {
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
                    artifactSha256: "   "
                    """)));
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

  @Test
  void parses_a_cpu_only_autoscale_block_with_the_three_new_fields_absent() {
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
                autoscale:
                  minReplicas: 1
                  maxReplicas: 5
                  targetCpuUtilizationPercent: 50
                """));

    AutoscalePolicy policy = spec.autoscale().orElseThrow();
    assertEquals(1, policy.minReplicas());
    assertEquals(5, policy.maxReplicas());
    assertEquals(50, policy.targetCpuUtilizationPercent());
    assertTrue(policy.targetRequestRatePerSecond().isEmpty());
    assertTrue(policy.targetErrorRatePercent().isEmpty());
    assertTrue(policy.targetQueueDepth().isEmpty());
  }

  @Test
  void parses_an_autoscale_block_with_all_six_fields_present() {
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
                autoscale:
                  minReplicas: 1
                  maxReplicas: 5
                  targetCpuUtilizationPercent: 50
                  targetRequestRatePerSecond: 10.5
                  targetErrorRatePercent: 5
                  targetQueueDepth: 20
                """));

    AutoscalePolicy policy = spec.autoscale().orElseThrow();
    assertEquals(10.5, policy.targetRequestRatePerSecond().orElseThrow());
    assertEquals(5.0, policy.targetErrorRatePercent().orElseThrow());
    assertEquals(20, policy.targetQueueDepth().orElseThrow());
  }

  @Test
  void parses_an_autoscale_block_with_only_a_subset_of_the_new_fields() {
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
                autoscale:
                  minReplicas: 1
                  maxReplicas: 5
                  targetCpuUtilizationPercent: 50
                  targetQueueDepth: 20
                """));

    AutoscalePolicy policy = spec.autoscale().orElseThrow();
    assertTrue(policy.targetRequestRatePerSecond().isEmpty());
    assertTrue(policy.targetErrorRatePercent().isEmpty());
    assertEquals(20, policy.targetQueueDepth().orElseThrow());
  }

  @Test
  void an_absent_mode_field_defaults_to_worst_signal_matching_pre_existing_manifests() {
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
                autoscale:
                  minReplicas: 1
                  maxReplicas: 5
                  targetCpuUtilizationPercent: 50
                  targetRequestRatePerSecond: 10.5
                  targetErrorRatePercent: 5
                  targetQueueDepth: 20
                """));

    AutoscalePolicy policy = spec.autoscale().orElseThrow();
    assertEquals(AutoscalePolicy.CombinationMode.WORST_SIGNAL, policy.combinationMode());
    assertTrue(policy.cpuWeight().isEmpty());
  }

  @Test
  void parses_a_weighted_autoscale_block_with_per_signal_weights() {
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
                autoscale:
                  minReplicas: 1
                  maxReplicas: 5
                  targetCpuUtilizationPercent: 50
                  targetRequestRatePerSecond: 10.5
                  mode: weighted
                  cpuWeight: 1.0
                  requestRateWeight: 3.0
                """));

    AutoscalePolicy policy = spec.autoscale().orElseThrow();
    assertEquals(AutoscalePolicy.CombinationMode.WEIGHTED, policy.combinationMode());
    assertEquals(1.0, policy.cpuWeight().orElseThrow());
    assertEquals(3.0, policy.requestRateWeight().orElseThrow());
    assertTrue(policy.errorRateWeight().isEmpty());
    assertTrue(policy.queueDepthWeight().isEmpty());
  }

  @Test
  void an_unrecognized_mode_value_throws() {
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
                    autoscale:
                      minReplicas: 1
                      maxReplicas: 5
                      targetCpuUtilizationPercent: 50
                      mode: bogus
                    """)));
  }

  @Test
  void zero_target_request_rate_throws() {
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
                    autoscale:
                      minReplicas: 1
                      maxReplicas: 5
                      targetCpuUtilizationPercent: 50
                      targetRequestRatePerSecond: 0
                    """)));
  }

  @Test
  void negative_target_error_rate_throws() {
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
                    autoscale:
                      minReplicas: 1
                      maxReplicas: 5
                      targetCpuUtilizationPercent: 50
                      targetErrorRatePercent: -5
                    """)));
  }

  @Test
  void zero_target_queue_depth_throws() {
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
                    autoscale:
                      minReplicas: 1
                      maxReplicas: 5
                      targetCpuUtilizationPercent: 50
                      targetQueueDepth: 0
                    """)));
  }

  @Test
  void non_numeric_target_request_rate_throws() {
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
                    autoscale:
                      minReplicas: 1
                      maxReplicas: 5
                      targetCpuUtilizationPercent: 50
                      targetRequestRatePerSecond: not-a-number
                    """)));
  }

  @Test
  void non_numeric_target_queue_depth_throws() {
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
                    autoscale:
                      minReplicas: 1
                      maxReplicas: 5
                      targetCpuUtilizationPercent: 50
                      targetQueueDepth: not-a-number
                    """)));
  }
}
