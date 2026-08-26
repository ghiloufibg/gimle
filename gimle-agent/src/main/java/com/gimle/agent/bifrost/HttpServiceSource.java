package com.gimle.agent.bifrost;

import com.gimle.core.exception.GimleClusterException;
import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The real {@link ServiceSource}: polls the control plane's {@code GET /services} and {@code GET
 * /services/{name}/endpoints} HTTP API. Reuses the exact {@link HttpClient}/{@link Json}
 * request-building/response-parsing shape {@code AgentMain}'s own registration/heartbeat/
 * assignment-fetch calls already use (same request timeout, same "non-200 becomes a {@link
 * GimleClusterException}" rule), rather than a second copy of that convention -- callers pass in
 * the same {@link HttpClient} instance {@code AgentMain} already built for its own control-plane
 * calls.
 */
public final class HttpServiceSource implements ServiceSource {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient httpClient;
  private final URI controlPlaneBaseUrl;

  public HttpServiceSource(HttpClient httpClient, URI controlPlaneBaseUrl) {
    this.httpClient = httpClient;
    this.controlPlaneBaseUrl = controlPlaneBaseUrl;
  }

  @Override
  public List<ServiceSummary> listServices() throws IOException, InterruptedException {
    HttpResponse<String> response = send(controlPlaneBaseUrl.resolve("/services"), "list services");
    // GET /services answers an array of Service JSON objects (ApiServer#serviceToJson): "name"
    // always present, "tenantId" present only for a tenant-scoped Service, "deploymentNames"
    // always a present (possibly empty) array.
    List<Map<String, Object>> raw = Json.asObjectList(Json.parse(response.body()));
    List<ServiceSummary> services = new ArrayList<>(raw.size());
    for (Map<String, Object> entry : raw) {
      Optional<String> tenantId = Optional.ofNullable((String) entry.get("tenantId"));
      Set<String> deploymentNames = new LinkedHashSet<>();
      for (Object deploymentName : Json.asArray(entry.get("deploymentNames"))) {
        deploymentNames.add((String) deploymentName);
      }
      services.add(new ServiceSummary((String) entry.get("name"), tenantId, deploymentNames));
    }
    return services;
  }

  @Override
  public Optional<ServiceEndpoints> fetchEndpoints(String serviceName)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                controlPlaneBaseUrl.resolve("/services/" + serviceName + "/endpoints"))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    // 404 means the service disappeared between listServiceNames() and this call -- a benign
    // race ServiceSource's own javadoc documents, not a failure worth throwing over.
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    if (response.statusCode() != 200) {
      throw GimleClusterException.unexpectedHttpStatus(
          "endpoint fetch for service " + serviceName, response.statusCode(), response.body());
    }
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    List<ServiceEndpoint> endpoints = new ArrayList<>();
    for (Map<String, Object> entry : Json.asObjectList(body.get("endpoints"))) {
      endpoints.add(
          new ServiceEndpoint(
              (String) entry.get("host"),
              ((Number) entry.get("port")).intValue(),
              Optional.ofNullable((String) entry.get("nodeId"))));
    }
    return Optional.of(
        new ServiceEndpoints(
            (String) body.get("name"),
            ((Number) body.get("port")).intValue(),
            ((Number) body.get("targetPort")).intValue(),
            Boolean.TRUE.equals(body.get("sessionAffinity")),
            endpoints));
  }

  private HttpResponse<String> send(URI uri, String operation)
      throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      throw GimleClusterException.unexpectedHttpStatus(
          operation, response.statusCode(), response.body());
    }
    return response;
  }
}
