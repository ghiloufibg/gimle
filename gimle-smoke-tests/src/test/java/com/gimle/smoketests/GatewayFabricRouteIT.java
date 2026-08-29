package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.tenant.Tenant;
import com.gimle.testkit.Await;
import com.gimle.testkit.PortLease;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real end-to-end coverage of {@code gimle-gateway}'s v1 fabric-routes scope: deploys the real,
 * committed {@code greeter-provider} module (already exports a real fabric {@code Greeter} service)
 * and the real, committed {@code gimle-gateway} module -- as a {@code DaemonSet}, scoped to an
 * edge-labeled node inside the reserved {@code gimle-system} tenant, matching how this platform
 * actually expects the gateway to be deployed -- into one real subprocess cluster, then hits the
 * gateway's own HTTP port with a real {@link java.net.http.HttpClient} and asserts the response
 * reflects a real fabric call that actually reached the real {@code greeter-provider} instance.
 *
 * <p>{@code gimle-system} admits this DaemonSet with no further operator-credential setup here:
 * this fixture's cluster runs in plaintext ({@code http://} base URLs throughout), and {@code
 * ApiServer#rejectIfReservedSystemTenant}'s own guard is a no-op in plaintext mode (see its own
 * javadoc -- there's no real caller identity to check without TLS in the first place, the same
 * carve-out {@link #SMOKE_OPERATOR_USERNAME}'s own account-creation call already relies on).
 */
@Tag("smoke")
class GatewayFabricRouteIT extends GreeterSmokeClusterSupport {

  private static final String EDGE_NODE_ID = "smoke-node-edge";

  // Leased rather than hardcoded, matching every other port this suite's own cluster processes
  // use -- gimle-gateway itself is already config-driven (GatewayHooks#onStart reads gateway.port
  // via ctx.config, never a fixed constant), so a genuinely dynamic port was already one config
  // write away; only this test's own literal needed to change.
  private int gatewayPort;

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void an_external_http_request_reaches_a_real_fabric_service_through_the_gateway()
      throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);
    String fafnirEndpoint = "127.0.0.1:" + fafnirPorts.get(0);

    Await.until(
        () -> nodeRegistered(baseUrl, "smoke-node-1"),
        Duration.ofSeconds(30),
        "smoke-node-1 should register with the control plane before a second node seeds off it");

    // A second real agent, labeled edge -- the existing, no-further-work mechanism
    // (AgentMain#nodeLabels, -Dgimle.node.labels=edge) an operator uses to mark a real machine as
    // an edge node today. gimle-gateway's own DaemonSet only ever places onto a node like this one.
    Process edgeAgent =
        spawnAgent(
            javaExecutable,
            classpath,
            EDGE_NODE_ID,
            gossipAddressNode2,
            gossipAddress,
            baseUrl,
            fafnirEndpoint,
            cluster.muninnEndpoint(),
            "edge",
            tempDir.resolve("agent-edge.log"));
    processes.add(edgeAgent);

    Await.until(
        () -> nodeRegistered(baseUrl, EDGE_NODE_ID),
        Duration.ofSeconds(30),
        EDGE_NODE_ID
            + " should register with the control plane once its own gossip join"
            + " completes");

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    Path gatewayJar =
        repoRoot.resolve("gimle-gateway/target/gimle-gateway-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);
    assertTrue(Files.isRegularFile(gatewayJar), "expected a built jar at " + gatewayJar);

    // The real greeter-provider, unscoped to any tenant -- gimle-gateway's own
    // defaultDenyCrossTenant
    // is off by default (AgentMain's own default), so an unscoped export is reachable from any
    // tenant, including gimle-system, exactly as it would be from any other hosted module.
    submitDeploymentWithRetry(
        baseUrl,
        "greeter-provider-deployment",
        "com.gimle.examples.greeter.provider",
        providerJar,
        Duration.ofSeconds(30));
    Await.until(
        () -> isActive(baseUrl, "greeter-provider-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-deployment should reach ACTIVE");

    // Leased and released right here, immediately before the DaemonSet submission just below that
    // triggers the real bind -- unlike this fixture's own cluster-process ports (all bound within
    // milliseconds of release, at process spawn), the gap between a config write and gimle-gateway
    // actually calling HttpServer#create is real wall-clock time (deployment admission, agent
    // install, hook invocation), so releasing any earlier than this widens PortLease's normally-
    // tiny close-to-bind race into a real one.
    try (PortLease gatewayLease = PortLease.reserve(1)) {
      gatewayPort = gatewayLease.ports().get(0);
      gatewayLease.release(gatewayPort);
    }

    // Written before the DaemonSet below is submitted: AgentMain#deliverConfig fetches this
    // tenant's plain config once, synchronously, as part of that single instance's own
    // install-then-start sequence -- there is no later re-delivery to pick up a key written after
    // the instance has already started.
    putPlainConfig(
        baseUrl, Tenant.RESERVED_SYSTEM_TENANT_ID, "gateway.port", String.valueOf(gatewayPort));
    putPlainConfig(
        baseUrl,
        Tenant.RESERVED_SYSTEM_TENANT_ID,
        "gateway.routes",
        "FABRIC /greet com.gimle.examples.greeter.Greeter 1 greet STRING\n");

    submitDaemonSetWithRetry(
        baseUrl,
        "gimle-gateway",
        "com.gimle.gateway",
        "1.0.0",
        gatewayJar,
        List.of("edge"),
        Optional.of(Tenant.RESERVED_SYSTEM_TENANT_ID),
        Duration.ofSeconds(30));

    Await.until(
        () ->
            daemonSetActiveNodeCount(
                    baseUrl, "gimle-gateway", Optional.of(Tenant.RESERVED_SYSTEM_TENANT_ID))
                == 1,
        Duration.ofSeconds(90),
        "the gimle-gateway DaemonSet should place exactly one ACTIVE instance, on the sole"
            + " edge-labeled node");
    assertTrue(
        daemonSetHasNodeAssignment(
            baseUrl, "gimle-gateway", EDGE_NODE_ID, Optional.of(Tenant.RESERVED_SYSTEM_TENANT_ID)));

    Await.until(
        () ->
            gatewayRespondsToGreet("Gimlé")
                .map("Hello, Gimlé! (from provider)"::equals)
                .orElse(false),
        Duration.ofSeconds(60),
        "the gateway's own /greet route should reflect a real fabric call reaching the real"
            + " greeter-provider instance");
  }

  /**
   * {@code Optional.empty()} for any transport failure or non-200 -- {@link #await} above is what
   * actually retries this, since the gateway's own readiness (its {@code HttpServer} actually
   * bound) races the DaemonSet-placement await just above it.
   */
  private Optional<String> gatewayRespondsToGreet(String name) {
    try {
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + "/greet"))
                  .POST(HttpRequest.BodyPublishers.ofString(name, StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
    } catch (Exception e) {
      return Optional.empty();
    }
  }
}
