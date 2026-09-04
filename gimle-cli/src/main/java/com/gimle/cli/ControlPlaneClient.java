package com.gimle.cli;

import com.gimle.core.exception.GimleTlsException;
import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLContext;

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
  // Jar-scale artifact transfers get their own budget rather than the 10s JSON-exchange one.
  private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(2);
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";

  private final URI baseUri;
  private final HttpClient httpClient;

  /**
   * The default construction path, used by every command that assumes a fully-provisioned identity
   * ({@code apply}, {@code get}, {@code set}, {@code delete}, {@code logs}, {@code cert approve},
   * {@code cert renew}): {@code https://} with full mTLS via {@code gimle.tls.*} when {@code
   * gimle.transport.protocol=tls}, plain {@code http://} otherwise.
   */
  public ControlPlaneClient(String serverAddress) {
    this(serverAddress, defaultSslContext());
  }

  /**
   * For the two pre-certificate flows, {@code cert request}/{@code cert status}, which by
   * definition run before the caller has a client certificate of its own to present. Trusts the
   * server (verifies against {@code gimle.tls.caFile}) without presenting one.
   */
  public static ControlPlaneClient trustOnly(String serverAddress) {
    return new ControlPlaneClient(serverAddress, trustOnlySslContext());
  }

  private ControlPlaneClient(String serverAddress, Optional<SSLContext> sslContext) {
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

  private static Optional<SSLContext> trustOnlySslContext() {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return Optional.empty();
    }
    String caFileProperty = System.getProperty(CA_FILE_PROPERTY);
    if (caFileProperty == null || caFileProperty.isBlank()) {
      throw GimleTlsException.missingProperty(CA_FILE_PROPERTY);
    }
    return Optional.of(SslContexts.forServerTrustOnly(Path.of(caFileProperty)));
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

  /**
   * As {@link #put}, but {@code PATCH} -- {@code HttpRequest.Builder} has no dedicated {@code
   * PATCH} convenience the way it does {@code GET}/{@code POST}/{@code PUT}/{@code DELETE}.
   */
  public ApiResponse patch(String path, String body) {
    return send(
        HttpRequest.newBuilder(resolve(path))
            .timeout(REQUEST_TIMEOUT)
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
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
   * PUTs {@code file}'s raw bytes to {@code path} -- a streaming upload (never the whole file in
   * memory) with a transfer-sized timeout, for jar-scale bodies the string-bodied {@link #put}
   * would corrupt and time out on.
   */
  public ApiResponse putFile(String path, Path file) {
    return putFile(path, file, Map.of());
  }

  /** As {@link #putFile(String, Path)}, with extra request headers -- e.g. a tenant claim. */
  public ApiResponse putFile(String path, Path file, Map<String, String> headers) {
    // Checked up front rather than left to the body publisher: a directory only fails once the
    // request body is read, deep inside send(), where the resulting IOException would be
    // misreported as the control plane being unreachable.
    if (Files.isDirectory(file)) {
      throw new CliException(
          file
              + " is a directory -- a multi-file application directory is published as a"
              + " 'kind: bundle' entry in an ArtifactSet manifest (gimle apply -f), not pushed"
              + " directly");
    }
    if (!Files.isRegularFile(file)) {
      throw new CliException("no such file: " + file);
    }
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(resolve(path))
              .timeout(TRANSFER_TIMEOUT)
              .PUT(HttpRequest.BodyPublishers.ofFile(file));
      headers.forEach(builder::header);
      return send(builder.build());
    } catch (FileNotFoundException e) {
      throw new CliException("no such file: " + file, e);
    }
  }

  /**
   * A bare {@code HEAD} request -- no body either way -- for a pre-flight existence/digest check
   * against a coordinate without paying for a full download. Not routed through {@link #send},
   * which always reads a response body; {@code HEAD} never carries one.
   */
  public HeadResult head(String path) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(resolve(path))
              .timeout(REQUEST_TIMEOUT)
              .method("HEAD", HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<Void> response =
          httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      return new HeadResult(
          response.statusCode(),
          response.headers().firstValue("X-Gimle-Artifact-Sha256"),
          response.headers().firstValue("X-Gimle-Artifact-Tenant"),
          response.headers().firstValue("X-Gimle-Artifact-Kind"));
    } catch (IOException e) {
      throw CliException.unavailable(unreachable(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CliException("interrupted while contacting control plane at " + baseUri, e);
    }
  }

  /** The outcome of a {@link #head} request. */
  public record HeadResult(
      int statusCode, Optional<String> sha256, Optional<String> tenantId, Optional<String> kind) {}

  /**
   * GETs {@code path} streaming straight into {@code target} (never the whole body in memory),
   * returning the response's {@code X-Gimle-Artifact-Sha256} digest header when the server sent
   * one.
   */
  public Optional<String> downloadFile(String path, Path target) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(resolve(path)).timeout(TRANSFER_TIMEOUT).GET().build();
      HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        throw errorFrom(new ApiResponse(response.statusCode(), readAll(response.body())));
      }
      try (InputStream in = response.body()) {
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      }
      return response.headers().firstValue("X-Gimle-Artifact-Sha256");
    } catch (IOException e) {
      throw CliException.unavailable(unreachable(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CliException("interrupted while contacting control plane at " + baseUri, e);
    }
  }

  /** GETs {@code path}, expects a 2xx response, and parses the body as a JSON object list. */
  public List<Map<String, Object>> getList(String path) {
    return parseObjectList(expectSuccess(get(path)));
  }

  /** GETs {@code path}, expects a 2xx response, and parses the body as a JSON object. */
  public Map<String, Object> getObject(String path) {
    return parseObject(expectSuccess(get(path)));
  }

  /**
   * Opens {@code path} as a long-lived streaming GET (a {@code follow=true} log tail) and returns
   * its response body unbuffered, for the caller to read line-by-line as bytes arrive -- unlike
   * every other method here, which fully buffers the response via {@code BodyHandlers.ofString}. No
   * request timeout: a live tail has no natural end, unlike every other call this client makes.
   */
  public InputStream openStream(String path) {
    try {
      HttpRequest request = HttpRequest.newBuilder(resolve(path)).GET().build();
      HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        throw errorFrom(new ApiResponse(response.statusCode(), readAll(response.body())));
      }
      return response.body();
    } catch (IOException e) {
      throw CliException.unavailable(unreachable(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CliException("interrupted while contacting control plane at " + baseUri, e);
    }
  }

  /**
   * A connection failure's own message is often {@code null} -- {@code ConnectException} and {@code
   * SocketException} routinely carry none -- which would otherwise reach an operator as "... at
   * http://host:port: null". Falls back to the exception's type, which at least names what went
   * wrong.
   */
  private String unreachable(final IOException cause) {
    String detail = cause.getMessage();
    return "could not reach control plane at "
        + baseUri
        + ": "
        + (detail == null || detail.isBlank() ? cause.getClass().getSimpleName() : detail);
  }

  private static String readAll(InputStream in) throws IOException {
    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
  }

  /**
   * Returns the response body on a 2xx status, else throws {@link CliException} with a message
   * tailored to the status code.
   */
  public String expectSuccess(ApiResponse response) {
    if (response.isSuccess()) {
      return response.body();
    }
    throw errorFrom(response);
  }

  /**
   * The single place a malformed or unexpectedly-shaped response body is translated into a {@link
   * CliException}, so callers never see a raw {@code Json} parsing/casting failure -- keeps {@code
   * GimleCli.run}'s top-level catch scoped to {@link CliException} alone rather than a bare {@code
   * RuntimeException} that could mask a genuine CLI-side bug as a server-response problem.
   */
  private static List<Map<String, Object>> parseObjectList(String body) {
    try {
      return Json.asObjectList(Json.parse(body));
    } catch (IllegalArgumentException | ClassCastException e) {
      throw new CliException("unexpected response from control plane: " + e.getMessage(), e);
    }
  }

  private static Map<String, Object> parseObject(String body) {
    try {
      return Json.asObject(Json.parse(body));
    } catch (IllegalArgumentException | ClassCastException e) {
      throw new CliException("unexpected response from control plane: " + e.getMessage(), e);
    }
  }

  /**
   * Turns a non-2xx response into the exception the caller will fail with, classified by the
   * distinction the status code already draws so the reason survives all the way to the process's
   * own exit status. {@code 401} and {@code 403} share {@link CliExitCode#FORBIDDEN}: both mean the
   * caller may not do this, and the CLI can offer no remedy that depends on telling them apart. A
   * {@code 307} only reaches here when the leader is unknown -- one with a {@code Location} header
   * was already followed -- which makes it a retry-shortly condition, the same class as an
   * unreachable server.
   */
  private static CliException errorFrom(ApiResponse response) {
    return switch (response.statusCode()) {
      case 400 -> CliException.invalidInput("invalid request: " + response.body());
      case 401 -> CliException.forbidden("unauthorized: " + response.body());
      case 403 -> CliException.forbidden("forbidden: " + response.body());
      case 404 -> CliException.notFound("not found: " + response.body());
      case 409 -> CliException.conflict("conflict: " + response.body());
      case 307 -> CliException.unavailable(describeNotLeader(response.body()));
      case 405 -> new CliException("method not allowed: " + response.body());
      default ->
          new CliException(
              "unexpected response (" + response.statusCode() + "): " + response.body());
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
      return new ApiResponse(
          response.statusCode(), response.body(), response.headers().allValues("X-Gimle-Warning"));
    } catch (IOException e) {
      throw CliException.unavailable(unreachable(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CliException("interrupted while contacting control plane at " + baseUri, e);
    }
  }
}
