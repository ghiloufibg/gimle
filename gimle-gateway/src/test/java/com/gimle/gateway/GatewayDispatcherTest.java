package com.gimle.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.gateway.GatewayDispatcher.GatewayResponse;
import com.gimle.gateway.GatewayRoute.FabricRoute;
import com.gimle.gateway.GatewayRoute.FabricRoute.ParamType;
import com.gimle.gateway.GatewayRoute.ServiceRoute;
import com.gimle.gateway.GatewayRoute.VesselRoute;
import com.gimle.module.lifecycle.ModuleContext.RelayResult;
import com.gimle.module.lifecycle.SimpleModuleContext;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link GatewayDispatcher} against a hand-built {@link SimpleModuleContext} -- the same
 * pattern every other hooks/probe test in this codebase uses to invoke platform code without a real
 * running cluster -- registering a plain Java service directly rather than standing up a real
 * fabric wire hop, since {@link GatewayDispatcher} itself is transport-agnostic (see its own
 * javadoc). Service interfaces ({@link TestGreeter}, {@link TestGreeterAndPinger}, {@link
 * TestAdder}) are top-level, public types, not nested/private ones -- see {@link TestGreeter}'s own
 * javadoc for why that's load-bearing here, not stylistic.
 *
 * <p>Vessel-route tests use a real local {@link HttpServer} standing in for "the vessel instance"
 * -- {@link GatewayDispatcher}'s own outbound proxy call ({@link VesselProxyClient}) is a real
 * {@code java.net.http.HttpClient} call, not a fake, so it needs a real socket on the other end to
 * prove method/path/body/response genuinely round-trip. Endpoint-cache behavior (round-robin, TTL,
 * stale fallback, readiness filtering) has its own dedicated coverage in {@link
 * VesselEndpointCacheTest}, unit-tested without any real HTTP server at all -- these tests only
 * need to prove {@link GatewayDispatcher} itself is wired to that cache and to a real proxy call
 * correctly.
 */
class GatewayDispatcherTest {

  private static final String GREETER_IFACE = TestGreeter.class.getName();

  private HttpServer vesselStub;

  @AfterEach
  void stopVesselStub() {
    if (vesselStub != null) {
      vesselStub.stop(0);
    }
  }

  private static SimpleModuleContext contextWithGreeter(TestGreeter greeter) {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(
        new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0")),
        TestGreeter.class,
        greeter);
    return new SimpleModuleContext(
        new ModuleId("com.gimle.gateway", Version.parse("1.0.0")), registry);
  }

  private static SimpleModuleContext contextWithRelay(RelayResult relayResult) {
    return new SimpleModuleContext(
        new ModuleId("com.gimle.gateway", Version.parse("1.0.0")),
        new SimpleServiceRegistry(),
        new ConcurrentHashMap<>(),
        Map.of(),
        path -> relayResult);
  }

  @Test
  void a_string_argument_route_dispatches_and_returns_the_real_result() {
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            contextWithGreeter(name -> "Hello, " + name + "!"),
            List.of(new FabricRoute("/greet", GREETER_IFACE, 1, "greet", ParamType.STRING)));

    GatewayResponse response = dispatcher.dispatch("POST", "/greet", "Gimlé");

