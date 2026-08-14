package com.gimle.controlplane.admission;

import static com.gimle.controlplane.admission.PolicyConfigPlugin.MAX_REPLICAS_PER_DEPLOYMENT_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Verb;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.store.StateStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit-level coverage of {@link PolicyConfigPlugin}, a policy-as-data admission plugin opt-in per
 * tenant via a plain {@code /config/*} entry.
 */
class PolicyConfigPluginTest {

  @TempDir(cleanup = CleanupMode.ON_SUCCESS)
  Path tempDir;

  private final PolicyConfigPlugin plugin = new PolicyConfigPlugin();

  @Test
  void untenanted_deployment_is_allowed_without_consulting_the_store() {
    DeploymentSpec spec = deployment("untenanted", 100, Optional.empty());

    AdmissionDecision<DeploymentSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store(), Optional.empty()));

    assertInstanceOf(AdmissionDecision.Allow.class, decision);
  }

  @Test
  void a_tenant_with_no_policy_configured_imposes_no_ceiling() {
    DeploymentSpec spec = deployment("unrestricted", 1000, Optional.of("acme"));

    AdmissionDecision<DeploymentSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store(), Optional.empty()));

    assertInstanceOf(AdmissionDecision.Allow.class, decision);
  }

  @Test
  void a_deployment_within_the_configured_ceiling_is_allowed() {
    StateStore store = store();
    setPolicy(store, "acme", 50);
    DeploymentSpec spec = deployment("within-ceiling", 10, Optional.of("acme"));

    AdmissionDecision<DeploymentSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.empty()));

    assertInstanceOf(AdmissionDecision.Allow.class, decision);
  }

  @Test
  void a_deployment_exceeding_the_configured_ceiling_is_rejected() {
    StateStore store = store();
    setPolicy(store, "acme", 5);
    DeploymentSpec spec = deployment("over-ceiling", 6, Optional.of("acme"));

    AdmissionDecision<DeploymentSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.empty()));

    assertEquals(
        "deployment over-ceiling requests 6 replicas, exceeding tenant acme's policy ceiling of 5"
            + " (policy.maxReplicasPerDeployment)",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  @Test
  void exactly_at_the_ceiling_is_allowed() {
    StateStore store = store();
    setPolicy(store, "acme", 5);
    DeploymentSpec spec = deployment("exact-fit", 5, Optional.of("acme"));

    AdmissionDecision<DeploymentSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.empty()));

    assertInstanceOf(AdmissionDecision.Allow.class, decision);
  }

  @Test
  void a_malformed_policy_value_is_rejected_rather_than_silently_ignored() {
    StateStore store = store();
    store.putConfigEntry(
        new ConfigEntry(
            "acme",
            MAX_REPLICAS_PER_DEPLOYMENT_KEY,
            "not-a-number".getBytes(StandardCharsets.UTF_8),
            false));
    DeploymentSpec spec = deployment("malformed-policy", 1, Optional.of("acme"));

    AdmissionDecision<DeploymentSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.empty()));

    assertEquals(
        "policy rule 'policy.maxReplicasPerDeployment' for tenant acme is not a valid integer",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  @Test
  void an_encrypted_policy_entry_is_rejected() {
    StateStore store = store();
    store.putConfigEntry(
        new ConfigEntry(
            "acme", MAX_REPLICAS_PER_DEPLOYMENT_KEY, "5".getBytes(StandardCharsets.UTF_8), true));
    DeploymentSpec spec = deployment("encrypted-policy", 1, Optional.of("acme"));

    AdmissionDecision<DeploymentSpec> decision =
        plugin.review(
            new AdmissionRequest<>(
                ResourceKind.DEPLOYMENT, Verb.WRITE, spec, store, Optional.empty()));

    assertEquals(
        "policy rule 'policy.maxReplicasPerDeployment' for tenant acme must not be an encrypted"
            + " config entry",
        assertInstanceOf(AdmissionDecision.Reject.class, decision).reason());
  }

  private static void setPolicy(StateStore store, String tenantId, int maxReplicas) {
    store.putConfigEntry(
        new ConfigEntry(
            tenantId,
            MAX_REPLICAS_PER_DEPLOYMENT_KEY,
            Integer.toString(maxReplicas).getBytes(StandardCharsets.UTF_8),
            false));
  }

  private DeploymentSpec deployment(String name, int replicas, Optional<String> tenantId) {
    return new DeploymentSpec(
        name,
        new ModuleId(name, Version.parse("1.0.0")),
        tempDir.resolve(name + ".jar").toAbsolutePath().toString(),
        replicas,
        PlacementConstraints.NONE,
        Optional.empty(),
        tenantId,
        Optional.empty());
  }

  private StateStore store() {
    return new StateStore(tempDir.resolve("policy-config-plugin-store"));
  }
}
