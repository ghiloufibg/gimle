package com.gimle.holmgang.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.holmgang.HolmgangException;
import com.gimle.testkit.Await;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Steps driving a real {@code IvaldiMain} process -- Gimlé's cluster designer backend -- purely
 * over HTTP the way its own console does. See {@link IvaldiHarness} for the process/API mechanics.
 */
public final class IvaldiSteps {

  private final ScenarioWorld world;

  private String blueprintId;
  private String firstBlueprintReadBack;
  private Map<String, Object> lastValidation;
  private IvaldiHarness.FreshCluster cluster;
  private final List<Map<String, String>> deployFiles = new ArrayList<>();

  public IvaldiSteps(final ScenarioWorld world) {
    this.world = world;
  }

  @Given("a running Ivaldi process")
  public void aRunningIvaldiProcess() {
    world.ivaldiHarness = IvaldiHarness.start();
  }

  // ---- Blueprint document storage (GIMLE-906) ----

  @When("a blueprint document named {string} is saved")
  public void aBlueprintDocumentNamedIsSaved(final String name) {
    blueprintId = world.ivaldiHarness.saveBlueprint(Json.write(Map.of("name", name)));
  }

  @Then("reading that blueprint back returns a document named {string}")
  public void readingThatBlueprintBackReturnsADocumentNamed(final String name) {
    firstBlueprintReadBack = world.ivaldiHarness.readBlueprint(blueprintId);
    Map<String, Object> parsed = Json.asObject(Json.parse(firstBlueprintReadBack));
    assertEquals(name, parsed.get("name"), firstBlueprintReadBack);
  }

  @Then("reading it back a second time returns the exact same content")
  public void readingItBackASecondTimeReturnsTheExactSameContent() {
    assertEquals(firstBlueprintReadBack, world.ivaldiHarness.readBlueprint(blueprintId));
  }

  // ---- Tier-2 validation against the real Hilmir/Mimir parsers (GIMLE-907) ----

  @When("a topology declaring no agents is submitted for validation")
  public void aTopologyDeclaringNoAgentsIsSubmittedForValidation() {
    String topology =
        """
        name: local
        machines:
          - {name: local, host: 127.0.0.1}
        store:
          replicas:
            - {machine: local}
        controlPlane:
          replicas:
            - {machine: local}
        fafnir:
          keyFile: /tmp/fafnir.key
          replicas:
            - {machine: local}
        """;
    lastValidation =
        world.ivaldiHarness.validate(List.of(Map.of("path", "topology.yaml", "content", topology)));
  }

  @Then("the validation response includes a {string} finding naming {string}")
  public void theValidationResponseIncludesAFindingNaming(final String code, final String file) {
    List<Map<String, Object>> findings = Json.asObjectList(lastValidation.get("findings"));
    assertTrue(
        findings.stream().anyMatch(f -> code.equals(f.get("code")) && file.equals(f.get("file"))),
        "expected a " + code + " finding naming " + file + ", got: " + findings);
  }

  // ---- Running a Blueprint through Ivaldi's own run engine (GIMLE-911) ----

  @Given("a saved blueprint deploying {string} as {string}")
  public void aSavedBlueprintDeployingAs(final String artifact, final String deployment) {
    String moduleName = ExampleModules.moduleName(artifact);
    String manifestPath = "manifests/01-" + artifact + ".yaml";
    String bundleYaml =
        """
        kind: Bundle
        name: holmgang-ivaldi-designer
        version: 1.0.0
        workloads:
          - file: %s
        """
            .formatted(manifestPath);
    String manifestYaml =
        """
        apiVersion: v1
        kind: Deployment
        name: %s
        replicas: 1
        module:
          name: %s
          version: 1.0.0
        """
            .formatted(deployment, moduleName);
    String artifactsYaml =
        """
        artifacts:
          - manifest: %s
            module: %s
            version: 1.0.0
            path: %s
        """
            .formatted(manifestPath, moduleName, ExampleModules.jar(artifact));
    deployFiles.add(Map.of("path", "bundle.yaml", "content", bundleYaml));
    deployFiles.add(Map.of("path", manifestPath, "content", manifestYaml));
    deployFiles.add(Map.of("path", "ivaldi.artifacts.yaml", "content", artifactsYaml));
    blueprintId =
        world.ivaldiHarness.saveBlueprint(
            Json.write(Map.of("name", "holmgang-ivaldi-designer-" + artifact)));
  }

  @Given("a saved cluster connection pointing at a fresh single-machine topology")
  public void aSavedClusterConnectionPointingAtAFreshSingleMachineTopology() {
    cluster = world.ivaldiHarness.createFreshCluster();
  }

  @When("the blueprint is run against that cluster")
  public void theBlueprintIsRunAgainstThatCluster() {
    List<Map<String, String>> files = new ArrayList<>();
    files.add(Map.of("path", "topology.yaml", "content", cluster.topologyYaml()));
    files.addAll(deployFiles);
    world.ivaldiHarness.startRun(cluster.clusterId(), blueprintId, files);
  }

  @Then("within {int}s the run reaches {string}")
  public void withinSTheRunReaches(final int seconds, final String status) {
    AtomicReference<Map<String, Object>> lastSnapshot = new AtomicReference<>();
    try {
      Await.until(
          () -> {
            Map<String, Object> snapshot = world.ivaldiHarness.currentSnapshot();
            lastSnapshot.set(snapshot);
            String current = String.valueOf(snapshot.get("status"));
            return current.equals(status) || current.equals("failed");
          },
          Duration.ofSeconds(seconds));
    } catch (AssertionError e) {
      throw new HolmgangException(
          "run did not settle to "
              + status
              + " or failed within "
              + seconds
              + "s: "
              + lastSnapshot.get(),
          e);
    }
    assertEquals(
        status,
        String.valueOf(lastSnapshot.get().get("status")),
        "run failed instead of reaching " + status + ": " + world.ivaldiHarness.runLog());
  }

  @Then("within {int}s deployment {string} is ACTIVE on that cluster")
  public void withinSDeploymentIsActiveOnThatCluster(final int seconds, final String deployment) {
    Await.until(
        () -> world.ivaldiHarness.isDeploymentActive(cluster.controlPlaneUrl(), deployment),
        Duration.ofSeconds(seconds),
        deployment + " should reach ACTIVE on the real control plane this run booted");
  }

  @When("the run is stopped")
  public void theRunIsStopped() {
    world.ivaldiHarness.stopRun();
  }

  @Then("within {int}s the run is idle")
  public void withinSTheRunIsIdle(final int seconds) {
    Await.until(
        () -> "idle".equals(world.ivaldiHarness.runStatus()),
        Duration.ofSeconds(seconds),
        "the run should settle to idle after stop");
  }

  @Then("the cluster's process tree is torn down")
  public void theClustersProcessTreeIsTornDown() {
    Await.until(
        world.ivaldiHarness::processTreeGone,
        Duration.ofSeconds(30),
        "the run ledger for machine m1 should be gone once the cluster is torn down");
  }
}
