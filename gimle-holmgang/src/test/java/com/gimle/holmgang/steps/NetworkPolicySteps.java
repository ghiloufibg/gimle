package com.gimle.holmgang.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.holmgang.HolmgangException;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Steps proving {@code NetworkPolicySpec} CRUD against a real running cluster: durable,
 * replica-independent persistence through {@code gimle-mimir} (POST against one control-plane
 * replica's {@code /networkpolicies} API, read back through another). Talks to the control plane's
 * own HTTP API directly, the same {@code /networkpolicies*} surface {@code
 * ApiServer.handlePostNetworkPolicy} exposes -- {@link com.gimle.holmgang.cluster.ClusterApi} has
 * no dedicated network-policy methods yet, so this class builds its own small requests rather than
 * widening that shared class.
 */
public final class NetworkPolicySteps {

  private final ScenarioWorld world;

  public NetworkPolicySteps(final ScenarioWorld world) {
    this.world = world;
  }

  @When(
      "a network policy {string} for tenant {string} allowing caller tenant {string} is created"
          + " via control-plane replica {int}")
  public void aNetworkPolicyIsCreatedViaReplica(
      final String name,
      final String tenantId,
      final String allowedCallerTenantId,
      final int replica) {
    postNetworkPolicy(replica, name, tenantId, List.of(), List.of(allowedCallerTenantId));
    world.networkPolicyTenants.put(name, tenantId);
  }

  @Then("within {int}s network policy {string} is visible via control-plane replica {int}")
  public void networkPolicyIsVisibleViaReplica(
      final int seconds, final String name, final int replica) {
    world
        .cluster()
        .when()
        .probe(
            "network policy " + name + " is visible via control-plane replica " + replica,
            () -> getNetworkPolicy(replica, name).isPresent())
        .await(Duration.ofSeconds(seconds));
  }

  @Then(
      "network policy {string} for tenant {string} via control-plane replica {int} allows caller"
          + " tenant {string}")
  public void networkPolicyAllowsCallerTenantViaReplica(
      final String name,
      final String tenantId,
      final int replica,
      final String allowedCallerTenantId) {
    final Map<String, Object> policy =
        getNetworkPolicy(replica, name)
            .orElseThrow(
                () ->
                    new HolmgangException(
                        "network policy "
                            + name
                            + " is not visible via control-plane replica "
                            + replica));
    assertEquals(tenantId, policy.get("tenantId"));
    assertTrue(
        Json.asArray(policy.get("allowedCallerTenantIds")).contains(allowedCallerTenantId),
        "expected " + name + "'s allowedCallerTenantIds to contain " + allowedCallerTenantId);
  }

  @When(
      "network policy {string} for tenant {string} adds caller tenant {string} via control-plane"
          + " replica {int}")
  public void networkPolicyAddsCallerTenantViaReplica(
      final String name,
      final String tenantId,
      final String addedCallerTenantId,
      final int replica) {
    final int version = versionOf(replica, name, tenantId);
    world.networkPolicyVersions.put(name, version);
    final Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", version);
    body.put("addAllowedCallerTenantIds", List.of(addedCallerTenantId));
    final int status = patchNetworkPolicy(replica, name, tenantId, body);
    if (status != 200) {
      throw new HolmgangException("network policy edit failed with status " + status);
    }
  }

  @Then(
      "a second edit of network policy {string} for tenant {string} against the version it started"
          + " from is rejected with status {int}")
  public void aStaleEditIsRejected(
      final String name, final String tenantId, final int expectedStatus) {
    final Integer staleVersion = world.networkPolicyVersions.get(name);
    if (staleVersion == null) {
      throw new HolmgangException("no recorded starting version for network policy " + name);
    }
    final Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", staleVersion);
    // An already-allowed tenant: this edit is refused for its stale version, not its content.
    body.put("addAllowedCallerTenantIds", List.of("partner-one"));
    assertEquals(expectedStatus, patchNetworkPolicy(0, name, tenantId, body));
  }

  @Then(
      "creating network policy {string} for tenant {string} allowing caller tenant {string} via"
          + " control-plane replica {int} is rejected with status {int}")
  public void creatingANetworkPolicyIsRejected(
      final String name,
      final String tenantId,
      final String allowedCallerTenantId,
      final int replica,
      final int expectedStatus) {
    final Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("tenantId", tenantId);
    body.put("allowedCallerTenantIds", List.of(allowedCallerTenantId));
    assertEquals(expectedStatus, send(replica, "POST", "/networkpolicies", body).statusCode());
  }

  private int versionOf(final int replica, final String name, final String tenantId) {
    world.networkPolicyTenants.putIfAbsent(name, tenantId);
    final Map<String, Object> policy =
        getNetworkPolicy(replica, name)
            .orElseThrow(
                () -> new HolmgangException("network policy " + name + " is not visible yet"));
    return ((Number) policy.get("version")).intValue();
  }

  private int patchNetworkPolicy(
      final int replica, final String name, final String tenantId, final Map<String, Object> body) {
    return send(replica, "PATCH", "/networkpolicies/" + name + "?tenant=" + tenantId, body)
        .statusCode();
  }

  private HttpResponse<String> send(
      final int replica, final String method, final String path, final Map<String, Object> body) {
    final String url = world.cluster().api(replica).baseUrl() + path;
    try {
      return httpClient()
          .send(
              HttpRequest.newBuilder(URI.create(url))
                  .method(
                      method,
                      HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                  .header("Content-Type", "application/json")
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final IOException | InterruptedException e) {
      throw new HolmgangException("network policy request failed against " + url, e);
    }
  }

  private void postNetworkPolicy(
      final int replica,
      final String name,
      final String tenantId,
      final List<String> deploymentNames,
      final List<String> allowedCallerTenantIds) {
    final Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("tenantId", tenantId);
    if (!deploymentNames.isEmpty()) {
      body.put("deploymentNames", deploymentNames);
    }
    body.put("allowedCallerTenantIds", allowedCallerTenantIds);
    final String url = world.cluster().api(replica).baseUrl() + "/networkpolicies";
    final HttpResponse<String> response;
    try {
      response =
          httpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(url))
                      .POST(
                          HttpRequest.BodyPublishers.ofString(
                              Json.write(body), StandardCharsets.UTF_8))
                      .build(),
                  HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final IOException | InterruptedException e) {
      throw new HolmgangException("network policy creation failed against " + url, e);
    }
    if (response.statusCode() != 200) {
      throw new HolmgangException(
          "network policy creation failed: "
              + response.statusCode()
              + " "
              + response.body()
              + " ("
              + url
              + ")");
    }
  }

  /**
   * Empty when the policy doesn't exist yet on this replica -- a legitimate transient state.
   * NetworkPolicy's {@code tenantId} is never optional (see {@code ApiServer#handleNetworkPolicy}),
   * so this resolves it from whatever {@code aNetworkPolicyIsCreatedViaReplica} recorded under this
   * same {@code name} rather than requiring every calling step's own Gherkin sentence to repeat it.
   */
  private Optional<Map<String, Object>> getNetworkPolicy(final int replica, final String name) {
    final String tenantId = world.networkPolicyTenants.get(name);
    final String url =
        world.cluster().api(replica).baseUrl()
            + "/networkpolicies/"
            + name
            + (tenantId != null ? "?tenant=" + tenantId : "");
    final HttpResponse<String> response;
    try {
      response =
          httpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(url)).GET().build(),
                  HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final IOException | InterruptedException e) {
      return Optional.empty();
    }
    if (response.statusCode() != 200) {
      return Optional.empty();
    }
    return Optional.of(Json.asObject(Json.parse(response.body())));
  }

  /**
   * Identity-free in plaintext mode (what {@code ha-plaintext} is) -- {@link
   * com.gimle.holmgang.cluster.GimleCluster#trustOnlyHttpClient} already builds exactly this
   * client, so this class reuses it rather than constructing its own.
   */
  private HttpClient httpClient() {
    return world.cluster().trustOnlyHttpClient();
  }
}
