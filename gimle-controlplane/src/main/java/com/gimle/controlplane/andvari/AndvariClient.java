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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;

/**
 * {@code gimle-controlplane}'s HTTP calling logic for the Andvari artifact registry, the same thin,
 * purpose-built shape {@code FafnirClient} established (and like it, deliberately no compile-time
 * dependency on {@code gimle-andvari} itself -- plain HTTP over the wire). Two differences that are
 * the whole reason this isn't a copy: bodies here are jars, so the proxy path streams rather than
 * buffering whole payloads in memory, and its timeout budget is sized for a multi-megabyte artifact
 * transfer rather than a small JSON exchange.
 *
 * <p>Accepts one or more comma-separated {@code host:port} endpoints, each naming a peer-syncing
 * Andvari replica (see {@code AndvariMain}'s own {@code --peer-endpoints}). Unlike {@code
 * gimle-mimir}'s {@code StoreClient}, Andvari has no leader: every replica accepts every write
 * independently (an idempotent, content-addressed store makes that safe -- see {@code
 * ArtifactStore}'s own javadoc), so there is no read/write split here. Every no-body call (GET,
 * HEAD, DELETE) rotates through the configured endpoints and fails over to the next one on a
 * transport failure, the same posture {@code StoreClient#sendRead} takes for its own reads. A PUT
 * carries a request body that can only be read once, so it is never retried against a second
 * endpoint after a failure -- it goes to exactly one endpoint, picked by the same rotation cursor,
 * and this replica's own periodic peer-sync tick is what eventually propagates it to every other
 * configured replica. That tradeoff (a push briefly visible on only one replica until the next sync
 * tick, rather than a slower and more failure-prone direct fan-out to all of them) is the simplest
 * option that stays correct: a fan-out risks a push landing on some endpoints and not others with
 * no way to roll it back, while a single-endpoint push plus eventual peer sync can never end up in
 * that half-committed state.
 */
public final class AndvariClient implements AutoCloseable {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(2);
  private static final String SHA256_HEADER = "X-Gimle-Artifact-Sha256";
  private static final String TENANT_HEADER = "X-Gimle-Artifact-Tenant";

  private final List<URI> baseUris;
  private final HttpClient httpClient;
  private final AtomicInteger cursor = new AtomicInteger();

  /**
   * {@code https://} with full mTLS via {@code gimle.tls.*} when {@code
   * gimle.transport.protocol=tls}, plain {@code http://} otherwise -- the same single cluster-wide
   * switch every other Gimlé transport already reads. {@code andvariEndpoints} is one or more
   * comma-separated {@code host:port} entries.
   */
  public AndvariClient(String andvariEndpoints) {
    this(parseEndpoints(andvariEndpoints), defaultSslContext());
  }

