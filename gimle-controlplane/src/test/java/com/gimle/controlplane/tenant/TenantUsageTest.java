package com.gimle.controlplane.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.manifest.WorkloadSpec;
import com.gimle.mimir.store.StateStore;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit-level coverage of {@link TenantUsage#currentlyAssigned}, complementary to {@code
 * TenantQuotaPluginTest}'s exercise of the same method through the admission path. Each fixture
 * jar's own resource request is fixed at 16Mi memory / 10m cpu by {@code
 * TestModuleBuilder.minimalDescriptor}, the same fixture convention {@code
 * TenantQuotaPluginTest}/{@code DeploymentReconcilerTest} already use.
 */
class TenantUsageTest {

  @TempDir(cleanup = CleanupMode.ON_SUCCESS)
  Path tempDir;

  @Test
  void with_no_exclusion_every_deployment_for_the_tenant_is_counted() {
    StateStore store = store();
    store.putDeployment(deployment("orders", "acme"));
    store.putDeployment(deployment("billing", "acme"));

    TenantUsage.Usage usage = TenantUsage.currentlyAssigned(store, "acme", Optional.empty());

    assertEquals(2 * 16L * 1024 * 1024, usage.memoryBytes());
    assertEquals(2 * 10, usage.cpuMillicores());
    assertEquals(2, usage.instances());
  }

  @Test
  void the_excluded_deployment_does_not_contribute_to_the_total() {
    StateStore store = store();
    DeploymentSpec orders = deployment("orders", "acme");
    store.putDeployment(orders);
    store.putDeployment(deployment("billing", "acme"));

    TenantUsage.Usage usage =
        TenantUsage.currentlyAssigned(store, "acme", Optional.of((WorkloadSpec) orders));

    assertEquals(16L * 1024 * 1024, usage.memoryBytes());
    assertEquals(10, usage.cpuMillicores());
    assertEquals(1, usage.instances());
  }

  /**
   * A Deployment and a Job can legitimately share a name -- they're different resource kinds, with
   * no cross-kind uniqueness constraint (see {@code WorkloadHealthState}'s own javadoc for the
   * identical collision already fixed once, for restart-budget bookkeeping). Excluding the
   * Deployment being replaced must not also drop the same-named Job's own real usage.
   */
  @Test
  void excluding_a_deployment_does_not_drop_a_same_named_jobs_own_usage() {
    StateStore store = store();
    DeploymentSpec orders = deployment("orders", "acme");
    store.putDeployment(orders);
    store.putJobSpec(job("orders", "acme"));

    TenantUsage.Usage usage =
        TenantUsage.currentlyAssigned(store, "acme", Optional.of((WorkloadSpec) orders));

    assertEquals(16L * 1024 * 1024, usage.memoryBytes(), "the Job's own usage must still count");
    assertEquals(10, usage.cpuMillicores());
    assertEquals(1, usage.instances());
  }

  private DeploymentSpec deployment(String name, String tenantId) {
    Path jar = buildFixtureJar(name);
    return new DeploymentSpec(
        name,
        new ModuleId(name, Version.parse("1.0.0")),
        jar.toAbsolutePath().toString(),
        1,
        PlacementConstraints.NONE,
        Optional.empty(),
        Optional.of(tenantId),
        Optional.empty());
  }

  private JobSpec job(String name, String tenantId) {
    // A distinct fixture-jar label from the deployment's own, even when name (the JobSpec's own
    // identity) deliberately collides with a Deployment's -- two different kinds sharing a jar
    // filename would overwrite one another in tempDir. No hyphen: the label doubles as the
    // fixture's own Java module name, which a hyphen isn't valid in.
    String fixtureLabel = name + "Job";
    Path jar = buildFixtureJar(fixtureLabel);
    return new JobSpec(
        name,
        new ModuleId(fixtureLabel, Version.parse("1.0.0")),
        jar.toAbsolutePath().toString(),
        PlacementConstraints.NONE,
        Optional.empty(),
        3,
        Optional.of(tenantId),
        Optional.empty(),
        Optional.empty());
  }

  private Path buildFixtureJar(String uniqueName) {
    return TestModuleBuilder.module("module " + uniqueName + " {\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
        .build(tempDir, uniqueName + ".jar");
  }

  private StateStore store() {
    return new StateStore();
  }
}
