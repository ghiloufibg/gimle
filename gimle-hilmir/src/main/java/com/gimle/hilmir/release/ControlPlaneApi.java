package com.gimle.hilmir.release;

import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.hilmir.HilmirException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLContext;

/**
 * Hilmir's own small HTTP calling logic for the release verbs, deliberately duplicating {@code
 * gimle-cli}'s {@code ControlPlaneClient} rather than depending on {@code gimle-cli} for one class
 * -- the same reasoning {@link com.gimle.hilmir.HilmirException}'s own javadoc gives for
 * duplicating {@code CliException}: the same {@code HttpClient} construction ({@code
 * connectTimeout}, {@code Redirect.NORMAL} so a not-leader {@code 307} response is transparently
 * retried against the real leader while preserving the original HTTP method), the same {@code
 * gimle.transport.protocol}- driven plaintext/mTLS switch, and the same status-code-tailored error
 * messages.
 */
final class ControlPlaneApi {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final URI baseUri;
  private final HttpClient httpClient;

  ControlPlaneApi(String serverAddress) {
    Optional<SSLContext> sslContext = defaultSslContext();
    String scheme = sslContext.isPresent() ? "https" : "http";
    this.baseUri = URI.create(scheme + "://" + serverAddress);
    HttpClient.Builder builder =
        HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL);
    sslContext.ifPresent(builder::sslContext);
    this.httpClient = builder.build();
  }

  private static Optional<SSLContext> defaultSslContext() {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return Optional.empty();
    }
    return Optional.of(SslContexts.forMutualTls(TlsSettings.fromConfig()));
  }

  ApiResponse get(String path) {
    return send(HttpRequest.newBuilder(resolve(path)).timeout(REQUEST_TIMEOUT).GET().build());
  }

  ApiResponse put(String path, String body) {
    return send(
        HttpRequest.newBuilder(resolve(path))
            .timeout(REQUEST_TIMEOUT)
            .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build());
  }

  ApiResponse delete(String path) {
    return send(HttpRequest.newBuilder(resolve(path)).timeout(REQUEST_TIMEOUT).DELETE().build());
  }

  /** GETs {@code path}, expects a 2xx response, and parses the body as a JSON object. */
  Map<String, Object> getObject(String path) {
    return Json.asObject(Json.parse(expectSuccess(get(path))));
  }

  /** GETs {@code path}, expects a 2xx response, and parses the body as a JSON object list. */
  List<Map<String, Object>> getList(String path) {
    return Json.asObjectList(Json.parse(expectSuccess(get(path))));
  }

  /**
   * A pure existence check -- {@code true} for a 2xx GET response, {@code false} for a 404 -- used
   * by a CronJob's own "ready" signal, which is nothing more than "does this resource round-trip
   * back from the control plane."
   */
  boolean exists(String path) {
    return get(path).isSuccess();
  }

  /**
   * Returns the response body on a 2xx status, else throws {@link HilmirException} with a message
   * tailored to the status code.
   */
  String expectSuccess(ApiResponse response) {
    if (response.isSuccess()) {
      return response.body();
    }
    throw new HilmirException(describeError(response));
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
      throw new HilmirException(
          "could not reach control plane at " + baseUri + ": " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HilmirException("interrupted while contacting control plane at " + baseUri, e);
    }
  }
}
