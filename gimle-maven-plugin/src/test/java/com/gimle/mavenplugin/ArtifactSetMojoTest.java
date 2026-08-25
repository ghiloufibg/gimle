package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

/**
 * {@link ArtifactSetMojo#executeAtRoot()} needs a live Maven session to run at all, but the
 * manifest content it generates is a pure function of the reactor's own project list, split out
 * into {@link ArtifactSetMojo#generateManifestYaml} and {@link ArtifactSetMojo#effectiveTenant}
 * specifically so they can be asserted here without any of that machinery -- the same seam {@code
 * FlakyTestsMojoTest} exercises for {@link FlakyTestsMojo}.
 */
class ArtifactSetMojoTest {

  private static MavenProject project(String finalName, String tenantId) {
    MavenProject project = new MavenProject();
    Build build = new Build();
    build.setDirectory("/repo/some-module/target");
    build.setFinalName(finalName);
    project.setBuild(build);
    if (tenantId != null) {
      project.getProperties().setProperty("gimle.artifactset.tenantId", tenantId);
    }
    return project;
  }

  @Test
  void a_submodules_own_property_wins_over_the_reactor_wide_default() {
    MavenProject withOwnTenant = project("billing-service-1.0.0", "billing");

    assertEquals("billing", ArtifactSetMojo.effectiveTenant(withOwnTenant, "orders-platform"));
  }

  @Test
  void a_submodule_with_no_property_falls_back_to_the_reactor_wide_default() {
    MavenProject noOwnTenant = project("orders-service-1.0.0", null);

    assertEquals(
        "orders-platform", ArtifactSetMojo.effectiveTenant(noOwnTenant, "orders-platform"));
  }

  @Test
  void no_default_and_no_own_property_means_untenanted() {
    MavenProject noTenantAtAll = project("shared-lib-1.0.0", null);

    assertNull(ArtifactSetMojo.effectiveTenant(noTenantAtAll, null));
  }

  @Test
  void groups_reactor_modules_by_tenant_and_lists_the_rest_as_untenanted() throws Exception {
    // No reactor-wide default here -- each tenanted module names its own tenant explicitly, so
    // "shared" (naming none) is genuinely untenanted rather than inheriting a default.
    MavenProject orders = project("orders-service-1.0.0", "orders-platform");
    MavenProject inventory = project("inventory-service-1.0.0", "orders-platform");
    MavenProject billing = project("billing-service-1.0.0", "billing");
    MavenProject shared = project("shared-lib-1.0.0", null);

    String yaml =
        ArtifactSetMojo.generateManifestYaml(List.of(orders, inventory, billing, shared), null);

    assertEquals(
        """
        kind: ArtifactSet
        tenant:
          orders-platform:
            - /repo/some-module/target/orders-service-1.0.0.jar
            - /repo/some-module/target/inventory-service-1.0.0.jar
          billing:
            - /repo/some-module/target/billing-service-1.0.0.jar
        modules:
          - /repo/some-module/target/shared-lib-1.0.0.jar
        """,
        yaml);
  }

  @Test
  void a_fully_untenanted_reactor_produces_no_tenant_section() throws Exception {
    String yaml = ArtifactSetMojo.generateManifestYaml(List.of(project("app-1.0.0", null)), null);

    assertEquals(
        """
        kind: ArtifactSet
        modules:
          - /repo/some-module/target/app-1.0.0.jar
        """,
        yaml);
  }

  private static MavenProject coordinateProject(String finalName, String tenantId) {
    MavenProject project = project(finalName, tenantId);
    project.setGroupId("com.acme");
    project.setArtifactId("report");
    project.setVersion("2.0.0");
    project.setFile(new java.io.File("/repo/some-module/pom.xml"));
    return project;
  }

  @Test
  void a_vessel_module_emits_a_mapping_entry_with_its_default_coordinate() throws Exception {
    MavenProject vessel = coordinateProject("report-2.0.0", null);
    vessel.getProperties().setProperty("gimle.artifactset.kind", "vessel");

    String yaml = ArtifactSetMojo.generateManifestYaml(List.of(vessel), null);

    assertEquals(
        """
        kind: ArtifactSet
        modules:
          - artifact: /repo/some-module/target/report-2.0.0.jar
            kind: vessel
            name: com.acme.report
            version: 2.0.0
        """,
        yaml);
  }

