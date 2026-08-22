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
        manifest.modules().stream().map(ArtifactSetModuleEntry::artifact).toList());
    assertEquals(
        List.of(Optional.of("orders-platform"), Optional.of("orders-platform")),
        manifest.modules().stream().map(ArtifactSetModuleEntry::tenantId).toList());
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
        manifest.modules().stream().map(ArtifactSetModuleEntry::tenantId).toList());
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
}
