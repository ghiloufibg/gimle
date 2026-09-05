package com.gimle.ivaldi.cluster;

import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLContext;

/**
 * Asks a cluster's own control plane whether it is answering.
 *
 * <p>Probed here rather than in the browser because a control plane sends no CORS headers, so the
 * console cannot reach an arbitrary one itself. The answer is deliberately about the configured
 * control plane and nothing else: reporting on Ivaldi's own health instead told every operator
 * their cluster was reachable, including the ones that were not.
 */
public final class ClusterHealth {

  private static final Duration TIMEOUT = Duration.ofSeconds(3);

  private ClusterHealth() {}

  public static Map<String, Object> probe(String clusterJson) {
    Map<String, Object> cluster = Json.asObject(Json.parse(clusterJson));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("checkedAt", Instant.now().toString());
    String address =
        stripScheme(String.valueOf(cluster.getOrDefault("controlPlaneUrl", "")).trim());
    if (address.isBlank()) {
      return unreachable(result, "this cluster has no control plane URL");
    }
    result.put("address", address);

    Optional<SSLContext> sslContext;
    try {
      sslContext = identityOf(cluster);
    } catch (RuntimeException badMaterial) {
      return unreachable(result, badMaterial.getMessage());
    }
    String scheme = sslContext.isPresent() ? "https" : "http";
    HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(TIMEOUT);
    sslContext.ifPresent(builder::sslContext);

    try (HttpClient http = builder.build()) {
      HttpResponse<Void> response =
          http.send(
              HttpRequest.newBuilder(URI.create(scheme + "://" + address + "/healthz"))
                  .timeout(TIMEOUT)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() / 100 != 2) {
        return unreachable(result, "control plane answered HTTP " + response.statusCode());
      }
      result.put("ok", true);
      result.put("message", null);
      return result;
    } catch (IOException | IllegalArgumentException e) {
      return unreachable(result, messageOf(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unreachable(result, "interrupted while probing the control plane");
    }
  }

  /**
   * A cluster carrying its own client certificate is one that speaks mTLS, so the probe has to as
   * well -- a plaintext request at a TLS listener fails in a way that says nothing useful.
   */
  private static Optional<SSLContext> identityOf(Map<String, Object> cluster) {
    Optional<Path> cert = path(cluster, "clientCertPath");
    Optional<Path> key = path(cluster, "clientKeyPath");
    if (cert.isEmpty() || key.isEmpty()) {
      return Optional.empty();
    }
    Path ca =
        path(cluster, "caPath")
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "this cluster has a client certificate but no 'caPath' to verify the"
                            + " control plane against"));
    for (Path file : java.util.List.of(cert.get(), key.get(), ca)) {
      if (!Files.isRegularFile(file)) {
        throw new IllegalArgumentException("no TLS material at " + file);
      }
    }
    return Optional.of(SslContexts.forMutualTls(new TlsSettings(cert.get(), key.get(), ca)));
  }

  private static Optional<Path> path(Map<String, Object> cluster, String field) {
    Object raw = cluster.get(field);
    String value = raw == null ? "" : String.valueOf(raw).trim();
    return value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
  }

  private static Map<String, Object> unreachable(Map<String, Object> result, String message) {
    result.put("ok", false);
    result.put("message", message);
    return result;
  }

  private static String messageOf(Exception e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  private static String stripScheme(String url) {
    int marker = url.indexOf("://");
    String address = marker < 0 ? url : url.substring(marker + 3);
    return address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
  }
}
