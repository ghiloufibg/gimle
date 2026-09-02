package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * The control-plane API's general per-address request limit: the backstop that bounds how fast any
 * one source may be served at all, on every route rather than only the CSR submission the earlier
 * buckets covered.
 *
 * <p>Configured through system properties read at construction, so each test sets them before
 * standing its own server up and restores them afterwards.
 */
@ResourceLock("gimle-controlplane-api-server-http")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ApiServerRateLimitTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private InProcessFafnir inProcessFafnir;
  private ApiServer server;
  private HttpClient client;
  private String baseUrl;
  private String previousEnabled;
  private String previousBurst;
  private String previousRefill;

  @BeforeEach
  void rememberProperties() {
    previousEnabled = System.getProperty(ApiServer.RATE_LIMIT_ENABLED_PROPERTY);
    previousBurst = System.getProperty(ApiServer.RATE_LIMIT_BURST_PROPERTY);
    previousRefill = System.getProperty(ApiServer.RATE_LIMIT_REFILL_MILLIS_PROPERTY);
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopEverything() {
    restore(ApiServer.RATE_LIMIT_ENABLED_PROPERTY, previousEnabled);
    restore(ApiServer.RATE_LIMIT_BURST_PROPERTY, previousBurst);
    restore(ApiServer.RATE_LIMIT_REFILL_MILLIS_PROPERTY, previousRefill);
    if (server != null) {
      server.close();
    }
    if (inProcessFafnir != null) {
      inProcessFafnir.close();
    }
    if (inProcessStore != null) {
      inProcessStore.close();
    }
  }

  private static void restore(String key, String previous) {
    if (previous == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previous);
    }
  }

  private void startServer() throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client());
    server.start();
    baseUrl = "http://localhost:" + server.port();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  void a_source_that_outruns_its_budget_is_refused_with_a_retry_after() throws Exception {
    // A burst of 3 with an hour between refills: the fourth request in a row cannot be served, and
    // no wall-clock timing makes the test flaky.
    System.setProperty(ApiServer.RATE_LIMIT_BURST_PROPERTY, "3");
    System.setProperty(ApiServer.RATE_LIMIT_REFILL_MILLIS_PROPERTY, "3600000");
    startServer();

    for (int i = 0; i < 3; i++) {
      assertEquals(200, get("/health").statusCode(), "request " + i + " should be within budget");
    }

    HttpResponse<String> refused = get("/health");
    assertEquals(429, refused.statusCode());
    String retryAfter = refused.headers().firstValue("Retry-After").orElse(null);
    assertNotNull(retryAfter, "a throttled response must say when to come back");
    assertTrue(Long.parseLong(retryAfter) > 0);
  }

  @Test
  void the_limit_covers_every_route_not_only_the_bootstrap_submission() throws Exception {
    // The whole point of hooking the choke point rather than individual handlers: spending the
    // budget on one route must refuse the next request on a different one.
    System.setProperty(ApiServer.RATE_LIMIT_BURST_PROPERTY, "2");
    System.setProperty(ApiServer.RATE_LIMIT_REFILL_MILLIS_PROPERTY, "3600000");
    startServer();

    assertEquals(200, get("/health").statusCode());
    get("/deployments");

    assertEquals(429, get("/nodes").statusCode());
  }

  @Test
  void a_refilled_bucket_serves_again() throws Exception {
    // Refill fast enough that the token is back well within the wait below, so this asserts the
    // bucket genuinely recovers rather than latching closed after one refusal.
    System.setProperty(ApiServer.RATE_LIMIT_BURST_PROPERTY, "1");
    System.setProperty(ApiServer.RATE_LIMIT_REFILL_MILLIS_PROPERTY, "50");
    startServer();

    assertEquals(200, get("/health").statusCode());
    assertEquals(429, get("/health").statusCode());

    Thread.sleep(250);

    assertEquals(200, get("/health").statusCode());
  }

  @Test
  void an_operator_may_turn_the_limit_off_entirely() throws Exception {
    System.setProperty(ApiServer.RATE_LIMIT_ENABLED_PROPERTY, "false");
    System.setProperty(ApiServer.RATE_LIMIT_BURST_PROPERTY, "1");
    System.setProperty(ApiServer.RATE_LIMIT_REFILL_MILLIS_PROPERTY, "3600000");
    startServer();

    for (int i = 0; i < 5; i++) {
      assertEquals(200, get("/health").statusCode(), "request " + i + " should not be throttled");
    }
  }

  @Test
  void the_default_budget_does_not_throttle_ordinary_traffic() throws Exception {
    // Guards the sizing decision itself: the default must sit far above what a console page load,
    // an operator's bulk apply, or an agent's own polling produces, or enabling it by default
    // would break the cluster it protects.
    startServer();

    for (int i = 0; i < 60; i++) {
      assertEquals(200, get("/health").statusCode(), "request " + i + " should be within budget");
    }
  }
}
