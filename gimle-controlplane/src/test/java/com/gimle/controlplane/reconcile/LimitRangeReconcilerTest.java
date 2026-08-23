package com.gimle.controlplane.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.LimitRangeSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.store.StateStore;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code TestModuleBuilder.minimalDescriptor} fixes every fixture's resource request at 16Mi memory
 * / 10m cpu and its limit at 32Mi memory / 50m cpu -- every {@link LimitRangeSpec} bound below is
 * set relative to those known, fixed values.
 */
class LimitRangeReconcilerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static int counter = 0;

  private Path buildFixtureJar() {
    String uniqueName = "com.gimle.fixture.limitrange" + (counter++);
    return TestModuleBuilder.module("module " + uniqueName + " {\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
        .build(tempDir, uniqueName + ".jar");
  }

  private DeploymentSpec tenantedDeployment(String name, Path jar, String tenantId) {
    return new DeploymentSpec(
        name,
        new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
        jar.toAbsolutePath().toString(),
        1,
        PlacementConstraints.NONE,
        Optional.empty(),
        Optional.of(tenantId));
  }

  /** No jar is ever built for this fixture -- {@code artifactPath} is a dangling path. */
  private DeploymentSpec unresolvableDeployment(String name, String tenantId) {
    return new DeploymentSpec(
        name,
        new ModuleId(name, Version.parse("1.0.0")),
        tempDir.resolve(name + ".jar").toAbsolutePath().toString(),
        1,
        PlacementConstraints.NONE,
        Optional.empty(),
        Optional.of(tenantId));
  }

  @Test
  void marks_a_deployment_violating_when_it_breaches_the_tenants_range() {
    StateStore store = new StateStore(tempDir.resolve("store-over"));
    store.putLimitRange(
        new LimitRangeSpec(
            "acme",
            Optional.of(new ResourceSpec("32Mi", "20m")), // above the fixture's 16Mi/10m request
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    Path jar = buildFixtureJar();
    store.putDeployment(tenantedDeployment("orders", jar, "acme"));

    new LimitRangeReconciler(store).reconcileOnce();

    assertTrue(store.isLimitRangeViolating("orders"));
    assertEquals(
        "request memory 16Mi below minimum 32Mi",
        store.limitRangeViolationReason("orders").orElseThrow());
  }

  @Test
  void does_not_mark_a_deployment_within_its_tenants_range() {
    StateStore store = new StateStore(tempDir.resolve("store-within"));
    store.putLimitRange(
        new LimitRangeSpec(
            "acme",
            Optional.of(new ResourceSpec("1Mi", "1m")),
            Optional.of(new ResourceSpec("64Mi", "100m")),
            Optional.empty(),
            Optional.empty()));
    Path jar = buildFixtureJar();
    store.putDeployment(tenantedDeployment("orders", jar, "acme"));

    new LimitRangeReconciler(store).reconcileOnce();

    assertFalse(store.isLimitRangeViolating("orders"));
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
            1,
            PlacementConstraints.NONE));

    new LimitRangeReconciler(store).reconcileOnce();

    assertFalse(store.isLimitRangeViolating("orders"));
  }

  @Test
  void ignores_a_deployment_whose_tenant_has_no_limit_range() {
    StateStore store = new StateStore(tempDir.resolve("store-no-range"));
    Path jar = buildFixtureJar();
    store.putDeployment(tenantedDeployment("orders", jar, "acme"));

    new LimitRangeReconciler(store).reconcileOnce();

    assertFalse(store.isLimitRangeViolating("orders"));
  }

  @Test
  void clears_a_violation_once_the_range_is_relaxed_again_convergence_from_arbitrary_state() {
    StateStore store = new StateStore(tempDir.resolve("store-converge"));
    store.putLimitRange(
        new LimitRangeSpec(
            "acme",
            Optional.of(new ResourceSpec("32Mi", "20m")),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    Path jar = buildFixtureJar();
    store.putDeployment(tenantedDeployment("orders", jar, "acme"));
    // Simulate a violation already recorded from a previous tick, before this reconciler instance
    // ever ran -- a fresh LimitRangeReconciler must still converge correctly from this pre-set
    // state, matching QuotaReconciler's own identical convergence test.
    store.putLimitRangeViolation("orders", "request memory 16Mi below minimum 32Mi");

    store.putLimitRange(
        new LimitRangeSpec(
            "acme",
            Optional.of(new ResourceSpec("1Mi", "1m")),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    new LimitRangeReconciler(store).reconcileOnce();

    assertFalse(store.isLimitRangeViolating("orders"));
    assertTrue(store.limitRangeViolationReason("orders").isEmpty());
  }

  @Test
  void updates_the_persisted_reason_when_the_failing_bound_changes_while_still_violating() {
    StateStore store = new StateStore(tempDir.resolve("store-reason-change"));
    store.putLimitRange(
        new LimitRangeSpec(
            "acme",
            Optional.of(new ResourceSpec("32Mi", "20m")), // fixture request memory 16Mi is below
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    Path jar = buildFixtureJar();
    store.putDeployment(tenantedDeployment("orders", jar, "acme"));
    new LimitRangeReconciler(store).reconcileOnce();
    assertEquals(
        "request memory 16Mi below minimum 32Mi",
        store.limitRangeViolationReason("orders").orElseThrow());

    // Relax the memory floor but tighten the limit ceiling below the fixture's 32Mi limit instead
    // -- still violating, but for a different reason entirely.
    store.putLimitRange(
        new LimitRangeSpec(
            "acme",
            Optional.of(new ResourceSpec("1Mi", "1m")),
            Optional.empty(),
            Optional.empty(),
            Optional.of(new ResourceSpec("16Mi", "20m"))));
    new LimitRangeReconciler(store).reconcileOnce();

    assertTrue(store.isLimitRangeViolating("orders"));
    assertEquals(
        "limit memory 32Mi above maximum 16Mi",
        store.limitRangeViolationReason("orders").orElseThrow());
  }

  @Test
  void one_deployments_unresolvable_artifact_does_not_abort_the_rest_of_the_tick() {
    StateStore store = new StateStore(tempDir.resolve("store-partial-failure"));
    store.putLimitRange(
        new LimitRangeSpec(
            "acme",
            Optional.of(new ResourceSpec("32Mi", "20m")),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    store.putDeployment(unresolvableDeployment("broken", "acme"));
    Path jar = buildFixtureJar();
    store.putDeployment(tenantedDeployment("orders", jar, "acme"));

    new LimitRangeReconciler(store).reconcileOnce();

    assertFalse(store.isLimitRangeViolating("broken"));
    assertTrue(store.isLimitRangeViolating("orders"));
  }
}
