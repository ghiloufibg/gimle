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
import java.util.ArrayList;
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

  private final HttpClient httpClient;
  private final String baseUrl;

  ClusterApi(final HttpClient httpClient, final String baseUrl) {
    this.httpClient = httpClient;
    this.baseUrl = baseUrl;
  }

  public String baseUrl() {
    return baseUrl;
  }

  public void putTenant(final String tenantId, final QuotaSpec quota) {
    expectOk("PUT", "/tenants/" + tenantId, tenantBody(quota), "tenant creation");
  }

  /**
   * Like {@link #putTenant}, but returns the raw status instead of failing on a non-200 -- for
   * scenarios asserting the rejection itself (an anonymous client's 401 under mTLS).
   */
  public int tryPutTenant(final String tenantId, final QuotaSpec quota) {
    try {
      return httpClient
          .send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/" + tenantId))
                  .method(
                      "PUT",
                      HttpRequest.BodyPublishers.ofString(
                          tenantBody(quota), StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
          .statusCode();
    } catch (final Exception e) {
      throw new HolmgangException("tenant write attempt failed against " + baseUrl, e);
    }
  }

  private static String tenantBody(final QuotaSpec quota) {
    return Json.write(
        Map.of(
            "quota",
            Map.of(
                "maxMemoryBytes", quota.maxMemoryBytes(),
                "maxCpuMillicores", quota.maxCpuMillicores(),
                "maxInstances", quota.maxInstances())));
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

  /** A deployment's {@code disruption:} budget; absent implies the platform's own default. */
  public record Disruption(int maxUnavailable, int maxSurge) {}

  public void submitDeployment(
      final String deploymentName,
      final String moduleName,
      final String moduleVersion,
      final Path jar,
      final int replicas,
      final Optional<String> tenantId) {
    submitDeployment(
        deploymentName, moduleName, moduleVersion, jar, replicas, tenantId, Optional.empty());
  }

  public void submitDeployment(
      final String deploymentName,
      final String moduleName,
      final String moduleVersion,
      final Path jar,
      final int replicas,
      final Optional<String> tenantId,
      final Optional<Disruption> disruption) {
    expectOk(
        "PUT",
        "/deployments/" + deploymentName,
        deploymentManifest(
            deploymentName,
            moduleName,
            moduleVersion,
            Optional.of(jar),
            replicas,
            tenantId,
            disruption),
        "deployment submission");
  }

  /**
   * Submits a deployment naming no {@code artifactPath} at all: the module coordinate alone, which
   * the control plane and the placing node agent each resolve through the artifact registry.
   */
  public void submitDeploymentByCoordinate(
      final String deploymentName,
      final String moduleName,
      final String moduleVersion,
      final int replicas,
      final Optional<String> tenantId) {
    expectOk(
        "PUT",
        "/deployments/" + deploymentName,
        deploymentManifest(
            deploymentName,
            moduleName,
            moduleVersion,
            Optional.empty(),
            replicas,
            tenantId,
            Optional.empty()),
        "coordinate-only deployment submission");
  }

  /**
   * Pushes a module jar to the artifact registry through the control plane's own {@code
   * /artifacts/*} proxy -- the same route {@code gimle artifact push} takes, rather than straight
   * at the Andvari port. The body is streamed from the file, never buffered whole.
   */
  public void pushArtifact(final String moduleName, final String moduleVersion, final Path jar) {
    final String path = "/artifacts/" + moduleName + "/" + moduleVersion;
    final HttpResponse<String> response;
    try {
      response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + path))
                  .PUT(HttpRequest.BodyPublishers.ofFile(jar.toAbsolutePath()))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final Exception e) {
      throw new HolmgangException("artifact push failed against " + baseUrl + path, e);
    }
    requireOk(response, path, "artifact push");
  }

  /**
   * Like {@link #submitDeployment}, but returns the raw status instead of failing on a non-200 --
   * for scenarios asserting the rejection itself (admission control's 409).
   */
  public int trySubmitDeployment(
      final String deploymentName,
      final String moduleName,
      final String moduleVersion,
      final Path jar,
      final int replicas,
      final Optional<String> tenantId) {
    final String manifest =
        deploymentManifest(
            deploymentName,
            moduleName,
            moduleVersion,
            Optional.of(jar),
            replicas,
            tenantId,
            Optional.empty());
    try {
      return httpClient
          .send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + deploymentName))
                  .method(
                      "PUT", HttpRequest.BodyPublishers.ofString(manifest, StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
          .statusCode();
    } catch (final Exception e) {
      throw new HolmgangException("deployment submission attempt failed against " + baseUrl, e);
    }
  }

  /**
   * Submits a deployment with an {@code autoscale:} policy. {@code targetCpuUtilizationPercent} is
   * always written (the platform requires it); the request-rate target is optional, matching the
   * policy's own "absent means not evaluated" shape.
   */
  public void submitAutoscaleDeployment(
      final String deploymentName,
      final String moduleName,
      final String moduleVersion,
      final Path jar,
      final int minReplicas,
      final int maxReplicas,
      final int targetCpuUtilizationPercent,
      final Optional<Double> targetRequestRatePerSecond) {
    final String manifest =
        """
        kind: Deployment
        name: %s
        module:
          name: %s
          version: %s
        artifactPath: %s
        replicas: %d
        autoscale:
          minReplicas: %d
          maxReplicas: %d
          targetCpuUtilizationPercent: %d
        %s"""
            .formatted(
                deploymentName,
                moduleName,
                moduleVersion,
                jar.toAbsolutePath(),
                minReplicas,
                minReplicas,
                maxReplicas,
                targetCpuUtilizationPercent,
                targetRequestRatePerSecond
                    .map(rate -> "  targetRequestRatePerSecond: " + rate + "\n")
                    .orElse(""));
    expectOk("PUT", "/deployments/" + deploymentName, manifest, "autoscale deployment submission");
  }

  /** True once {@code GET /tenants/{id}} answers 200 -- the read side of a workload's writes. */
  public boolean tenantExists(final String tenantId) {
    final Optional<HttpResponse<String>> response = tryGet("/tenants/" + tenantId);
    return response.isPresent() && response.get().statusCode() == 200;
  }

  public void deleteDeployment(final String deploymentName) {
    expectOkNoBody("DELETE", "/deployments/" + deploymentName, "deployment deletion");
  }

  public void deleteTenant(final String tenantId) {
    expectOkNoBody("DELETE", "/tenants/" + tenantId, "tenant deletion");
  }

  public void cordonNode(final String nodeId) {
    expectOkNoBody("POST", "/nodes/" + nodeId + "/cordon", "node cordon");
  }

  public void uncordonNode(final String nodeId) {
    expectOkNoBody("POST", "/nodes/" + nodeId + "/uncordon", "node uncordon");
  }

  /** An absent {@code jar} writes no {@code artifactPath} line: a registry-coordinate manifest. */
  private static String deploymentManifest(
      final String deploymentName,
      final String moduleName,
      final String moduleVersion,
      final Optional<Path> jar,
      final int replicas,
      final Optional<String> tenantId,
      final Optional<Disruption> disruption) {
    return """
        kind: Deployment
        name: %s
        module:
          name: %s
          version: %s
        %sreplicas: %d
        %s%s
        """
        .formatted(
            deploymentName,
            moduleName,
            moduleVersion,
            jar.map(path -> "artifactPath: " + path.toAbsolutePath() + "\n").orElse(""),
            replicas,
            tenantId.map(id -> "tenantId: " + id + "\n").orElse(""),
            disruption
                .map(
                    d ->
                        "disruption:\n  maxUnavailable: %d\n  maxSurge: %d\n"
                            .formatted(d.maxUnavailable(), d.maxSurge()))
                .orElse(""));
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

  public boolean isServing() {
    final Optional<HttpResponse<String>> response = tryGet("/deployments");
    return response.isPresent() && response.get().statusCode() < 500;
  }

  /** One instance's placement: its index, the node hosting it, and its lifecycle state. */
  public record InstancePlacement(int instanceIndex, String nodeId, String lifecycleState) {}

  /** Every instance of a deployment with its node and state -- the placement-spread source. */
  public List<InstancePlacement> placements(final String deploymentName) {
    final List<InstancePlacement> placements = new ArrayList<>();
    for (final Map<String, Object> instance : instancesOf(deploymentName)) {
      final String state =
          instance.get("observation") instanceof Map<?, ?> observation
              ? String.valueOf(observation.get("lifecycleState"))
              : "UNOBSERVED";
      placements.add(
          new InstancePlacement(
              ((Number) instance.get("instanceIndex")).intValue(),
              String.valueOf(instance.get("nodeId")),
              state));
    }
    return placements;
  }

  /**
   * One instance's platform lifecycle event log ({@code GET /events}): each entry carries the
   * platform's own {@code occurredAtEpochMilli}, {@code kind}, and {@code message}. Empty when the
   * instance has no events yet or the read fails. This is the authoritative source for
   * startup-phase latencies -- timestamps the platform recorded, not the harness observed.
   */
  public List<Map<String, Object>> instanceEvents(
      final String deploymentName, final int instanceIndex) {
    final Optional<HttpResponse<String>> response =
        tryGet("/events?deployment=" + deploymentName + "&instance=" + instanceIndex);
    if (response.isEmpty() || response.get().statusCode() != 200) {
      return List.of();
    }
    try {
      return Json.asObjectList(Json.parse(response.get().body()));
    } catch (final RuntimeException e) {
      return List.of();
    }
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

  private void expectOkNoBody(final String method, final String path, final String description) {
    final HttpResponse<String> response;
    try {
      response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + path))
                  .method(method, HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final Exception e) {
      throw new HolmgangException(description + " failed against " + baseUrl + path, e);
    }
    requireOk(response, path, description);
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
    requireOk(response, path, description);
  }

  private void requireOk(
      final HttpResponse<String> response, final String path, final String description) {
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
