package com.gimle.holmgang.steps;

import com.gimle.holmgang.HolmgangException;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;

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

  @When("store {int} is killed")
  public void storeIsKilled(final int storeIndex) {
    world.cluster().store(storeIndex).killWithDescendants();
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

  /**
   * The real proof of worker-tier self-healing: the platform respawns a killed worker faster than
   * the control-plane-visible state can be relied on to ever show a non-ACTIVE view, so recovery is
   * observed at the process level -- a live worker for the same instance whose pid differs from the
   * one recorded at kill time.
   */
  @Then("within {int}s instance {int} of {string} is hosted by a new worker")
  public void instanceIsHostedByANewWorker(
      final int seconds, final int instanceIndex, final String deployment) {
    final String key = deployment + "#" + instanceIndex;
    final Long previousPid = world.workerPids.get(key);
    if (previousPid == null) {
      throw new HolmgangException("no worker pid was recorded for " + key + " before this step");
    }
    world
        .cluster()
        .when()
        .probe(
            "a new worker (not pid " + previousPid + ") hosts " + key,
            () ->
                world
                    .cluster()
                    .workerFor(deployment, instanceIndex)
                    .map(worker -> worker.isAlive() && worker.pid() != previousPid)
                    .orElse(false))
        .await(Duration.ofSeconds(seconds));
  }
}
