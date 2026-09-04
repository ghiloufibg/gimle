package com.gimle.hilmir.release;

import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.hilmir.HilmirException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
 *
 * <p>Public (rather than package-private, its original shape) so {@code com.gimle.hilmir.doctor}'s
 * cluster-aware checks -- e.g. "does this tenant actually exist" -- can reuse it too, rather than a
 * second small HTTP client duplicating this one within the same module.
 */
public final class ControlPlaneApi {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /**
   * A whole-jar streaming upload can run far longer than any ordinary JSON call, so it gets its
   * own, much longer timeout budget rather than sharing {@link #REQUEST_TIMEOUT} -- the same split
   * {@code gimle-cli}'s own {@code ControlPlaneClient} makes between its JSON-call and
   * file-transfer timeouts.
   */
  private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(2);

  private final URI baseUri;
  private final HttpClient httpClient;

  public ControlPlaneApi(String serverAddress) {
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

  ApiResponse post(String path, String body) {
    return send(
        HttpRequest.newBuilder(resolve(path))
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build());
  }

  ApiResponse delete(String path) {
    return send(HttpRequest.newBuilder(resolve(path)).timeout(REQUEST_TIMEOUT).DELETE().build());
  }

  /**
   * Streams {@code file}'s bytes straight from disk as a PUT body (never the whole jar buffered in
   * memory), the same {@code HttpRequest.BodyPublishers.ofFile} approach {@code gimle-cli}'s own
   * {@code ControlPlaneClient.putFile} uses, over {@link #TRANSFER_TIMEOUT} rather than {@link
   * #REQUEST_TIMEOUT}. Throws on any non-2xx response the same way {@link #expectSuccess} does --
   * {@code expectSuccess} itself is package-private, so a caller outside this package (an artifact
   * push from {@code com.gimle.hilmir.extension}, say) needs this method to already guarantee
   * success rather than being handed a raw {@link ApiResponse} it has no way to check itself.
   */
  public void putFile(String path, Path file) {
    try {
      expectSuccess(
          send(
              HttpRequest.newBuilder(resolve(path))
                  .timeout(TRANSFER_TIMEOUT)
                  .PUT(HttpRequest.BodyPublishers.ofFile(file))
                  .build()));
    } catch (FileNotFoundException e) {
      throw new HilmirException("no such file: " + file, e);
    }
  }

  /**
   * PUTs a JSON body to {@code path} and expects a 2xx response -- public for the same reason
   * {@link #putFile} is: a caller outside this package driving a control-plane resource that isn't
   * itself part of a release (Ivaldi's own standalone-manifest push, for instance) has no other way
   * to reach {@link #put}, which stays package-private for {@link ReleaseReconciler}'s own internal
   * use.
   */
  public void putJson(String path, String jsonBody) {
    expectSuccess(put(path, jsonBody));
  }

  /**
   * POSTs a JSON body to {@code path} and expects a 2xx response. The counterpart to {@link
   * #putJson} for the control-plane collections that create-or-replace by the name their body
   * carries ({@code /services}, {@code /networkpolicies}) rather than by a name in the URL.
   */
  public void postJson(String path, String jsonBody) {
    expectSuccess(post(path, jsonBody));
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
  public boolean exists(String path) {
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
      case 403 -> describeForbidden(response.body());
      case 404 -> "not found: " + response.body();
      case 405 -> "method not allowed: " + response.body();
      case 409 -> "conflict: " + response.body();
      case 307 -> describeNotLeader(response.body());
      default -> "unexpected response (" + response.statusCode() + "): " + response.body();
    };
  }

  /**
   * Names the cause a caller has no way to guess from the body alone. Every release verb records
   * itself under the fixed {@code gimle-hilmir} bookkeeping tenant, creating that tenant on first
   * use -- and a tenant creation is exactly what a plaintext control plane refuses once any other
   * tenant already exists. The refusal therefore has nothing to do with the bundle being deployed
   * or the tenant it targets, which is where an operator reading the bare message looks first.
   */
  private static String describeForbidden(String body) {
    String detail = body == null || body.isBlank() ? "" : ": " + body;
    if (body != null && body.contains("only one real tenant may exist")) {
      return "forbidden"
          + detail
          + "\n\nevery hilmir release verb records itself under the fixed gimle-hilmir"
          + " bookkeeping tenant and creates it on first use, which a plaintext control plane"
          + " refuses once another tenant already exists -- this is not about the bundle or the"
          + " tenant it targets. Use mTLS (-Dgimle.transport.protocol=tls with operator"
          + " credentials) for a cluster that has more than one tenant.";
    }
    return "forbidden" + detail;
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
      // e.getMessage() is null for some IOException subtypes (a bare ConnectException on some
      // platforms carries no detail message at all) -- falling back to the exception's own class
      // name keeps this readable instead of ending in a bare, confusing ": null".
      String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      throw new HilmirException("could not reach control plane at " + baseUri + ": " + detail, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HilmirException("interrupted while contacting control plane at " + baseUri, e);
    }
  }
}
