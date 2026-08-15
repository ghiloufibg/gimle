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

  /**
   * Silently partitions whichever store currently leads away from every other store's raft traffic
   * -- reachable by nothing on the peer side, but not killed -- and remembers which index that was,
   * since the cluster-wide notion of "the leader" moves on the moment a new one is elected.
   */
  @When("the store leader is partitioned from its peers")
  public void theStoreLeaderIsPartitionedFromItsPeers() {
    final int leaderIndex = world.cluster().storeLeaderIndex();
    world.isolatedStoreIndex = leaderIndex;
    world.partitions.addLast(world.cluster().faults().cutStoreFromPeers(leaderIndex));
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

  /**
   * Reads the isolated node's own status directly off its real client port (see {@link
   * com.gimle.holmgang.cluster.GimleCluster#storeStatus}) so this proves the leader's own
   * check-quorum self-demotion actually ran, not merely that the rest of the cluster elected
   * someone else.
   */
  @Then("within {int}s the isolated store leader steps down")
  public void withinSecondsTheIsolatedStoreLeaderStepsDown(final int seconds) {
    if (world.isolatedStoreIndex == null) {
      throw new HolmgangException("no store leader was partitioned in this scenario");
    }
    final int index = world.isolatedStoreIndex;
    world
        .cluster()
        .when()
        .probe(
            "store " + index + " (the isolated former leader) steps down",
            () -> !world.cluster().storeStatus(index).leader())
        .await(Duration.ofSeconds(seconds));
  }
}
