package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.apache.maven.model.Build;
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
  void groups_reactor_modules_by_tenant_and_lists_the_rest_as_untenanted() {
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
  void a_fully_untenanted_reactor_produces_no_tenant_section() {
    String yaml = ArtifactSetMojo.generateManifestYaml(List.of(project("app-1.0.0", null)), null);

    assertEquals(
        """
        kind: ArtifactSet
        modules:
          - /repo/some-module/target/app-1.0.0.jar
        """,
        yaml);
  }
}
