package com.gimle.controlplane.reconcile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.manifest.DeploymentSpec;
import com.gimle.controlplane.manifest.PlacementConstraints;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code TestModuleBuilder.minimalDescriptor} fixes every fixture's resource request at 16Mi memory
 * / 10m cpu (Phase 5 design §5.2, §8's required convergence-from-arbitrary-state coverage).
 */
class QuotaReconcilerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static int counter = 0;

  private Path buildFixtureJar() {
    String uniqueName = "com.gimle.fixture.quota" + (counter++);
    return TestModuleBuilder.module("module " + uniqueName + " {\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
        .build(tempDir, uniqueName + ".jar");
  }

  private DeploymentSpec tenantedDeployment(String name, int replicas, Path jar, String tenantId) {
    return new DeploymentSpec(
        name,
        new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
        jar.toAbsolutePath().toString(),
        replicas,
        PlacementConstraints.NONE,
        Optional.empty(),
        Optional.of(tenantId));
  }

  @Test
  void marks_a_deployment_violating_when_its_tenant_exceeds_quota() {
    StateStore store = new StateStore(tempDir.resolve("store-over"));
    store.putTenant(new Tenant("acme", new ResourceQuota(1, 1, 1)));
    Path jar = buildFixtureJar();
    store.putDeployment(
        tenantedDeployment("orders", 2, jar, "acme")); // 2 instances > maxInstances 1

    new QuotaReconciler(store).reconcileOnce();

    assertTrue(store.isQuotaViolating("orders"));
  }

  @Test
  void does_not_mark_a_deployment_within_its_tenants_quota() {
    StateStore store = new StateStore(tempDir.resolve("store-within"));
    store.putTenant(new Tenant("acme", new ResourceQuota(1_000_000_000L, 4000, 10)));
    Path jar = buildFixtureJar();
    store.putDeployment(tenantedDeployment("orders", 2, jar, "acme"));

    new QuotaReconciler(store).reconcileOnce();

    assertFalse(store.isQuotaViolating("orders"));
  }

  @Test
  void ignores_untenanted_deployments() {
    StateStore store = new StateStore(tempDir.resolve("store-untenanted"));
    Path jar = buildFixtureJar();
    store.putDeployment(
        new DeploymentSpec(
            "orders",
            new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
            jar.toAbsolutePath().toString(),
            50,
            PlacementConstraints.NONE));

    new QuotaReconciler(store).reconcileOnce();

    assertFalse(store.isQuotaViolating("orders"));
  }

  @Test
  void ignores_a_deployment_whose_tenant_is_unregistered() {
    StateStore store = new StateStore(tempDir.resolve("store-unknown-tenant"));
    Path jar = buildFixtureJar();
    store.putDeployment(tenantedDeployment("orders", 50, jar, "ghost-tenant"));

    new QuotaReconciler(store).reconcileOnce();

    assertFalse(store.isQuotaViolating("orders"));
  }

  @Test
  void clears_a_violation_once_the_quota_is_raised_again_convergence_from_arbitrary_state() {
    StateStore store = new StateStore(tempDir.resolve("store-converge"));
    store.putTenant(new Tenant("acme", new ResourceQuota(1, 1, 1)));
    Path jar = buildFixtureJar();
    store.putDeployment(tenantedDeployment("orders", 2, jar, "acme"));
    // Simulate a violation already recorded from a previous tick, before this reconciler instance
    // ever ran -- a fresh QuotaReconciler must still converge correctly from this pre-set state.
    store.putQuotaViolation("orders", true);

    store.putTenant(new Tenant("acme", new ResourceQuota(1_000_000_000L, 4000, 10)));
    new QuotaReconciler(store).reconcileOnce();

    assertFalse(store.isQuotaViolating("orders"));
  }
}
