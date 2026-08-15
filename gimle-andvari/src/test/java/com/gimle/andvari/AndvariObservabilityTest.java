package com.gimle.andvari;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.andvari.testsupport.InProcessStore;
import com.gimle.observability.AndvariMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Micrometer request metrics for {@code gimle-andvari}, wired via {@link AndvariServer#instrument}
 * at context-registration time -- mirrors {@code FafnirObservabilityTest}'s own metrics-recording
 * assertions, since the two servers share the identical instrumentation shape. Assertions poll
 * rather than read the metric immediately after the client's request completes: {@code
 * instrument}'s own recording happens in a {@code finally} block that runs on the server's
 * request-handling thread *after* the delegate handler has already closed the exchange, so the
 * client can observe a fully-flushed response a few nanoseconds before that thread finishes
 * recording it -- the same class of race {@code AndvariServerTest}'s own corrupted-GET-quarantine
 * test already works around.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-andvari-server-http")
class AndvariObservabilityTest {

  @TempDir Path tempDir;

  @Test
  @Timeout(10)
  void a_real_request_is_recorded_in_andvari_metrics() throws Exception {
    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      AndvariMetrics metrics = new AndvariMetrics(registry);
      try (AndvariServer server =
          new AndvariServer(store.client(), 0, tempDir.resolve("data"), metrics)) {
        server.start();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/status"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
        awaitEquals(1.0, () -> metrics.requestCount("status", "GET"));
        awaitEquals(0.0, () -> metrics.errorCount("status", "GET"));
      }
    }
  }

  @Test
  @Timeout(10)
  void a_404_response_is_recorded_as_an_error() throws Exception {
    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      AndvariMetrics metrics = new AndvariMetrics(registry);
      try (AndvariServer server =
          new AndvariServer(store.client(), 0, tempDir.resolve("data"), metrics)) {
        server.start();
        HttpClient client = HttpClient.newHttpClient();

        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" + server.port() + "/artifacts/com.example.ghost"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        awaitEquals(1.0, () -> metrics.errorCount("artifacts", "GET"));
      }
    }
  }

  @Test
  @Timeout(10)
  void every_registered_route_is_independently_tagged() throws Exception {
    try (InProcessStore store = InProcessStore.start(tempDir.resolve("store"))) {
      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      AndvariMetrics metrics = new AndvariMetrics(registry);
      try (AndvariServer server =
          new AndvariServer(store.client(), 0, tempDir.resolve("data"), metrics)) {
        server.start();
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = "http://127.0.0.1:" + server.port();

        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/status")).GET().build(),
            HttpResponse.BodyHandlers.discarding());
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/artifacts")).GET().build(),
            HttpResponse.BodyHandlers.discarding());

        // A hit on one route's counter must never leak into a different route's own tag set.
        awaitEquals(1.0, () -> metrics.requestCount("status", "GET"));
        awaitEquals(1.0, () -> metrics.requestCount("artifacts", "GET"));
        awaitEquals(0.0, () -> metrics.errorCount("status", "GET"));
      }
    }
  }

  /**
   * Polls {@code actual} until it equals {@code expected} or the enclosing {@code @Timeout} fires
   * -- see this class's own javadoc for why an immediate read right after the client's request
   * completes can observe a metric that hasn't been recorded yet.
   */
  private static void awaitEquals(double expected, DoubleSupplier actual)
      throws InterruptedException {
    while (actual.getAsDouble() != expected) {
      Thread.sleep(20);
    }
  }
}
