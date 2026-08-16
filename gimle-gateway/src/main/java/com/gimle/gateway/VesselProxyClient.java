package com.gimle.gateway;

import com.gimle.gateway.GatewayDispatcher.GatewayResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The plain outbound HTTP call a {@link GatewayRoute.VesselRoute} makes to a resolved {@link
 * VesselEndpointCache.HostPort} -- no TLS (matching the gateway's own existing plaintext-only
 * posture, see this module's own README/deployment.yaml), and no header forwarding in either
 * direction: only the inbound request's method, path, and body are proxied, and only the downstream
 * response's status and body are handed back, a deliberate v1 narrowing rather than an oversight
 * (forwarding an arbitrary header set correctly -- stripping hop-by-hop headers, rewriting {@code
 * Host}, not double-setting {@code Content-Length} -- is real work this increment doesn't attempt).
 *
 * <p>Both connection establishment and the request as a whole are bounded (see {@link
 * #CONNECT_TIMEOUT}/{@link #REQUEST_TIMEOUT}) -- the same "a caller must never hang forever on a
 * wedged peer" posture the service fabric's own {@code FabricClient} already takes for its cross-
 * hop calls; a vessel instance a route proxies to is exactly as capable of accepting a connection
 * and then never responding as any other peer on the network.
 */
final class VesselProxyClient {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient client;

  VesselProxyClient() {
    this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  }

  GatewayResponse proxy(
      VesselEndpointCache.HostPort target, String httpMethod, String path, String body) {
    URI uri = URI.create("http://" + target.host() + ":" + target.port() + path);
    HttpRequest.BodyPublisher bodyPublisher =
        (body == null || body.isEmpty())
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .method(httpMethod, bodyPublisher)
            .timeout(REQUEST_TIMEOUT)
            .build();
    try {
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new GatewayResponse(response.statusCode(), response.body());
    } catch (IOException e) {
      return new GatewayResponse(502, "vessel proxy call to " + uri + " failed: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new GatewayResponse(502, "vessel proxy call to " + uri + " was interrupted");
    }
  }
}
