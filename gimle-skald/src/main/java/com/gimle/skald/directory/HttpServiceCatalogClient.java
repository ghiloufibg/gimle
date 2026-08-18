package com.gimle.skald.directory;

import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The real {@link ServiceCatalogClient}: reads {@code GET /services} and {@code GET
 * /services/{name}/endpoints} off the control plane's HTTP API, using {@code gimle-core}'s own
 * {@link Json} reader rather than pulling in a JSON library -- the same "hand-roll it, it's small"
 * posture the control plane's own request/response shapes already take elsewhere.
 */
public final class HttpServiceCatalogClient implements ServiceCatalogClient {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient httpClient;
  private final URI baseUri;

  /**
   * {@code baseUri} must end in a trailing slash so {@link URI#resolve} appends rather than
   * replaces the last path segment.
   */
  public HttpServiceCatalogClient(HttpClient httpClient, URI baseUri) {
    this.httpClient = httpClient;
    this.baseUri = baseUri;
  }

  @Override
  public List<String> listServiceNames() throws IOException, InterruptedException {
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(baseUri.resolve("services"))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException(
          "control plane answered " + response.statusCode() + " for GET /services");
    }
    List<Object> raw = Json.asArray(Json.parse(response.body()));
    List<String> names = new ArrayList<>(raw.size());
    for (Object entry : raw) {
      names.add(String.valueOf(entry));
    }
    return List.copyOf(names);
  }

  @Override
  public Optional<ServiceEndpoints> fetchEndpoints(String serviceName)
      throws IOException, InterruptedException {
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(baseUri.resolve("services/" + serviceName + "/endpoints"))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 404) {
      // Raced to deletion between the listing call and this one -- not a failure worth logging,
      // just nothing left to cache for this name this cycle.
      return Optional.empty();
    }
    if (response.statusCode() != 200) {
      throw new IOException(
          "control plane answered "
              + response.statusCode()
              + " for GET /services/"
              + serviceName
              + "/endpoints");
    }
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    String name = String.valueOf(body.get("name"));
    int port = ((Number) body.get("port")).intValue();
    int targetPort = ((Number) body.get("targetPort")).intValue();
    List<Map<String, Object>> endpoints = Json.asObjectList(body.get("endpoints"));
    List<String> hosts = new ArrayList<>(endpoints.size());
    for (Map<String, Object> endpoint : endpoints) {
      hosts.add(String.valueOf(endpoint.get("host")));
    }
    return Optional.of(new ServiceEndpoints(name, port, targetPort, hosts));
  }
}
