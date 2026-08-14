package com.gimle.holmgang;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.cluster.GimleCluster;
import com.gimle.holmgang.junit.Holmgang;
import com.gimle.holmgang.junit.HolmgangCluster;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The HA topology (3 stores, 2 control planes, 2 Fafnirs, Muninn, 2 nodes) from {@code
 * topologies/ha-plaintext.yaml}: deployments are submitted through control-plane replica 0 and
 * every condition is pinned to views observed through replica 1 ({@code cluster.when(1)}) --
 * proving the replicas share state via the store cluster rather than each holding its own, with
 * zero polling in test code.
 */
@Tag("holmgang")
@Holmgang(topology = "topologies/ha-plaintext.yaml")
class HaTopologyIT {

  private static final String GIMLE_VERSION = "0.1.0-SNAPSHOT";

  @HolmgangCluster GimleCluster cluster;

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void deployments_written_via_one_replica_are_observed_active_via_the_other() {
    cluster
        .api(0)
        .submitDeployment(
            "greeter-provider-deployment",
            "com.gimle.examples.greeter.provider",
            "1.0.0",
            exampleJar("greeter-provider"),
            1,
            Optional.empty());
    cluster
        .api(0)
        .submitDeployment(
            "greeter-consumer-deployment",
            "com.gimle.examples.greeter.consumer",
            "1.0.0",
            exampleJar("greeter-consumer"),
            1,
            Optional.empty());
    cluster
        .when(1)
        .deployment("greeter-provider-deployment")
        .isActive()
        .await(Duration.ofMinutes(2));
    cluster
        .when(1)
        .deployment("greeter-consumer-deployment")
        .isActive()
        .await(Duration.ofMinutes(2));
    // The real cross-worker fabric call, observed through replica 1's log proxy: the consumer
    // retries its lookup+call every 5s, so a healthy cluster shows this quickly.
    cluster
        .when(1)
        .instanceLog("greeter-consumer-deployment", 0, "APPLICATION")
        .contains("Hello, Gimlé!")
        .await(Duration.ofMinutes(1));
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
