package com.gimle.holmgang.steps;

import com.gimle.holmgang.topology.LimitRangeSpec;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;

/**
 * Steps provisioning tenant LimitRanges -- the per-workload counterpart to {@link TenantSteps}' own
 * aggregate-quota steps. Both scenarios in {@code limitrange.feature} need only a max-request
 * bound, so this deliberately covers just that one shape today (see {@link
 * LimitRangeSpec#maxRequest}), the same "no more than the feature file needs" posture {@link
 * TenantSteps} already establishes.
 */
public final class LimitRangeSteps {

  private final ScenarioWorld world;

  public LimitRangeSteps(final ScenarioWorld world) {
    this.world = world;
  }

  @Given("a limitrange for tenant {string} with max request memory {string} and cpu {string}")
  public void aLimitRangeForTenantWithMaxRequest(
      final String tenantId, final String memory, final String cpu) {
    world.cluster().api().putLimitRange(tenantId, LimitRangeSpec.maxRequest(memory, cpu));
  }

  @When(
      "limitrange for tenant {string} is tightened to max request memory {string} and cpu"
          + " {string}")
  public void limitRangeForTenantIsTightened(
      final String tenantId, final String memory, final String cpu) {
    world.cluster().api().putLimitRange(tenantId, LimitRangeSpec.maxRequest(memory, cpu));
  }

  @Then("within {int}s deployment {string} reports a limit range violation")
  public void deploymentReportsALimitRangeViolation(final int seconds, final String deployment) {
    world
        .cluster()
        .when()
        .deployment(deployment)
        .reportsLimitRangeViolation()
        .await(Duration.ofSeconds(seconds));
  }
}
