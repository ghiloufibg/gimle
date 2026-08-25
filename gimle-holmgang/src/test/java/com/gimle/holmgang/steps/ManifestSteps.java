package com.gimle.holmgang.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.holmgang.HolmgangException;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Steps submitting hand-built manifests of every workload kind directly against the real {@code
 * /deployments}, {@code /daemonsets}, {@code /cronjobs}, and {@code /statefulsets} routes --
 * proving {@code ManifestParser}'s kind dispatch and each kind-specific parser's own validation
 * (accept a well-formed manifest, reject a malformed one with a clean 400) against the real HTTP
 * admission surface, not the parsers' own unit tests.
 */
public final class ManifestSteps {

  private final ScenarioWorld world;

  public ManifestSteps(final ScenarioWorld world) {
    this.world = world;
  }

  @When("a manifest with kind {string} is submitted to {string}")
  public void aManifestWithKindIsSubmittedTo(final String kind, final String path) {
    submit(path, "kind: " + kind + "\nname: whatever\n");
  }

  /**
   * The apiVersion mechanics against the real admission surface: {@code v1alpha1} (the same ruleset
   * an unversioned manifest gets) still accepts a local {@code artifactPath}, {@code v1} rejects
   * the field outright in favor of the artifact registry, and an unknown version is rejected rather
   * than silently defaulted.
   */
  @When(
      "a deployment manifest with apiVersion {string} naming a local artifact path is submitted"
          + " as {string}")
  public void aDeploymentManifestWithApiVersionNamingALocalArtifactPathIsSubmittedAs(
      final String apiVersion, final String name) {
    submit(
        "/deployments/" + name,
        """
        apiVersion: %s
        kind: Deployment
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: 1
        """
            .formatted(
                apiVersion,
                name,
                ExampleModules.moduleName("greeter-provider"),
                ExampleModules.jar("greeter-provider").toAbsolutePath()));
  }

  /** No {@code artifactPath} line at all -- the only artifact reference {@code v1} accepts. */
  @When(
      "a v1 coordinate-only deployment manifest for module {string} version {string} is submitted"
          + " as {string}")
  public void aV1CoordinateOnlyDeploymentManifestIsSubmittedAs(
      final String artifact, final String version, final String name) {
    submit(
        "/deployments/" + name,
        """
        apiVersion: v1
        kind: Deployment
        name: %s
        module:
          name: %s
          version: %s
        replicas: 1
        """
            .formatted(name, ExampleModules.moduleName(artifact), version));
  }

  @When("a deployment manifest with a weighted autoscale policy is submitted as {string}")
  public void aDeploymentManifestWithAWeightedAutoscalePolicyIsSubmittedAs(final String name) {
    submit(
        "/deployments/" + name,
        """
        kind: Deployment
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: 1
        autoscale:
          minReplicas: 1
          maxReplicas: 3
          targetCpuUtilizationPercent: 80
          targetRequestRatePerSecond: 50
          mode: weighted
          cpuWeight: 2.0
          requestRateWeight: 1.0
        """
            .formatted(
                name,
                ExampleModules.moduleName("greeter-provider"),
                ExampleModules.jar("greeter-provider").toAbsolutePath()));
  }

  @When(
      "a deployment manifest with maxUnavailable {int} and maxSurge {int} is submitted as {string}")
  public void aDeploymentManifestWithDisruptionBudgetIsSubmittedAs(
      final int maxUnavailable, final int maxSurge, final String name) {
    submit(
        "/deployments/" + name,
        """
        kind: Deployment
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: does-not-need-to-exist-for-a-rejected-manifest
        replicas: 1
        disruption:
          maxUnavailable: %d
          maxSurge: %d
        """
            .formatted(
                name, ExampleModules.moduleName("greeter-provider"), maxUnavailable, maxSurge));
  }

  @When("a daemonset manifest requesting anti-affinity is submitted as {string}")
  public void aDaemonsetManifestRequestingAntiAffinityIsSubmittedAs(final String name) {
    submit(
        "/daemonsets/" + name,
        """
        kind: DaemonSet
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: does-not-need-to-exist-for-a-rejected-manifest
        placement:
          antiAffinity: true
        """
            .formatted(name, ExampleModules.moduleName("greeter-provider")));
  }

  @When("a daemonset manifest with maxSurge {int} is submitted as {string}")
  public void aDaemonsetManifestWithMaxSurgeIsSubmittedAs(final int maxSurge, final String name) {
    final boolean accepted = maxSurge == 0;
    submit(
        "/daemonsets/" + name,
        """
        kind: DaemonSet
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        disruption:
          maxUnavailable: 1
          maxSurge: %d
        """
            .formatted(
                name,
                ExampleModules.moduleName("greeter-provider"),
                accepted
                    ? ExampleModules.jar("greeter-provider").toAbsolutePath()
                    : "does-not-need-to-exist-for-a-rejected-manifest",
                maxSurge));
  }

