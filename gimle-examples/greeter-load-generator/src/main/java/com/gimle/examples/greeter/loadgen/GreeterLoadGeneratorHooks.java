package com.gimle.examples.greeter.loadgen;

import com.gimle.examples.greeter.Greeter;
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
 * The bridge an external HTTP load generator needs to drive real fabric traffic: the wire protocol
 * {@code ModuleContext#lookupService}'s cross-worker proxy speaks has no client outside a hosted
 * module, so an outside process (Gatling, in {@code gimle-smoke-tests}) can't call {@code Greeter}
 * directly. Every inbound HTTP request to {@code /call} on the configured {@code load.port} does
 * exactly one real, synchronous {@code lookupService(Greeter.class)} + {@code greet(...)} call and
 * reflects the outcome in the HTTP response -- so the request rate an external tool controls
 * becomes greeter-provider's own real, worker-reported {@code requestRatePerSecond}, not a
 * synthetic stand-in for it. {@code load.port} is read via {@code ctx.config(...)}, the same
 * config-driven pattern {@code gimle-gateway}'s own {@code gateway.port} already uses -- a caller
 * leases a real port and delivers it as tenant config rather than this module binding a fixed one,
 * so concurrently running instances (this suite's own {@code AutoscaleIT}/{@code RollingUpdateIT}
 * deploy one each, with no isolation between them) never collide on the same listen port.
 *
 * <p>Deliberately logs nothing per request (unlike {@code GreeterConsumerHooks}' own one-line-per-
 * call logging, fine at its 5s interval): a Gatling run driving tens of requests/second would flood
 * this instance's own log file and skew the very load characteristics under test.
 */
public final class GreeterLoadGeneratorHooks implements ModuleLifecycleHooks {

  private static final Logger log = LoggerFactory.getLogger(GreeterLoadGeneratorHooks.class);

  static final AtomicBoolean ready = new AtomicBoolean(false);

  private HttpServer server;

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    int port = requiredIntConfig(ctx, "load.port");
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
      server.createContext("/call", exchange -> handleCall(ctx, exchange));
      // One virtual thread per request: a Gatling run's whole point is a real, possibly-bursty
      // concurrent request rate, and each handler blocks synchronously on a real fabric round
      // trip -- a fixed-size platform-thread pool would itself become the bottleneck under load,
      // not the thing this module exists to measure.
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.start();
      ready.set(true);
      log.info("greeter-load-generator listening on 127.0.0.1:{}", port);
    } catch (IOException e) {
      throw new UncheckedIOException("greeter-load-generator failed to bind its HTTP port", e);
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
      Optional<Greeter> greeter = ctx.lookupService(Greeter.class);
      if (greeter.isEmpty()) {
        respond(exchange, 503, "no Greeter available yet");
        return;
      }
      String reply = greeter.get().greet("Gatling");
      respond(exchange, 200, reply);
    } catch (RuntimeException e) {
      // No member currently exports Greeter, or the resolved endpoint failed -- both surfaced to
      // the caller as a real HTTP failure rather than swallowed, so Gatling's own error rate
      // reflects genuine call failures.
      respond(exchange, 502, "call to Greeter failed: " + e.getMessage());
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
