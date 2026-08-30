package com.gimle.holmgang.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.protocol.Json;
import com.gimle.holmgang.HolmgangException;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Steps exercising the custom-kind (Galdr) surface against the real HTTP API: teaching the cluster
 * a kind via a {@code KindDefinition} manifest, applying instances validated against its schema,
 * and reading the status a real hosted operator module reported back through the workload-identity
 * relay -- the whole define/apply/reconcile loop over real processes, not the parsers' own unit
 * tests.
 */
public final class GaldrSteps {

  /** The stored, prefix-normalized name of the kind {@link #aGreetingKindDefinitionIsApplied}. */
  private static final String GREETING_KIND = "custom.Greeting";

  private final ScenarioWorld world;

  public GaldrSteps(final ScenarioWorld world) {
    this.world = world;
  }

  /**
   * Deliberately applied under the bare name {@code Greeting}: the server's prefix normalization
   * (bare name stored as {@code custom.Greeting}) is part of what the scenario proves.
   */
  @When("a Greeting kind definition is applied")
  public void aGreetingKindDefinitionIsApplied() {
    world.lastSubmissionStatus =
        world
            .cluster()
            .api()
            .tryPutManifest(
                "/kinddefinitions/Greeting",
                """
                kind: KindDefinition
                name: Greeting
                scope: Tenant
                description: "A greeting this cluster should keep saying"
                names:
                  plural: greetings
                  shortNames: [gr]
                schema:
                  fields:
                    - name: message
                      type: string
                      required: true
                    - name: repeat
                      type: int
                      default: 1
                      min: 1
                      max: 100
                    - name: tone
                      type: enum
                      values: [friendly, formal]
                      default: friendly
                printColumns:
                  - name: MESSAGE
                    path: spec.message
                  - name: SAID
                    path: status.timesSaid
                """);
    if (world.lastSubmissionStatus == 200) {
      world.kindDefinitions.add(GREETING_KIND);
    }
  }

  @Then("the kind catalog lists {string}")
  public void theKindCatalogLists(final String kindName) {
    final boolean listed =
        world.cluster().api().kindCatalog().stream()
            .anyMatch(definition -> kindName.equals(definition.get("kindName")));
    if (!listed) {
      throw new HolmgangException(
          "expected the kind catalog to list " + kindName + ", got " + catalogNames());
    }
  }

  @When(
      "Greeting {string} for tenant {string} is applied with message {string} repeated {int} times")
  public void greetingIsApplied(
      final String name, final String tenantId, final String message, final int repeat) {
    world.lastSubmissionStatus =
        world
            .cluster()
            .api()
            .tryPutManifest(
                "/resources/" + GREETING_KIND + "/" + name,
                """
                kind: %s
                name: %s
                tenantId: %s
                spec:
                  message: "%s"
                  repeat: %d
                """
                    .formatted(GREETING_KIND, name, tenantId, message, repeat));
    if (world.lastSubmissionStatus == 200) {
      rememberApplied(name, Optional.of(tenantId));
    }
  }

  @When("a Greeting manifest with an unknown spec field is submitted for tenant {string}")
  public void aGreetingManifestWithAnUnknownSpecFieldIsSubmitted(final String tenantId) {
    world.lastSubmissionStatus =
        world
            .cluster()
            .api()
            .tryPutManifest(
                "/resources/" + GREETING_KIND + "/typo-instance",
                """
                kind: %s
                name: typo-instance
                tenantId: %s
                spec:
                  message: "hi"
                  repeta: 3
                """
                    .formatted(GREETING_KIND, tenantId));
    rememberApplied("typo-instance", Optional.of(tenantId));
  }

  @When("a Greeting manifest with repeat {int} is submitted for tenant {string}")
  public void aGreetingManifestWithRepeatIsSubmitted(final int repeat, final String tenantId) {
    world.lastSubmissionStatus =
        world
            .cluster()
            .api()
            .tryPutManifest(
                "/resources/" + GREETING_KIND + "/bounds-instance",
                """
                kind: %s
                name: bounds-instance
                tenantId: %s
                spec:
                  message: "hi"
                  repeat: %d
                """
                    .formatted(GREETING_KIND, tenantId, repeat));
    rememberApplied("bounds-instance", Optional.of(tenantId));
  }

