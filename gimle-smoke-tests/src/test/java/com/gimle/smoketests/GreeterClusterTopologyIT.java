package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Base cluster topology: deploys {@code greeter-provider} and {@code greeter-consumer} across a
 * real multi-node store cluster and multiple control-plane replicas via the real HTTP API, asserts
 * both reach {@code ACTIVE} (observed through a *different* replica than the one they were
 * submitted to, proving shared state via {@code gimle-mimir} rather than each replica holding its
 * own), that the consumer's real fabric call to the provider shows up in its own application log,
 * and that a real tenant-scoped secret round-trips through Fafnir end to end -- then drives {@code
 * gimle-console}'s Playwright suite against that same live cluster.
 */
@Tag("smoke")
class GreeterClusterTopologyIT extends GreeterSmokeClusterSupport {

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void greeter_modules_deploy_across_a_store_cluster_and_multiple_control_plane_replicas()
      throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String writeUrl = cluster.controlPlaneBaseUrls().get(0);
    // A DIFFERENT control-plane replica than the one the deployments are submitted through --
    // proves the two share state via gimle-mimir rather than each holding its own, which is the
    // entire point of decoupling ApiServer replica count from the store's own membership.
    String readUrl = cluster.controlPlaneBaseUrls().get(CONTROLPLANE_COUNT - 1);

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    Path consumerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-consumer/target/greeter-consumer-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);
    assertTrue(Files.isRegularFile(consumerJar), "expected a built jar at " + consumerJar);

    // Must exist before the tenant-scoped deployment below is admitted (DeploymentSpec's own
    // javadoc: a tenantId must already name a registered Tenant), and before it, so the secret is
    // already readable the moment the agent delivers config at install time.
    provisionTenantAndSecret(writeUrl);

    submitDeployment(
        writeUrl,
        "greeter-provider-deployment",
        "com.gimle.examples.greeter.provider",
        providerJar,
        Optional.of(SECRET_TENANT_ID));
    submitDeployment(
        writeUrl,
        "greeter-consumer-deployment",
        "com.gimle.examples.greeter.consumer",
        consumerJar);

    await(
        () -> isActive(readUrl, "greeter-provider-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-deployment should reach ACTIVE, observed through a different"
            + " control-plane replica than the one it was submitted to");
    await(
        () -> isActive(readUrl, "greeter-consumer-deployment"),
        Duration.ofSeconds(60),
        "greeter-consumer-deployment should reach ACTIVE, observed through a different"
            + " control-plane replica than the one it was submitted to");

    // Proves the real fabric call happened, not just that both processes started: the consumer
    // retries its lookup+call every 5s, so a healthy cluster should show this well within a
    // minute of both instances going ACTIVE.
    await(
        () -> consumerLogShowsAGreeting(readUrl),
        Duration.ofSeconds(60),
        "greeter-consumer-deployment's own log should show a real reply from greeter-provider");

    // The real secret round trip: written via the API above, fetched
    // by the agent straight from Fafnir, delivered to the worker, and read back by the module's
    // own onStart hook -- logged there, asserted here. onStart already ran by the time the
    // ACTIVE await above passed, so this should already be true; the await is headroom for log
    // flush/propagation latency, not for the secret fetch itself.
    await(
        () -> providerLogShowsTheSecret(readUrl),
        Duration.ofSeconds(30),
        "greeter-provider-deployment's own log should show the real secret value fetched from"
            + " Fafnir");

    // Playwright targets readUrl, not writeUrl: readUrl is the replica the awaits above already
    // polled until it showed fresh ACTIVE state. Store reads are deliberately loose across the
    // M-node store cluster (no linearizability requirement), so a replica that
    // hasn't been read from yet could still be serving a stale view for a few hundred ms after a
    // write -- real, expected, and not what this Playwright leg exists to characterize. The
    // cross-replica-consistency property itself is already proven above via plain HTTP.
    createLoginAccount(readUrl, SMOKE_OPERATOR_USERNAME, SMOKE_OPERATOR_PASSWORD);
    runPlaywrightSuite(repoRoot, readUrl);
  }
}
