package com.gimle.holmgang.steps;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.gimle.holmgang.HolmgangException;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/** Steps operating on the cluster itself: acquisition, nodes, and worker processes. */
public final class ClusterSteps {

  private final ScenarioWorld world;

  public ClusterSteps(final ScenarioWorld world) {
    this.world = world;
  }

  @Given("a running cluster from topology {string}")
  public void aRunningClusterFromTopology(final String topologyName) {
    if (world.isDestructive()) {
      final ClusterPool.FreshCluster fresh = ClusterPool.fresh(topologyName);
      world.attachOwned(fresh.cluster(), fresh.workDir());
    } else {
      world.attachPooled(ClusterPool.pooled(topologyName));
    }
  }

  @When("node {string} is cordoned")
  public void nodeIsCordoned(final String nodeId) {
    world.cluster().api().cordonNode(nodeId);
    world.cordonedNodes.add(nodeId);
  }

  @When("node {string} is uncordoned")
  public void nodeIsUncordoned(final String nodeId) {
    world.cluster().api().uncordonNode(nodeId);
    world.cordonedNodes.remove(nodeId);
  }

  @When("the worker hosting instance {int} of {string} is killed")
  public void theWorkerHostingInstanceIsKilled(final int instanceIndex, final String deployment) {
    final ProcessHandle worker =
        world
            .cluster()
            .workerFor(deployment, instanceIndex)
            .orElseThrow(
                () ->
                    new HolmgangException(
                        "no live worker found for " + deployment + "#" + instanceIndex));
    world.workerPids.put(deployment + "#" + instanceIndex, worker.pid());
    worker.destroyForcibly();
  }

  @Then("instance {int} of {string} is hosted by a different worker than before")
  public void instanceIsHostedByADifferentWorker(final int instanceIndex, final String deployment) {
    final String key = deployment + "#" + instanceIndex;
    final Long previousPid = world.workerPids.get(key);
    if (previousPid == null) {
      throw new HolmgangException("no worker pid was recorded for " + key + " before this step");
    }
    final ProcessHandle worker =
        world
            .cluster()
            .workerFor(deployment, instanceIndex)
            .orElseThrow(() -> new HolmgangException("no live worker found for " + key));
    assertNotEquals(
        (long) previousPid,
        worker.pid(),
        "the instance should be hosted by a respawned worker process, not the killed one");
  }
}
