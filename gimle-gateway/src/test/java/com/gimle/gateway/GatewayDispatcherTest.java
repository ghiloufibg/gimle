package com.gimle.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.gateway.GatewayDispatcher.GatewayResponse;
import com.gimle.gateway.GatewayRoute.ParamType;
import com.gimle.module.lifecycle.SimpleModuleContext;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link GatewayDispatcher} against a hand-built {@link SimpleModuleContext} -- the same
 * pattern every other hooks/probe test in this codebase uses to invoke platform code without a real
 * running cluster -- registering a plain Java service directly rather than standing up a real
 * fabric wire hop, since {@link GatewayDispatcher} itself is transport-agnostic (see its own
 * javadoc). Service interfaces ({@link TestGreeter}, {@link TestGreeterAndPinger}, {@link
 * TestAdder}) are top-level, public types, not nested/private ones -- see {@link TestGreeter}'s own
 * javadoc for why that's load-bearing here, not stylistic.
 */
class GatewayDispatcherTest {

  private static final String GREETER_IFACE = TestGreeter.class.getName();

  private static SimpleModuleContext contextWithGreeter(TestGreeter greeter) {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    registry.register(
        new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0")),
        TestGreeter.class,
        greeter);
    return new SimpleModuleContext(
        new ModuleId("com.gimle.gateway", Version.parse("1.0.0")), registry);
  }

  @Test
  void a_string_argument_route_dispatches_and_returns_the_real_result() {
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            contextWithGreeter(name -> "Hello, " + name + "!"),
            List.of(new GatewayRoute("/greet", GREETER_IFACE, 1, "greet", ParamType.STRING)));

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
                new GatewayRoute(
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
            List.of(new GatewayRoute("/greet", GREETER_IFACE, 1, "greet", ParamType.STRING)));

    GatewayResponse response = dispatcher.dispatch("POST", "/nope", "x");

    assertEquals(404, response.status());
  }

  @Test
  void the_wrong_http_method_for_a_route_returns_405() {
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            contextWithGreeter(name -> name),
            List.of(new GatewayRoute("/greet", GREETER_IFACE, 1, "greet", ParamType.STRING)));

    assertEquals(405, dispatcher.dispatch("GET", "/greet", "").status());
  }

  @Test
  void a_body_that_does_not_coerce_to_the_declared_param_type_returns_400() {
    // The parse failure on a non-numeric body happens before any invocation is attempted, so this
    // route needs no real int-taking method behind it at all.
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            contextWithGreeter(name -> name),
            List.of(new GatewayRoute("/count", GREETER_IFACE, 1, "count", ParamType.INT)));

    GatewayResponse response = dispatcher.dispatch("POST", "/count", "not-a-number");

    assertEquals(400, response.status());
  }

  @Test
  void a_downstream_call_that_throws_returns_502() {
    TestGreeter greeter =
        name -> {
          throw new IllegalStateException("boom");
        };
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            contextWithGreeter(greeter),
            List.of(new GatewayRoute("/greet", GREETER_IFACE, 1, "greet", ParamType.STRING)));

    GatewayResponse response = dispatcher.dispatch("POST", "/greet", "x");

    assertEquals(502, response.status());
  }

  @Test
  void a_route_naming_a_service_nothing_exports_is_served_as_200_with_an_empty_body() {
    // Documented v1 limitation (see GatewayDispatcher#dispatch's own javadoc): invokeByName's
    // Optional.empty() means either "not found" or "found and returned void/null" -- there's no
    // separate signal here to tell them apart, so a misconfigured route reads as a quiet success.
    SimpleServiceRegistry emptyRegistry = new SimpleServiceRegistry();
    SimpleModuleContext ctx =
        new SimpleModuleContext(
            new ModuleId("com.gimle.gateway", Version.parse("1.0.0")), emptyRegistry);
    GatewayDispatcher dispatcher =
        new GatewayDispatcher(
            ctx, List.of(new GatewayRoute("/greet", GREETER_IFACE, 1, "greet", ParamType.STRING)));

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
                new GatewayRoute(
                    "/increment", TestAdder.class.getName(), 1, "increment", ParamType.INT)));

    GatewayResponse response = dispatcher.dispatch("POST", "/increment", "41");

    assertEquals(200, response.status());
    assertEquals("42", response.body());
  }
}
