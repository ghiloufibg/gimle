package com.gimle.module.artifactset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The manifest's {@code tenant:}/{@code modules:} grouping flattened into one ordered list --
 * resolution, ordering, and the ownership-must-be-unambiguous duplicate-path rule.
 */
class ArtifactSetManifestParserTest {

  private static final Path MANIFEST = Path.of("/repo/gimle-examples/orders-platform/bundle.yaml");

  private static byte[] yaml(String content) {
    return content.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void tenant_paths_resolve_relative_to_the_manifest_directory_and_preserve_order() {
    ArtifactSetManifest manifest =
        ArtifactSetManifestParser.parse(
            MANIFEST,
            yaml(
                """
                kind: ArtifactSet
                tenant:
                  orders-platform:
                    - orders-service/target/orders-service-1.0.0.jar
                    - inventory-service/target/inventory-service-1.0.0.jar
                """));

    assertEquals(
        List.of(
            Path.of(
                "/repo/gimle-examples/orders-platform/orders-service/target/orders-service-1.0.0.jar"),
            Path.of(
                "/repo/gimle-examples/orders-platform/inventory-service/target"
                    + "/inventory-service-1.0.0.jar")),
        manifest.modules().stream().map(ArtifactSetEntry::artifact).toList());
    assertEquals(
        List.of(Optional.of("orders-platform"), Optional.of("orders-platform")),
        manifest.modules().stream().map(ArtifactSetEntry::tenantId).toList());
  }

  @Test
  void untenanted_modules_carry_an_empty_tenant_id() {
    ArtifactSetManifest manifest =
        ArtifactSetManifestParser.parse(
            MANIFEST,
            yaml(
                """
                kind: ArtifactSet
                modules:
                  - shared-lib/target/shared-lib-1.0.0.jar
                """));

    assertEquals(Optional.empty(), manifest.modules().get(0).tenantId());
  }

  @Test
  void push_order_is_tenant_map_order_then_each_list_then_untenanted_modules() {
    ArtifactSetManifest manifest =
        ArtifactSetManifestParser.parse(
            MANIFEST,
            yaml(
                """
                kind: ArtifactSet
                tenant:
                  orders-platform:
                    - a.jar
                    - b.jar
                  billing:
                    - c.jar
                modules:
                  - d.jar
                """));

    assertEquals(
        List.of("a.jar", "b.jar", "c.jar", "d.jar"),
        manifest.modules().stream().map(m -> m.artifact().getFileName().toString()).toList());
  }

  @Test
  void a_bundle_may_carry_more_than_one_tenants_modules() {
    ArtifactSetManifest manifest =
        ArtifactSetManifestParser.parse(
            MANIFEST,
            yaml(
                """
                kind: ArtifactSet
                tenant:
                  orders-platform:
                    - a.jar
                  billing:
                    - b.jar
                """));

    assertEquals(
        List.of(Optional.of("orders-platform"), Optional.of("billing")),
        manifest.modules().stream().map(ArtifactSetEntry::tenantId).toList());
  }

  @Test
  void the_same_path_under_two_different_tenants_is_rejected() {
    GimleManifestException exception =
        assertThrows(
            GimleManifestException.class,
            () ->
                ArtifactSetManifestParser.parse(
                    MANIFEST,
                    yaml(
                        """
                        kind: ArtifactSet
                        tenant:
                          orders-platform:
                            - shared.jar
                          billing:
                            - shared.jar
                        """)));

    assertTrue(exception.getMessage().contains("shared.jar"));
  }

  @Test
  void the_same_path_under_both_a_tenant_and_modules_is_rejected() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ArtifactSetManifestParser.parse(
                MANIFEST,
                yaml(
                    """
                    kind: ArtifactSet
                    tenant:
                      orders-platform:
                        - shared.jar
                    modules:
                      - shared.jar
                    """)));
  }

  @Test
  void a_bundle_naming_no_modules_at_all_is_rejected() {
    assertThrows(
        GimleManifestException.class,
        () -> ArtifactSetManifestParser.parse(MANIFEST, yaml("kind: ArtifactSet\n")));
  }

  @Test
  void a_non_mapping_root_is_rejected() {
    assertThrows(
        GimleManifestException.class,
        () -> ArtifactSetManifestParser.parse(MANIFEST, yaml("- just\n- a\n- list\n")));
  }

  @Test
  void malformed_yaml_is_rejected_with_a_manifest_exception_not_a_snakeyaml_one() {
    assertThrows(
        GimleManifestException.class,
        () -> ArtifactSetManifestParser.parse(MANIFEST, yaml("tenant: [this is not a mapping")));
  }

  @Test
  void tenant_must_be_a_mapping_not_a_list() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ArtifactSetManifestParser.parse(
                MANIFEST, yaml("kind: ArtifactSet\ntenant:\n  - not-a-mapping\n")));
  }

  @Test
  void a_manifest_with_no_directory_component_resolves_relative_to_the_working_directory() {
    ArtifactSetManifest manifest =
        ArtifactSetManifestParser.parse(
            Path.of("bundle.yaml"), yaml("kind: ArtifactSet\nmodules:\n  - app.jar\n"));

    assertEquals(Path.of("app.jar"), manifest.modules().get(0).artifact());
  }

  @Test
  void a_vessel_mapping_entry_parses_with_its_explicit_coordinate() {
    ArtifactSetManifest manifest =
        ArtifactSetManifestParser.parse(
            MANIFEST,
            yaml(
                """
                kind: ArtifactSet
                tenant:
                  billing:
                    - artifact: billing/target/billing-1.0.0.jar
                      kind: vessel
                      name: com.acme.billing
                      version: 1.0.0
                """));

    ArtifactSetEntry.Vessel vessel = (ArtifactSetEntry.Vessel) manifest.modules().get(0);
    assertEquals("com.acme.billing", vessel.name());
    assertEquals("1.0.0", vessel.version());
    assertEquals(Optional.of("billing"), vessel.tenantId());
  }

  @Test
  void a_bundle_mapping_entry_parses_with_its_entrypoint() {
    ArtifactSetManifest manifest =
        ArtifactSetManifestParser.parse(
            MANIFEST,
            yaml(
                """
                kind: ArtifactSet
                modules:
                  - artifact: report/target/quarkus-app
                    kind: bundle
                    name: com.acme.report
                    version: 2.0.0
                    command: [java, -jar, quarkus-run.jar]
                    workdir: .
                """));

    ArtifactSetEntry.Bundle bundle = (ArtifactSetEntry.Bundle) manifest.modules().get(0);
    assertEquals("com.acme.report", bundle.name());
    assertEquals(List.of("java", "-jar", "quarkus-run.jar"), bundle.entrypoint().command());
    assertEquals(".", bundle.entrypoint().workdir());
  }

  @Test
  void a_mapping_entry_without_a_kind_is_rejected() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ArtifactSetManifestParser.parse(
                MANIFEST,
                yaml(
                    """
                    kind: ArtifactSet
                    modules:
                      - artifact: a.jar
                        name: com.acme.a
                        version: 1.0.0
                    """)));
  }

  @Test
  void an_unknown_entry_kind_is_rejected() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ArtifactSetManifestParser.parse(
                MANIFEST,
                yaml(
                    """
                    kind: ArtifactSet
                    modules:
                      - artifact: a.tar
                        kind: tarball
                        name: com.acme.a
                        version: 1.0.0
                    """)));
  }

  @Test
  void a_vessel_entry_declaring_a_command_is_rejected() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ArtifactSetManifestParser.parse(
                MANIFEST,
                yaml(
                    """
                    kind: ArtifactSet
                    modules:
                      - artifact: a.jar
                        kind: vessel
                        name: com.acme.a
                        version: 1.0.0
                        command: [java, -jar, a.jar]
                    """)));
  }

  @Test
  void a_bundle_entry_without_a_command_is_rejected() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ArtifactSetManifestParser.parse(
                MANIFEST,
                yaml(
                    """
                    kind: ArtifactSet
                    modules:
                      - artifact: report/target/quarkus-app
                        kind: bundle
                        name: com.acme.report
                        version: 2.0.0
                    """)));
  }

  @Test
  void a_bundle_entry_with_an_escaping_workdir_is_rejected() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ArtifactSetManifestParser.parse(
                MANIFEST,
                yaml(
                    """
                    kind: ArtifactSet
                    modules:
                      - artifact: report/target/quarkus-app
                        kind: bundle
                        name: com.acme.report
                        version: 2.0.0
                        command: [run]
                        workdir: ../outside
                    """)));
  }

  @Test
  void a_mapping_entry_colliding_with_a_bare_path_entry_is_rejected() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ArtifactSetManifestParser.parse(
                MANIFEST,
                yaml(
                    """
                    kind: ArtifactSet
                    modules:
                      - shared.jar
                      - artifact: shared.jar
                        kind: vessel
                        name: com.acme.shared
                        version: 1.0.0
                    """)));
  }

  @Test
  void v1_parses_identically_to_an_unversioned_manifest() {
    // ArtifactSet's v1 is a straight promotion of the alpha schema: artifact: entries are local
    // push inputs, so unlike the workload kinds nothing is deprecated between the two versions.
    String body =
        """
        kind: ArtifactSet
        modules:
          - shared-lib/target/shared-lib-1.0.0.jar
        """;

    ArtifactSetManifest unversioned = ArtifactSetManifestParser.parse(MANIFEST, yaml(body));
    ArtifactSetManifest v1 =
        ArtifactSetManifestParser.parse(MANIFEST, yaml("apiVersion: v1\n" + body));
    ArtifactSetManifest alpha =
        ArtifactSetManifestParser.parse(MANIFEST, yaml("apiVersion: v1alpha1\n" + body));

    assertEquals(unversioned, v1);
    assertEquals(unversioned, alpha);
  }

  @Test
  void an_unknown_api_version_throws_naming_the_kind() {
    GimleManifestException failure =
        assertThrows(
            GimleManifestException.class,
            () ->
                ArtifactSetManifestParser.parse(
                    MANIFEST,
                    yaml(
                        """
                        apiVersion: v2
                        kind: ArtifactSet
                        modules:
                          - shared-lib/target/shared-lib-1.0.0.jar
                        """)));
    assertTrue(failure.getMessage().contains("unsupported apiVersion 'v2'"), failure.getMessage());
    assertTrue(failure.getMessage().contains("kind ArtifactSet"), failure.getMessage());
  }

  @Test
  void a_blank_api_version_throws_rather_than_defaulting() {
    assertThrows(
        GimleManifestException.class,
        () ->
            ArtifactSetManifestParser.parse(
                MANIFEST,
                yaml(
                    """
                    apiVersion: " "
                    kind: ArtifactSet
                    modules:
                      - shared-lib/target/shared-lib-1.0.0.jar
                    """)));
  }
}
