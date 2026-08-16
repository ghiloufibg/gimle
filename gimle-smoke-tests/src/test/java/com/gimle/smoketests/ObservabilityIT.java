package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.testkit.Await;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
    submitDeploymentWithRetry(
        baseUrl,
        "greeter-provider-deployment",
        "com.gimle.examples.greeter.provider",
        providerJar,
        Optional.of(SECRET_TENANT_ID),
        Duration.ofSeconds(30));
    Await.until(
        () -> isActive(baseUrl, "greeter-provider-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-deployment should reach ACTIVE");
    Await.until(
        () -> providerLogShowsTheSecret(baseUrl),
        Duration.ofSeconds(30),
        "greeter-provider-deployment's own log should show the real secret value, served live by"
            + " its owning agent");

    // Confirm the line already made it into Muninn's own shipped history -- polled directly
    // against Muninn's own /logs/instances/* read surface rather than the control plane's
    // /logs/* proxy above, which is still served live by the agent at this point and so
    // wouldn't distinguish "already shipped to Muninn" from "still readable from the live
    // agent." Once that's confirmed, the agent (and, with it, its MuninnShipper) can be killed
    // knowing only whatever Muninn already received needs to survive.
    Await.until(
        () -> muninnHasProviderSecretLine(cluster.muninnEndpoint()),
        Duration.ofSeconds(30),
        "greeter-provider-deployment's own log line should already be shipped to and directly"
            + " readable from Muninn before its owning agent is killed");

    killWithDescendants(cluster.agentProcesses().get(0));

    Await.until(
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
    String processId = "127.0.0.1:" + controlPlanePorts.get(0);

    // Real traffic against this replica's own /deployments endpoint -- exactly what
    // ApiServerMetricsTest's own unit-level assertion drives, here observed end to end through a
    // real shipped-and-read-back round trip instead of an in-process registry read.
    for (int i = 0; i < 5; i++) {
      httpClient.send(
          HttpRequest.newBuilder(URI.create(baseUrl + "/deployments")).GET().build(),
          HttpResponse.BodyHandlers.discarding());
    }

    Await.until(
        () -> metricsHistoryShowsDeploymentsRequestCount(baseUrl, processId),
        Duration.ofSeconds(30),
        "gimle.controlplane.request.count for the deployments endpoint should be shipped to"
            + " Muninn and readable back through /metrics-history/*");
  }

  /**
   * True once Muninn's own {@code GET /logs/instances/*} read surface -- queried directly against
   * Muninn, not proxied through the control plane -- already has the provider's secret line in its
   * shipped history.
   */
  private boolean muninnHasProviderSecretLine(String muninnEndpoint) {
    try {
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://"
                              + muninnEndpoint
                              + "/logs/instances/greeter-provider-deployment/0/APPLICATION"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return response.statusCode() == 200 && response.body().contains(SECRET_VALUE);
    } catch (IOException | InterruptedException | RuntimeException e) {
      return false;
    }
  }
}
