package com.gimle.holmgang.steps;

import com.gimle.holmgang.topology.QuotaSpec;
import com.gimle.testkit.heimdall.Invariants;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;

/** Steps provisioning tenants, quotas, and secrets. */
public final class TenantSteps {

  private final ScenarioWorld world;

  public TenantSteps(final ScenarioWorld world) {
    this.world = world;
  }

  @Given("a tenant {string} with quota {long} bytes memory, {long} millicores and {int} instances")
  public void aTenantWithQuota(
      final String tenantId,
      final long maxMemoryBytes,
      final long maxCpuMillicores,
      final int maxInstances) {
    world
        .cluster()
        .api()
        .putTenant(tenantId, QuotaSpec.of(maxMemoryBytes, maxCpuMillicores, maxInstances));
    world.tenants.add(tenantId);
  }

  @When(
      "tenant {string} quota is lowered to {long} bytes memory, {long} millicores and {int} instances")
  public void tenantQuotaIsLowered(
      final String tenantId,
      final long maxMemoryBytes,
      final long maxCpuMillicores,
      final int maxInstances) {
    world
        .cluster()
        .api()
        .putTenant(tenantId, QuotaSpec.of(maxMemoryBytes, maxCpuMillicores, maxInstances));
  }

  /**
   * A direct read-side check for a tenant written outside the normal deploy/workload steps --
   * bounded, not single-shot: {@code StoreClient}'s own reads round-robin across every configured
   * store endpoint with no leader-awareness (see its own javadoc), so a read landing on a replica
   * that has only just rejoined or been promoted can legitimately still be catching up for a brief
   * real window after the cluster as a whole already accepts writes again.
   */
  @Then("within {int}s tenant {string} is readable")
  public void withinSecondsTenantIsReadable(final int seconds, final String tenantId) {
    world
        .cluster()
        .when()
        .probe(
            "tenant " + tenantId + " is readable",
            () -> world.cluster().api().tenantExists(tenantId))
        .await(Duration.ofSeconds(seconds));
  }

  @Given("secret {string} = {string} for tenant {string}")
  public void secretForTenant(final String key, final String value, final String tenantId) {
    world.cluster().api().putSecret(tenantId, key, value);
  }

  @Then("within {int}s deployment {string} reports a quota violation")
  public void deploymentReportsAQuotaViolation(final int seconds, final String deployment) {
    world
        .cluster()
        .when()
        .deployment(deployment)
        .reportsQuotaViolation()
        .await(Duration.ofSeconds(seconds));
  }

  @Then("deployment {string} keeps {int} ACTIVE instance(s) for {int}s")
  public void deploymentKeepsActiveInstancesFor(
      final String deployment, final int count, final int seconds) {
    world
        .cluster()
        .holdInvariantFor(
            Invariants.deployment(deployment).minActiveReplicas(count),
            Duration.ofSeconds(seconds));
  }
}
