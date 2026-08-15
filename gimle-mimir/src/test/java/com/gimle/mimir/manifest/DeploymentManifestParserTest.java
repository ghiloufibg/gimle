package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ArtifactReference;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.vessel.VesselEnvValue;
import com.gimle.core.vessel.VesselProbeSpec;
import com.gimle.core.vessel.VesselSpec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalInt;
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
  void missing_artifact_path_parses_as_a_registry_coordinate() {
    var spec =
        DeploymentManifestParser.parse(
            yaml(
                """
                    name: orders-service
                    module:
                      name: com.gimle.example.orders
                      version: 1.2.0
                    replicas: 1
                    """));

    assertTrue(ArtifactReference.isRegistryCoordinate(spec.artifactPath()));
  }

  @Test
  void explicitly_blank_artifact_path_throws() {
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
                    artifactPath: ""
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

  @Test
  void absent_disruption_block_defaults_to_empty() {
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

    assertTrue(spec.disruption().isEmpty());
    assertEquals(1, spec.effectiveDisruptionBudget().maxUnavailable());
    assertEquals(0, spec.effectiveDisruptionBudget().maxSurge());
  }

  @Test
  void parses_a_disruption_block_with_only_max_unavailable() {
    DeploymentSpec spec =
        DeploymentManifestParser.parse(
            yaml(
                """
                name: orders-service
                module:
                  name: com.gimle.example.orders
                  version: 1.2.0
                artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
                replicas: 5
                disruption:
                  maxUnavailable: 3
                """));

    DisruptionBudget budget = spec.disruption().orElseThrow();
    assertEquals(3, budget.maxUnavailable());
    assertEquals(0, budget.maxSurge());
  }

  @Test
  void disruption_max_unavailable_defaults_to_1_if_the_block_is_present_but_the_key_is_absent() {
    DeploymentSpec spec =
        DeploymentManifestParser.parse(
            yaml(
                """
                name: orders-service
                module:
                  name: com.gimle.example.orders
                  version: 1.2.0
                artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
                replicas: 5
                disruption: {}
                """));

    assertEquals(1, spec.disruption().orElseThrow().maxUnavailable());
  }

  @Test
  void accepts_a_nonzero_max_surge() {
    DeploymentSpec spec =
        DeploymentManifestParser.parse(
            yaml(
                """
                name: orders-service
                module:
                  name: com.gimle.example.orders
                  version: 1.2.0
                artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
                replicas: 5
                disruption:
                  maxUnavailable: 2
                  maxSurge: 1
                """));

    DisruptionBudget budget = spec.disruption().orElseThrow();
    assertEquals(2, budget.maxUnavailable());
    assertEquals(1, budget.maxSurge());
  }

  @Test
  void accepts_an_explicit_max_surge_of_0() {
    DeploymentSpec spec =
        DeploymentManifestParser.parse(
            yaml(
                """
                name: orders-service
                module:
                  name: com.gimle.example.orders
                  version: 1.2.0
                artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
                replicas: 5
                disruption:
                  maxUnavailable: 2
                  maxSurge: 0
                """));

    assertEquals(0, spec.disruption().orElseThrow().maxSurge());
  }

  @Test
  void rejects_a_disruption_block_that_is_not_a_mapping() {
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
                    disruption: not-a-mapping
                    """)));
  }

  @Test
  void rejects_max_unavailable_0_with_no_max_surge_to_rescue_it() {
    // maxSurge defaults to 0 when omitted, so this is the {maxUnavailable: 0, maxSurge: 0}
    // combination DisruptionBudget's own compact constructor rejects -- "never replace anything."
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
                    disruption:
                      maxUnavailable: 0
                    """)));
  }

  @Test
  void accepts_max_unavailable_0_paired_with_a_nonzero_max_surge_for_a_pure_surge_rollout() {
    DeploymentSpec spec =
        DeploymentManifestParser.parse(
            yaml(
                """
                name: orders-service
                module:
                  name: com.gimle.example.orders
                  version: 1.2.0
                artifactPath: /var/gimle/artifacts/orders-1.2.0.jar
                replicas: 5
                disruption:
                  maxUnavailable: 0
                  maxSurge: 2
                """));

    DisruptionBudget budget = spec.disruption().orElseThrow();
    assertEquals(0, budget.maxUnavailable());
    assertEquals(2, budget.maxSurge());
  }

  @Test
  void absent_vessel_block_parses_as_module_hosted() {
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
                """));

    assertTrue(spec.vessel().isEmpty());
  }

  @Test
  void parses_a_full_vessel_block() {
    DeploymentSpec spec =
        DeploymentManifestParser.parse(
            yaml(
                """
                name: billing-api
                module:
                  name: com.acme.billing-api
                  version: 2.3.1
                artifactPath: /var/gimle/artifacts/billing-api-2.3.1.jar
                replicas: 3
                vessel:
                  args: ["--spring.profiles.active=prod"]
                  jvmFlags: ["-XX:+UseZGC"]
                  env:
                    DB_PASSWORD: {secret: db.password}
                    HTTP_PORT: {port: dynamic}
                    FIXED_PORT: {port: 9000}
                    LOG_LEVEL: INFO
                  files:
                    - {path: conf/application.yaml, config: billing.app-config}
                  probes:
                    liveness: {http: /actuator/health/liveness, initialDelaySeconds: 20}
                    readiness: {tcp: true}
                  resources:
                    request: {memory: 512Mi, cpu: 250m}
                    limit: {memory: 1Gi, cpu: 1000m}
                """));

    VesselSpec vessel = spec.vessel().orElseThrow();
    assertEquals(List.of("--spring.profiles.active=prod"), vessel.args());
    assertEquals(List.of("-XX:+UseZGC"), vessel.jvmFlags());
    assertEquals(new VesselEnvValue.SecretRef("db.password"), vessel.env().get("DB_PASSWORD"));
    assertEquals(
        new VesselEnvValue.PortAllocation(OptionalInt.empty()), vessel.env().get("HTTP_PORT"));
    assertEquals(
        new VesselEnvValue.PortAllocation(OptionalInt.of(9000)), vessel.env().get("FIXED_PORT"));
    assertEquals(new VesselEnvValue.Literal("INFO"), vessel.env().get("LOG_LEVEL"));
    assertEquals(1, vessel.files().size());
    assertEquals("conf/application.yaml", vessel.files().get(0).path());
    assertEquals("billing.app-config", vessel.files().get(0).configKey());
    VesselProbeSpec.Http liveness = (VesselProbeSpec.Http) vessel.probes().liveness().orElseThrow();
    assertEquals("/actuator/health/liveness", liveness.path());
    assertEquals(20, liveness.initialDelaySeconds());
    assertTrue(vessel.probes().readiness().orElseThrow() instanceof VesselProbeSpec.Tcp);
    assertEquals("512Mi", vessel.resourceRequest().memory());
    assertEquals("1Gi", vessel.resourceLimit().memory());
  }

  @Test
  void a_tcp_probe_with_no_declared_port_is_rejected() {
    assertThrows(
        GimleManifestException.class,
        () ->
            DeploymentManifestParser.parse(
                yaml(
                    """
                    name: billing-api
                    module:
                      name: com.acme.billing-api
                      version: 2.3.1
                    artifactPath: /var/gimle/artifacts/billing-api-2.3.1.jar
                    replicas: 1
                    vessel:
                      probes:
                        readiness: {tcp: true}
                      resources:
                        request: {memory: 512Mi, cpu: 250m}
                        limit: {memory: 1Gi, cpu: 1000m}
                    """)));
  }

  @Test
  void a_vessel_block_missing_resources_is_rejected() {
    assertThrows(
        GimleManifestException.class,
        () ->
            DeploymentManifestParser.parse(
                yaml(
                    """
                    name: billing-api
                    module:
                      name: com.acme.billing-api
                      version: 2.3.1
                    artifactPath: /var/gimle/artifacts/billing-api-2.3.1.jar
                    replicas: 1
                    vessel: {}
                    """)));
  }
}
