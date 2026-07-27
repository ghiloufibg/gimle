package com.gimle.cli;

import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * The CLI's HTTP calling logic, shared by every command class. Wraps {@code HttpClient} with an
 * explicit request timeout and status-code checking, so callers get a {@link CliException} instead
 * of a raw response to inspect.
 *
 * <p>{@code HttpClient.Redirect.NORMAL} handles the client side of a not-leader {@code 307}
 * response: it follows the redirect while preserving the original HTTP method (why the control
 * plane returns {@code 307} rather than {@code 301}/{@code 302} for this case), so a write sent to
 * any reachable replica transparently reaches the current leader. Only a {@code 307} with no {@code
 * Location} header (leader currently unknown) ever reaches {@link #expectSuccess}.
 */
public final class ControlPlaneClient {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final URI baseUri;
  private final HttpClient httpClient;

  public ControlPlaneClient(String serverAddress) {
    this.baseUri = URI.create("http://" + serverAddress);
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  public ApiResponse get(String path) {
    return send(HttpRequest.newBuilder(resolve(path)).timeout(REQUEST_TIMEOUT).GET().build());
  }

  public ApiResponse put(String path, String body) {
    return send(
        HttpRequest.newBuilder(resolve(path))
            .timeout(REQUEST_TIMEOUT)
            .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build());
  }

  public ApiResponse post(String path, String body) {
    return send(
        HttpRequest.newBuilder(resolve(path))
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build());
  }

  public ApiResponse delete(String path) {
    return send(HttpRequest.newBuilder(resolve(path)).timeout(REQUEST_TIMEOUT).DELETE().build());
  }

  /**
   * Returns the response body on a 2xx status, else throws {@link CliException} with a message
   * tailored to the status code.
   */
  public String expectSuccess(ApiResponse response) {
    if (response.isSuccess()) {
      return response.body();
    }
    throw new CliException(describeError(response));
  }

  private static String describeError(ApiResponse response) {
    return switch (response.statusCode()) {
      case 400 -> "invalid request: " + response.body();
      case 404 -> "not found: " + response.body();
      case 405 -> "method not allowed: " + response.body();
      case 409 -> "conflict: " + response.body();
      case 307 -> describeNotLeader(response.body());
      default -> "unexpected response (" + response.statusCode() + "): " + response.body();
    };
  }

  private static String describeNotLeader(String body) {
    try {
      Map<String, Object> parsed = Json.asObject(Json.parse(body));
      Object leaderApiAddress = parsed.get("leaderApiAddress");
      if (leaderApiAddress != null) {
        return "control plane leader is at " + leaderApiAddress + "; retry against that address";
      }
    } catch (RuntimeException ignored) {
      // fall through to the generic message below
    }
    return "control plane leader is currently unknown; try again shortly";
  }

  private URI resolve(String path) {
    return baseUri.resolve(path);
  }

  private ApiResponse send(HttpRequest request) {
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new ApiResponse(response.statusCode(), response.body());
    } catch (IOException e) {
      throw new CliException(
          "could not reach control plane at " + baseUri + ": " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CliException("interrupted while contacting control plane at " + baseUri, e);
    }
  }
}
