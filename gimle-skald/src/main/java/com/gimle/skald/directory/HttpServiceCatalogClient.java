package com.gimle.skald.directory;

import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

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
  public List<ServiceListing> listServices() throws IOException, InterruptedException {
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
    // GET /services answers an array of Service JSON objects (ApiServer#serviceToJson), not bare
    // name strings -- each entry's "name" and "tenantId" fields are what this poller actually
    // needs (tenantId is absent for an untenanted Service, present for a tenant-scoped one).
    List<Map<String, Object>> raw = Json.asObjectList(Json.parse(response.body()));
    List<ServiceListing> listings = new ArrayList<>(raw.size());
    for (Map<String, Object> entry : raw) {
      String name = String.valueOf(entry.get("name"));
      Optional<String> tenantId =
          entry.get("tenantId") instanceof String s ? Optional.of(s) : Optional.empty();
      listings.add(new ServiceListing(name, tenantId));
    }
    return List.copyOf(listings);
  }

  @Override
  public Optional<ServiceEndpoints> fetchEndpoints(ServiceListing listing)
      throws IOException, InterruptedException {
    String serviceName = listing.name();
    // The tenant this Service was listed under has to ride the endpoints read: the control plane
    // keys a Service by (tenant, name), so a tenant-scoped one asked for by bare name answers 404.
    String tenantQuery =
        listing
            .tenantId()
            .map(t -> "?tenant=" + URLEncoder.encode(t, StandardCharsets.UTF_8))
            .orElse("");
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(
                    baseUri.resolve(
                        "services/"
                            + URLEncoder.encode(serviceName, StandardCharsets.UTF_8)
                            + "/endpoints"
                            + tenantQuery))
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
    OptionalInt targetPort =
        body.get("targetPort") instanceof Number raw
            ? OptionalInt.of(raw.intValue())
            : OptionalInt.empty();
    List<Map<String, Object>> rawEndpoints = Json.asObjectList(body.get("endpoints"));
    List<HostPort> endpoints = new ArrayList<>(rawEndpoints.size());
    for (Map<String, Object> endpoint : rawEndpoints) {
      endpoints.add(
          new HostPort(
              String.valueOf(endpoint.get("host")), ((Number) endpoint.get("port")).intValue()));
    }
    return Optional.of(new ServiceEndpoints(name, port, targetPort, endpoints));
  }
}
