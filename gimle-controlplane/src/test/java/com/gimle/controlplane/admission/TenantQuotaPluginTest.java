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
        "workload over-quota would push tenant tight past its resource quota: memory 16Mi exceeds"
            + " the 1 ceiling by 16777215 (0 already assigned + 16Mi for this workload); cpu 10m"
            + " exceeds the 1m ceiling by 9m (0m already assigned + 10m for this workload)",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  /**
   * A rejection naming only "past its quota" leaves an operator guessing which of three independent
   * dimensions tripped. Each of the next three tests exceeds exactly one, and asserts the message
   * names that one, its numbers, and the overage -- and stays silent about the two that fit.
   */
  @Test
  void a_memory_only_overage_names_memory_its_ceiling_and_the_overage() {
    StateStore store = store();
    // The fixture requests 16Mi/10m per instance; only the memory ceiling is below that.
    store.putTenant(new Tenant("mem-tight", new ResourceQuota(8L * 1024 * 1024, 4000, 10)));
    Path jar = buildFixtureJar("com.gimle.fixture.admission.memonly");
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    DeploymentSpec spec = deployment("mem-hog", jar, Optional.of("mem-tight"));

    AdmissionDecision<WorkloadSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.of(artifact)));

    assertEquals(
        "workload mem-hog would push tenant mem-tight past its resource quota: memory 16Mi exceeds"
            + " the 8Mi ceiling by 8Mi (0 already assigned + 16Mi for this workload)",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  @Test
  void a_cpu_only_overage_names_cpu_its_ceiling_and_the_overage() {
    StateStore store = store();
    store.putTenant(new Tenant("cpu-tight", new ResourceQuota(1_000_000_000L, 4, 10)));
    Path jar = buildFixtureJar("com.gimle.fixture.admission.cpuonly");
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    DeploymentSpec spec = deployment("cpu-hog", jar, Optional.of("cpu-tight"));

    AdmissionDecision<WorkloadSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.of(artifact)));

    assertEquals(
        "workload cpu-hog would push tenant cpu-tight past its resource quota: cpu 10m exceeds the"
            + " 4m ceiling by 6m (0m already assigned + 10m for this workload)",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  @Test
  void an_instance_count_only_overage_names_the_instance_ceiling_and_the_overage() {
    StateStore store = store();
    store.putTenant(new Tenant("no-instances", new ResourceQuota(1_000_000_000L, 4000, 0)));
    Path jar = buildFixtureJar("com.gimle.fixture.admission.instances");
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    DeploymentSpec spec = deployment("one-too-many", jar, Optional.of("no-instances"));

    AdmissionDecision<WorkloadSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.of(artifact)));

    assertEquals(
        "workload one-too-many would push tenant no-instances past its resource quota: instances 1"
            + " exceeds the 0 ceiling by 1 (0 already assigned + 1 for this workload)",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  /**
   * The split between what the tenant already has assigned and what this submission would add is
   * the actionable half of the message: it tells an operator whether to shrink this workload or
   * free up an existing one.
   */
  @Test
  void the_overage_separates_already_assigned_usage_from_this_submissions_own_addition() {
    StateStore store = store();
    store.putTenant(new Tenant("busy", new ResourceQuota(24L * 1024 * 1024, 4000, 10)));
    Path jar = buildFixtureJar("com.gimle.fixture.admission.busy");
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    store.putDeployment(deployment("already-running", jar, Optional.of("busy")));
    DeploymentSpec spec = deployment("newcomer", jar, Optional.of("busy"));

    AdmissionDecision<WorkloadSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.of(artifact)));

    assertEquals(
        "workload newcomer would push tenant busy past its resource quota: memory 32Mi exceeds the"
            + " 24Mi ceiling by 8Mi (16Mi already assigned + 16Mi for this workload)",
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
        "workload surging would push tenant surge-tight past its resource quota: memory 32Mi"
            + " exceeds the 16Mi ceiling by 16Mi (0 already assigned + 32Mi for this workload); cpu"
            + " 20m exceeds the 10m ceiling by 10m (0m already assigned + 20m for this workload);"
            + " instances 2 exceeds the 1 ceiling by 1 (0 already assigned + 2 for this workload)",
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

  /**
   * The tenant every manifest without an explicit {@code tenantId} lands in is a real tenant with a
   * real, operator-editable quota row -- once an operator narrows that row, the number they wrote
   * has to be the number enforced. Exempting it by name meant a quota could be set, reported back
   * by {@code gimle get tenants}, and never applied to a single submission.
   */
  @Test
  void a_quota_an_operator_set_on_the_default_tenant_is_enforced() {
    StateStore store = store();
    // The fixture requests 16Mi/10m per instance; a one-instance ceiling below that cannot fit.
    store.putTenant(new Tenant(Tenant.DEFAULT_TENANT_ID, new ResourceQuota(1, 1, 1)));
    Path jar = buildFixtureJar("com.gimle.fixture.admission.defaulttenant");
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    DeploymentSpec spec = deployment("over-quota", jar, Optional.of(Tenant.DEFAULT_TENANT_ID));

    AdmissionDecision<WorkloadSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.of(artifact)));

    assertTrue(
        assertInstanceOf(AdmissionDecision.Reject.class, decision)
            .reason()
            .startsWith("workload over-quota would push tenant default past its resource quota:"),
        String.valueOf(decision));
  }

  /** The instance dimension specifically, the one the quota screenshot in the field reported. */
  @Test
  void a_default_tenant_submission_that_would_exceed_the_instance_ceiling_is_rejected() {
    StateStore store = store();
    ResourceQuota oneInstanceOnly = new ResourceQuota(64L * 1024 * 1024 * 1024, 64_000, 1);
    store.putTenant(new Tenant(Tenant.DEFAULT_TENANT_ID, oneInstanceOnly));
    Path jar = buildFixtureJar("com.gimle.fixture.admission.defaultinstances");
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    store.putDeployment(deployment("already-running", jar, Optional.of(Tenant.DEFAULT_TENANT_ID)));
    DeploymentSpec second = deployment("second", jar, Optional.of(Tenant.DEFAULT_TENANT_ID));

    AdmissionDecision<WorkloadSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, second, store, Optional.of(artifact)));

    assertTrue(
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason().contains("instances"),
        String.valueOf(decision));
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
