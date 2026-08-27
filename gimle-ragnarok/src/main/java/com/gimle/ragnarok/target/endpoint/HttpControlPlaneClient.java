package com.gimle.ragnarok.target.endpoint;

import com.gimle.core.protocol.Json;
import com.gimle.ragnarok.RagnarokException;
import com.gimle.ragnarok.target.ControlPlaneClient;
import java.io.IOException;
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
 * A {@link ControlPlaneClient} speaking the real control-plane HTTP API directly over {@link
 * HttpClient} -- an independent, standalone implementation of just the ten methods Fenrir and Surtr
 * actually call, not a wrapper over the harness's own much broader test-only {@code ClusterApi}.
 * Deployment manifests are plain YAML built by hand, matching the wire shape the control plane's
 * real {@code PUT /deployments/{name}} route already expects; every other exchange is JSON via
 * {@link Json}.
 */
public final class HttpControlPlaneClient implements ControlPlaneClient {

  private final HttpClient httpClient;
  private final String baseUrl;

  public HttpControlPlaneClient(final HttpClient httpClient, final String baseUrl) {
    this.httpClient = httpClient;
    this.baseUrl = baseUrl;
  }

  @Override
  public int tryPutTenant(
      final String tenantId,
      final long maxMemoryBytes,
      final long maxCpuMillicores,
      final int maxInstances) {
    final String body =
        Json.write(
            Map.of(
                "quota",
                Map.of(
                    "maxMemoryBytes", maxMemoryBytes,
                    "maxCpuMillicores", maxCpuMillicores,
                    "maxInstances", maxInstances)));
    return send("PUT", "/tenants/" + tenantId, body, "tenant write attempt");
  }

  @Override
  public int trySubmitDeployment(
      final String deploymentName,
      final String moduleName,
      final String moduleVersion,
      final Path jar,
      final int replicas,
      final Optional<String> tenantId) {
    // Built by plain concatenation rather than a formatted text block: a multi-line format
    // string trips SpotBugs's VA_FORMAT_STRING_USES_NEWLINE, which a text block's own embedded
    // line breaks can never actually avoid triggering.
    final StringBuilder manifest = new StringBuilder();
    manifest
        .append("kind: Deployment\n")
        .append("name: ")
        .append(deploymentName)
        .append('\n')
        .append("module:\n")
        .append("  name: ")
        .append(moduleName)
        .append('\n')
        .append("  version: ")
        .append(moduleVersion)
        .append('\n')
        .append("artifactPath: ")
        .append(jar.toAbsolutePath())
        .append('\n')
        .append("replicas: ")
        .append(replicas)
        .append('\n');
    tenantId.ifPresent(id -> manifest.append("tenantId: ").append(id).append('\n'));
    return send(
        "PUT", "/deployments/" + deploymentName, manifest.toString(), "deployment submission");
  }

  @Override
  public void deleteDeployment(final String deploymentName) {
    expectOkNoBody("DELETE", "/deployments/" + deploymentName, "deployment deletion");
  }

  @Override
  public void deleteTenant(final String tenantId) {
    expectOkNoBody("DELETE", "/tenants/" + tenantId, "tenant deletion");
  }

  @Override
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

  @Override
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

  @Override
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

  @Override
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

  @Override
  public void putSecret(final String tenantId, final String key, final String value) {
    final String body =
        Json.write(
            Map.of(
                "value",
                Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
    expectOk("PUT", "/secrets/" + tenantId + "/" + key, body, "secret write");
  }

  @Override
  public boolean isServing() {
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
    } catch (final IOException e) {
      return Optional.empty();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  private int send(
      final String method, final String path, final String body, final String description) {
    try {
      return httpClient
          .send(
              HttpRequest.newBuilder(URI.create(baseUrl + path))
                  .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
          .statusCode();
    } catch (final Exception e) {
      throw new RagnarokException(description + " failed against " + baseUrl + path, e);
    }
  }

  private void expectOk(
      final String method, final String path, final String body, final String description) {
    final int status = send(method, path, body, description);
    if (status != 200) {
      throw new RagnarokException(description + " failed: " + status + " (" + baseUrl + path + ")");
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
      throw new RagnarokException(description + " failed against " + baseUrl + path, e);
    }
    if (response.statusCode() != 200) {
      throw new RagnarokException(
          description + " failed: " + response.statusCode() + " (" + baseUrl + path + ")");
    }
  }
}
