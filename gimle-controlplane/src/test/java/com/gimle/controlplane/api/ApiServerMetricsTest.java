package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * {@link ApiServer}'s per-endpoint request/error/latency metrics, mirroring {@code
 * FafnirObservabilityTest}'s own "real request against a real server, assert on the registry" shape
 * -- but reading the registry back through {@link ApiServer#metrics()} rather than constructor
 * injection, since {@code ApiServerMetrics} isn't a constructor parameter here (see that field's
 * own javadoc for why).
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerMetricsTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private InProcessFafnir inProcessFafnir;
  private ApiServer server;
  private HttpClient client;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client());
    server.start();
    baseUrl = "http://localhost:" + server.port();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopServer() {
    server.close();
    inProcessFafnir.close();
    inProcessStore.close();
  }

  @Test
  @Timeout(10)
  void a_real_request_is_recorded_in_apiserver_metrics() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    // Awaited, not asserted immediately: the client's send() completes the moment the response
    // body arrives, while the instrument wrapper records into the registry in a finally that runs
    // just *after* the handler wrote that body -- a genuine, timing-dependent gap.
    awaitMetric(() -> server.metrics().requestCount("deployments", "GET"), 1.0);
    assertEquals(0.0, server.metrics().errorCount("deployments", "GET"));
  }

  @Test
  @Timeout(10)
  void a_404_response_is_recorded_as_an_error() throws Exception {
    client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/does-not-exist")).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    // Same post-response recording gap as the request-count await above.
    awaitMetric(() -> server.metrics().errorCount("deployments", "GET"), 1.0);
  }

  private static void awaitMetric(DoubleSupplier metric, double expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      if (metric.getAsDouble() == expected) {
        return;
      }
      Thread.sleep(10);
    }
    assertEquals(expected, metric.getAsDouble());
  }
}
