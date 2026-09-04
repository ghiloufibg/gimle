package com.gimle.examples.greeter.provider;

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
 * Registers a real {@link Greeter} on the fabric when this instance starts, and opens a small real
 * HTTP status listener alongside it. {@link GreeterReadinessProbe} shares {@link #ready} with this
 * class: the instance isn't ready for traffic until the service is actually registered and
 * reachable.
 *
 * <p>The status listener exists so {@code ctx.reportPort} has a genuine, real listening port to
 * report -- without it, this module would only ever be reachable over the fabric, which a
 * control-plane-declared {@code Service} cannot front (a Service resolves a plain TCP/HTTP
 * endpoint, not a fabric interface call). It binds port {@code 0} rather than a fixed one: this
 * manifest declares {@code TIER_2} (a dedicated worker JVM per instance), but nothing stops more
 * than one replica's worker from landing on the same node, and a fixed port would make the second
 * replica's bind fail outright.
 */
public final class GreeterProviderHooks implements ModuleLifecycleHooks {

  private static final Logger log = LoggerFactory.getLogger(GreeterProviderHooks.class);

  static final AtomicBoolean ready = new AtomicBoolean(false);

  private HttpServer statusServer;

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    ctx.registerService(Greeter.class, name -> "Hello, " + name + "! (from provider)");
    ready.set(true);
    log.info("greeter-provider registered its Greeter service on the fabric");
    // Exercises the real config/secrets delivery path end to end: the
    // agent fetches this tenant's secrets straight from Fafnir and hands them down alongside plain
    // config, so this is a real write-via-API -> fetch-via-agent -> observed-inside-a-deployed-
    // module round trip, not a unit-level check. Logged, not asserted here -- gimle-smoke-tests'
    // GreeterClusterTopologyIT reads it back out of this instance's own application log.
    Optional<String> secret = ctx.config("some-secret-key");
    log.info("greeter-provider read some-secret-key: {}", secret.orElse("<absent>"));

    statusServer = startStatusServer();
    int port = statusServer.getAddress().getPort();
    // Folds into this instance's own per-tick MetricsReport the identical way a Vessel's own
    // agent-allocated port already does, landing in InstanceObservation.ports() -- what a
    // control-plane-declared Service fronting this deployment needs to resolve a live endpoint.
    ctx.reportPort("http", port);
    log.info("greeter-provider serving a status endpoint on port {}", port);
  }

  @Override
  public void onStop(ModuleContext ctx) {
    ready.set(false);
    if (statusServer != null) {
      statusServer.stop(0);
      statusServer = null;
    }
  }

  @Override
  public void onUninstall(ModuleContext ctx) {}

  private static HttpServer startStatusServer() {
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress(0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException("greeter-provider could not bind its status HTTP listener", e);
    }
    server.createContext("/", GreeterProviderHooks::handleStatus);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return server;
  }

  /** {@code GET /} -- a plain-text line naming this instance's own readiness state. */
  private static void handleStatus(HttpExchange exchange) throws IOException {
    byte[] body =
        ("greeter-provider: " + (ready.get() ? "ACTIVE" : "STARTING") + "\n")
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