  @When("a Greeting manifest without a tenant is submitted")
  public void aGreetingManifestWithoutATenantIsSubmitted() {
    world.lastSubmissionStatus =
        world
            .cluster()
            .api()
            .tryPutManifest(
                "/resources/" + GREETING_KIND + "/untenanted-instance",
                """
                kind: %s
                name: untenanted-instance
                spec:
                  message: "hi"
                """
                    .formatted(GREETING_KIND));
    rememberApplied("untenanted-instance", Optional.empty());
  }

  @Then("Greeting {string} for tenant {string} has generation {int}")
  public void greetingHasGeneration(final String name, final String tenantId, final int expected) {
    assertEquals(
        (long) expected,
        ((Number) requireField(readGreeting(name, tenantId), "generation")).longValue(),
        "generation of Greeting " + name);
  }

  /** Proves defaults are applied at admission and persisted -- never left for readers to infer. */
  @Then("Greeting {string} for tenant {string} has tone {string}")
  public void greetingHasTone(final String name, final String tenantId, final String expected) {
    final Map<String, Object> spec =
        Json.asObject(requireField(readGreeting(name, tenantId), "spec"));
    assertEquals(expected, spec.get("tone"), "persisted default tone of Greeting " + name);
  }

  /**
   * The operator loop's own end-to-end oracle: the reported {@code timesSaid} matches the spec's
   * {@code repeat}, and {@code observedGeneration} has caught up with the store's generation -- so
   * a stale status from before a spec change never satisfies this.
   */
  @Then(
      "within {int}s Greeting {string} for tenant {string} reports timesSaid {int} caught up with"
          + " its spec")
  public void greetingReportsTimesSaidCaughtUp(
      final int seconds, final String name, final String tenantId, final int timesSaid) {
    world
        .cluster()
        .when()
        .probe(
            "Greeting " + name + " reports timesSaid " + timesSaid + " at its own generation",
            () -> reportsCaughtUpTimesSaid(name, tenantId, timesSaid))
        .await(Duration.ofSeconds(seconds));
  }

  @Then("Greeting {string} for tenant {string} still reports timesSaid {int}")
  public void greetingStillReportsTimesSaid(
      final String name, final String tenantId, final int timesSaid) {
    if (!reportsCaughtUpTimesSaid(name, tenantId, timesSaid)) {
      throw new HolmgangException(
          "expected Greeting "
              + name
              + " to still report timesSaid "
              + timesSaid
              + ", got "
              + readGreeting(name, tenantId).get("status"));
    }
  }

  private boolean reportsCaughtUpTimesSaid(
      final String name, final String tenantId, final int timesSaid) {
    final Optional<Map<String, Object>> resource =
        world.cluster().api().customResource(GREETING_KIND, name, Optional.of(tenantId));
    if (resource.isEmpty() || !(resource.get().get("status") instanceof Map<?, ?> status)) {
      return false;
    }
    return status.get("timesSaid") instanceof Number said
        && said.longValue() == timesSaid
        && status.get("observedGeneration") instanceof Number observed
        && resource.get().get("generation") instanceof Number generation
        && observed.longValue() == generation.longValue();
  }

  private Map<String, Object> readGreeting(final String name, final String tenantId) {
    return world
        .cluster()
        .api()
        .customResource(GREETING_KIND, name, Optional.of(tenantId))
        .orElseThrow(
            () ->
                new HolmgangException(
                    "expected Greeting " + name + " for tenant " + tenantId + " to exist"));
  }

  private static Object requireField(final Map<String, Object> resource, final String field) {
    final Object value = resource.get(field);
    if (value == null) {
      throw new HolmgangException(
          "expected resource to carry '" + field + "', got keys " + resource.keySet());
    }
    return value;
  }

  /**
   * Recorded even for a submission expected to be rejected: cleanup deletes are idempotent, and
   * remembering unconditionally means a scenario whose rejection assertion was wrong still leaves
   * the pooled cluster clean.
   */
  private void rememberApplied(final String name, final Optional<String> tenantId) {
    final ScenarioWorld.AppliedCustomResource applied =
        new ScenarioWorld.AppliedCustomResource(GREETING_KIND, name, tenantId);
    if (!world.customResources.contains(applied)) {
      world.customResources.add(applied);
    }
  }

  private String catalogNames() {
    return world.cluster().api().kindCatalog().stream()
        .map(definition -> String.valueOf(definition.get("kindName")))
        .toList()
        .toString();
  }
}
