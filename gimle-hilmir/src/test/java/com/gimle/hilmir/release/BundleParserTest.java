package com.gimle.hilmir.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleManifestException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BundleParserTest {

  private static final String HAPPY_PATH =
      """
      kind: Bundle
      name: greeter-suite
      version: 1.0.0
      values:
        apiToken: ""
      tenants:
        - id: acme
          quota: {maxMemoryBytes: 268435456, maxCpuMillicores: 1000, maxInstances: 10}
      config:
        - {tenant: acme, key: greeting.prefix, value: "Hello"}
      secrets:
        - {tenant: acme, key: api.token, value: "${values.apiToken}"}
      workloads:
        - file: provider-deployment.yaml
        - manifest: |
            kind: Deployment
            name: greeter-consumer
      """;

  private static Bundle parse(String yaml) {
    return BundleParser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void parses_every_section_of_a_well_formed_bundle() {
    Bundle bundle = parse(HAPPY_PATH);

    assertEquals("greeter-suite", bundle.name());
    assertEquals("1.0.0", bundle.version());
    assertEquals("", bundle.values().get("apiToken"));
    assertEquals(1, bundle.tenants().size());
    assertEquals("acme", bundle.tenants().get(0).id());
    assertEquals(268435456L, bundle.tenants().get(0).quota().maxMemoryBytes());
    assertEquals(1, bundle.config().size());
    assertEquals("greeting.prefix", bundle.config().get(0).key());
    assertEquals(1, bundle.secrets().size());
    assertEquals("${values.apiToken}", bundle.secrets().get(0).value());
    assertEquals(2, bundle.workloads().size());
    assertEquals("provider-deployment.yaml", bundle.workloads().get(0).file().orElseThrow());
    assertTrue(
        bundle.workloads().get(1).inlineManifest().orElseThrow().contains("greeter-consumer"));
  }

  @Test
  void rejects_an_unknown_top_level_key() {
    GimleManifestException e =
        assertThrows(
            GimleManifestException.class,
            () -> parse("kind: Bundle\nname: x\nversion: 1.0.0\nfrobnicate: true\n"));
    assertTrue(e.getMessage().contains("frobnicate"));
  }

  @Test
  void rejects_an_unknown_nested_key_in_a_tenant_quota() {
    GimleManifestException e =
        assertThrows(
            GimleManifestException.class,
            () ->
                parse(
                    """
                    kind: Bundle
                    name: x
                    version: 1.0.0
                    tenants:
                      - id: acme
                        quota: {maxMemoryBytes: 1, maxCpuMillicores: 1, maxInstances: 1, extra: true}
                    """));
    assertTrue(e.getMessage().contains("extra"));
  }

  @Test
  void rejects_an_unknown_key_in_a_workload_entry() {
    GimleManifestException e =
        assertThrows(
            GimleManifestException.class,
            () ->
                parse(
                    """
                    kind: Bundle
                    name: x
                    version: 1.0.0
                    workloads:
                      - file: a.yaml
                        extra: true
                    """));
    assertTrue(e.getMessage().contains("extra"));
  }

  @Test
  void rejects_a_workload_entry_with_neither_file_nor_manifest() {
    assertThrows(
        GimleManifestException.class,
        () ->
            parse(
                """
                kind: Bundle
                name: x
                version: 1.0.0
                workloads:
                  - {}
                """));
  }

  @Test
  void rejects_a_workload_entry_with_both_file_and_manifest() {
    assertThrows(
        GimleManifestException.class,
        () ->
            parse(
                """
                kind: Bundle
                name: x
                version: 1.0.0
                workloads:
                  - file: a.yaml
                    manifest: "kind: Deployment"
                """));
  }

  @Test
  void rejects_a_kind_other_than_bundle() {
    GimleManifestException e =
        assertThrows(
            GimleManifestException.class,
            () -> parse("kind: Deployment\nname: x\nversion: 1.0.0\n"));
    assertTrue(e.getMessage().contains("Deployment"));
  }

  @Test
  void
      rejects_a_raw_deployment_manifest_with_the_wrong_kind_message_rather_than_an_unknown_field_one() {
    // The exact shape hilmir init writes -- kind: Deployment with a module: block, not wrapped in
    // a Bundle's own workloads: list. This must fail on 'kind', not on the unrelated 'module'
    // field, so the message actually explains what's wrong (see BundleParser#parseRoot).
    GimleManifestException e =
        assertThrows(
            GimleManifestException.class,
            () ->
                parse(
                    """
                    kind: Deployment
                    name: x-deployment
                    module:
                      name: com.example.x
                      version: 1.0.0
                    artifactPath: x.jar
                    replicas: 1
                    """));
    assertTrue(e.getMessage().contains("expected 'Bundle'"));
    assertTrue(e.getMessage().contains("workloads"));
  }

  @Test
  void rejects_a_document_that_is_not_a_yaml_mapping() {
    assertThrows(GimleManifestException.class, () -> parse("- just\n- a\n- list\n"));
  }
}
