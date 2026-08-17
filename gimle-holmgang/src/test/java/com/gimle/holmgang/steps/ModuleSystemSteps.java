package com.gimle.holmgang.steps;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.fixtures.ModuleSystemFixtures;
import com.gimle.holmgang.steps.ScenarioWorld.DeployedModule;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Steps exercising module-system mechanics no committed example module reaches: cross-deployment
 * {@code requires:} dependency resolution and version-range gating, same-worker name-driven service
 * invocation and its version cutover, a hook that always fails, a hook that never releases its own
 * in-flight counter, and a manifest that fails its own parser validation. Every fixture built here
 * is {@code TIER_1}, so the node agent's own density packing (see {@code
 * AgentMain#findReusableTier1Worker}) is what makes these modules share one worker JVM's {@code
 * ModuleRegistry}/{@code ServiceRegistry} at all -- deployed one at a time, each awaited to {@code
 * ACTIVE} before the next is submitted, so the already-open worker connection the next deployment
 * reuses is never racing its own first connect.
 */
public final class ModuleSystemSteps {

  private static final String LIB_MODULE = "com.gimle.holmgang.fixtures.libgreeter";
  private static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(120);

  private final ScenarioWorld world;

  public ModuleSystemSteps(final ScenarioWorld world) {
    this.world = world;
  }

  @Given("the dependency library version {string} with greeting {string} deployed as {string}")
  public void theDependencyLibraryDeployedAs(
      final String version, final String greeting, final String deployment) {
    final Path jar = ModuleSystemFixtures.libGreeterJar(world.fixturesDir(), version, greeting);
    deployAndAwaitActive(deployment, LIB_MODULE, version, jar);
  }

  @When(
      "module {string} requiring the dependency library in version range {string} is deployed as"
          + " {string}")
  public void moduleRequiringTheDependencyLibraryIsDeployedAs(
      final String variant, final String versionRange, final String deployment) {
    final Path jar =
        ModuleSystemFixtures.dependentInvokerJar(world.fixturesDir(), variant, versionRange);
    submitOnly(deployment, "com.gimle.holmgang.fixtures.dependent" + variant, "1.0.0", jar);
  }

  @When("a service-cutover consumer is deployed as {string}")
  public void aServiceCutoverConsumerIsDeployedAs(final String deployment) {
    final Path jar = ModuleSystemFixtures.serviceCutoverInvokerJar(world.fixturesDir());
    deployAndAwaitActive(deployment, "com.gimle.holmgang.fixtures.cutoverconsumer", "1.0.0", jar);
  }

  @When("a module whose start hook always throws is deployed as {string}")
  public void aModuleWhoseStartHookAlwaysThrowsIsDeployedAs(final String deployment) {
    final Path jar = ModuleSystemFixtures.onStartThrowsJar(world.fixturesDir());
    submitOnly(deployment, "com.gimle.holmgang.fixtures.brokenstart", "1.0.0", jar);
  }

  @Given("a module with a perpetually in-flight request deployed as {string}")
  public void aModuleWithAPerpetuallyInFlightRequestDeployedAs(final String deployment) {
    final Path jar = ModuleSystemFixtures.perpetualInFlightJar(world.fixturesDir());
    deployAndAwaitActive(deployment, "com.gimle.holmgang.fixtures.stuckrequest", "1.0.0", jar);
  }

  @When("a module with a bogus isolation tier is deployed as {string}")
  public void aModuleWithABogusIsolationTierIsDeployedAs(final String deployment) {
    final Path jar = ModuleSystemFixtures.malformedManifestJar(world.fixturesDir(), "bad-tier");
    submitOnly(deployment, "com.gimle.holmgang.fixtures.malformedbadtier", "1.0.0", jar);
  }

  @When("a module whose resource request exceeds its own limit is deployed as {string}")
  public void aModuleWhoseResourceRequestExceedsItsOwnLimitIsDeployedAs(final String deployment) {
    final Path jar =
        ModuleSystemFixtures.malformedManifestJar(world.fixturesDir(), "request-exceeds-limit");
    submitOnly(
        deployment, "com.gimle.holmgang.fixtures.malformedrequestexceedslimit", "1.0.0", jar);
  }

  @When("a provider that always fails liveness with a {int}s initial delay is deployed as {string}")
  public void aProviderThatAlwaysFailsLivenessWithAnInitialDelayIsDeployedAs(
      final int initialDelaySeconds, final String deployment) {
    final Path jar =
        ModuleSystemFixtures.unhealthyProviderWithInitialDelayJar(
            world.fixturesDir(), initialDelaySeconds);
    submitOnly(deployment, "com.gimle.examples.greeter.provider", "1.0.0-delayed", jar);
  }

  /**
   * The control plane's own {@code GET /nodes} listing a node id proves the whole node-agent
   * bootstrap sequence already happened: the agent's registration call succeeded, at least one
   * heartbeat since has kept it listed, and (since every scenario in this file only ever reaches
   * {@code ACTIVE} through a real placement) its assignment-fetch loop is what delivered the
   * instances those scenarios observe.
   */
  @Then("node {string} is registered")
  public void nodeIsRegistered(final String nodeId) {
    assertTrue(
        world.cluster().api().nodeRegistered(nodeId),
        "node " + nodeId + " is not listed by the control plane's own /nodes");
  }

  private void submitOnly(
      final String deployment, final String moduleName, final String version, final Path jar) {
    world
        .cluster()
        .api()
        .submitDeployment(deployment, moduleName, version, jar, 1, Optional.empty());
    world.deployments.put(deployment, new DeployedModule(moduleName, version, 1, Optional.empty()));
  }

  private void deployAndAwaitActive(
      final String deployment, final String moduleName, final String version, final Path jar) {
    submitOnly(deployment, moduleName, version, jar);
    world.cluster().when().deployment(deployment).hasActiveReplicas(1).await(DEPLOY_TIMEOUT);
  }
}
