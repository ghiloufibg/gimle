package com.gimle.ivaldi.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link FileSetValidator} against the exact rendered shapes {@code
 * gimle-ivaldi-console}'s own {@code lib/render.ts} is specified to produce (see the Ivaldi design
 * doc's section 6), so this test doubles as the executable contract that render implementation is
 * written against: every fixture below is hand-written YAML in that shape, not generated.
 */
class FileSetValidatorTest {

  private static RenderedFile file(String path, String content) {
    return new RenderedFile(path, content);
  }

  private static Set<String> codes(List<Finding> findings) {
    return findings.stream().map(Finding::code).collect(Collectors.toSet());
  }

  private static Finding only(List<Finding> findings) {
    assertEquals(1, findings.size(), "expected exactly one finding, got: " + findings);
    return findings.get(0);
  }

  @Test
  void validates_a_clean_topology_with_only_the_expected_advisory_warnings() {
    String topology =
        """
        name: orders-platform-local
        machines:
          - {name: local, host: 127.0.0.1}
        runtime:
          dataRoot: /tmp/ivaldi-run
        store:
          replicas:
            - {machine: local}
        controlPlane:
          replicas:
            - {machine: local}
        fafnir:
          keyFile: /tmp/ivaldi-run/fafnir.key
          replicas:
            - {machine: local}
        agents:
          - {machine: local, nodeId: node-1, labels: [ssd]}
        """;

    List<Finding> findings = FileSetValidator.validate(List.of(file("topology.yaml", topology)));

    assertEquals(Set.of("SINGLE_STORE", "SINGLE_CONTROL_PLANE"), codes(findings));
    for (Finding f : findings) {
      assertEquals(Finding.Severity.WARNING, f.severity());
      assertEquals("topology.yaml", f.file());
    }
  }

  @Test
  void reports_a_topology_missing_fafnir_as_an_error_with_the_hilmir_rule_code() {
    String topology =
        """
        name: broken
        machines:
          - {name: local, host: 127.0.0.1}
        store:
          replicas:
            - {machine: local}
        controlPlane:
          replicas:
            - {machine: local}
        """;

    List<Finding> findings = FileSetValidator.validate(List.of(file("topology.yaml", topology)));

    assertTrue(
        findings.stream()
            .anyMatch(f -> "NO_FAFNIR".equals(f.code()) && f.severity() == Finding.Severity.ERROR),
        "expected a NO_FAFNIR error, got: " + findings);
  }

  @Test
  void reports_malformed_topology_yaml_as_a_parse_error() {
    List<Finding> findings =
        FileSetValidator.validate(List.of(file("topology.yaml", "not: [a, valid topology")));

    Finding finding = only(findings);
    assertEquals("TOPOLOGY_PARSE_ERROR", finding.code());
    assertEquals(Finding.Severity.ERROR, finding.severity());
  }

  @Test
  void validates_a_registry_sourced_deployment_manifest_clean() {
    String manifest =
        """
        apiVersion: v1
        kind: Deployment
        name: web-ui-deployment
        tenantId: orders-platform
        module:
          name: com.example.webui
          version: 1.1.1
        replicas: 2
        placement:
          antiAffinity: true
        """;

    List<Finding> findings =
        FileSetValidator.validate(List.of(file("manifests/20-web-ui-deployment.yaml", manifest)));

    assertEquals(List.of(), findings);
  }

  @Test
  void surfaces_the_v1alpha1_local_path_deprecation_as_a_warning_not_an_error() {
    String manifest =
        """
        kind: Deployment
        name: web-ui-deployment
        module:
          name: com.example.webui
          version: 1.1.1
        artifactPath: /abs/path/to/web-ui.jar
        replicas: 1
        """;

    List<Finding> findings =
        FileSetValidator.validate(List.of(file("manifests/20-web-ui-deployment.yaml", manifest)));

    Finding finding = only(findings);
    assertEquals("MANIFEST_DEPRECATION", finding.code());
    assertEquals(Finding.Severity.WARNING, finding.severity());
  }

  @Test
  void rejects_a_v1_manifest_that_still_names_a_local_artifact_path() {
    String manifest =
        """
        apiVersion: v1
        kind: Deployment
        name: web-ui-deployment
        module:
          name: com.example.webui
          version: 1.1.1
        artifactPath: /abs/path/to/web-ui.jar
        replicas: 1
        """;

    List<Finding> findings =
        FileSetValidator.validate(List.of(file("manifests/20-web-ui-deployment.yaml", manifest)));

    assertEquals("MANIFEST_INVALID", only(findings).code());
  }

  @Test
  void rejects_a_deployment_with_a_negative_replica_count() {
    String manifest =
        """
        kind: Deployment
        name: web-ui-deployment
        module:
          name: com.example.webui
          version: 1.1.1
        replicas: -1
        """;

    List<Finding> findings =
        FileSetValidator.validate(List.of(file("manifests/20-web-ui-deployment.yaml", manifest)));

    assertEquals("MANIFEST_INVALID", only(findings).code());
  }

  @Test
  void rejects_a_manifest_with_no_kind_field() {
    List<Finding> findings =
        FileSetValidator.validate(List.of(file("manifests/10-mystery.yaml", "name: mystery\n")));

    assertEquals("MANIFEST_NO_KIND", only(findings).code());
  }