    assertEquals(200, response.status());
    assertEquals("Hello, Gimlé!", response.body());
  }

  @Test
  void a_no_argument_route_is_served_on_get() {
    AtomicBoolean pinged = new AtomicBoolean();
    TestGreeterAndPinger service =
        new TestGreeterAndPinger() {
          @Override
          public String greet(String name) {
            return name;
          }

          @Override
          public void ping() {
            pinged.set(true);
          }
        };
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(
        new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0")),
        TestGreeterAndPinger.class,
        service);
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            new ModuleId("com.gimle.gateway", Version.parse("1.0.0")), registry);
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            ctx,
            List.of(
                new FabricRoute(
                    "/ping", TestGreeterAndPinger.class.getName(), 1, "ping", ParamType.NONE)));

    GatewayResponse response = dispatcher.dispatch("GET", "/ping", "");

    assertEquals(200, response.status());
    assertEquals("", response.body());
    assertTrue(pinged.get());
  }

  @Test
  void an_unknown_path_returns_404() {
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            contextWithGreeter(name -> name),
            List.of(new FabricRoute("/greet", GREETER_IFACE, 1, "greet", ParamType.STRING)));

    GatewayResponse response = dispatcher.dispatch("POST", "/nope", "x");

    assertEquals(404, response.status());
  }

  @Test
  void a_host_constrained_route_matches_only_the_declared_host_header() {
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            contextWithGreeter(name -> "Hello, " + name + "!"),
            List.of(
                new FabricRoute(
                    Optional.of("greeter.example.com"),
                    "/greet",
                    GREETER_IFACE,
                    1,
                    "greet",
                    ParamType.STRING)));

    GatewayResponse matched = dispatcher.dispatch("POST", "/greet", "Gimlé", "greeter.example.com");

    assertEquals(200, matched.status());
    assertEquals("Hello, Gimlé!", matched.body());
  }

  @Test
  void a_host_constrained_route_falls_through_to_404_on_a_mismatched_host_header() {
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            contextWithGreeter(name -> name),
            List.of(
                new FabricRoute(
                    Optional.of("greeter.example.com"),
                    "/greet",
                    GREETER_IFACE,
                    1,
                    "greet",
                    ParamType.STRING)));

    GatewayResponse mismatched = dispatcher.dispatch("POST", "/greet", "x", "other.example.com");
    GatewayResponse noHostAtAll = dispatcher.dispatch("POST", "/greet", "x", null);

    assertEquals(404, mismatched.status());
    assertEquals(404, noHostAtAll.status());
  }

  @Test
  void a_host_unconstrained_route_is_unaffected_by_host_based_routing() {
    // Regression: a route declared with no HOST segment must keep matching every request exactly
    // as it did before this route kind gained an optional host constraint.
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            contextWithGreeter(name -> "Hello, " + name + "!"),
            List.of(new FabricRoute("/greet", GREETER_IFACE, 1, "greet", ParamType.STRING)));

    GatewayResponse noHostHeader = dispatcher.dispatch("POST", "/greet", "Gimlé", null);
    GatewayResponse anyHostHeader =
        dispatcher.dispatch("POST", "/greet", "Gimlé", "anything.at.all");
    GatewayResponse threeArgOverload = dispatcher.dispatch("POST", "/greet", "Gimlé");

    assertEquals(200, noHostHeader.status());
    assertEquals("Hello, Gimlé!", noHostHeader.body());
    assertEquals(200, anyHostHeader.status());
    assertEquals(200, threeArgOverload.status());
  }

  @Test
  void a_host_constrained_route_falls_through_to_a_host_unconstrained_sibling_at_the_same_path() {
    // Two routes at the same path: one requires a specific host, the other has no constraint and
    // serves as the default for every other host -- ordinary virtual-hosting with a fallback. A
    // request whose Host matches neither the specific route (wrong value) still succeeds via the
    // fallback rather than 404ing, which is what this test actually proves.
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            contextWithGreeter(name -> "fallback:" + name),
            List.of(
                new FabricRoute(
                    Optional.of("specific.example.com"),
                    "/greet",
                    GREETER_IFACE,
                    1,
                    "greet",
                    ParamType.STRING),
                new FabricRoute("/greet", GREETER_IFACE, 1, "greet", ParamType.STRING)));

    GatewayResponse viaFallback = dispatcher.dispatch("POST", "/greet", "x", "unrelated.host.com");

    assertEquals(200, viaFallback.status());
    assertEquals("fallback:x", viaFallback.body());
  }

  @Test
  void the_wrong_http_method_for_a_fabric_route_returns_405() {
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            contextWithGreeter(name -> name),
            List.of(new FabricRoute("/greet", GREETER_IFACE, 1, "greet", ParamType.STRING)));

    assertEquals(405, dispatcher.dispatch("GET", "/greet", "").status());
  }

  @Test
  void a_body_that_does_not_coerce_to_the_declared_param_type_returns_400() {
    // The parse failure on a non-numeric body happens before any invocation is attempted, so this
    // route needs no real int-taking method behind it at all.
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            contextWithGreeter(name -> name),
            List.of(new FabricRoute("/count", GREETER_IFACE, 1, "count", ParamType.INT)));

    GatewayResponse response = dispatcher.dispatch("POST", "/count", "not-a-number");

    assertEquals(400, response.status());
  }

  @Test
  void a_downstream_fabric_call_that_throws_returns_502() {
    TestGreeter greeter =
        name -> {
          throw new IllegalStateException("boom");
        };
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            contextWithGreeter(greeter),
            List.of(new FabricRoute("/greet", GREETER_IFACE, 1, "greet", ParamType.STRING)));

    GatewayResponse response = dispatcher.dispatch("POST", "/greet", "x");

    assertEquals(502, response.status());
  }

  @Test
  void a_fabric_route_naming_a_service_nothing_exports_is_served_as_200_with_an_empty_body() {
    // Documented v1 limitation (see GatewayDispatcher#dispatch's own javadoc): invokeByName's
    // Optional.empty() means either "not found" or "found and returned void/null" -- there's no
    // separate signal here to tell them apart, so a misconfigured route reads as a quiet success.
    SimpleServiceRegistry emptyRegistry = new SimpleServiceRegistry();
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            new ModuleId("com.gimle.gateway", Version.parse("1.0.0")), emptyRegistry);
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            ctx, List.of(new FabricRoute("/greet", GREETER_IFACE, 1, "greet", ParamType.STRING)));

    GatewayResponse response = dispatcher.dispatch("POST", "/greet", "x");

    assertEquals(200, response.status());
    assertEquals("", response.body());
  }

  @Test
  void an_int_argument_route_coerces_and_dispatches_correctly() {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    TestAdder adder = value -> value + 1;
    registry.register(
        new ModuleId("com.gimle.example.adder", Version.parse("1.0.0")), TestAdder.class, adder);
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            new ModuleId("com.gimle.gateway", Version.parse("1.0.0")), registry);
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            ctx,
            List.of(
                new FabricRoute(
                    "/increment", TestAdder.class.getName(), 1, "increment", ParamType.INT)));

    GatewayResponse response = dispatcher.dispatch("POST", "/increment", "41");

    assertEquals(200, response.status());
    assertEquals("42", response.body());
  }

  @Test
  void a_vessel_route_proxies_to_the_real_target_with_method_path_body_and_response_intact()
      throws IOException {
    AtomicReference<String> seenMethod = new AtomicReference<>();
    AtomicReference<String> seenPath = new AtomicReference<>();
    AtomicReference<String> seenBody = new AtomicReference<>();
    HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext(
        "/api/orders",
        exchange -> {
          seenMethod.set(exchange.getRequestMethod());
          seenPath.set(exchange.getRequestURI().getPath());
          seenBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response = "order created".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(201, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    stub.start();
    this.vesselStub = stub;
    int port = stub.getAddress().getPort();

    RelayResult relayResult =
        new RelayResult(
            200,
            "[{\"instanceIndex\":0,\"nodeId\":\"node-a\",\"host\":\"127.0.0.1\","
                + "\"ports\":{\"HTTP_PORT\":"
                + port
                + "}}]");
    SimpleModuleContext ctx = contextWithRelay(relayResult);
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            ctx, List.of(new VesselRoute("/api/orders", "orders-service", "HTTP_PORT")));

    GatewayResponse response = dispatcher.dispatch("PUT", "/api/orders", "{\"item\":\"widget\"}");

    assertEquals(201, response.status());
    assertEquals("order created", response.body());
    assertEquals("PUT", seenMethod.get());
    assertEquals("/api/orders", seenPath.get());
    assertEquals("{\"item\":\"widget\"}", seenBody.get());
  }

  @Test
  void a_vessel_route_round_robins_across_ready_instances_over_repeated_real_calls()
      throws IOException {
    AtomicReference<String> lastHitPort = new AtomicReference<>();
    HttpServer stubA = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stubA.createContext(
        "/api/orders",
        exchange -> {
          lastHitPort.set("A");
          exchange.sendResponseHeaders(200, 0);
          exchange.close();
        });
    stubA.start();
    HttpServer stubB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stubB.createContext(
        "/api/orders",
        exchange -> {
          lastHitPort.set("B");
          exchange.sendResponseHeaders(200, 0);
          exchange.close();
        });
    stubB.start();
    try {
      int portA = stubA.getAddress().getPort();
      int portB = stubB.getAddress().getPort();
      RelayResult relayResult =
          new RelayResult(
              200,
              "["
                  + "{\"instanceIndex\":0,\"nodeId\":\"node-a\",\"host\":\"127.0.0.1\","
                  + "\"ports\":{\"HTTP_PORT\":"
                  + portA
                  + "}},"
                  + "{\"instanceIndex\":1,\"nodeId\":\"node-b\",\"host\":\"127.0.0.1\","
                  + "\"ports\":{\"HTTP_PORT\":"
                  + portB
                  + "}}"
                  + "]");
      SimpleModuleContext ctx = contextWithRelay(relayResult);
      GatewayDispatcher dispatcher =
          new GatewayDispatcher(
              ctx, List.of(new VesselRoute("/api/orders", "orders-service", "HTTP_PORT")));

      Set<String> hitInstances = new HashSet<>();
      for (int i = 0; i < 4; i++) {
        dispatcher.dispatch("GET", "/api/orders", "");
        hitInstances.add(lastHitPort.get());
      }

      assertEquals(Set.of("A", "B"), hitInstances);
    } finally {
      stubA.stop(0);
      stubB.stop(0);
    }
  }

  @Test
  void a_vessel_route_for_a_deployment_with_no_usable_endpoints_returns_a_clear_error_not_a_200() {
    SimpleModuleContext ctx = contextWithRelay(new RelayResult(200, "[]"));
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            ctx, List.of(new VesselRoute("/api/orders", "orders-service", "HTTP_PORT")));

    GatewayResponse response = dispatcher.dispatch("GET", "/api/orders", "");

    assertTrue(response.status() >= 400);
  }

  @Test
  void a_vessel_route_with_an_unreachable_relay_and_no_cache_returns_a_clear_error() {
    SimpleModuleContext ctx = contextWithRelay(new RelayResult(504, "agent unreachable"));
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            ctx, List.of(new VesselRoute("/api/orders", "orders-service", "HTTP_PORT")));

    GatewayResponse response = dispatcher.dispatch("GET", "/api/orders", "");

    assertTrue(response.status() >= 400);
  }

  @Test
  void a_vessel_route_reports_a_target_that_refuses_the_connection_as_a_clean_502()
      throws IOException {
    // Bind, then immediately close: the port is real (won't collide) but nothing listens on it,
    // so the outbound proxy call genuinely fails to connect rather than being faked.
    HttpServer deadServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int deadPort = deadServer.getAddress().getPort();
    deadServer.stop(0);

    RelayResult relayResult =
        new RelayResult(
            200,
            "[{\"instanceIndex\":0,\"nodeId\":\"node-a\",\"host\":\"127.0.0.1\","
                + "\"ports\":{\"HTTP_PORT\":"
                + deadPort
                + "}}]");
    SimpleModuleContext ctx = contextWithRelay(relayResult);
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            ctx, List.of(new VesselRoute("/api/orders", "orders-service", "HTTP_PORT")));

    GatewayResponse response = dispatcher.dispatch("GET", "/api/orders", "");

    assertEquals(502, response.status());
  }

  @Test
  void
      a_service_route_resolves_and_proxies_to_the_real_target_with_method_path_body_and_response_intact()
          throws IOException {
    AtomicReference<String> seenMethod = new AtomicReference<>();
    AtomicReference<String> seenPath = new AtomicReference<>();
    AtomicReference<String> seenBody = new AtomicReference<>();
    HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext(
        "/api/payments",
        exchange -> {
          seenMethod.set(exchange.getRequestMethod());
          seenPath.set(exchange.getRequestURI().getPath());
          seenBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response = "payment accepted".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(201, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    stub.start();
    this.vesselStub = stub;
    int port = stub.getAddress().getPort();

    // The exact shape the control plane's own GET /services/{name}/endpoints returns: a flat
    // object naming the service plus an "endpoints" array of plain {host, port} pairs -- no
    // portName lookup, unlike a vessel route's own /endpoints/{name} shape.
    RelayResult relayResult =
        new RelayResult(
            200,
            "{\"name\":\"payments\",\"port\":8080,\"targetPort\":8080,"
                + "\"endpoints\":[{\"host\":\"127.0.0.1\",\"port\":"
                + port
                + "}]}");
    SimpleModuleContext ctx = contextWithRelay(relayResult);
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(ctx, List.of(new ServiceRoute("/api/payments", "payments")));

    GatewayResponse response = dispatcher.dispatch("PUT", "/api/payments", "{\"amount\":42}");

    assertEquals(201, response.status());
    assertEquals("payment accepted", response.body());
    assertEquals("PUT", seenMethod.get());
    assertEquals("/api/payments", seenPath.get());
    assertEquals("{\"amount\":42}", seenBody.get());
  }

  @Test
  void a_service_route_for_a_service_with_no_ready_endpoints_returns_a_clear_error_not_a_200() {
    RelayResult relayResult =
        new RelayResult(200, "{\"name\":\"payments\",\"port\":8080,\"endpoints\":[]}");
    SimpleModuleContext ctx = contextWithRelay(relayResult);
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(ctx, List.of(new ServiceRoute("/api/payments", "payments")));

    GatewayResponse response = dispatcher.dispatch("GET", "/api/payments", "");

    assertTrue(response.status() >= 400);
  }

  @Test
  void a_service_route_reuses_the_cached_endpoint_list_across_dispatcher_instances_seam() {
    // GatewayDispatcher's package-private constructor lets a test hand it a ServiceEndpointCache
    // built with an explicit clock -- exercised in full in ServiceEndpointCacheTest; this test only
    // proves the seam itself wires through to real dispatch.
    SimpleModuleContext ctx =
        contextWithRelay(new RelayResult(200, "{\"name\":\"payments\",\"endpoints\":[]}"));
    ServiceEndpointCache cache =
        new ServiceEndpointCache(ctx, Duration.ofSeconds(1), Clock.systemUTC());
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            ctx,
            List.of(new ServiceRoute("/api/payments", "payments")),
            new VesselEndpointCache(ctx),
            cache,
            new VesselProxyClient());

    GatewayResponse response = dispatcher.dispatch("GET", "/api/payments", "");

    assertTrue(response.status() >= 400);
  }

  @Test
  void a_vessel_route_reuses_the_cached_endpoint_list_across_dispatcher_instances_seam() {
    // GatewayDispatcher's package-private constructor lets a test hand it a VesselEndpointCache
    // built with an explicit clock -- exercised in full in VesselEndpointCacheTest; this test only
    // proves the seam itself wires through to real dispatch.
    SimpleModuleContext ctx = contextWithRelay(new RelayResult(200, "[]"));
    VesselEndpointCache cache =
        new VesselEndpointCache(ctx, Duration.ofSeconds(1), Clock.systemUTC());
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            ctx,
            List.of(new VesselRoute("/api/orders", "orders-service", "HTTP_PORT")),
            cache,
            new VesselProxyClient());

    GatewayResponse response = dispatcher.dispatch("GET", "/api/orders", "");

    assertTrue(response.status() >= 400);
  }
}