  AndvariClient(List<String> andvariEndpoints, Optional<SSLContext> sslContext) {
    if (andvariEndpoints.isEmpty()) {
      throw new IllegalArgumentException("AndvariClient requires at least one andvari endpoint");
    }
    String scheme = sslContext.isPresent() ? "https" : "http";
    List<URI> uris = new ArrayList<>();
    for (String endpoint : andvariEndpoints) {
      uris.add(URI.create(scheme + "://" + endpoint));
    }
    this.baseUris = List.copyOf(uris);
    HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT);
    sslContext.ifPresent(builder::sslContext);
    this.httpClient = builder.build();
  }

  private static List<String> parseEndpoints(String spec) {
    List<String> endpoints = new ArrayList<>();
    for (String entry : spec.split(",")) {
      String trimmed = entry.trim();
      if (!trimmed.isEmpty()) {
        endpoints.add(trimmed);
      }
    }
    return endpoints;
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
    try {
      return withEndpoints(baseUri -> headOnce(baseUri, moduleId));
    } catch (IOException e) {
      return new HeadOutcome.Unreachable(String.valueOf(e.getMessage()));
    }
  }

  private HeadOutcome headOnce(URI baseUri, ModuleId moduleId)
      throws IOException, InterruptedException {
    URI uri = artifactUri(baseUri, moduleId);
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
  }

  /**
   * Resolves a coordinate to a local jar via {@code cache}, downloading through this client's own
   * connection on a miss -- how the control plane reads a registry-resolved artifact's descriptor
   * for scheduling and quota without a second client abstraction. Every configured endpoint is
   * offered to {@code cache}, which fails over between them itself (see {@link
   * ArtifactPullCache#resolve(HttpClient, List, ModuleId)}).
   */
  public Path pullThrough(ArtifactPullCache cache, ModuleId moduleId) {
    return cache.resolve(httpClient, baseUris, moduleId);
  }

  /**
   * A streaming proxy hop for the {@code /artifacts/*} surface: unlike {@code
   * FafnirClient.forward}'s buffered byte relay, both the request and response bodies here can be
   * whole jars, so each side streams -- a push flows from the caller's socket straight through to
   * Andvari, and a pull flows back the same way, never a whole jar in this process's memory. {@code
   * headers} carries the calling principal's identity as an internal claim, trusted by Andvari only
   * because it arrives over this mTLS-authenticated connection and re-checked there regardless.
   *
   * <p>{@code body == null} (every method but PUT) rotates through every configured endpoint and
   * fails over on a transport failure; a non-null body can only be read once, so it is sent to
   * exactly one endpoint with no retry -- see this class's own javadoc for why that's the safer
   * choice for a write.
   */
  public StreamingResponse forward(
      String method, String path, InputStream body, Map<String, String> headers)
      throws IOException {
    if (body != null) {
      try {
        return forwardOnce(nextEndpoint(), method, path, body, headers);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("andvari request to " + path + " interrupted", e);
      }
    }
    return withEndpoints(baseUri -> forwardOnce(baseUri, method, path, null, headers));
  }

  private StreamingResponse forwardOnce(
      URI baseUri, String method, String path, InputStream body, Map<String, String> headers)
      throws IOException, InterruptedException {
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
        response.headers().firstValue(TENANT_HEADER),
        response.body());
  }

  /**
   * A relayed response whose body is still on the wire: the caller must consume or close {@code
   * body} exactly once.
   */
  public record StreamingResponse(
      int statusCode,
      Optional<String> contentType,
      Optional<String> sha256,
      Optional<String> tenantId,
      InputStream body) {}

  private URI artifactUri(URI baseUri, ModuleId moduleId) {
    return baseUri.resolve("/artifacts/" + moduleId.name() + "/" + moduleId.version().toString());
  }

  /** The next endpoint in rotation, with no failover -- used for a call that carries a body. */
  private URI nextEndpoint() {
    int index = cursor.getAndUpdate(i -> (i + 1) % baseUris.size());
    return baseUris.get(index % baseUris.size());
  }

  @FunctionalInterface
  private interface Attempt<T> {
    T call(URI baseUri) throws IOException, InterruptedException;
  }

  /**
   * Tries every configured endpoint in rotation starting from a shared cursor, moving to the next
   * only on a transport failure ({@link IOException}) -- a real HTTP response of any status (404,
   * 403, ...) is a legitimate answer from that endpoint and is returned as-is, never treated as a
   * reason to try another one. Mirrors {@code StoreClient#sendRead}'s own rotation exactly, adapted
   * from "any store member can answer a read" to "any Andvari replica can answer a pull."
   */
  private <T> T withEndpoints(Attempt<T> attempt) throws IOException {
    int start = cursor.getAndUpdate(i -> (i + 1) % baseUris.size());
    IOException lastFailure = null;
    for (int i = 0; i < baseUris.size(); i++) {
      URI baseUri = baseUris.get((start + i) % baseUris.size());
      try {
        return attempt.call(baseUri);
      } catch (IOException e) {
        lastFailure = e; // this endpoint is unreachable this attempt; the next one may still answer
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("andvari request interrupted", e);
      }
    }
    throw new IOException(
        "no configured andvari endpoint reachable"
            + (lastFailure == null ? "" : ": " + lastFailure.getMessage()),
        lastFailure);
  }

  @Override
  public void close() {
    httpClient.close();
  }
}
