package com.gimle.controlplane.fafnir;

import com.gimle.core.exception.GimleSecretsException;
import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TransportProtocol;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLContext;

/**
 * {@code gimle-controlplane}'s HTTP calling logic for Fafnir's internal crypto surface -- a thin,
 * purpose-built client, not a reuse of {@code gimle-cli}'s own {@code ControlPlaneClient}
 * (different audience, different endpoint shapes, and this class has no business depending on
 * {@code gimle-cli}). Deliberately carries **no** compile-time dependency on {@code gimle-fafnir}
 * itself -- plain HTTP+JSON over the wire, the same "pure network relay" shape the eventual {@code
 * /secrets/*} proxy to Fafnir uses, just with typed request/response handling here instead of a
 * byte relay, since these are fixed, small, internal operations rather than a pass-through of an
 * evolving public API.
 */
public final class FafnirClient implements AutoCloseable {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final URI baseUri;
  private final HttpClient httpClient;

  /**
   * {@code https://} with full mTLS via {@code gimle.tls.*} when {@code
   * gimle.transport.protocol=tls}, plain {@code http://} otherwise -- the same single cluster-wide
   * switch every other Gimlé transport already reads (see {@link TransportProtocol}'s own javadoc).
   */
  public FafnirClient(String fafnirAddress) {
    this(fafnirAddress, SslContexts.forMutualTlsFromConfig());
  }

  FafnirClient(String fafnirAddress, Optional<SSLContext> sslContext) {
    String scheme = sslContext.isPresent() ? "https" : "http";
    this.baseUri = URI.create(scheme + "://" + fafnirAddress);
    HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT);
    sslContext.ifPresent(builder::sslContext);
    this.httpClient = builder.build();
  }

  public byte[] encrypt(byte[] plaintext) {
    Map<String, Object> response =
        post("/internal/secrets/encrypt", Map.of("value", encode(plaintext)));
    return decode((String) response.get("ciphertext"));
  }

  public List<byte[]> decryptBatch(List<byte[]> ciphertexts) {
    List<String> encoded = ciphertexts.stream().map(FafnirClient::encode).toList();
    Map<String, Object> response = post("/internal/secrets/decrypt", Map.of("values", encoded));
    List<Object> rawValues = Json.asArray(response.get("values"));
    return rawValues.stream().map(v -> decode((String) v)).toList();
  }

  public byte rotateKey() {
    Map<String, Object> response = post("/secrets/rotate-key", Map.of());
    return (byte) ((Number) response.get("activeKeyId")).intValue();
  }

  /**
   * A byte-for-byte proxy hop for the versioned {@code /secrets/*} surface -- {@code ApiServer}
   * doesn't need typed request/response handling here the way it does for the fixed internal
   * operations above, since it never inspects the body, only relays it ("pure network relay").
   * {@code headers} carries the calling principal's identity as an internal claim -- an {@code
   * X-Gimle-Forwarded-Principal}/{@code X-Gimle-Forwarded-Groups} pair, trusted by Fafnir only
   * because it arrives over this mTLS-authenticated connection, never treated by Fafnir as itself
   * proof of authorization.
   */
  public RawResponse forward(String method, String path, byte[] body, Map<String, String> headers) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(baseUri.resolve(path)).timeout(REQUEST_TIMEOUT);
      headers.forEach(builder::header);
      builder.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(body));
      HttpResponse<byte[]> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
      return new RawResponse(response.statusCode(), response.body());
    } catch (IOException e) {
      throw GimleSecretsException.fafnirUnavailable("fafnir request to " + path + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw GimleSecretsException.fafnirUnavailable(
          "fafnir request to " + path + " interrupted", e);
    }
  }

  /** A raw HTTP response relayed verbatim back to the original caller by {@code ApiServer}. */
  public record RawResponse(int statusCode, byte[] body) {}

  private Map<String, Object> post(String path, Object body) {
    try {
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(baseUri.resolve(path))
                  .timeout(REQUEST_TIMEOUT)
                  .POST(
                      HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        throw GimleSecretsException.fafnirUnavailable(
            "fafnir request to " + path + " failed with status " + response.statusCode());
      }
      return Json.asObject(Json.parse(response.body()));
    } catch (IOException e) {
      throw GimleSecretsException.fafnirUnavailable("fafnir request to " + path + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw GimleSecretsException.fafnirUnavailable(
          "fafnir request to " + path + " interrupted", e);
    }
  }

  private static String encode(byte[] value) {
    return Base64.getEncoder().encodeToString(value);
  }

  private static byte[] decode(String value) {
    return Base64.getDecoder().decode(value);
  }

  @Override
  public void close() {
    httpClient.close();
  }
}