  @Test
  void rejects_a_manifest_with_an_unrecognized_kind() {
    List<Finding> findings =
        FileSetValidator.validate(
            List.of(file("manifests/10-mystery.yaml", "kind: Ingress\nname: mystery\n")));

    assertEquals("MANIFEST_UNKNOWN_KIND", only(findings).code());
  }

  @Test
  void validates_a_clean_service_manifest() {
    String manifest =
        """
        kind: Service
        name: web-ui
        tenantId: orders-platform
        deploymentNames:
          - web-ui-deployment
        port: 80
        targetPort: 8090
        """;

    List<Finding> findings =
        FileSetValidator.validate(List.of(file("manifests/40-service-web-ui.yaml", manifest)));

    assertEquals(List.of(), findings);
  }

  @Test
  void rejects_a_service_manifest_with_no_deployment_names_and_no_external_name() {
    String manifest =
        """
        kind: Service
        name: web-ui
        deploymentNames: []
        port: 80
        """;

    List<Finding> findings =
        FileSetValidator.validate(List.of(file("manifests/40-service-web-ui.yaml", manifest)));

    Finding finding = only(findings);
    assertEquals("SERVICE_INVALID", finding.code());
    assertTrue(finding.message().contains("deploymentNames must not be empty"), finding.message());
  }

  @Test
  void rejects_a_service_manifest_with_a_port_out_of_range() {
    String manifest =
        """
        kind: Service
        name: web-ui
        deploymentNames: [web-ui-deployment]
        port: 70000
        """;

    List<Finding> findings =
        FileSetValidator.validate(List.of(file("manifests/40-service-web-ui.yaml", manifest)));

    Finding finding = only(findings);
    assertTrue(finding.message().contains("port must be in [1, 65535]"), finding.message());
  }

  @Test
  void validates_a_clean_network_policy_manifest() {
    String manifest =
        """
        kind: NetworkPolicy
        name: web-ui-deny-cross-tenant
        tenantId: orders-platform
        deploymentNames:
          - web-ui-deployment
        allowedCallerTenantIds: []
        """;

    List<Finding> findings =
        FileSetValidator.validate(
            List.of(file("manifests/50-networkpolicy-web-ui-deny-cross-tenant.yaml", manifest)));

    assertEquals(List.of(), findings);
  }

  @Test
  void rejects_a_network_policy_that_restricts_no_direction() {
    String manifest =
        """
        kind: NetworkPolicy
        name: web-ui-deny-cross-tenant
        tenantId: orders-platform
        deploymentNames:
          - web-ui-deployment
        """;

    List<Finding> findings =
        FileSetValidator.validate(
            List.of(file("manifests/50-networkpolicy-web-ui-deny-cross-tenant.yaml", manifest)));

    Finding finding = only(findings);
    assertEquals("NETWORKPOLICY_INVALID", finding.code());
    assertTrue(
        finding.message().contains("must restrict at least one direction"), finding.message());
  }

  @Test
  void validates_a_clean_bundle() {
    String bundle =
        """
        kind: Bundle
        name: orders-platform-local
        version: 1.0.0
        values:
          adminToken: ""
        tenants:
          - id: orders-platform
            quota: {maxMemoryBytes: 1073741824, maxCpuMillicores: 4000, maxInstances: 20}
        config: []
        secrets:
          - {tenant: orders-platform, key: admin.token, value: "${values.adminToken}"}
        workloads:
          - {file: manifests/20-web-ui-deployment.yaml}
        """;

    List<Finding> findings = FileSetValidator.validate(List.of(file("bundle.yaml", bundle)));

    assertEquals(List.of(), findings);
  }

  @Test
  void rejects_a_malformed_bundle() {
    List<Finding> findings =
        FileSetValidator.validate(List.of(file("bundle.yaml", "kind: Bundle\nname: x\n")));

    assertEquals("BUNDLE_PARSE_ERROR", only(findings).code());
  }

  @Test
  void skips_files_that_are_not_topology_bundle_or_manifests() {
    List<Finding> findings =
        FileSetValidator.validate(
            List.of(
                file("README.md", "# not a manifest"),
                file("values.example.yaml", "adminToken: \"\""),
                file("ivaldi.blueprint.json", "{}")));

    assertEquals(List.of(), findings);
  }

  @Test
  void validates_every_file_in_a_full_rendered_set_at_once() {
    String topology =
        """
        name: orders-platform-local
        machines:
          - {name: local, host: 127.0.0.1}
        store:
          replicas:
            - {machine: local}
        controlPlane:
          replicas:
            - {machine: local}
        fafnir:
          keyFile: /tmp/fafnir.key
          replicas:
            - {machine: local}
        agents:
          - {machine: local, nodeId: node-1, labels: []}
        """;
    String deployment =
        """
        apiVersion: v1
        kind: Deployment
        name: web-ui-deployment
        tenantId: orders-platform
        module: {name: com.example.webui, version: 1.1.1}
        replicas: 1
        """;
    String service =
        """
        kind: Service
        name: web-ui
        tenantId: orders-platform
        deploymentNames: [web-ui-deployment]
        port: 80
        targetPort: 8090
        """;

    List<Finding> findings =
        FileSetValidator.validate(
            List.of(
                file("topology.yaml", topology),
                file("manifests/20-web-ui-deployment.yaml", deployment),
                file("manifests/40-service-web-ui.yaml", service),
                file("README.md", "irrelevant")));

    assertEquals(Set.of("SINGLE_STORE", "SINGLE_CONTROL_PLANE"), codes(findings));
  }
}