  @Test
  void a_bundle_module_emits_its_command_and_artifact_override() throws Exception {
    MavenProject bundle = coordinateProject("report-2.0.0", "orders-platform");
    bundle.getProperties().setProperty("gimle.artifactset.kind", "bundle");
    bundle.getProperties().setProperty("gimle.artifactset.artifact", "target/quarkus-app");
    bundle.getProperties().setProperty("gimle.artifactset.command", "java,-jar,quarkus-run.jar");
    bundle.getProperties().setProperty("gimle.artifactset.workdir", ".");

    String yaml = ArtifactSetMojo.generateManifestYaml(List.of(bundle), null);

    assertEquals(
        """
        kind: ArtifactSet
        tenant:
          orders-platform:
            - artifact: /repo/some-module/target/quarkus-app
              kind: bundle
              name: com.acme.report
              version: 2.0.0
              command: ['java', '-jar', 'quarkus-run.jar']
              workdir: '.'
        """,
        yaml);
  }

  @Test
  void a_bundle_module_without_a_command_fails_generation_naming_it() {
    MavenProject bundle = coordinateProject("report-2.0.0", null);
    bundle.getProperties().setProperty("gimle.artifactset.kind", "bundle");

    MojoExecutionException failure =
        assertThrows(
            MojoExecutionException.class,
            () -> ArtifactSetMojo.generateManifestYaml(List.of(bundle), null));
    assertTrue(failure.getMessage().contains("report"));
  }

  @Test
  void an_unknown_kind_fails_generation() {
    MavenProject unknown = coordinateProject("report-2.0.0", null);
    unknown.getProperties().setProperty("gimle.artifactset.kind", "tarball");

    assertThrows(
        MojoExecutionException.class,
        () -> ArtifactSetMojo.generateManifestYaml(List.of(unknown), null));
  }

  @Test
  void a_command_property_on_a_plain_module_fails_generation() {
    MavenProject module = coordinateProject("report-2.0.0", null);
    module.getProperties().setProperty("gimle.artifactset.command", "java,-jar,x.jar");

    assertThrows(
        MojoExecutionException.class,
        () -> ArtifactSetMojo.generateManifestYaml(List.of(module), null));
  }

  @Test
  void a_pom_packaged_aggregator_is_skipped_rather_than_pushed_as_a_nonexistent_jar()
      throws Exception {
    MavenProject aggregator = project("reactor-root-1.0.0", null);
    aggregator.setPackaging("pom");
    MavenProject app = project("app-1.0.0", null);

    String yaml = ArtifactSetMojo.generateManifestYaml(List.of(aggregator, app), null);

    assertEquals(
        """
        kind: ArtifactSet
        modules:
          - /repo/some-module/target/app-1.0.0.jar
        """,
        yaml);
  }

  @Test
  void a_pom_packaged_module_with_an_explicit_kind_is_still_included() throws Exception {
    MavenProject bundle = coordinateProject("report-2.0.0", null);
    bundle.setPackaging("pom");
    bundle.getProperties().setProperty("gimle.artifactset.kind", "bundle");
    bundle.getProperties().setProperty("gimle.artifactset.artifact", "target/quarkus-app");
    bundle.getProperties().setProperty("gimle.artifactset.command", "java,-jar,quarkus-run.jar");

    String yaml = ArtifactSetMojo.generateManifestYaml(List.of(bundle), null);

    assertTrue(yaml.contains("kind: bundle"), yaml);
    assertTrue(yaml.contains("name: com.acme.report"), yaml);
  }

  @Test
  void a_reactor_of_only_kindless_pom_projects_fails_with_a_clear_message() {
    MavenProject aggregator = project("reactor-root-1.0.0", null);
    aggregator.setPackaging("pom");

    MojoExecutionException failure =
        assertThrows(
            MojoExecutionException.class,
            () -> ArtifactSetMojo.generateManifestYaml(List.of(aggregator), null));
    assertTrue(failure.getMessage().contains("no reactor module"), failure.getMessage());
  }
}
