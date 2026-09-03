package com.gimle.mavenplugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin HTTP client for an Ivaldi cluster-designer server's own surface ({@code /api/health}, {@code
 * /api/shutdown}). Deliberately talks only HTTP -- this plugin never links against the server's own
 * module, so the two ship and evolve independently and the plugin works against any already-running
 * server build. Mirrors {@code SagaClient} exactly.
 */
final class IvaldiClient {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final String endpoint;
  private final HttpClient http;

  IvaldiClient(String endpoint) {
    this.endpoint =
        endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  String endpoint() {
    return endpoint;
  }

  /** Never throws: an unreachable or unhappy server is simply "not healthy". */
  boolean isHealthy() {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint + "/api/health"))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
    try {
      return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Best-effort {@code POST /api/shutdown}; false when unreachable or not implemented. */
  boolean shutdown() {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint + "/api/shutdown"))
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    try {
      int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
      return status / 100 == 2;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
