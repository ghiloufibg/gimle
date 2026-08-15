package com.gimle.holmgang.steps;

import io.cucumber.java.en.Then;
import java.time.Duration;

/** Steps asserting on data shipped to and read back from Muninn. */
public final class ObservabilitySteps {

  private final ScenarioWorld world;

  public ObservabilitySteps(final ScenarioWorld world) {
    this.world = world;
  }

  /**
   * Drives real traffic against a control-plane replica and asserts the resulting request counter
   * is readable back through {@code GET /metrics-history/*} -- a route with no live-process
   * fallback, so a hit here can only come from Muninn's own shipped history. Each poll issues a
   * fresh request rather than relying on incidental traffic from elsewhere in the scenario, so the
   * assertion is self-contained even on a freshly booted cluster.
   */
  @Then("within {int}s the control plane's own request metrics are shipped to muninn")
  public void theControlPlanesOwnRequestMetricsAreShippedToMuninn(final int seconds) {
    final String processId = world.cluster().controlPlane(0).endpoint();
    world
        .cluster()
        .when()
        .probe(
            "control-plane metrics history shows a request count via muninn",
            () -> {
              world.cluster().api().isServing();
              return world
                  .cluster()
                  .api()
                  .metricsHistoryContains(
                      "CONTROLPLANE", processId, "gimle.controlplane.request.count");
            })
        .await(Duration.ofSeconds(seconds));
  }
}
