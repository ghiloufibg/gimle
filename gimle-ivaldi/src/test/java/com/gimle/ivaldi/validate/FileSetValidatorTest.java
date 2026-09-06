package com.gimle.ivaldi.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link FileSetValidator} against the exact rendered shapes {@code
 * gimle-ivaldi-console}'s own {@code lib/render.ts} is specified to produce (see the Ivaldi design
 * doc's section 6), so this test doubles as the executable contract that render implementation is
 * written against: every fixture below is hand-written YAML in that shape, not generated.
 */
class FileSetValidatorTest {

  @TempDir Path tempDir;

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

  // ---- cross-file pre-flight ----

  private static final String PLAINTEXT_TOPOLOGY =
      """
      name: cross-file
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
        - {machine: local, nodeId: node-1}
      """;

  private static String withAndvari(String topology) {
    return topology
        + """
        andvari:
          replicas:
            - {machine: local}
        """;
  }

  private static String bundleWithTenants(String... tenantIds) {
    StringBuilder bundle =
        new StringBuilder("kind: Bundle\nname: b\nversion: \"1\"\nworkloads: []\ntenants:\n");
    for (String id : tenantIds) {
      bundle
          .append("  - id: ")
          .append(id)
          .append("\n    quota: {maxMemoryBytes: 1, maxCpuMillicores: 1, maxInstances: 1}\n");
    }
    return bundle.toString();
  }

  private static final String JAR_WORKLOAD =
      """
      apiVersion: v1
      kind: Deployment
      name: hello
      tenantId: examples
      module: {name: com.gimle.examples.hello, version: 1.0.0}
      replicas: 1
      """;

  private static final String JAR_SIDECAR =
      """
      artifacts:
        - manifest: manifests/01-hello.yaml
          module: com.gimle.examples.hello
          version: 1.0.0
          path: /tmp/hello-module.jar
      """;

