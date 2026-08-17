package com.gimle.holmgang;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.cluster.GimleCluster;
import com.gimle.holmgang.junit.Holmgang;
import com.gimle.holmgang.junit.HolmgangCluster;
import com.gimle.testkit.heimdall.HeimdallConditionError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The smallest functioning cluster, booted from {@code topologies/minimal.yaml} by the {@link
 * Holmgang} extension: deploys the committed greeter-provider through the real HTTP API and
 * validates it entirely through event-driven conditions -- no polling loop appears anywhere in this
 * class. Also proves a failed condition's forensic report carries the investigation.
 */
@Tag("holmgang")
@Holmgang(topology = "topologies/minimal.yaml")
class MinimalTopologyIT {

  private static final String GIMLE_VERSION = "0.1.0-alpha.1";
  private static final String TENANT = "holmgang-tenant";
  private static final String SECRET_VALUE = "holmgang-secret-value";

  @HolmgangCluster GimleCluster cluster;

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void greeter_deploys_to_active_with_its_secret_visible_in_its_own_log() {
    // The tenant itself comes from the topology's seed block; the secret is a scenario action --
    // together they prove both the seed path and the write-via-API -> fetch-via-agent ->
    // observed-inside-the-module Fafnir round trip.
    cluster.api().putSecret(TENANT, "some-secret-key", SECRET_VALUE);
    cluster
        .api()
        .submitDeployment(
            "greeter-provider-deployment",
            "com.gimle.examples.greeter.provider",
            "1.0.0",
            exampleJar("greeter-provider"),
            1,
            Optional.of(TENANT));
    cluster
        .when()
        .deployment("greeter-provider-deployment")
        .isActive()
        .await(Duration.ofMinutes(2));
    cluster
        .when()
        .instanceLog("greeter-provider-deployment", 0, "APPLICATION")
        .contains(SECRET_VALUE)
        .await(Duration.ofMinutes(1));
  }

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void a_failed_condition_reports_the_cluster_state_it_gave_up_on() {
    final HeimdallConditionError error =
        assertThrows(
            HeimdallConditionError.class,
            () ->
                cluster
                    .when()
                    .deployment("no-such-deployment")
                    .hasActiveReplicas(1)
                    .await(Duration.ofSeconds(3)));
    final String report = error.getMessage();
    assertTrue(report.contains("no-such-deployment"), report);
    assertTrue(report.contains("last ClusterView"), report);
    assertTrue(report.contains("process status:"), report);
    assertTrue(report.contains("process logs under:"), report);
  }

  private static Path exampleJar(final String artifact) {
    final Path jar =
        Path.of("")
            .toAbsolutePath()
            .getParent()
            .resolve("gimle-examples")
            .resolve(artifact)
            .resolve("target")
            .resolve(artifact + "-" + GIMLE_VERSION + ".jar");
    assertTrue(
        Files.isRegularFile(jar),
        "expected a built jar at " + jar + " -- run `mvn install` before -Pvalidation");
    return jar;
  }
}
