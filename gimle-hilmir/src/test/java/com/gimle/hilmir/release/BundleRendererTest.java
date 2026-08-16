package com.gimle.hilmir.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundleRendererTest {

  @TempDir Path tempDir;

  private static Bundle parse(String yaml) {
    return BundleParser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void resolves_a_value_reference_from_the_bundles_own_defaults() {
    Bundle bundle =
        parse(
            """
            kind: Bundle
            name: x
            version: 1.0.0
            values: {apiToken: default-token}
            secrets:
              - {tenant: acme, key: api.token, value: "${values.apiToken}"}
            """);

    RenderedBundle rendered = BundleRenderer.render(bundle, bundle.values(), tempDir);

    assertEquals("default-token", rendered.secrets().get(0).value());
  }

  @Test
  void an_override_value_wins_over_the_bundles_own_default() {
    Bundle bundle =
        parse(
            """
            kind: Bundle
            name: x
            version: 1.0.0
            values: {apiToken: default-token}
            secrets:
              - {tenant: acme, key: api.token, value: "${values.apiToken}"}
            """);
    Map<String, String> overridden =
        ValueOverrides.merge(bundle.values(), Optional.empty(), List.of("apiToken=set-token"));

    RenderedBundle rendered = BundleRenderer.render(bundle, overridden, tempDir);

    assertEquals("set-token", rendered.secrets().get(0).value());
  }

  @Test
  void an_unresolved_reference_is_a_named_error_not_a_literal_string() {
    Bundle bundle =
        parse(
            """
            kind: Bundle
            name: x
            version: 1.0.0
            config:
              - {tenant: acme, key: greeting.prefix, value: "${values.missing}"}
            """);

    GimleManifestException e =
        assertThrows(
            GimleManifestException.class, () -> BundleRenderer.render(bundle, Map.of(), tempDir));
    assertTrue(e.getMessage().contains("values.missing"));
  }

  @Test
  void substitutes_inside_an_inline_workload_manifest_and_extracts_its_kind_and_name() {
    Bundle bundle =
        parse(
            """
            kind: Bundle
            name: x
            version: 1.0.0
            values: {greeting: Hello}
            workloads:
              - manifest: |
                  kind: Deployment
                  name: greeter
                  greeting: ${values.greeting}
            """);

    RenderedBundle rendered = BundleRenderer.render(bundle, bundle.values(), tempDir);

    RenderedWorkload workload = rendered.workloads().get(0);
    assertEquals("Deployment", workload.kind());
    assertEquals("greeter", workload.name());
    assertTrue(workload.yaml().contains("greeting: Hello"));
  }

  @Test
  void loads_a_file_referenced_workload_relative_to_the_bundle_directory() throws IOException {
    Files.writeString(
        tempDir.resolve("provider.yaml"),
        "kind: Deployment\nname: provider\n",
        StandardCharsets.UTF_8);
    Bundle bundle =
        parse(
            """
            kind: Bundle
            name: x
            version: 1.0.0
            workloads:
              - file: provider.yaml
            """);

    RenderedBundle rendered = BundleRenderer.render(bundle, Map.of(), tempDir);

    assertEquals("provider", rendered.workloads().get(0).name());
  }

  @Test
  void rejects_a_workload_manifest_with_no_top_level_kind() {
    Bundle bundle =
        parse(
            """
            kind: Bundle
            name: x
            version: 1.0.0
            workloads:
              - manifest: "name: greeter\\n"
            """);

    assertThrows(
        GimleManifestException.class, () -> BundleRenderer.render(bundle, Map.of(), tempDir));
  }
}
