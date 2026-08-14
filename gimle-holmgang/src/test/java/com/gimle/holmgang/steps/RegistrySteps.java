package com.gimle.holmgang.steps;

import io.cucumber.java.en.When;

/** Steps pushing module artifacts to the registry the cluster resolves coordinates through. */
public final class RegistrySteps {

  private final ScenarioWorld world;

  public RegistrySteps(final ScenarioWorld world) {
    this.world = world;
  }

  @When("module {string} version {string} is pushed to the artifact registry")
  public void moduleIsPushedToTheArtifactRegistry(final String artifact, final String version) {
    world
        .cluster()
        .api()
        .pushArtifact(ExampleModules.moduleName(artifact), version, ExampleModules.jar(artifact));
  }
}
