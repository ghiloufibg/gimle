package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

/**
 * {@code /health} is what a load balancer and an operator both poll, so its one hard requirement is
 * that it always answers. A handler that dials the store inline inherits the store's own worst case
 * -- a leader search that runs for seconds -- and a caller polling it gets nothing back at all,
 * which is strictly less actionable than being told the store is down.
 */
class ApiServerHealthTest {

  @TempDir Path tempDir;

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private ApiServer server;
  private InProcessStore store;
  private InProcessFafnir fafnir;
  private String previousMaxAge;

  @AfterEach
  void tearDown() throws IOException {
    if (previousMaxAge == null) {
      System.clearProperty(ApiServer.STORE_PROBE_MAX_AGE_PROPERTY);
    } else {
      System.setProperty(ApiServer.STORE_PROBE_MAX_AGE_PROPERTY, previousMaxAge);
    }
    if (server != null) {
      server.close();
    }
    if (fafnir != null) {
      fafnir.close();
    }
    if (store != null) {
      store.close();
    }
  }

  @Test
  @Timeout(60)
  void health_answers_503_quickly_once_the_store_is_gone_instead_of_hanging() throws Exception {
    previousMaxAge = System.getProperty(ApiServer.STORE_PROBE_MAX_AGE_PROPERTY);
    System.setProperty(ApiServer.STORE_PROBE_MAX_AGE_PROPERTY, "1000");
    store = InProcessStore.start(tempDir.resolve("store"));
    fafnir = InProcessFafnir.start(store.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(store.client(), 0, fafnir.client());
    server.start();
    String baseUrl = "http://localhost:" + server.port();
    assertEquals(200, get(baseUrl).statusCode(), "a healthy cluster reports UP");

    store.close();
    store = null;

    // Every poll must come back, and each one within a bound a hanging handler could never meet.
    HttpResponse<String> response = null;
    Instant deadline = Instant.now().plusSeconds(45);
    while (Instant.now().isBefore(deadline)) {
      Instant startedAt = Instant.now();
      response = get(baseUrl);
      Duration took = Duration.between(startedAt, Instant.now());
      assertTrue(
          took.compareTo(Duration.ofSeconds(3)) < 0,
          "/health must answer promptly even with the store gone, took " + took);
      if (response.statusCode() == 503) {
        break;
      }
    }

    assertEquals(503, response.statusCode(), "an unreachable store must fail closed");
    assertTrue(response.body().contains("DOWN"), "the body should say what is wrong: " + response.body());
  }

  private HttpResponse<String> get(String baseUrl) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
