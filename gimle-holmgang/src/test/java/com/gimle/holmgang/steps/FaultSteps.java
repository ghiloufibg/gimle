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

  /**
   * The follower counterpart to {@link #theStoreLeaderIsPartitionedFromItsPeers}: resolves the
   * currently-leading store first and isolates a different index -- never the leader itself -- so
   * the isolated node is guaranteed to be a follower that falls behind while cut off, not whichever
   * role happened to be leading at the time. What a scenario proving the candidate log up-to-date
   * election check needs: a stale node that later campaigns with a high term but an outdated log.
   */
  @When("a non-leader store is partitioned from its peers")
  public void aNonLeaderStoreIsPartitionedFromItsPeers() {
    final int leaderIndex = world.cluster().storeLeaderIndex();
    final int nonLeaderIndex = (leaderIndex + 1) % world.cluster().storeCount();
    world.isolatedStoreIndex = nonLeaderIndex;
    world.partitions.addLast(world.cluster().faults().cutStoreFromPeers(nonLeaderIndex));
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
   * The election-safety proof: waits for any store to be reported as leader again (a mid-election
   * gap first, necessarily), then asserts that leader is not the isolated node -- the Raft log
   * up-to-date check means a candidate whose log lags can never win a vote, however high its own
   * term has climbed while cut off, so the first leader reported after recovery already settles
   * this; no bounded observation window is needed the way a liveness property would.
   */
  @Then("within {int}s the isolated store never wins the election that follows")
  public void withinSecondsTheIsolatedStoreNeverWinsTheElectionThatFollows(final int seconds) {
    if (world.isolatedStoreIndex == null) {
      throw new HolmgangException("no store was partitioned in this scenario");
    }
    final int storeIndex = world.isolatedStoreIndex;
    world
        .cluster()
        .when()
        .probe(
            "a store leader is reported again", () -> world.cluster().storeLeaderId().isPresent())
        .await(Duration.ofSeconds(seconds));
    final String leaderId =
        world
            .cluster()
            .storeLeaderId()
            .orElseThrow(() -> new HolmgangException("no store leader is currently known"));
    final String staleId = world.cluster().storeRaftId(storeIndex);
    if (staleId.equals(leaderId)) {
      throw new HolmgangException(
          "store "
              + storeIndex
              + " (the stale, partitioned-then-healed node) won the election as "
              + leaderId
              + " -- the candidate log up-to-date check should have rejected its vote");
    }
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
