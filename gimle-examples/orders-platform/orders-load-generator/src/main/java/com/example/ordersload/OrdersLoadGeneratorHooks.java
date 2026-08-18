package com.example.ordersload;

import com.example.orders.OrderCatalog;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The same bridge {@code gimle-examples/greeter-load-generator} establishes, retargeted at this
 * app's own {@link OrderCatalog}: every inbound HTTP request to {@code /call} on the configured
 * {@code orders.load-port} does exactly one real, synchronous {@code
 * lookupService(OrderCatalog.class)} + {@code totalUnitsOrdered("widget")} call and reflects the
 * outcome in the HTTP response -- a read, not a write, so a sustained load run exercises
 * orders-service's own real, worker-reported {@code requestRatePerSecond} (the signal {@code
 * orders-service-deployment}'s own {@code autoscale} policy reads -- see its own {@code
 * deployment.yaml}) without also growing the orders table without bound the way repeatedly calling
 * {@code placeOrder} would. {@code orders.load-port} is delivered as plain, non-secret {@code
 * ctx.config(...)} the same way {@code web-ui}'s connection settings are (see ../README.md), so
 * this module needs the same {@code orders-platform} tenant its siblings already use, purely for
 * delivery to reach it at all.
 *
 * <p>Deliberately logs nothing per request, matching {@code GreeterLoadGeneratorHooks}: a driven
 * run of tens of requests/second would flood this instance's own log file and skew the very load
 * characteristics under test.
 */
public final class OrdersLoadGeneratorHooks implements ModuleLifecycleHooks {

  private static final Logger log = LoggerFactory.getLogger(OrdersLoadGeneratorHooks.class);

  static final AtomicBoolean ready = new AtomicBoolean(false);

  private HttpServer server;

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    int port = requiredIntConfig(ctx, "orders.load-port");
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
      server.createContext("/call", exchange -> handleCall(ctx, exchange));
      // One virtual thread per request: a driven run's whole point is a real, possibly-bursty
      // concurrent request rate, and each handler blocks synchronously on a real fabric round
      // trip -- a fixed-size platform-thread pool would itself become the bottleneck under load,
      // not the thing this module exists to measure.
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.start();
      ready.set(true);
      log.info("orders-load-generator listening on 127.0.0.1:{}", port);
    } catch (IOException e) {
      throw new UncheckedIOException("orders-load-generator failed to bind its HTTP port", e);
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

  private void handleCall(ModuleContext ctx, HttpExchange exchange) {
    try {
      Optional<OrderCatalog> orderCatalog = ctx.lookupService(OrderCatalog.class);
      if (orderCatalog.isEmpty()) {
        respond(exchange, 503, "no OrderCatalog available yet");
        return;
      }
      int total = orderCatalog.get().totalUnitsOrdered("widget");
      respond(exchange, 200, "widget total: " + total);
    } catch (RuntimeException e) {
      // No member currently exports OrderCatalog, or the resolved endpoint failed -- both
      // surfaced as a real HTTP failure rather than swallowed, so a driving tool's own error rate
      // reflects genuine call failures.
      respond(exchange, 502, "call to OrderCatalog failed: " + e.getMessage());
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

  private static int requiredIntConfig(ModuleContext ctx, String key) {
    String raw =
        ctx.config(key)
            .orElseThrow(
                () ->
                    new LoadGeneratorConfigException(
                        "required load-generator config key missing: " + key));
    try {
      return Integer.parseInt(raw.strip());
    } catch (NumberFormatException e) {
      throw new LoadGeneratorConfigException(
          "load-generator config key " + key + " must be an integer, got: " + raw);
    }
  }
}
