package com.gimle.holmgang.steps;

import io.cucumber.java.en.Then;
import java.time.Duration;

/** Steps asserting on an instance's own application log via the live follow stream. */
public final class LogSteps {

  private final ScenarioWorld world;

  public LogSteps(final ScenarioWorld world) {
    this.world = world;
  }

  @Then("within {int}s instance {int} of {string} logs {string}")
  public void instanceLogs(
      final int seconds, final int instanceIndex, final String deployment, final String text) {
    world
        .cluster()
        .when()
        .instanceLog(deployment, instanceIndex, "APPLICATION")
        .contains(text)
        .await(Duration.ofSeconds(seconds));
  }
}
