package com.gimle.controlplane.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.store.StateStore;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code TestModuleBuilder.minimalDescriptor} fixes every fixture's resource request at 16Mi memory
 * / 10m cpu, keeping quota-admission checks and the convergence-from-arbitrary-starting-state tests
 * below comparable against a known, fixed request size.
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
    StateStore store = new StateStore();
    store.putTenant(new Tenant("acme", new ResourceQuota(1, 1, 1)));
    Path jar = buildFixtureJar();
    store.putDeployment(
        tenantedDeployment("orders", 2, jar, "acme")); // 2 instances > maxInstances 1

    new QuotaReconciler(store).reconcileOnce();

    assertTrue(store.isQuotaViolating(Optional.of("acme"), "orders"));
  }

  @Test
  void does_not_mark_a_deployment_within_its_tenants_quota() {
    StateStore store = new StateStore();
    store.putTenant(new Tenant("acme", new ResourceQuota(1_000_000_000L, 4000, 10)));
    Path jar = buildFixtureJar();
    store.putDeployment(tenantedDeployment("orders", 2, jar, "acme"));

    new QuotaReconciler(store).reconcileOnce();

    assertFalse(store.isQuotaViolating(Optional.of("acme"), "orders"));
  }

  /**
   * A quota an operator explicitly set on the default tenant is a real constraint. Skipping that
   * tenant by name meant its usage was never even accumulated, so a workload far past the ceiling
   * was still reported as not violating.
   */
  @Test
  void marks_a_deployment_violating_a_quota_set_on_the_default_tenant() {
    StateStore store = new StateStore();
    store.putTenant(new Tenant(Tenant.DEFAULT_TENANT_ID, new ResourceQuota(1, 1, 1)));
    Path jar = buildFixtureJar();
    store.putDeployment(tenantedDeployment("orders", 2, jar, Tenant.DEFAULT_TENANT_ID));

    new QuotaReconciler(store).reconcileOnce();

    assertTrue(store.isQuotaViolating(Optional.of(Tenant.DEFAULT_TENANT_ID), "orders"));
  }

  @Test
  void does_not_mark_a_default_tenant_deployment_within_the_quota_set_on_it() {
    StateStore store = new StateStore();
    store.putTenant(
        new Tenant(Tenant.DEFAULT_TENANT_ID, new ResourceQuota(1_000_000_000L, 4000, 10)));
    Path jar = buildFixtureJar();
    store.putDeployment(tenantedDeployment("orders", 2, jar, Tenant.DEFAULT_TENANT_ID));

    new QuotaReconciler(store).reconcileOnce();

    assertFalse(store.isQuotaViolating(Optional.of(Tenant.DEFAULT_TENANT_ID), "orders"));
  }

  @Test
  void ignores_untenanted_deployments() {
    StateStore store = new StateStore();
    Path jar = buildFixtureJar();
    store.putDeployment(
        new DeploymentSpec(
            "orders",
            new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
            jar.toAbsolutePath().toString(),
            50,
            PlacementConstraints.NONE));

    new QuotaReconciler(store).reconcileOnce();

    assertFalse(store.isQuotaViolating(Optional.empty(), "orders"));
  }

  @Test
  void ignores_a_deployment_whose_tenant_is_unregistered() {
    StateStore store = new StateStore();
    Path jar = buildFixtureJar();
    store.putDeployment(tenantedDeployment("orders", 50, jar, "ghost-tenant"));

    new QuotaReconciler(store).reconcileOnce();

    assertFalse(store.isQuotaViolating(Optional.of("ghost-tenant"), "orders"));
  }

  @Test
  void clears_a_violation_once_the_quota_is_raised_again_convergence_from_arbitrary_state() {
    StateStore store = new StateStore();
    store.putTenant(new Tenant("acme", new ResourceQuota(1, 1, 1)));
    Path jar = buildFixtureJar();
    store.putDeployment(tenantedDeployment("orders", 2, jar, "acme"));
    // Simulate a violation already recorded from a previous tick, before this reconciler instance
    // ever ran -- a fresh QuotaReconciler must still converge correctly from this pre-set state.
    store.putQuotaViolation(Optional.of("acme"), "orders", true);

    store.putTenant(new Tenant("acme", new ResourceQuota(1_000_000_000L, 4000, 10)));
    new QuotaReconciler(store).reconcileOnce();

    assertFalse(store.isQuotaViolating(Optional.of("acme"), "orders"));
  }

  @Test
  void proposes_nothing_for_an_untenanted_deployment_across_repeated_ticks() {
    StateStore store = new StateStore();
    Path jar = buildFixtureJar();
    // The exact live-measured scenario: an untenanted deployment, which is structurally always
    // non-violating -- the unguarded reconciler used to re-propose the same false value on every
    // tick, forever, on a fully idle cluster (100/100 of the newest Raft log entries in the live
    // repro). isQuotaViolating already defaults to false for an absent entry, so a non-violating
    // deployment needs no store write at all, not even once.
    store.putDeployment(
        new DeploymentSpec(
            "orders",
            new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
            jar.toAbsolutePath().toString(),
            2,
            PlacementConstraints.NONE));
    AtomicInteger proposals = new AtomicInteger();
    MutationSink countingSink =
        mutation -> {
          proposals.incrementAndGet();
          return mutation.applyTo(store);
        };
    QuotaReconciler reconciler = new QuotaReconciler(store, countingSink);

    reconciler.reconcileOnce();
    reconciler.reconcileOnce();
    reconciler.reconcileOnce();

    assertEquals(0, proposals.get(), "a never-violating deployment must never be proposed");
  }

  @Test
  void proposes_exactly_once_when_a_violation_is_introduced_then_nothing_more_while_it_persists() {
    StateStore store = new StateStore();
    store.putTenant(new Tenant("acme", new ResourceQuota(1, 1, 1)));
    Path jar = buildFixtureJar();
    store.putDeployment(tenantedDeployment("orders", 2, jar, "acme")); // 2 > maxInstances 1
    AtomicInteger proposals = new AtomicInteger();
    MutationSink countingSink =
        mutation -> {
          proposals.incrementAndGet();
          return mutation.applyTo(store);
        };
    QuotaReconciler reconciler = new QuotaReconciler(store, countingSink);

    reconciler.reconcileOnce(); // false -> true: the one real transition
    reconciler.reconcileOnce();
    reconciler.reconcileOnce(); // still violating, must not re-propose

    assertTrue(store.isQuotaViolating(Optional.of("acme"), "orders"));
    assertEquals(1, proposals.get(), "only the false->true transition should propose");
  }
}
