package com.gimle.agent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The three rungs of a vessel's probe ladder, dialed by this agent from outside the process --
 * unlike {@code LivenessProbe}/{@code ReadinessProbe} (Java interfaces called in-JVM by a worker),
 * a vessel is an opaque OS process this node doesn't share a classloader with, so every check here
 * is a genuine network dial. "Process still running" (the always-available floor rung) is
 * deliberately not a method here -- the caller already holds the {@link Process} handle and can
 * check {@link Process#isAlive()} directly with no I/O at all.
 */
final class VesselProber {

  private static final Duration DIAL_TIMEOUT = Duration.ofSeconds(2);

  private VesselProber() {}

  /**
   * Opens, then immediately closes, a TCP connection -- true only if the connect itself succeeds.
   */
  static boolean tcp(String host, int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), (int) DIAL_TIMEOUT.toMillis());
      return true;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  /** True only for a 2xx response to a GET against {@code http://<host>:<port><path>}. */
  static boolean http(HttpClient httpClient, String host, int port, String path) {
    String normalizedPath = path.startsWith("/") ? path : "/" + path;
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + normalizedPath))
              .timeout(DIAL_TIMEOUT)
              .GET()
              .build();
      HttpResponse<Void> response =
          httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      return response.statusCode() >= 200 && response.statusCode() < 300;
    } catch (IOException | InterruptedException | RuntimeException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }
}
