package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DaemonSetManifestParserTest {

  private static InputStream yaml(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The manifest shipped under {@code gimle-skald/deploy/} is the one a reader copies, so it has to
   * survive the same admission every other manifest does. It didn't: it left {@code artifactPath}
   * explicitly blank (refused, since blank is a typo rather than the registry sentinel) and named
   * no port at all, leaving its own HTTP probes with nothing to resolve against.
   */
  @Test
  void the_shipped_skald_daemonset_example_parses_and_its_probes_resolve_a_declared_port()
      throws Exception {
    Path example = Path.of("..", "gimle-skald", "deploy", "skald-daemonset.yaml");
    assertTrue(Files.exists(example), "shipped example missing: " + example.toAbsolutePath());

    DaemonSetSpec spec;
    try (InputStream in = Files.newInputStream(example)) {
      spec = DaemonSetManifestParser.parse(in);
    }

    assertEquals("skald", spec.name());
    assertEquals(ArtifactReference.REGISTRY_COORDINATE, spec.artifactPath());
    VesselSpec vessel = spec.vessel().orElseThrow();
    assertTrue(vessel.env().containsKey("SKALD_HEALTH_PORT"), vessel.env().keySet().toString());
    assertEquals(
        Optional.of("SKALD_HEALTH_PORT"), vessel.probes().liveness().orElseThrow().portName());
    assertEquals(
        Optional.of("SKALD_HEALTH_PORT"), vessel.probes().readiness().orElseThrow().portName());
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
    assertEquals(Optional.of("default"), spec.tenantId());
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
  void parses_placement_priority_even_though_anti_affinity_is_rejected_here() {
    // A DaemonSet instance is never itself a preemption victim, but it still has to be placeable
    // on a node that is already full -- so priority is meaningful here even where antiAffinity is
    // not, and dropping it would silently ignore what the manifest declared.
    DaemonSetSpec spec =
        DaemonSetManifestParser.parse(
            yaml(
                """
                name: skald
                module:
                  name: com.gimle.skald
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/skald-1.0.0.jar
                placement:
                  priority: 1000
                tenantId: gimle-system
                """));

    assertEquals(1000, spec.placement().priority());
  }

  @Test
  void placement_priority_defaults_to_zero_when_undeclared() {
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
                """));

    assertEquals(0, spec.placement().priority());
  }

  @Test
  void tolerate_all_taints_defaults_to_false() {
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

    assertFalse(spec.tolerateAllTaints());
  }

  @Test
  void tolerate_all_taints_is_parsed_when_set_true() {
    DaemonSetSpec spec =
        DaemonSetManifestParser.parse(
            yaml(
                """
                name: cluster-log-shipper
                module:
                  name: com.gimle.example.cluster-log-shipper
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/cluster-log-shipper-1.0.0.jar
                tolerateAllTaints: true
                """));

    assertTrue(spec.tolerateAllTaints());
  }

  @Test
  void tolerate_all_taints_rejects_a_non_boolean_value() {
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
                    tolerateAllTaints: "yes"
                    """)));
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
  void missing_artifact_path_parses_as_a_registry_coordinate() {
    var spec =
        DaemonSetManifestParser.parse(
            yaml(
                """
                    name: node-exporter
                    module:
                      name: com.gimle.example.node-exporter
                      version: 1.0.0
                    """));

    assertTrue(ArtifactReference.isRegistryCoordinate(spec.artifactPath()));
  }

  @Test
  void explicitly_blank_artifact_path_throws() {
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
                    artifactPath: ""
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

  @Test
  void absent_disruption_block_defaults_to_empty() {
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

    assertTrue(spec.disruption().isEmpty());
    assertEquals(1, spec.effectiveDisruptionBudget().maxUnavailable());
  }

  @Test
  void parses_a_disruption_block_with_max_unavailable() {
    DaemonSetSpec spec =
        DaemonSetManifestParser.parse(
            yaml(
                """
                name: node-exporter
                module:
                  name: com.gimle.example.node-exporter
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/node-exporter-1.0.0.jar
                disruption:
                  maxUnavailable: 2
                """));

    assertEquals(2, spec.disruption().orElseThrow().maxUnavailable());
    assertEquals(0, spec.disruption().orElseThrow().maxSurge());
  }

  @Test
  void disruption_max_surge_field_is_rejected_outright_if_nonzero() {
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
                    disruption:
                      maxUnavailable: 1
                      maxSurge: 1
                    """)));
  }

  @Test
  void disruption_max_surge_field_set_to_0_is_accepted() {
    // Unlike a nonzero value (rejected outright, see the test above), an explicit 0 is exactly
    // what omitting the field means -- accepted, not rejected, the same "0 is the explicit way to
    // say no surge" contract Deployment's own disruption.maxSurge documents.
    DaemonSetSpec spec =
        DaemonSetManifestParser.parse(
            yaml(
                """
                name: node-exporter
                module:
                  name: com.gimle.example.node-exporter
                  version: 1.0.0
                artifactPath: /var/gimle/artifacts/node-exporter-1.0.0.jar
                disruption:
                  maxUnavailable: 1
                  maxSurge: 0
                """));

    assertEquals(0, spec.disruption().orElseThrow().maxSurge());
  }

  @Test
  void rejects_a_max_unavailable_of_0() {
    // Unlike a Deployment manifest, there's no maxSurge to rescue this here -- DaemonSetSpec's
    // own maxSurge is always 0 (rejected outright if the manifest sets it nonzero), so 0 always
    // means "never replace anything."
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
                    disruption:
                      maxUnavailable: 0
                    """)));
  }
}
