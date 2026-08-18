package com.gimle.holmgang.steps;

import com.gimle.core.protocol.Json;
import com.gimle.holmgang.HolmgangException;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Steps driving the Service abstraction's own HTTP surface directly ({@code POST /services}, {@code
 * GET /services/{name}/endpoints}) rather than through {@code ClusterApi} -- a Service travels as
 * plain JSON, not a {@code WorkloadSpec} manifest, so there is no existing manifest-submission
 * helper to reuse the way {@code DeploymentSteps} reuses one for every workload kind.
 */
public final class ServiceSteps {

  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  private final ScenarioWorld world;

  public ServiceSteps(final ScenarioWorld world) {
    this.world = world;
  }

  @When(
      "a Service {string} is declared fronting deployment {string} on port {int} targeting port"
          + " {int}")
  public void aServiceIsDeclaredFrontingDeploymentOnPortTargetingPort(
      final String serviceName, final String deployment, final int port, final int targetPort) {
    final Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", serviceName);
    body.put("deploymentNames", List.of(deployment));
    body.put("port", port);
    body.put("targetPort", targetPort);
    final HttpResponse<String> response = postService(body);
    if (response.statusCode() != 200) {
      throw new HolmgangException(
          "declaring Service "
              + serviceName
              + " failed with status "
              + response.statusCode()
              + ": "
              + response.body());
    }
  }

  @Then("within {int}s Service {string} resolves a live endpoint")
  public void withinSServiceResolvesALiveEndpoint(final int seconds, final String serviceName) {
    world
        .cluster()
        .when()
        .probe(
            "Service " + serviceName + " resolves a live endpoint",
            () -> !endpointsOf(serviceName).isEmpty())
        .await(Duration.ofSeconds(seconds));
  }

  private List<Object> endpointsOf(final String serviceName) {
    final HttpResponse<String> response = getServiceEndpoints(serviceName);
    if (response.statusCode() != 200) {
      return List.of();
    }
    final Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    return Json.asArray(body.get("endpoints"));
  }

  private HttpResponse<String> postService(final Map<String, Object> body) {
    try {
      return HTTP_CLIENT.send(
          HttpRequest.newBuilder(URI.create(world.cluster().api().baseUrl() + "/services"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
              .build(),
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final Exception e) {
      throw new HolmgangException("Service creation request failed", e);
    }
  }

  private HttpResponse<String> getServiceEndpoints(final String serviceName) {
    try {
      return HTTP_CLIENT.send(
          HttpRequest.newBuilder(
                  URI.create(
                      world.cluster().api().baseUrl() + "/services/" + serviceName + "/endpoints"))
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final Exception e) {
      throw new HolmgangException("Service endpoints request failed", e);
    }
  }
}
