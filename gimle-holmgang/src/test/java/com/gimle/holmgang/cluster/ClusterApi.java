package com.gimle.holmgang.cluster;

import com.gimle.core.protocol.Json;
import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.topology.QuotaSpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Scenario-facing client for one control-plane replica's HTTP API: submissions fail loudly on any
 * non-200, while the single-shot {@code is*} readers swallow transport failures ("not ready yet"
 * and "connection refused" both just mean "not yet", never a hard failure worth surfacing). Waiting
 * for state belongs to the Heimdall condition API, not here.
 */
public final class ClusterApi {

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final String baseUrl;

  ClusterApi(final String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String baseUrl() {
    return baseUrl;
  }

  public void putTenant(final String tenantId, final QuotaSpec quota) {
    final String body =
        Json.write(
            Map.of(
                "quota",
                Map.of(
                    "maxMemoryBytes", quota.maxMemoryBytes(),
                    "maxCpuMillicores", quota.maxCpuMillicores(),
                    "maxInstances", quota.maxInstances())));
    expectOk("PUT", "/tenants/" + tenantId, body, "tenant creation");
  }

  public void putAccount(final String username, final String password) {
    expectOk(
        "PUT",
        "/accounts/" + username,
        Json.write(Map.of("password", password)),
        "account creation");
  }

  public void putSecret(final String tenantId, final String key, final String value) {
    final String body =
        Json.write(
            Map.of(
                "value",
                Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
    expectOk("PUT", "/secrets/" + tenantId + "/" + key, body, "secret write");
  }

  public void putConfig(final String tenantId, final String key, final String value) {
    final String body = Json.write(Map.of("value", value, "encrypted", false));
    expectOk("PUT", "/config/" + tenantId + "/" + key, body, "config write");
  }

  public void submitDeployment(
      final String deploymentName,
      final String moduleName,
      final String moduleVersion,
      final Path jar,
      final int replicas,
      final Optional<String> tenantId) {
    final String manifest =
        """
        kind: Deployment
        name: %s
        module:
          name: %s
          version: %s
        artifactPath: %s
        replicas: %d
        %s
        """
            .formatted(
                deploymentName,
                moduleName,
                moduleVersion,
                jar.toAbsolutePath(),
                replicas,
                tenantId.map(id -> "tenantId: " + id).orElse(""));
    expectOk("PUT", "/deployments/" + deploymentName, manifest, "deployment submission");
  }

  public boolean isDeploymentActive(final String deploymentName) {
    try {
      final List<Map<String, Object>> instances = instancesOf(deploymentName);
      if (instances.isEmpty()) {
        return false;
      }
      for (final Map<String, Object> instance : instances) {
        if (!(instance.get("observation") instanceof Map<?, ?> observation)
            || !"ACTIVE".equals(observation.get("lifecycleState"))) {
          return false;
        }
      }
      return true;
    } catch (final RuntimeException e) {
      return false;
    }
  }

  public int activeInstanceCount(final String deploymentName) {
    try {
      int active = 0;
      for (final Map<String, Object> instance : instancesOf(deploymentName)) {
        if (instance.get("observation") instanceof Map<?, ?> observation
            && "ACTIVE".equals(observation.get("lifecycleState"))) {
          active++;
        }
      }
      return active;
    } catch (final RuntimeException e) {
      return 0;
    }
  }

  public boolean nodeRegistered(final String nodeId) {
    final Optional<HttpResponse<String>> response = tryGet("/nodes");
    if (response.isEmpty() || response.get().statusCode() != 200) {
      return false;
    }
    try {
      for (final Object entry : Json.asArray(Json.parse(response.get().body()))) {
        if (nodeId.equals(Json.asObject(entry).get("nodeId"))) {
          return true;
        }
      }
      return false;
    } catch (final RuntimeException e) {
      return false;
    }
  }

  public boolean instanceLogContains(
      final String deploymentName,
      final int instanceIndex,
      final String category,
      final String text) {
    final Optional<HttpResponse<String>> response =
        tryGet("/logs/instances/" + deploymentName + "/" + instanceIndex + "?category=" + category);
    return response.isPresent()
        && response.get().statusCode() == 200
        && response.get().body().contains(text);
  }

  boolean isServing() {
    final Optional<HttpResponse<String>> response = tryGet("/deployments");
    return response.isPresent() && response.get().statusCode() < 500;
  }

  private List<Map<String, Object>> instancesOf(final String deploymentName) {
    final Optional<HttpResponse<String>> response = tryGet("/deployments/" + deploymentName);
    if (response.isEmpty() || response.get().statusCode() != 200) {
      return List.of();
    }
    return Json.asObjectList(Json.asObject(Json.parse(response.get().body())).get("instances"));
  }

  private Optional<HttpResponse<String>> tryGet(final String path) {
    try {
      return Optional.of(
          httpClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    } catch (final Exception e) {
      return Optional.empty();
    }
  }

  private void expectOk(
      final String method, final String path, final String body, final String description) {
    final HttpResponse<String> response;
    try {
      response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + path))
                  .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final Exception e) {
      throw new HolmgangException(description + " failed against " + baseUrl + path, e);
    }
    if (response.statusCode() != 200) {
      throw new HolmgangException(
          description
              + " failed: "
              + response.statusCode()
              + " "
              + response.body()
              + " ("
              + baseUrl
              + path
              + ")");
    }
  }
}
