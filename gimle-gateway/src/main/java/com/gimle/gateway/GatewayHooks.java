package com.gimle.gateway;

import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gateway module's own lifecycle hooks: reads its route configuration and listen port from
 * {@code ctx.config(...)}, binds a plain (no TLS -- see this module's own package description for
 * why that's deliberately out of scope) {@code HttpServer}, and dispatches every inbound request
 * through {@link GatewayDispatcher}.
 *
 * <p>Configuration keys, both required (there is no fixed default port -- see this module's own
 * README/deployment.yaml comment: an operator picks a non-colliding port across co-located
 * DaemonSet instances the same way {@code greeter-load-generator}'s own hardcoded port already
 * accepts that gap, just config-driven here instead of hardcoded):
 *
 * <ul>
 *   <li>{@code gateway.port} -- the TCP port this instance's {@code HttpServer} binds on {@code
 *       0.0.0.0}.
 *   <li>{@code gateway.routes} -- the route table, in {@link GatewayRouteConfig}'s own format.
 * </ul>
 */
public final class GatewayHooks implements ModuleLifecycleHooks {

  private static final Logger log = LoggerFactory.getLogger(GatewayHooks.class);

  static final AtomicBoolean ready = new AtomicBoolean(false);

  private HttpServer server;

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    int port = requiredIntConfig(ctx, "gateway.port");
    String routesConfig = requiredConfig(ctx, "gateway.routes");
    List<GatewayRoute> routes = GatewayRouteConfig.parse(routesConfig);
    GatewayDispatcher dispatcher = new GatewayDispatcher(ctx, routes);

    try {
      server = HttpServer.create(new InetSocketAddress(port), 0);
      for (GatewayRoute route : routes) {
        server.createContext(route.path(), exchange -> handle(dispatcher, exchange));
      }
      // One virtual thread per request: an inbound gateway request blocks synchronously on a real
      // fabric round trip (possibly cross-machine), so a fixed-size platform-thread pool would
      // itself become the bottleneck this gateway exists not to be.
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.start();
      ready.set(true);
      log.info("gimle-gateway listening on 0.0.0.0:{} with {} route(s)", port, routes.size());
    } catch (IOException e) {
      throw new UncheckedIOException("gimle-gateway failed to bind port " + port, e);
    }
  }

  @Override
  public void onStop(ModuleContext ctx) {
    ready.set(false);
    if (server != null) {
      server.stop(0);
    }
  }

  @Override
  public void onUninstall(ModuleContext ctx) {}

  private void handle(GatewayDispatcher dispatcher, HttpExchange exchange) {
    try {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      GatewayDispatcher.GatewayResponse response =
          dispatcher.dispatch(
              exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body);
      respond(exchange, response.status(), response.body());
    } catch (IOException e) {
      log.warn("gimle-gateway failed reading a request body: {}", e.getMessage());
      respond(exchange, 500, "failed reading request body");
    } finally {
      exchange.close();
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) {
    try {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String requiredConfig(ModuleContext ctx, String key) {
    return ctx.config(key)
        .orElseThrow(
            () -> new GatewayConfigException("required gateway config key missing: " + key));
  }

  private static int requiredIntConfig(ModuleContext ctx, String key) {
    String raw = requiredConfig(ctx, key);
    try {
      return Integer.parseInt(raw.strip());
    } catch (NumberFormatException e) {
      throw new GatewayConfigException(
          "gateway config key " + key + " must be an integer, got: " + raw);
    }
  }
}
