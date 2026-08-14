package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Observability round trips through Muninn: a deployed instance's own log line survives its owning
 * agent's death, served from Muninn's shipped history instead of a 502 with no client-visible
 * difference in how the request is made; and a real control-plane request metric is shipped to
 * Muninn and readable back through {@code GET /metrics-history/*} by name.
 */
@Tag("smoke")
class ObservabilityIT extends GreeterSmokeClusterSupport {

  /**
   * The Muninn logs fallback, end to end: a real deployed instance's own log line is observed once
   * through the live agent, survives that agent's own death, and is still observable through the
   * identical {@code /logs/instances/*} request afterward -- served from Muninn's shipped history
   * instead of a 502, with no client-visible difference in how the request is made. Reuses {@link
   * #providerLogShowsTheSecret} both before and after the kill: the exact same substring match
   * against the exact same JSON shape either endpoint returns is itself proof the fallback is
   * genuinely transparent, not a client-visible failover with different output.
   */
  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void a_deployed_instances_log_survives_its_owning_agent_dying() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);

    provisionTenantAndSecret(baseUrl);
    submitDeployment(
        baseUrl,
        "greeter-provider-deployment",
        "com.gimle.examples.greeter.provider",
        providerJar,
        Optional.of(SECRET_TENANT_ID));
    await(
        () -> isActive(baseUrl, "greeter-provider-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-deployment should reach ACTIVE");
    await(
        () -> providerLogShowsTheSecret(baseUrl),
        Duration.ofSeconds(30),
        "greeter-provider-deployment's own log should show the real secret value, served live by"
            + " its owning agent");

    // Headroom past AgentMain's own 5s MuninnShipper tick interval, so the line above is
    // genuinely already shipped before the agent that shipped it is killed -- once it's dead, the
    // shipper dies with it, and only whatever Muninn already received survives.
    Thread.sleep(Duration.ofSeconds(8).toMillis());

    killWithDescendants(cluster.agentProcesses().get(0));

    await(
        () -> providerLogShowsTheSecret(baseUrl),
        Duration.ofSeconds(30),
        "greeter-provider-deployment's own log should still show the real secret value after its"
            + " owning agent died, now served from Muninn's shipped history instead");
  }

  /**
   * The metrics round trip: a real request against a real control-plane replica increments a real
   * counter, that counter is shipped to Muninn, and the shipped value is readable back through
   * {@code GET /metrics-history/*} -- not just that the endpoint returns *something*, but that the
   * specific meter this test's own traffic drives shows up by name.
   */
  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void a_control_planes_own_request_metrics_round_trip_through_muninn() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);
    // Matches ControlPlaneMain's own selfApiAddress derivation (selfHost defaults to 127.0.0.1
    // when --host isn't passed, which spawnControlPlane above never passes).
    String processId = "127.0.0.1:" + CONTROLPLANE_PORT_BASE;

    // Real traffic against this replica's own /deployments endpoint -- exactly what
    // ApiServerMetricsTest's own unit-level assertion drives, here observed end to end through a
    // real shipped-and-read-back round trip instead of an in-process registry read.
    for (int i = 0; i < 5; i++) {
      httpClient.send(
          HttpRequest.newBuilder(URI.create(baseUrl + "/deployments")).GET().build(),
          HttpResponse.BodyHandlers.discarding());
    }

    await(
        () -> metricsHistoryShowsDeploymentsRequestCount(baseUrl, processId),
        Duration.ofSeconds(30),
        "gimle.controlplane.request.count for the deployments endpoint should be shipped to"
            + " Muninn and readable back through /metrics-history/*");
  }
}
