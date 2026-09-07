package com.gimle.hilmir.launch;

import com.gimle.core.tls.SslContexts;
import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.topology.ProcessRole;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.Transport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import javax.net.ssl.SSLContext;

/**
 * A real request against the one HTTP endpoint a process kind already exposes for exactly this
 * purpose ({@code /health} on the control plane, {@code /status} on Fafnir/Andvari/Muninn) --
 * unlike {@link ReadinessPoller}'s bare TCP connect, which only proves a listener is still bound,
 * this proves the process behind it is actually answering requests. A process wedged badly enough
 * to stop responding while still holding its port open passes the TCP check forever; this is the
 * signal that catches it. The store speaks its own binary Raft/RPC protocol on its readiness port,
 * and the agent's own readiness address is blank (see {@code LaunchPlanner}'s own javadoc for why)
 * -- neither has an HTTP endpoint to ask, so both are reported healthy unconditionally here,
 * leaving the plain TCP/process-table signal as the only one available for them.
 */
final class HealthProbe {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

  private HealthProbe() {}

  /**
   * {@code true} when {@code role} has no HTTP health endpoint to ask, or when a real request
   * against that endpoint completes with a successful status within {@link #REQUEST_TIMEOUT}. Any
   * failure -- connection refused, timeout, a non-2xx response, a TLS handshake that never
   * completes -- reports unhealthy: a process that cannot answer its own health check within a few
   * seconds is exactly the wedged case this exists to catch, not a transient blip to tolerate.
   */
  static boolean isHealthy(
      final Topology topology, final ProcessRole role, final String readinessAddress) {
    final Optional<String> path = healthPathFor(role);
    if (path.isEmpty() || readinessAddress.isBlank()) {
      return true;
    }
    final boolean tls = topology.transport() == Transport.MTLS;
    final HttpClient.Builder clientBuilder =
        HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT);
    try {
      if (tls) {
        clientBuilder.sslContext(serverTrustOnlyContext(topology));
      }
      final String uri = (tls ? "https://" : "http://") + readinessAddress + path.get();
      final HttpResponse<Void> response =
          clientBuilder
              .build()
              .send(
                  HttpRequest.newBuilder(URI.create(uri)).timeout(REQUEST_TIMEOUT).GET().build(),
                  HttpResponse.BodyHandlers.discarding());
      return response.statusCode() / 100 == 2;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (final IOException | RuntimeException e) {
      return false;
    }
  }

  private static Optional<String> healthPathFor(final ProcessRole role) {
    return switch (role) {
      case CONTROL_PLANE -> Optional.of("/health");
      case FAFNIR, ANDVARI, MUNINN -> Optional.of("/status");
      case STORE, AGENT, WORKER -> Optional.empty();
    };
  }

  private static SSLContext serverTrustOnlyContext(final Topology topology) {
    final Path caFile =
        topology
            .tls()
            .orElseThrow(
                () ->
                    new HilmirException(
                        "cannot health-check over mtls: topology has no tls.materialDir"))
            .materialDir()
            .resolve("ca.crt");
    return SslContexts.forServerTrustOnly(caFile);
  }
}
