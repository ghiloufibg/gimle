package com.gimle.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.core.module.Version;
import com.gimle.module.lifecycle.ControlPlaneRelayClient;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.SimpleModuleContext;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * A gateway's route table must follow the Ingresses declared for its tenant while it runs, not be
 * baked in once at {@code onStart} -- an operator's route change had no effect at all on an
 * already-running instance, which for a DaemonSet-deployed fleet of independently-restarting
 * gateways meant the fleet disagreed about what it served. {@link GatewayHooks}'s package-private
 * constructor lets these tests shrink the reload interval to milliseconds; production always uses
 * {@code GatewayHooks#DEFAULT_ROUTE_RELOAD_INTERVAL} via the public no-arg constructor.
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
  private HttpServer controlPlane;

  /** What the stub control plane currently declares, swapped mid-test to drive a reload. */
  private final AtomicReference<String> declaredPaths = new AtomicReference<>("");

  /** Which tenant the stub control plane files its Ingress under. */
  private final AtomicReference<String> declaredTenant = new AtomicReference<>("default");

  @AfterEach
  void stopEverything() {
    if (hooks != null) {
      hooks.onStop(null);
    }
    if (controlPlane != null) {
      controlPlane.stop(0);
    }
  }

  @Test
  void a_route_added_to_the_ingress_becomes_reachable_without_a_restart() throws Exception {
    declaredPaths.set("/greet");
    hooks = new GatewayHooks(RELOAD_INTERVAL);
    hooks.onStart(contextPointingAtStubControlPlane());
    HttpClient client = HttpClient.newHttpClient();

    awaitStatus(client, "/greet", 200);
    // The new path genuinely doesn't exist yet -- no HttpServer context is registered for it.
    assertEquals(404, send(client, "/greet2").statusCode());

    declaredPaths.set("/greet,/greet2");

    awaitStatus(client, "/greet2", 200);
    // The untouched path's already-registered context keeps working through the swap too.
    assertEquals(200, send(client, "/greet").statusCode());
  }

  @Test
  void a_route_removed_from_the_ingress_stops_being_reachable() throws Exception {
    declaredPaths.set("/greet,/greet2");
    hooks = new GatewayHooks(RELOAD_INTERVAL);
    hooks.onStart(contextPointingAtStubControlPlane());
    HttpClient client = HttpClient.newHttpClient();
    awaitStatus(client, "/greet2", 200);

    declaredPaths.set("/greet");

    awaitStatus(client, "/greet2", 404);
    assertEquals(200, send(client, "/greet").statusCode());
  }

  /**
   * An unreachable control plane is not a reason to stop serving: the routes already applied stay
   * in place until it answers again, rather than the fleet emptying its route table the moment the
   * control plane restarts.
   */
  @Test
  void an_unreachable_control_plane_leaves_the_previous_table_serving() throws Exception {
    declaredPaths.set("/greet");
    hooks = new GatewayHooks(RELOAD_INTERVAL);
    hooks.onStart(contextPointingAtStubControlPlane());
    HttpClient client = HttpClient.newHttpClient();
    awaitStatus(client, "/greet", 200);

    controlPlane.stop(0);
    controlPlane = null;
    Thread.sleep(RELOAD_INTERVAL.toMillis() * 10);

    assertEquals(200, send(client, "/greet").statusCode());
  }

  /**
   * A gateway deployed into a tenant serves that tenant's declared routes. Defaulting to the
   * cluster's default tenant instead left such an instance filtering every Ingress away and serving
   * nothing, while listening happily and reporting itself healthy.
   */
  @Test
  void a_gateway_follows_its_own_tenants_ingresses_without_being_told_which_tenant_it_is_in()
      throws Exception {
    declaredTenant.set("gimle-system");
    declaredPaths.set("/greet");
    hooks = new GatewayHooks(RELOAD_INTERVAL);
    hooks.onStart(contextForInstanceInTenant("gimle-system"));

    awaitStatus(HttpClient.newHttpClient(), "/greet", 200);
  }

  @Test
  void an_explicit_gateway_tenant_id_still_wins_over_the_instances_own_tenant() throws Exception {
    declaredTenant.set("other-tenant");
    declaredPaths.set("/greet");
    hooks = new GatewayHooks(RELOAD_INTERVAL);
    hooks.onStart(contextForInstanceInTenant("gimle-system", "gateway.tenantId", "other-tenant"));

    awaitStatus(HttpClient.newHttpClient(), "/greet", 200);
  }

  /** Like {@link #contextPointingAtStubControlPlane}, but the instance reports a real tenant. */
  private SimpleModuleContext contextForInstanceInTenant(String tenantId, String... extraConfig)
      throws IOException {
    return contextPointingAtStubControlPlane(Optional.of(tenantId), extraConfig);
  }

  private SimpleModuleContext contextPointingAtStubControlPlane() throws IOException {
    return contextPointingAtStubControlPlane(Optional.empty());
  }

  private SimpleModuleContext contextPointingAtStubControlPlane(
      Optional<String> instanceTenantId, String... extraConfig) throws IOException {
    controlPlane = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    controlPlane.createContext(
        "/ingresses",
        exchange -> {
          byte[] body = ingressesJson().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    controlPlane.start();

    ConcurrentHashMap<String, String> configValues =
        new ConcurrentHashMap<>(
            Map.of(
                "gateway.port",
                "0",
                "gateway.controlPlaneEndpoint",
                "127.0.0.1:" + controlPlane.getAddress().getPort()));
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    ModuleInstanceId gatewayId =
        ModuleInstanceId.unattached(new ModuleId("com.gimle.gateway", Version.parse("1.0.0")));
    registry.register(gatewayId, TestGreeter.class, name -> "hello, " + name);
    for (int i = 0; i + 1 < extraConfig.length; i += 2) {
      configValues.put(extraConfig[i], extraConfig[i + 1]);
    }
    return new SimpleModuleContext(
        gatewayId,
        registry,
        configValues,
        Map.of(),
        ControlPlaneRelayClient.unavailable(),
        () ->
            instanceTenantId.map(
                tenant ->
                    new ModuleContext.InstanceInfo(
                        "gimle-gateway", 0, "test-node", instanceTenantId)));
  }

  private String ingressesJson() {
    String paths = declaredPaths.get();
    String routes =
        paths.isEmpty()
            ? ""
            : Stream.of(paths.split(","))
                .map(
                    path ->
                        "{\"kind\":\"FABRIC\",\"path\":\""
                            + path
                            + "\",\"prefix\":false,\"interfaceName\":\""
                            + TestGreeter.class.getName()
                            + "\",\"majorVersion\":1,\"methodName\":\"greet\","
                            + "\"paramType\":\"STRING\"}")
                .collect(Collectors.joining(","));
    return "[{\"name\":\"greeter\",\"tenantId\":\""
        + declaredTenant.get()
        + "\",\"routes\":["
        + routes
        + "]}]";
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
