package com.gimle.holmgang;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.cluster.ClusterApi;
import com.gimle.holmgang.cluster.GimleCluster;
import com.gimle.holmgang.topology.ClusterSpec;
import com.gimle.holmgang.topology.ClusterTopologyParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Boots real clusters from the committed topology documents and deploys the committed greeter
 * example modules to {@code ACTIVE} through the real HTTP API -- proving the whole
 * topology-to-running-cluster path, not any single component. Assumes {@code mvn install} has
 * already produced the example jars, the same precondition {@code gimle-smoke-tests} has.
 *
 * <p>Opt in with {@code -Pvalidation}; see this module's own pom for the double-lock keeping this
 * out of plain {@code mvn verify}.
 */
@Tag("holmgang")
class HolmgangBootstrapIT {

  private static final String GIMLE_VERSION = "0.1.0-SNAPSHOT";
  private static final String TENANT = "holmgang-tenant";
  private static final String SECRET_KEY = "some-secret-key";
  private static final String SECRET_VALUE = "holmgang-secret-value";

  // Never cleaned up: the spawned processes' log files under it are the primary forensic
  // artifact when a boot or deployment goes wrong.
  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void a_minimal_topology_boots_from_yaml_and_deploys_greeter_to_active() {
    final ClusterSpec spec = ClusterTopologyParser.fromClasspath("topologies/minimal.yaml");
    try (GimleCluster cluster = GimleCluster.start(spec, tempDir.resolve("minimal"))) {
      final ClusterApi api = cluster.api();
      // The tenant itself comes from the topology's own seed block; only the secret is a
      // scenario action here -- proving both the seed path and the Fafnir round trip.
      api.putSecret(TENANT, SECRET_KEY, SECRET_VALUE);
      api.submitDeployment(
          "greeter-provider-deployment",
          "com.gimle.examples.greeter.provider",
          "1.0.0",
          exampleJar("greeter-provider"),
          1,
          Optional.of(TENANT));
      api.awaitDeploymentActive("greeter-provider-deployment", Duration.ofSeconds(120));
      // The provider's own onStart hook logs the secret it read back via ctx.config(...), so the
      // instance's application log showing the value proves the write-via-API ->
      // fetch-via-agent -> observed-inside-a-deployed-module round trip.
      api.awaitInstanceLogContains(
          "greeter-provider-deployment", 0, "APPLICATION", SECRET_VALUE, Duration.ofSeconds(60));
    }
  }

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void an_ha_topology_serves_deployments_across_control_plane_replicas() {
    final ClusterSpec spec = ClusterTopologyParser.fromClasspath("topologies/ha-plaintext.yaml");
    try (GimleCluster cluster = GimleCluster.start(spec, tempDir.resolve("ha-plaintext"))) {
      final ClusterApi writeApi = cluster.api(0);
      // A DIFFERENT control-plane replica than the one the deployments are submitted through --
      // proves the replicas share state via the store cluster rather than each holding its own.
      final ClusterApi readApi = cluster.api(1);
      writeApi.submitDeployment(
          "greeter-provider-deployment",
          "com.gimle.examples.greeter.provider",
          "1.0.0",
          exampleJar("greeter-provider"),
          1,
          Optional.empty());
      writeApi.submitDeployment(
          "greeter-consumer-deployment",
          "com.gimle.examples.greeter.consumer",
          "1.0.0",
          exampleJar("greeter-consumer"),
          1,
          Optional.empty());
      readApi.awaitDeploymentActive("greeter-provider-deployment", Duration.ofSeconds(120));
      readApi.awaitDeploymentActive("greeter-consumer-deployment", Duration.ofSeconds(120));
      // Proves the real cross-worker fabric call happened, not just that both processes started:
      // the consumer retries its lookup+call every 5s, so a healthy cluster shows this quickly.
      readApi.awaitInstanceLogContains(
          "greeter-consumer-deployment", 0, "APPLICATION", "Hello, Gimlé!", Duration.ofSeconds(60));
    }
  }

  private static Path exampleJar(final String artifact) {
    final Path jar =
        repoRoot()
            .resolve("gimle-examples")
            .resolve(artifact)
            .resolve("target")
            .resolve(artifact + "-" + GIMLE_VERSION + ".jar");
    assertTrue(
        Files.isRegularFile(jar),
        "expected a built jar at " + jar + " -- run `mvn install` before -Pvalidation");
    return jar;
  }

  private static Path repoRoot() {
    return Path.of("").toAbsolutePath().getParent();
  }
}
