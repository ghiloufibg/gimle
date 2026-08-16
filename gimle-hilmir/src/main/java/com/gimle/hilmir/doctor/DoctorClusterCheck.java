package com.gimle.hilmir.doctor;

import com.gimle.core.module.Version;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.hilmir.release.ControlPlaneApi;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import javax.net.ssl.SSLContext;

/**
 * {@code doctor --server host:port}'s cluster-aware checks: does the artifact's own registry
 * coordinate actually exist, and (if {@code --tenant} was given) does that tenant exist. Both go
 * through the control plane's own API address -- the same single {@code --server} flag the release
 * verbs already use -- rather than a second endpoint flag pointed directly at Andvari: the control
 * plane's existing {@code /artifacts/*} proxy (see {@code ApiServer.handleArtifactsProxy}) already
 * forwards a {@code HEAD /artifacts/{name}/{version}} straight through to Andvari with the same
 * {@code X-Gimle-Artifact-Sha256} response header {@code AndvariClient.head} reads, so this needs
 * no new server-side surface and doctor never needs to know Andvari's own address.
 *
 * <p>{@link RegistryOutcome} mirrors {@code AndvariClient.HeadOutcome}'s own three-way shape
 * exactly, reimplemented with a small local {@code HttpClient} HEAD call rather than a dependency
 * on {@code gimle-controlplane} for one class.
 */
public final class DoctorClusterCheck {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final String SHA256_HEADER = "X-Gimle-Artifact-Sha256";

  private final URI baseUri;
  private final HttpClient httpClient;
  private final ControlPlaneApi controlPlaneApi;

  public DoctorClusterCheck(String serverAddress) {
    Optional<SSLContext> sslContext = defaultSslContext();
    String scheme = sslContext.isPresent() ? "https" : "http";
    this.baseUri = URI.create(scheme + "://" + serverAddress);
    HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT);
    sslContext.ifPresent(builder::sslContext);
    this.httpClient = builder.build();
    this.controlPlaneApi = new ControlPlaneApi(serverAddress);
  }

  private static Optional<SSLContext> defaultSslContext() {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return Optional.empty();
    }
    return Optional.of(SslContexts.forMutualTls(TlsSettings.fromConfig()));
  }

  public sealed interface RegistryOutcome {
    record Found(String sha256) implements RegistryOutcome {}

    record NotFound() implements RegistryOutcome {}

    record Unreachable(String reason) implements RegistryOutcome {}
  }

  public RegistryOutcome checkRegistryCoordinate(String moduleName, Version version) {
    URI uri = baseUri.resolve("/artifacts/" + moduleName + "/" + version);
    try {
      HttpResponse<Void> response =
          httpClient.send(
              HttpRequest.newBuilder(uri)
                  .timeout(REQUEST_TIMEOUT)
                  .method("HEAD", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() == 404) {
        return new RegistryOutcome.NotFound();
      }
      if (response.statusCode() != 200) {
        return new RegistryOutcome.Unreachable("control plane answered " + response.statusCode());
      }
      return response
          .headers()
          .firstValue(SHA256_HEADER)
          .<RegistryOutcome>map(RegistryOutcome.Found::new)
          .orElseGet(
              () -> new RegistryOutcome.Unreachable("no " + SHA256_HEADER + " header returned"));
    } catch (IOException e) {
      return new RegistryOutcome.Unreachable(String.valueOf(e.getMessage()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new RegistryOutcome.Unreachable("interrupted");
    }
  }

  public boolean tenantExists(String tenantId) {
    return controlPlaneApi.exists("/tenants/" + tenantId);
  }
}
