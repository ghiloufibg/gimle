package com.gimle.holmgang.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.cluster.ClusterApi;
import com.gimle.holmgang.fixtures.ModuleFixtures;
import com.gimle.holmgang.steps.ScenarioWorld.DeployedModule;
import com.gimle.testkit.heimdall.Invariants;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** Steps submitting, mutating, and asserting on deployments. */
public final class DeploymentSteps {

  private static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(120);

  private final ScenarioWorld world;

  public DeploymentSteps(final ScenarioWorld world) {
    this.world = world;
  }

  @Given("module {string} version {string} deployed with {int} replica(s) as {string}")
  public void moduleDeployed(
      final String artifact, final String version, final int replicas, final String deployment) {
    deploy(artifact, version, replicas, deployment, Optional.empty());
  }

  @Given(
      "module {string} version {string} deployed with {int} replica(s) as {string} for tenant {string}")
  public void moduleDeployedForTenant(
      final String artifact,
      final String version,
      final int replicas,
      final String deployment,
      final String tenantId) {
    deploy(artifact, version, replicas, deployment, Optional.of(tenantId));
  }

  /** Submit-only: no readiness await, for scenarios about placement being blocked or failing. */
  @When("module {string} version {string} submitted with {int} replica(s) as {string}")
  public void moduleSubmitted(
      final String artifact, final String version, final int replicas, final String deployment) {
    world
        .cluster()
        .api()
        .submitDeployment(
            deployment,
            ExampleModules.moduleName(artifact),
            version,
            ExampleModules.jar(artifact),
            replicas,
            Optional.empty());
    world.deployments.put(
        deployment,
        new DeployedModule(
            ExampleModules.moduleName(artifact), version, replicas, Optional.empty()));
  }

  /**
   * The registry path: the submission names a module coordinate and no artifact path at all, so
   * reaching ACTIVE requires the control plane and the placing agent to each resolve it themselves.
   */
  @When(
      "module {string} version {string} submitted from the registry with {int} replica(s) as"
          + " {string}")
  public void moduleSubmittedFromTheRegistry(
      final String artifact, final String version, final int replicas, final String deployment) {
    final String moduleName = ExampleModules.moduleName(artifact);
    world
        .cluster()
        .api()
        .submitDeploymentByCoordinate(deployment, moduleName, version, replicas, Optional.empty());
    world.deployments.put(
        deployment, new DeployedModule(moduleName, version, replicas, Optional.empty()));
  }

  @When("a provider that always fails liveness is deployed as {string}")
  public void anAlwaysUnhealthyProviderIsDeployedAs(final String deployment) {
    final Path jar = ModuleFixtures.alwaysUnhealthyProviderJar(world.fixturesDir());
    world
        .cluster()
        .api()
        .submitDeployment(
            deployment,
            "com.gimle.examples.greeter.provider",
            "1.0.0-unhealthy",
            jar,
            1,
            Optional.empty());
    world.deployments.put(
        deployment,
        new DeployedModule(
            "com.gimle.examples.greeter.provider", "1.0.0-unhealthy", 1, Optional.empty()));
  }

  /**
   * A hosted module that calls {@code ctx.reportPort} from its own {@code onStart}, so a Service
   * fronting this deployment can resolve a live endpoint the same way it already can for a Vessel
   * workload's allocated port -- awaited to ACTIVE, matching {@link #deploy}'s "deployed" verb.
   */
  @Given("a module reporting its own port is deployed as {string}")
  public void aModuleReportingItsOwnPortIsDeployedAs(final String deployment) {
    final Path jar = ModuleFixtures.portReportingProviderJar(world.fixturesDir());
    world
        .cluster()
        .api()
        .submitDeployment(
            deployment,
            "com.gimle.examples.greeter.provider",
            "1.0.0-portreporting",
            jar,
            1,
            Optional.empty());
    world.deployments.put(
        deployment,
        new DeployedModule(
            "com.gimle.examples.greeter.provider", "1.0.0-portreporting", 1, Optional.empty()));
    world.cluster().when().deployment(deployment).hasActiveReplicas(1).await(DEPLOY_TIMEOUT);
  }

  @When(
      "{string} is rolled to a rebuilt provider version {string} with maxUnavailable {int} and maxSurge {int}")
  public void isRolledToARebuiltProviderVersion(
      final String deployment, final String version, final int maxUnavailable, final int maxSurge) {
    if (!"1.1.0".equals(version)) {
      throw new HolmgangException("the rebuilt provider fixture is version 1.1.0, not " + version);
    }
    final DeployedModule recorded = world.deployments.get(deployment);
    if (recorded == null) {
      throw new HolmgangException(
          "deployment " + deployment + " was never deployed in this scenario");
    }
    final Path v2Jar = ModuleFixtures.providerV2Jar(world.fixturesDir());
    world
        .cluster()
        .api()
        .submitDeployment(
            deployment,
            recorded.moduleName(),
            version,
            v2Jar,
            recorded.replicas(),
            recorded.tenantId(),
            Optional.of(new ClusterApi.Disruption(maxUnavailable, maxSurge)));
    world.deployments.put(
        deployment,
        new DeployedModule(
            recorded.moduleName(), version, recorded.replicas(), recorded.tenantId()));
  }

