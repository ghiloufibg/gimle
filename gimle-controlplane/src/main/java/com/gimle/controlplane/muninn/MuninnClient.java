package com.gimle.controlplane.muninn;

import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import javax.net.ssl.SSLContext;

/**
 * {@code gimle-controlplane}'s HTTP calling logic for Muninn's {@code /logs/*} history read surface
 * (design doc Part B/O-11) -- a thin, purpose-built client mirroring {@code FafnirClient}'s own
 * shape (scheme selection off {@link TransportProtocol#fromConfig()}, no compile-time dependency on
 * {@code gimle-muninn} itself, plain HTTP+JSON over the wire). Unlike {@code fafnirClient}, this
 * one is genuinely optional on {@link com.gimle.controlplane.api.ApiServer}: a cluster with no
 * Muninn endpoint configured simply never gets the {@code /logs/*} fallback for a gone
 * node/instance, the exact same "optional, degrade gracefully" posture {@code gimle-agent}'s own
 * {@code muninnEndpoint} already has.
 */
public final class MuninnClient implements AutoCloseable {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final URI baseUri;
  private final HttpClient httpClient;

  public MuninnClient(String muninnAddress) {
    this(muninnAddress, defaultSslContext());
  }

  MuninnClient(String muninnAddress, Optional<SSLContext> sslContext) {
    String scheme = sslContext.isPresent() ? "https" : "http";
    this.baseUri = URI.create(scheme + "://" + muninnAddress);
    HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT);
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
   * Relays Muninn's response verbatim -- {@code ApiServer} doesn't need typed handling here, only a
   * byte-for-byte proxy hop, the same "pure network relay" shape {@code FafnirClient#forward}
   * already uses for {@code /secrets/*}. Throws {@link IOException} on any network-level failure
   * (unreachable, timeout); the caller decides what that means (typically: fall back to a plain
   * 404, since neither the live agent nor Muninn had anything).
   */
  public RawResponse get(String pathAndQuery) throws IOException {
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
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while reaching muninn", e);
    }
  }

  /** A raw HTTP response relayed verbatim back to the original caller by {@code ApiServer}. */
  public record RawResponse(int statusCode, String contentType, byte[] body) {}

  @Override
  public void close() {
    httpClient.close();
  }
}