  /**
   * A jar-sourced workload is pushed to the cluster's own registry at run time, so a topology with
   * no andvari replica cannot host one -- discoverable here, or as a 503 at the far end of a run
   * that has already booted the whole platform.
   */
  @Test
  void flags_a_jar_sourced_workload_when_the_topology_declares_no_andvari() {
    List<Finding> findings =
        FileSetValidator.validate(
            List.of(
                file("topology.yaml", PLAINTEXT_TOPOLOGY),
                file("ivaldi.artifacts.yaml", JAR_SIDECAR),
                file("manifests/01-hello.yaml", JAR_WORKLOAD)));

    Finding jarFinding =
        findings.stream()
            .filter(f -> f.code().equals("NO_ANDVARI_FOR_JAR"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no NO_ANDVARI_FOR_JAR in " + findings));
    assertEquals(Finding.Severity.ERROR, jarFinding.severity());
    assertEquals("manifests/01-hello.yaml", jarFinding.file());
  }

  @Test
  void accepts_the_same_jar_sourced_workload_once_andvari_is_declared() {
    List<Finding> findings =
        FileSetValidator.validate(
            List.of(
                file("topology.yaml", withAndvari(PLAINTEXT_TOPOLOGY)),
                file("ivaldi.artifacts.yaml", JAR_SIDECAR),
                file("manifests/01-hello.yaml", JAR_WORKLOAD)));

    assertFalse(codes(findings).contains("NO_ANDVARI_FOR_JAR"), findings.toString());
  }

  private static final String LIMIT_RANGE_MIN_32MI =
      "kind: LimitRange\nname: examples\nminRequest: {memory: 32Mi, cpu: 10m}\n";

  /**
   * A minimal but real module artifact: a JPMS-shaped jar ({@code module-info.class}, checked only
   * for presence, never parsed as real bytecode) carrying a real, parseable {@code
   * gimle-module.yaml} declaring exactly the resources the caller asks for -- what {@code
   * ModuleArtifactReader} needs to hand back a real {@code ModuleDescriptor}, the same way a real
   * build's own jar would.
   */
  private Path realModuleJar(String requestMemory, String requestCpu) {
    String descriptor =
        """
        name: com.gimle.examples.hello
        version: 1.0.0
        isolation:
          tier: TIER_1
        resources:
          request:
            memory: %s
            cpu: %s
          limit:
            memory: 512Mi
            cpu: 1000m
        """
            .formatted(requestMemory, requestCpu);
    Path jar = tempDir.resolve("hello-" + System.nanoTime() + ".jar");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new JarEntry("module-info.class"));
      out.write(new byte[] {0});
      out.closeEntry();
      out.putNextEntry(new JarEntry("META-INF/gimle/gimle-module.yaml"));
      out.write(descriptor.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return jar;
  }

  private static String jarSidecar(Path jar) {
    return """
        artifacts:
          - manifest: manifests/01-hello.yaml
            module: com.gimle.examples.hello
            version: 1.0.0
            path: %s
        """
        .formatted(jar);
  }

  /**
   * The module's own real resource declaration -- read from the jar the run actually pushes, not
   * whatever value the console's own Inspector fields happen to model -- is what gets checked here,
   * the same way the control plane's admission plugin checks it once the module is really placed.
   */
  @Test
  void flags_a_jar_sourced_workload_whose_real_resources_violate_the_tenant_limit_range() {
    Path jar = realModuleJar("16Mi", "10m");

    List<Finding> findings =
        FileSetValidator.validate(
            List.of(
                file("topology.yaml", withAndvari(PLAINTEXT_TOPOLOGY)),
                file("ivaldi.artifacts.yaml", jarSidecar(jar)),
                file("manifests/01-hello.yaml", JAR_WORKLOAD),
                file("manifests/02-lr.yaml", LIMIT_RANGE_MIN_32MI)));

    Finding finding =
        findings.stream()
            .filter(f -> f.code().equals("LIMITRANGE_VIOLATION"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no LIMITRANGE_VIOLATION in " + findings));
    assertEquals(Finding.Severity.ERROR, finding.severity());
    assertEquals("manifests/01-hello.yaml", finding.file());
    assertTrue(finding.message().contains("16Mi"), finding.message());
    assertTrue(finding.message().contains("32Mi"), finding.message());
  }

  @Test
  void accepts_a_jar_sourced_workload_whose_real_resources_satisfy_the_limit_range() {
    Path jar = realModuleJar("64Mi", "50m");

    List<Finding> findings =
        FileSetValidator.validate(
            List.of(
                file("topology.yaml", withAndvari(PLAINTEXT_TOPOLOGY)),
                file("ivaldi.artifacts.yaml", jarSidecar(jar)),
                file("manifests/01-hello.yaml", JAR_WORKLOAD),
                file("manifests/02-lr.yaml", LIMIT_RANGE_MIN_32MI)));

    assertFalse(codes(findings).contains("LIMITRANGE_VIOLATION"), findings.toString());
  }

  /**
   * Mirrors {@code RunController}'s own "not a pushable module artifact" push-time check, just
   * early -- but only once a matching LimitRange actually needs the jar opened; see the next test.
   */
  @Test
  void flags_an_unreadable_jar_when_its_tenant_has_a_limit_range_to_check_it_against() {
    List<Finding> findings =
        FileSetValidator.validate(
            List.of(
                file("topology.yaml", withAndvari(PLAINTEXT_TOPOLOGY)),
                file("ivaldi.artifacts.yaml", JAR_SIDECAR), // path: /tmp/hello-module.jar, absent
                file("manifests/01-hello.yaml", JAR_WORKLOAD),
                file("manifests/02-lr.yaml", LIMIT_RANGE_MIN_32MI)));

    Finding finding =
        findings.stream()
            .filter(f -> f.code().equals("JAR_ARTIFACT_UNREADABLE"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no JAR_ARTIFACT_UNREADABLE in " + findings));
    assertEquals("manifests/01-hello.yaml", finding.file());
  }

  /** No LimitRange for the workload's tenant means nothing here ever needs to open its jar. */
  @Test
  void does_not_open_the_jar_at_all_when_its_tenant_has_no_limit_range() {
    List<Finding> findings =
        FileSetValidator.validate(
            List.of(
                file("topology.yaml", withAndvari(PLAINTEXT_TOPOLOGY)),
                file("ivaldi.artifacts.yaml", JAR_SIDECAR), // path: /tmp/hello-module.jar, absent
                file("manifests/01-hello.yaml", JAR_WORKLOAD)));

    assertFalse(codes(findings).contains("JAR_ARTIFACT_UNREADABLE"), findings.toString());
    assertFalse(codes(findings).contains("LIMITRANGE_VIOLATION"), findings.toString());
  }

  @Test
  void does_not_flag_a_registry_sourced_workload_without_andvari() {
    String registryWorkload =
        """
        apiVersion: v1
        kind: Deployment
        name: hello
        module: {name: com.gimle.examples.hello, version: 1.0.0}
        replicas: 1
        """;

    List<Finding> findings =
        FileSetValidator.validate(
            List.of(
                file("topology.yaml", PLAINTEXT_TOPOLOGY),
                file("manifests/01-hello.yaml", registryWorkload)));

    assertFalse(codes(findings).contains("NO_ANDVARI_FOR_JAR"), findings.toString());
  }

  /**
   * Plaintext has no caller identity to tell tenants apart, so the control plane permits exactly
   * one tenant of the operator's own -- a bundle declaring two can never apply, whoever applies it.
   */
  @Test
  void flags_a_bundle_declaring_two_own_tenants_under_plaintext() {
    List<Finding> findings =
        FileSetValidator.validate(
            List.of(
                file("topology.yaml", PLAINTEXT_TOPOLOGY),
                file("bundle.yaml", bundleWithTenants("examples", "other"))));

    Finding finding =
        findings.stream()
            .filter(f -> f.code().equals("PLAINTEXT_MULTI_TENANT"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no PLAINTEXT_MULTI_TENANT in " + findings));
    assertEquals(Finding.Severity.ERROR, finding.severity());
    assertEquals("bundle.yaml", finding.file());
  }

  @Test
  void allows_a_single_own_tenant_under_plaintext() {
    List<Finding> findings =
        FileSetValidator.validate(
            List.of(
                file("topology.yaml", PLAINTEXT_TOPOLOGY),
                file("bundle.yaml", bundleWithTenants("examples"))));

    assertFalse(codes(findings).contains("PLAINTEXT_MULTI_TENANT"), findings.toString());
  }

  /** The platform seeds these itself; they were never the operator asking for a tenant. */
  @Test
  void does_not_count_platform_seeded_tenants_toward_the_plaintext_limit() {
    List<Finding> findings =
        FileSetValidator.validate(
            List.of(
                file("topology.yaml", PLAINTEXT_TOPOLOGY),
                file(
                    "bundle.yaml",
                    bundleWithTenants("gimle-hilmir", "default", "gimle-system", "examples"))));

    assertFalse(codes(findings).contains("PLAINTEXT_MULTI_TENANT"), findings.toString());
  }

  @Test
  void allows_two_tenants_once_the_topology_asks_for_mtls() {
    String mtls =
        PLAINTEXT_TOPOLOGY.replace("name: cross-file", "name: cross-file\ntransport: mtls")
            + """
            tls:
              materialDir: /tmp/ivaldi-run/tls
            """;

    List<Finding> findings =
        FileSetValidator.validate(
            List.of(
                file("topology.yaml", mtls),
                file("bundle.yaml", bundleWithTenants("examples", "other"))));

    assertFalse(codes(findings).contains("PLAINTEXT_MULTI_TENANT"), findings.toString());
  }

  /**
   * The limit range is built as the platform's own record, not shape-tested: a shape test passed
   * three documents the cluster refuses, so the run failed at PUT /limitranges after the whole
   * platform had already booted.
   */
  @Test
  void a_limit_range_the_platform_would_refuse_is_refused_here() {
    String header = "kind: LimitRange\nname: acme\n";

    assertTrue(
        codesOf(header + "minRequest: {memory: \"\", cpu: \"\"}\n").contains("LIMITRANGE_INVALID"),
        "a blank quantity");
    assertTrue(
        codesOf(header + "minRequest: {memory: banana, cpu: 10m}\n").contains("LIMITRANGE_INVALID"),
        "an unparseable quantity");
    assertTrue(
        codesOf(
                header
                    + "minRequest: {memory: 512Mi, cpu: 900m}\n"
                    + "maxRequest: {memory: 32Mi, cpu: 10m}\n")
            .contains("LIMITRANGE_INVALID"),
        "a minimum above its maximum");
    assertTrue(
        codesOf(header + "minRequest: {memory: 32Mi}\n").contains("LIMITRANGE_INVALID"),
        "a bound missing one half");
  }

  @Test
  void a_well_formed_limit_range_passes_and_an_unbounded_one_only_warns() {
    String valid =
        "kind: LimitRange\nname: acme\nminRequest: {memory: 32Mi, cpu: 10m}\n"
            + "maxRequest: {memory: 512Mi, cpu: 1000m}\n";

    assertFalse(codesOf(valid).contains("LIMITRANGE_INVALID"));
    assertTrue(codesOf("kind: LimitRange\nname: acme\n").contains("LIMITRANGE_NO_BOUNDS"));
  }

  private static List<String> codesOf(String manifest) {
    return FileSetValidator.validate(List.of(new RenderedFile("manifests/01-lr.yaml", manifest)))
        .stream()
        .map(Finding::code)
        .toList();
  }
}
