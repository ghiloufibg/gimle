package com.gimle.controlplane.muninn;

import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TransportProtocol;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;

/**
 * {@code gimle-controlplane}'s HTTP calling logic for Muninn's {@code /logs/*} history read surface
 * -- a thin, purpose-built client mirroring {@code FafnirClient}'s own shape (scheme selection off
 * {@link TransportProtocol#fromConfig()}, no compile-time dependency on {@code gimle-muninn}
 * itself, plain HTTP+JSON over the wire). Unlike {@code fafnirClient}, this one is genuinely
 * optional on {@link com.gimle.controlplane.api.ApiServer}: a cluster with no Muninn endpoint
 * configured simply never gets the {@code /logs/*} fallback for a gone node/instance, the exact
 * same "optional, degrade gracefully" posture {@code gimle-agent}'s own {@code muninnEndpoint}
 * already has.
 *
 * <p>Holds a list of configured Muninn replicas rather than one, and rotates across them on
 * transport failure -- the same round-robin-with-failover shape {@code gimle-mimir}'s own {@code
 * StoreClient#sendRead} uses for its reads: no endpoint is "primary," a request just tries every
 * configured replica in rotation (starting from a shared cursor, so repeated calls spread across
 * the pool) until one answers or all of them are unreachable.
 */
public final class MuninnClient implements AutoCloseable {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final List<URI> baseUris;
  private final HttpClient httpClient;
  private final AtomicInteger readCursor = new AtomicInteger();

  public MuninnClient(String muninnAddress) {
    this(List.of(muninnAddress), SslContexts.forMutualTlsFromConfig());
  }

  public MuninnClient(List<String> muninnAddresses) {
    this(muninnAddresses, SslContexts.forMutualTlsFromConfig());
  }

  MuninnClient(List<String> muninnAddresses, Optional<SSLContext> sslContext) {
    if (muninnAddresses.isEmpty()) {
      throw new IllegalArgumentException("MuninnClient requires at least one Muninn endpoint");
    }
    String scheme = sslContext.isPresent() ? "https" : "http";
    List<URI> uris = new ArrayList<>();
    for (String address : muninnAddresses) {
      uris.add(URI.create(scheme + "://" + address));
    }
    this.baseUris = List.copyOf(uris);
    HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT);
    sslContext.ifPresent(builder::sslContext);
    this.httpClient = builder.build();
  }

  /**
   * Relays Muninn's response verbatim -- {@code ApiServer} doesn't need typed handling here, only a
   * byte-for-byte proxy hop, the same "pure network relay" shape {@code FafnirClient#forward}
   * already uses for {@code /secrets/*}. Tries every configured endpoint in rotation, failing over
   * to the next on a transport-level failure (unreachable, timeout) -- an actual HTTP response from
   * Muninn, including a 404, is returned as-is rather than treated as a reason to try another
   * replica. Throws {@link IOException} only once every configured endpoint has failed; the caller
   * decides what that means (typically: fall back to a plain 404, since neither the live agent nor
   * any Muninn replica had anything).
   */
  public RawResponse get(String pathAndQuery) throws IOException {
    int start = readCursor.getAndUpdate(i -> (i + 1) % baseUris.size());
    IOException lastFailure = null;
    for (int i = 0; i < baseUris.size(); i++) {
      URI baseUri = baseUris.get((start + i) % baseUris.size());
      try {
        HttpResponse<byte[]> response =
            httpClient.send(
                HttpRequest.newBuilder(baseUri.resolve(pathAndQuery))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        String contentType =
            response.headers().firstValue("Content-Type").orElse("application/octet-stream");
        return new RawResponse(response.statusCode(), contentType, response.body());
      } catch (IOException e) {
        // this endpoint is unreachable this attempt; the next configured replica may still answer.
        lastFailure = e;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted while reaching muninn", e);
      }
    }
    throw lastFailure;
  }

  /**
   * Every configured replica's answer to {@code pathAndQuery}, for a read whose correct result is
   * the union of what the replicas hold rather than whichever one answers first. A shipper fans a
   * batch out best-effort and treats one acceptance as success, so a replica that was briefly
   * unreachable is permanently missing that batch -- and its silence about a record is not evidence
   * the record does not exist. Only transport failures are skipped; an HTTP answer, 404 included,
   * is a real answer and is returned for the caller to merge.
   *
   * @throws IOException only when no configured replica could be reached at all
   */
  public List<RawResponse> getFromEveryReplica(String pathAndQuery) throws IOException {
    List<RawResponse> answers = new ArrayList<>();
    IOException lastFailure = null;
    for (URI baseUri : baseUris) {
      try {
        HttpResponse<byte[]> response =
            httpClient.send(
                HttpRequest.newBuilder(baseUri.resolve(pathAndQuery))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        answers.add(
            new RawResponse(
                response.statusCode(),
                response.headers().firstValue("Content-Type").orElse("application/octet-stream"),
                response.body()));
      } catch (IOException e) {
        lastFailure = e;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted while reaching muninn", e);
      }
    }
    if (answers.isEmpty()) {
      throw lastFailure;
    }
    return answers;
  }

  /** A raw HTTP response relayed verbatim back to the original caller by {@code ApiServer}. */
  public record RawResponse(int statusCode, String contentType, byte[] body) {}

  @Override
  public void close() {
    httpClient.close();
  }
}
