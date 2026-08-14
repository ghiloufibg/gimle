package com.gimle.controlplane.andvari;

import com.gimle.core.module.ModuleId;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.module.artifact.ArtifactPullCache;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLContext;

/**
 * {@code gimle-controlplane}'s HTTP calling logic for the Andvari artifact registry, the same thin,
 * purpose-built shape {@code FafnirClient} established (and like it, deliberately no compile-time
 * dependency on {@code gimle-andvari} itself -- plain HTTP over the wire). Two differences that are
 * the whole reason this isn't a copy: bodies here are jars, so the proxy path streams rather than
 * buffering whole payloads in memory, and its timeout budget is sized for a multi-megabyte artifact
 * transfer rather than a small JSON exchange.
 */
public final class AndvariClient implements AutoCloseable {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(2);
  private static final String SHA256_HEADER = "X-Gimle-Artifact-Sha256";

  private final URI baseUri;
  private final HttpClient httpClient;

  /**
   * {@code https://} with full mTLS via {@code gimle.tls.*} when {@code
   * gimle.transport.protocol=tls}, plain {@code http://} otherwise -- the same single cluster-wide
   * switch every other Gimlé transport already reads.
   */
  public AndvariClient(String andvariAddress) {
    this(andvariAddress, defaultSslContext());
  }

  AndvariClient(String andvariAddress, Optional<SSLContext> sslContext) {
    String scheme = sslContext.isPresent() ? "https" : "http";
    this.baseUri = URI.create(scheme + "://" + andvariAddress);
    HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT);
    sslContext.ifPresent(builder::sslContext);
    this.httpClient = builder.build();
  }

  private static Optional<SSLContext> defaultSslContext() {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return Optional.empty();
    }
    return Optional.of(SslContexts.forMutualTls(TlsSettings.fromConfig()));
  }

  /**
   * The admission-time existence-plus-digest check for a registry-coordinate spec. The three
   * outcomes are deliberately distinct because admission treats them differently: a definitive "not
   * there" rejects the manifest outright, while an unreachable registry admits it with no recorded
   * digest -- the level-triggered reconcilers converge once the registry is back, the same tolerant
   * posture an unreadable local {@code artifactPath} already gets.
   */
  public sealed interface HeadOutcome {
    record Found(String sha256) implements HeadOutcome {}

    record NotFound() implements HeadOutcome {}

    record Unreachable(String reason) implements HeadOutcome {}
  }

  public HeadOutcome head(ModuleId moduleId) {
    URI uri = artifactUri(moduleId);
    try {
      HttpResponse<Void> response =
          httpClient.send(
              HttpRequest.newBuilder(uri)
                  .timeout(CONNECT_TIMEOUT)
                  .method("HEAD", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() == 404) {
        return new HeadOutcome.NotFound();
      }
      if (response.statusCode() != 200) {
        return new HeadOutcome.Unreachable("registry answered " + response.statusCode());
      }
      return response
          .headers()
          .firstValue(SHA256_HEADER)
          .<HeadOutcome>map(HeadOutcome.Found::new)
          .orElseGet(() -> new HeadOutcome.Unreachable("registry sent no " + SHA256_HEADER));
    } catch (IOException e) {
      return new HeadOutcome.Unreachable(String.valueOf(e.getMessage()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new HeadOutcome.Unreachable("interrupted");
    }
  }

  /**
   * Resolves a coordinate to a local jar via {@code cache}, downloading through this client's own
   * connection on a miss -- how the control plane reads a registry-resolved artifact's descriptor
   * for scheduling and quota without a second client abstraction.
   */
  public Path pullThrough(ArtifactPullCache cache, ModuleId moduleId) {
    return cache.resolve(httpClient, baseUri, moduleId);
  }

  /**
   * A streaming proxy hop for the {@code /artifacts/*} surface: unlike {@code
   * FafnirClient.forward}'s buffered byte relay, both the request and response bodies here can be
   * whole jars, so each side streams -- a push flows from the caller's socket straight through to
   * Andvari, and a pull flows back the same way, never a whole jar in this process's memory. {@code
   * headers} carries the calling principal's identity as an internal claim, trusted by Andvari only
   * because it arrives over this mTLS-authenticated connection and re-checked there regardless.
   */
  public StreamingResponse forward(
      String method, String path, InputStream body, Map<String, String> headers)
      throws IOException {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(baseUri.resolve(path)).timeout(TRANSFER_TIMEOUT);
      headers.forEach(builder::header);
      builder.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofInputStream(() -> body));
      HttpResponse<InputStream> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
      return new StreamingResponse(
          response.statusCode(),
          response.headers().firstValue("Content-Type"),
          response.headers().firstValue(SHA256_HEADER),
          response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("andvari request to " + path + " interrupted", e);
    }
  }

  /**
   * A relayed response whose body is still on the wire: the caller must consume or close {@code
   * body} exactly once.
   */
  public record StreamingResponse(
      int statusCode, Optional<String> contentType, Optional<String> sha256, InputStream body) {}

  private URI artifactUri(ModuleId moduleId) {
    return baseUri.resolve("/artifacts/" + moduleId.name() + "/" + moduleId.version().toString());
  }

  @Override
  public void close() {
    httpClient.close();
  }
}
