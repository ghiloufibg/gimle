package com.gimle.controlplane.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Verb;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.LimitRangeSpec;
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
 * Unit-level coverage of {@link LimitRangePlugin}'s own decision logic, complementary to {@code
 * ApiServerLimitRangesTest}'s HTTP-level coverage of the same behavior. Every fixture jar built
 * here declares a fixed {@code resources.request}/{@code resources.limit} of 128Mi/100m and
 * 256Mi/200m -- the exact values each test's {@link LimitRangeSpec} bound is set relative to.
 */
class LimitRangePluginTest {

  private static final String REQUEST_MEMORY = "128Mi";
  private static final String REQUEST_CPU = "100m";
  private static final String LIMIT_MEMORY = "256Mi";
  private static final String LIMIT_CPU = "200m";

  @TempDir(cleanup = CleanupMode.ON_SUCCESS)
  Path tempDir;

  private final LimitRangePlugin plugin = new LimitRangePlugin();

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
  void a_tenant_with_no_limit_range_is_allowed_without_consulting_the_artifact() {
    DeploymentSpec spec = deployment("no-range", Optional.of("tenant-a"));

    AdmissionDecision<WorkloadSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store(), Optional.empty()));

    assertEquals(spec, assertInstanceOf(AdmissionDecision.Allow.class, decision).spec());
  }

  @Test
  void an_unreadable_artifact_for_a_ranged_tenant_is_rejected() {
    StateStore store = store();
    store.putLimitRange(
        new LimitRangeSpec(
            "tenant-a",
            Optional.of(new ResourceSpec("1Mi", "1m")),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    DeploymentSpec spec = deployment("unreadable", Optional.of("tenant-a"));

    AdmissionDecision<WorkloadSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.empty()));

    assertTrue(
        assertInstanceOf(AdmissionDecision.Reject.class, decision)
            .reason()
            .startsWith("cannot verify limit range: artifact unreadable at"));
  }

  @Test
  void a_request_below_the_minimum_is_rejected() {
    assertRejected(
        "minrequest",
        new LimitRangeSpec(
            "tenant-a",
            Optional.of(new ResourceSpec("256Mi", "100m")),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
  }

  @Test
  void a_request_above_the_maximum_is_rejected() {
    assertRejected(
        "maxrequest",
        new LimitRangeSpec(
            "tenant-a",
            Optional.empty(),
            Optional.of(new ResourceSpec("64Mi", "50m")),
            Optional.empty(),
            Optional.empty()));
  }

  @Test
  void a_limit_below_the_minimum_is_rejected() {
    assertRejected(
        "minlimit",
        new LimitRangeSpec(
            "tenant-a",
            Optional.empty(),
            Optional.empty(),
            Optional.of(new ResourceSpec("512Mi", "400m")),
            Optional.empty()));
  }

  @Test
  void a_limit_above_the_maximum_is_rejected() {
    assertRejected(
        "maxlimit",
        new LimitRangeSpec(
            "tenant-a",
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(new ResourceSpec("128Mi", "100m"))));
  }

  @Test
  void a_value_exactly_at_the_boundary_is_allowed() {
    LimitRangeSpec range =
        new LimitRangeSpec(
            "tenant-a",
            Optional.of(new ResourceSpec(REQUEST_MEMORY, REQUEST_CPU)),
            Optional.of(new ResourceSpec(REQUEST_MEMORY, REQUEST_CPU)),
            Optional.of(new ResourceSpec(LIMIT_MEMORY, LIMIT_CPU)),
            Optional.of(new ResourceSpec(LIMIT_MEMORY, LIMIT_CPU)));

    assertAllowed("exactboundary", range);
  }

  @Test
  void a_deployment_satisfying_every_bound_is_allowed() {
    LimitRangeSpec range =
        new LimitRangeSpec(
            "tenant-a",
            Optional.of(new ResourceSpec("64Mi", "50m")),
            Optional.of(new ResourceSpec("256Mi", "200m")),
            Optional.of(new ResourceSpec("128Mi", "100m")),
            Optional.of(new ResourceSpec("512Mi", "400m")));

    assertAllowed("withinevery", range);
  }

  private void assertRejected(String uniqueName, LimitRangeSpec range) {
    StateStore store = store();
    store.putLimitRange(range);
    Path jar = buildFixtureJar("com.gimle.fixture.admission.limitrange" + uniqueName);
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    DeploymentSpec spec = deployment("ranged", jar, Optional.of("tenant-a"));

    AdmissionDecision<WorkloadSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.of(artifact)));

    assertInstanceOf(AdmissionDecision.Reject.class, decision);
  }

  private void assertAllowed(String uniqueName, LimitRangeSpec range) {
    StateStore store = store();
    store.putLimitRange(range);
    Path jar = buildFixtureJar("com.gimle.fixture.admission.limitrangeok" + uniqueName);
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    DeploymentSpec spec = deployment("ranged-ok", jar, Optional.of("tenant-a"));

    AdmissionDecision<WorkloadSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.of(artifact)));

    assertEquals(spec, assertInstanceOf(AdmissionDecision.Allow.class, decision).spec());
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
    return new DeploymentSpec(
        name,
        new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
        jar.toAbsolutePath().toString(),
        1,
        PlacementConstraints.NONE,
        Optional.empty(),
        tenantId,
        Optional.empty());
  }

  private Path buildFixtureJar(String uniqueName) {
    return TestModuleBuilder.module("module " + uniqueName + " {\n}\n")
        .withDescriptor(
            """
            name: %s
            version: 1.0.0
            isolation:
              tier: TIER_1
            resources:
              request:
                memory: %s
                cpu: %s
              limit:
                memory: %s
                cpu: %s
            """
                .formatted(uniqueName, REQUEST_MEMORY, REQUEST_CPU, LIMIT_MEMORY, LIMIT_CPU))
        .build(tempDir, uniqueName + ".jar");
  }

  private StateStore store() {
    return new StateStore();
  }
}
