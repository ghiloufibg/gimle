package com.gimle.holmgang.steps;

import com.gimle.holmgang.load.LoadGenerator;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.Optional;

/** Steps driving real Gatling load and asserting the cluster's autoscaling reaction to it. */
public final class LoadSteps {

  private final ScenarioWorld world;

  public LoadSteps(final ScenarioWorld world) {
    this.world = world;
  }

  @Given(
      "an autoscaling deployment {string} of provider {string} with min {int} max {int} replicas targeting {double} requests per second")
  public void anAutoscalingProviderDeployment(
      final String deployment,
      final String version,
      final int minReplicas,
      final int maxReplicas,
      final double targetRequestRatePerSecond) {
    world
        .cluster()
        .api()
        .submitAutoscaleDeployment(
            deployment,
            ExampleModules.moduleName("greeter-provider"),
            version,
            ExampleModules.jar("greeter-provider"),
            minReplicas,
            maxReplicas,
            // Deliberately unreachable, so only the request-rate signal can drive scaling and a
            // passing scenario is unambiguous about which signal it proved.
            200,
            Optional.of(targetRequestRatePerSecond));
    world.deployments.put(
        deployment,
        new ScenarioWorld.DeployedModule(
            ExampleModules.moduleName("greeter-provider"), version, minReplicas, Optional.empty()));
    world
        .cluster()
        .when()
        .deployment(deployment)
        .hasActiveReplicas(minReplicas)
        .await(Duration.ofSeconds(120));
  }

  @When(
      "an open-model load of {int} requests per second runs for {int}s against the load generator")
  public void anOpenModelLoadRuns(final int requestsPerSecond, final int durationSeconds) {
    world.loadProcesses.add(
        LoadGenerator.openModel(
            requestsPerSecond,
            durationSeconds,
            world.fixturesDir().resolve("gatling-results"),
            world.fixturesDir().resolve("gatling.log")));
  }

  @Then("within {int}s deployment {string} has {int} ACTIVE replicas")
  public void deploymentHasActiveReplicas(
      final int seconds, final String deployment, final int count) {
    world
        .cluster()
        .when()
        .deployment(deployment)
        .hasActiveReplicas(count)
        .await(Duration.ofSeconds(seconds));
  }
}
