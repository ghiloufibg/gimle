package com.gimle.holmgang.utgard;

import com.gimle.core.protocol.Json;
import com.gimle.holmgang.HolmgangException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLContext;

/**
 * A minimal control-plane HTTP client for driving a real deployment through one containerized
 * cluster's own mapped port from the test JVM. Deliberately not {@code
 * com.gimle.holmgang.cluster.ClusterApi} -- that type's constructor is package-private to {@code
 * cluster}, and its surface is built around an in-process {@code GimleCluster}'s own loopback
 * addressing; this is the small slice of the same idea Utgard's own containerized scenarios
 * actually need.
 */
final class UtgardControlPlaneClient {

  private final HttpClient httpClient;
  private final String baseUrl;

  UtgardControlPlaneClient(final String baseUrl) {
    this(baseUrl, HttpClient.newHttpClient());
  }

  UtgardControlPlaneClient(final String baseUrl, final SSLContext sslContext) {
    this(baseUrl, HttpClient.newBuilder().sslContext(sslContext).build());
  }

  private UtgardControlPlaneClient(final String baseUrl, final HttpClient httpClient) {
    this.baseUrl = baseUrl;
    this.httpClient = httpClient;
  }

  /** {@code containerJarPath} is a path on the machine hosting the placed instance's own agent. */
  void submitDeployment(
      final String deploymentName,
      final String moduleName,
      final String moduleVersion,
      final String containerJarPath,
      final int replicas) {
    final String manifest =
        """
        kind: Deployment
        name: %s
        module:
          name: %s
          version: %s
        artifactPath: %s
        replicas: %d
        """
            .formatted(deploymentName, moduleName, moduleVersion, containerJarPath, replicas);
    final HttpResponse<String> response = send("PUT", "/deployments/" + deploymentName, manifest);
    requireOk(response, "deployment submission");
  }

  boolean isServing() {
    try {
      return send("GET", "/deployments", null).statusCode() < 500;
    } catch (final RuntimeException e) {
      return false;
    }
  }

  boolean isActive(final String deploymentName) {
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

  int activeInstanceCount(final String deploymentName) {
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

  /** The node id currently hosting instance 0 of {@code deploymentName}, if any is placed yet. */
  Optional<String> nodeOfInstanceZero(final String deploymentName) {
    for (final Map<String, Object> instance : instancesOf(deploymentName)) {
      if (((Number) instance.get("instanceIndex")).intValue() == 0) {
        return Optional.ofNullable((String) instance.get("nodeId"));
      }
    }
    return Optional.empty();
  }

  boolean nodeRegistered(final String nodeId) {
    final HttpResponse<String> response = send("GET", "/nodes", null);
    if (response.statusCode() != 200) {
      return false;
    }
    for (final Object entry : Json.asArray(Json.parse(response.body()))) {
      if (nodeId.equals(Json.asObject(entry).get("nodeId"))) {
        return true;
      }
    }
    return false;
  }

  private List<Map<String, Object>> instancesOf(final String deploymentName) {
    final HttpResponse<String> response = send("GET", "/deployments/" + deploymentName, null);
    if (response.statusCode() != 200) {
      return List.of();
    }
    final Object raw = Json.asObject(Json.parse(response.body())).get("instances");
    return raw == null ? List.of() : Json.asObjectList(raw);
  }

  private HttpResponse<String> send(final String method, final String path, final String body) {
    final HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    try {
      return httpClient.send(
          builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final Exception e) {
      throw new HolmgangException(method + " " + path + " failed against " + baseUrl, e);
    }
  }

  private void requireOk(final HttpResponse<String> response, final String description) {
    if (response.statusCode() != 200) {
      throw new HolmgangException(
          description + " failed: " + response.statusCode() + " " + response.body());
    }
  }
}