  /**
   * No {@code backoffLimit} field at all -- the acceptance itself is the proof that {@link
   * com.gimle.mimir.manifest.CronJobManifestParser}'s own default applies rather than requiring the
   * operator to spell out every optional field.
   */
  @When("a cronjob manifest with no explicit backoffLimit is submitted as {string}")
  public void aCronjobManifestWithNoExplicitBackoffLimitIsSubmittedAs(final String name) {
    submit("/cronjobs/" + name, cronJobManifest(name, "*/5 * * * *", null));
  }

  @When("a cronjob manifest with schedule {string} is submitted as {string}")
  public void aCronjobManifestWithScheduleIsSubmittedAs(final String schedule, final String name) {
    submit("/cronjobs/" + name, cronJobManifest(name, schedule, null));
  }

  @When("a cronjob manifest with concurrencyPolicy {string} is submitted as {string}")
  public void aCronjobManifestWithConcurrencyPolicyIsSubmittedAs(
      final String concurrencyPolicy, final String name) {
    submit("/cronjobs/" + name, cronJobManifest(name, "*/5 * * * *", concurrencyPolicy));
  }

  /**
   * Builds the schedule's day-of-month and day-of-week fields from today's own real UTC date -- a
   * day of month that is deliberately never today's, paired with today's own weekday -- so cron's
   * documented OR quirk (either restricted field matching is enough) is what fires this within a
   * single reconcile tick, not a genuine coincidence of both fields matching.
   */
  @When(
      "a cronjob manifest scheduled for today's weekday but a different day of month is submitted"
          + " as {string}")
  public void aCronjobManifestScheduledForTodaysWeekdayIsSubmittedAs(final String name) {
    final LocalDate today = LocalDate.now(ZoneOffset.UTC);
    final int todayDayOfMonth = today.getDayOfMonth();
    final int otherDayOfMonth = todayDayOfMonth == 1 ? 2 : 1;
    // java.time.DayOfWeek is MONDAY=1..SUNDAY=7; cron's own day-of-week field is
    // SUNDAY=0..SATURDAY=6.
    final int cronDayOfWeek = today.getDayOfWeek().getValue() % 7;
    final String schedule = "* * " + otherDayOfMonth + " * " + cronDayOfWeek;
    submit("/cronjobs/" + name, cronJobManifest(name, schedule, null));
  }

  private static String cronJobManifest(
      final String name, final String schedule, final String concurrencyPolicy) {
    return """
        kind: CronJob
        name: %s
        schedule: "%s"
        jobTemplate:
          module:
            name: %s
            version: 1.0.0
          artifactPath: %s
        %s"""
        .formatted(
            name,
            schedule,
            ExampleModules.moduleName("greeter-provider"),
            ExampleModules.jar("greeter-provider").toAbsolutePath(),
            concurrencyPolicy == null ? "" : "concurrencyPolicy: " + concurrencyPolicy + "\n");
  }

  @When("a statefulset manifest with {int} replicas is submitted as {string}")
  public void aStatefulsetManifestWithReplicasIsSubmittedAs(final int replicas, final String name) {
    final boolean accepted = replicas >= 0;
    submit(
        "/statefulsets/" + name,
        """
        kind: StatefulSet
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: %d
        """
            .formatted(
                name,
                ExampleModules.moduleName("greeter-provider"),
                accepted
                    ? ExampleModules.jar("greeter-provider").toAbsolutePath()
                    : "does-not-need-to-exist-for-a-rejected-manifest",
                replicas));
  }

  @Then("the manifest submission is accepted")
  public void theManifestSubmissionIsAccepted() {
    if (world.lastSubmissionStatus == null) {
      throw new HolmgangException("no manifest submission was attempted in this scenario");
    }
    assertEquals(
        200,
        (int) world.lastSubmissionStatus,
        "expected the manifest submission to be accepted (200)");
  }

  @Then("within {int}s cronjob {string} has at least 1 job run")
  public void withinSecondsCronjobHasAtLeastOneJobRun(final int seconds, final String name) {
    world
        .cluster()
        .when()
        .probe(
            "cronjob " + name + " has been scheduled at least once",
            () -> world.cluster().api().cronJobLastScheduleTime(name).isPresent())
        .await(Duration.ofSeconds(seconds));
  }

  private void submit(final String path, final String yaml) {
    world.lastSubmissionStatus = world.cluster().api().tryPutManifest(path, yaml);
  }
}