  @When(
      "deploying module {string} version {string} with {int} replica(s) as {string} for tenant {string} is attempted")
  public void deployingIsAttempted(
      final String artifact,
      final String version,
      final int replicas,
      final String deployment,
      final String tenantId) {
    world.lastSubmissionStatus =
        world
            .cluster()
            .api()
            .trySubmitDeployment(
                deployment,
                ExampleModules.moduleName(artifact),
                version,
                ExampleModules.jar(artifact),
                replicas,
                Optional.of(tenantId));
  }

  @Then("the submission is rejected with status {int}")
  public void theSubmissionIsRejectedWithStatus(final int expectedStatus) {
    if (world.lastSubmissionStatus == null) {
      throw new HolmgangException("no deployment submission was attempted in this scenario");
    }
    assertEquals(expectedStatus, (int) world.lastSubmissionStatus);
  }

  @When("deployment {string} is deleted")
  public void deploymentIsDeleted(final String deployment) {
    world.cluster().api().deleteDeployment(deployment);
    world.deployments.remove(deployment);
  }

  /**
   * Restores the revision immediately before the current one -- no explicit target, matching {@code
   * gimle deployment rollback <name>}'s own no-flag default. Deliberately does not update {@code
   * world.deployments}: the client has no way to know which prior version the server picked without
   * reading it back, and no scenario using this step needs that bookkeeping -- {@link
   * #allInstancesActiveOnVersion} asserts against live cluster state, not recorded bookkeeping.
   */
  @When("{string} is rolled back to the previous revision")
  public void isRolledBackToThePreviousRevision(final String deployment) {
    world.cluster().api().rollbackDeployment(deployment);
  }

  @Then("within {int}s deployment {string} is ACTIVE")
  public void deploymentIsActive(final int seconds, final String deployment) {
    world.cluster().when().deployment(deployment).isActive().await(Duration.ofSeconds(seconds));
  }

  @Then("within {int}s deployment {string} is ACTIVE observed via control-plane replica {int}")
  public void deploymentIsActiveViaReplica(
      final int seconds, final String deployment, final int replica) {
    world
        .cluster()
        .when(replica)
        .deployment(deployment)
        .isActive()
        .await(Duration.ofSeconds(seconds));
  }

  @Then("within {int}s deployment {string} is absent")
  public void deploymentIsAbsent(final int seconds, final String deployment) {
    world.cluster().when().deployment(deployment).isAbsent().await(Duration.ofSeconds(seconds));
  }

  @Then("within {int}s deployment {string} has a FAILED instance")
  public void deploymentHasAFailedInstance(final int seconds, final String deployment) {
    world
        .cluster()
        .when()
        .deployment(deployment)
        .hasFailedInstance()
        .await(Duration.ofSeconds(seconds));
  }

  @Then("within {int}s all {int} instances of {string} are ACTIVE on version {string}")
  public void allInstancesActiveOnVersion(
      final int seconds, final int count, final String deployment, final String version) {
    world
        .cluster()
        .when()
        .deployment(deployment)
        .allOnVersion(version, count)
        .await(Duration.ofSeconds(seconds));
  }

  @Then("deployment {string} stays unplaced for {int}s")
  public void deploymentStaysUnplacedFor(final String deployment, final int seconds) {
    world
        .cluster()
        .holdInvariantFor(
            Invariants.deployment(deployment).staysUnplaced(), Duration.ofSeconds(seconds));
  }

  @Given("a guard that {string} keeps at least {int} ACTIVE instance(s)")
  public void aGuardThatKeepsAtLeastActiveInstances(final String deployment, final int count) {
    world.guards.addLast(
        world.cluster().holdInvariant(Invariants.deployment(deployment).minActiveReplicas(count)));
  }

  @Then("the guard held")
  public void theGuardHeld() {
    if (world.guards.isEmpty()) {
      throw new HolmgangException("no guard is open in this scenario");
    }
    world.guards.pollLast().close();
  }

  private void deploy(
      final String artifact,
      final String version,
      final int replicas,
      final String deployment,
      final Optional<String> tenantId) {
    world
        .cluster()
        .api()
        .submitDeployment(
            deployment,
            ExampleModules.moduleName(artifact),
            version,
            ExampleModules.jar(artifact),
            replicas,
            tenantId);
    world.deployments.put(
        deployment,
        new DeployedModule(ExampleModules.moduleName(artifact), version, replicas, tenantId));
    world.cluster().when().deployment(deployment).hasActiveReplicas(replicas).await(DEPLOY_TIMEOUT);
  }
}
