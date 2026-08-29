package com.gimle.controlplane.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Verb;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.DisruptionBudget;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.manifest.WorkloadSpec;
import com.gimle.mimir.store.StateStore;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit-level coverage of {@link TenantQuotaPlugin}'s own decision logic, complementary to {@code
 * ApiServerTest}'s HTTP-level coverage of the same behavior (a real PUT /deployments round trip
 * through the whole {@link AdmissionChain}). {@code TestModuleBuilder.minimalDescriptor} fixes the
 * built jar's resource request at 16Mi memory / 10m cpu, same fixture convention {@code
 * DeploymentReconcilerTest} already uses.
 */
class TenantQuotaPluginTest {

  @TempDir(cleanup = CleanupMode.ON_SUCCESS)
  Path tempDir;

  private final TenantQuotaPlugin plugin = new TenantQuotaPlugin();

  @Test
  void untenanted_deployment_is_allowed_without_consulting_the_store() {
    DeploymentSpec spec = deployment("untenanted", Optional.empty());

    AdmissionDecision<WorkloadSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store(), Optional.empty()));

    assertEquals(spec, assertInstanceOf(AdmissionDecision.Allow.class, decision).spec());
  }

  @Test
  void deployment_for_an_unknown_tenant_is_rejected() {
    DeploymentSpec spec = deployment("orphan", Optional.of("does-not-exist"));

    AdmissionDecision<WorkloadSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store(), Optional.empty()));

    assertEquals(
        "unknown tenantId: does-not-exist",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  @Test
  void tenanted_deployment_with_an_unreadable_artifact_is_rejected() {
    StateStore store = store();
    store.putTenant(new Tenant("acme", new ResourceQuota(1_000_000_000L, 4000, 10)));
    DeploymentSpec spec = deployment("unreadable", Optional.of("acme"));

    AdmissionDecision<WorkloadSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.empty()));

    assertTrue(
        assertInstanceOf(AdmissionDecision.Reject.class, decision)
            .reason()
            .startsWith("cannot verify tenant quota: artifact unreadable at"));
  }

  @Test
  void deployment_exceeding_its_tenants_quota_is_rejected() {
    StateStore store = store();
    // The fixture's own request is 16Mi/10m; a one-instance ceiling below that is guaranteed to
    // be exceeded regardless of the exact byte/millicore values this fixture happens to use.
    store.putTenant(new Tenant("tight", new ResourceQuota(1, 1, 1)));
    Path jar = buildFixtureJar("com.gimle.fixture.admission.over");
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    DeploymentSpec spec = deployment("over-quota", jar, Optional.of("tight"));

    AdmissionDecision<WorkloadSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.of(artifact)));

    assertEquals(
        "workload over-quota would push tenant tight past its resource quota",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  @Test
  void deployment_within_its_tenants_quota_is_allowed() {
    StateStore store = store();
    store.putTenant(new Tenant("roomy", new ResourceQuota(1_000_000_000L, 4000, 10)));
    Path jar = buildFixtureJar("com.gimle.fixture.admission.within");
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    DeploymentSpec spec = deployment("within-quota", jar, Optional.of("roomy"));

    AdmissionDecision<WorkloadSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.of(artifact)));

    assertEquals(spec, assertInstanceOf(AdmissionDecision.Allow.class, decision).spec());
  }

  @Test
  void a_deployment_fitting_at_replicas_alone_but_not_with_surge_is_rejected() {
    StateStore store = store();
    // Sized to exactly the fixture's own per-instance request (16Mi/10m, one instance) -- fits
    // replicas=1 exactly (not exceeded), but a maxSurge:1 rollout would transiently need two
    // instances' worth, which this quota cannot cover. checkTenantQuota must sum against
    // replicas + maxSurge, not replicas alone, or it would wrongly allow this submission through.
    store.putTenant(new Tenant("surge-tight", new ResourceQuota(16L * 1024 * 1024, 10, 1)));
    Path jar = buildFixtureJar("com.gimle.fixture.admission.surge");
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    DeploymentSpec spec =
        deployment(
            "surging", jar, Optional.of("surge-tight"), Optional.of(new DisruptionBudget(1, 1)));

    AdmissionDecision<WorkloadSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.of(artifact)));

    assertEquals(
        "workload surging would push tenant surge-tight past its resource quota",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  @Test
  void the_same_tight_quota_with_no_surge_configured_is_allowed() {
    // Same quota as above, same replicas=1, but no disruption block (maxSurge defaults to 0) --
    // maxCommittedInstances() == replicas, and usage lands exactly at the quota ceiling, which
    // TenantUsage.Usage#exceeds treats as within bounds (a strict ">" comparison).
    StateStore store = store();
    store.putTenant(new Tenant("exact-fit", new ResourceQuota(16L * 1024 * 1024, 10, 1)));
    Path jar = buildFixtureJar("com.gimle.fixture.admission.exact");
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    DeploymentSpec spec = deployment("exact-fit-deployment", jar, Optional.of("exact-fit"));

    AdmissionDecision<WorkloadSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.of(artifact)));

    assertInstanceOf(AdmissionDecision.Allow.class, decision);
  }

  /** No jar is ever built or read for this fixture -- {@code artifactPath} is a dangling path. */
  private DeploymentSpec deployment(String name, Optional<String> tenantId) {
    return new DeploymentSpec(
        name,
        new ModuleId(name, Version.parse("1.0.0")),
        tempDir.resolve(name + ".jar").toAbsolutePath().toString(),
        1,
        PlacementConstraints.NONE,
        Optional.empty(),
        tenantId,
        Optional.empty());
  }

  private DeploymentSpec deployment(String name, Path jar, Optional<String> tenantId) {
    return deployment(name, jar, tenantId, Optional.empty());
  }

  private DeploymentSpec deployment(
      String name, Path jar, Optional<String> tenantId, Optional<DisruptionBudget> disruption) {
    return new DeploymentSpec(
        name,
        new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
        jar.toAbsolutePath().toString(),
        1,
        PlacementConstraints.NONE,
        Optional.empty(),
        tenantId,
        Optional.empty(),
        disruption);
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
