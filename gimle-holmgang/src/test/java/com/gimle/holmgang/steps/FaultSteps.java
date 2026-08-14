package com.gimle.holmgang.steps;

import com.gimle.holmgang.HolmgangException;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;

/** Steps injecting and healing network faults over a proxied topology's Loki links. */
public final class FaultSteps {

  private final ScenarioWorld world;

  public FaultSteps(final ScenarioWorld world) {
    this.world = world;
  }

  @When("the network between control plane {int} and all stores is cut")
  public void theNetworkBetweenControlPlaneAndAllStoresIsCut(final int controlPlaneIndex) {
    world.partitions.addLast(world.cluster().faults().cutControlPlaneFromStores(controlPlaneIndex));
  }

  @When("the partition heals")
  public void thePartitionHeals() {
    if (world.partitions.isEmpty()) {
      throw new HolmgangException("no partition is open in this scenario");
    }
    world.partitions.pollLast().heal();
  }

  @Then("within {int}s control plane {int} stops serving")
  public void controlPlaneStopsServing(final int seconds, final int controlPlaneIndex) {
    world
        .cluster()
        .when()
        .probe(
            "control plane " + controlPlaneIndex + " stops serving",
            () -> !world.cluster().api(controlPlaneIndex).isServing())
        .await(Duration.ofSeconds(seconds));
  }
}
