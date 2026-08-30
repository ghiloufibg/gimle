package com.gimle.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.module.lifecycle.SimpleModuleContext;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * FUNC-62 regression: {@code onStart} used to parse {@code gateway.routes} exactly once and bake it
 * into a fixed set of {@code HttpServer} contexts, with no listener, poll, or re-parse anywhere
 * afterward -- an operator's route-config update had zero effect on an already-running instance.
 * {@link GatewayHooks}'s package-private constructor lets these tests shrink the reload interval to
 * milliseconds; production always uses {@code GatewayHooks#DEFAULT_ROUTE_RELOAD_INTERVAL} via the
 * public no-arg constructor. {@code gateway.routes} is re-read from the exact same {@link
 * ConcurrentHashMap} {@link SimpleModuleContext#config} reads live from -- mutating it here is
 * exactly what {@code ConfigRelay}'s own delivery does to a real running instance.
 *
 * <p>{@code @ResourceLock(SYSTEM_PROPERTIES)} for the same reason {@code GatewayHooksTlsTest}
 * carries it: {@code createHttpServer} reads the process-global {@code gimle.transport.protocol}
 * system property, so a plaintext-only test like this one must still serialize against any test
 * that toggles it, or it can race a concurrently-running TLS test and bind an {@code HttpsServer}
 * it never asked for -- a plain {@link HttpClient} then gets exactly the corrupted-response
 * symptoms (an unparseable status line, a bare EOF) a TLS handshake looks like to it.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class GatewayHooksRouteReloadTest {

  private static final Duration RELOAD_INTERVAL = Duration.ofMillis(20);
  private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(2);

  private GatewayHooks hooks;

  @AfterEach
  void stopGateway() {
    if (hooks != null) {
      hooks.onStop(null);
    }
  }

  @Test
  void a_route_added_to_the_config_becomes_reachable_without_a_restart() throws Exception {
    ConcurrentHashMap<String, String> configValues = configWithRoutes(routeLine("/greet"));
    hooks = new GatewayHooks(RELOAD_INTERVAL);
    hooks.onStart(contextWithGreeterRoute(configValues));
    HttpClient client = HttpClient.newHttpClient();

    assertEquals(200, send(client, "/greet").statusCode());
    // The new path genuinely doesn't exist yet -- no HttpServer context is registered for it.
    assertEquals(404, send(client, "/greet2").statusCode());

    configValues.put("gateway.routes", routeLine("/greet") + routeLine("/greet2"));

    awaitStatus(client, "/greet2", 200);
    // The untouched path's already-registered context keeps working through the swap too.
    assertEquals(200, send(client, "/greet").statusCode());
  }

  @Test
  void a_route_removed_from_the_config_stops_being_reachable() throws Exception {
    ConcurrentHashMap<String, String> configValues =
        configWithRoutes(routeLine("/greet") + routeLine("/greet2"));
    hooks = new GatewayHooks(RELOAD_INTERVAL);
    hooks.onStart(contextWithGreeterRoute(configValues));
    HttpClient client = HttpClient.newHttpClient();
    assertEquals(200, send(client, "/greet2").statusCode());

    configValues.put("gateway.routes", routeLine("/greet"));

    awaitStatus(client, "/greet2", 404);
    assertEquals(200, send(client, "/greet").statusCode());
  }

  @Test
  void a_malformed_route_config_update_is_rejected_and_the_previous_table_keeps_serving()
      throws Exception {
    ConcurrentHashMap<String, String> configValues = configWithRoutes(routeLine("/greet"));
    hooks = new GatewayHooks(RELOAD_INTERVAL);
    hooks.onStart(contextWithGreeterRoute(configValues));
    HttpClient client = HttpClient.newHttpClient();
    assertEquals(200, send(client, "/greet").statusCode());

    configValues.put("gateway.routes", "NOT A VALID ROUTE LINE");
    // Give the reload task several ticks to (fail to) apply the bad config.
    Thread.sleep(RELOAD_INTERVAL.toMillis() * 10);

    assertEquals(200, send(client, "/greet").statusCode());
  }

  private static String routeLine(String path) {
    return "FABRIC " + path + " " + TestGreeter.class.getName() + " 1 greet STRING\n";
  }

  private static ConcurrentHashMap<String, String> configWithRoutes(String routesConfig) {
    return new ConcurrentHashMap<>(Map.of("gateway.port", "0", "gateway.routes", routesConfig));
  }

  private static SimpleModuleContext contextWithGreeterRoute(
      ConcurrentHashMap<String, String> configValues) {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    ModuleId gatewayId = new ModuleId("com.gimle.gateway", Version.parse("1.0.0"));
    registry.register(gatewayId, TestGreeter.class, name -> "hello, " + name);
    return new SimpleModuleContext(gatewayId, registry, configValues);
  }

  private HttpResponse<String> send(HttpClient client, String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + hooks.port() + path))
            .POST(HttpRequest.BodyPublishers.ofString("Freya"))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private void awaitStatus(HttpClient client, String path, int expectedStatus) throws Exception {
    long deadlineNanos = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
    int lastStatus = -1;
    while (System.nanoTime() < deadlineNanos) {
      lastStatus = send(client, path).statusCode();
      if (lastStatus == expectedStatus) {
        return;
      }
      Thread.sleep(10);
    }
    fail(
        "expected "
            + path
            + " to reach status "
            + expectedStatus
            + " within "
            + AWAIT_TIMEOUT
            + ", last saw "
            + lastStatus);
  }
}
