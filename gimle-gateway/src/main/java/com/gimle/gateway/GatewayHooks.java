package com.gimle.gateway;

import com.gimle.core.io.SizeLimitedInputStream;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gateway module's own lifecycle hooks: reads its route configuration and listen port from
 * {@code ctx.config(...)}, binds either a plain {@code HttpServer} or, when {@link
 * TransportProtocol#fromConfig()} resolves to {@link TransportProtocol#TLS}, an {@code HttpsServer}
 * terminating TLS for inbound requests -- built the identical way {@code ApiServer}/{@code
 * FafnirServer}/{@code MuninnServer}/{@code AndvariServer} already build theirs (see {@link
 * #createHttpServer}) -- and dispatches every inbound request through {@link GatewayDispatcher}.
 * Outbound calls a route makes to a resolved fabric/vessel target ({@link VesselProxyClient}) stay
 * plain HTTP regardless -- this is TLS *termination*, not a TLS relay: an external caller's
 * connection is decrypted here, and the gateway speaks to the rest of the cluster the same way it
 * always has.
 *
 * <p>Configuration keys, both required (there is no fixed default port -- see this module's own
 * README/deployment.yaml comment: an operator picks a non-colliding port across co-located
 * DaemonSet instances the same way {@code greeter-load-generator}'s own hardcoded port already
 * accepts that gap, just config-driven here instead of hardcoded):
 *
 * <ul>
 *   <li>{@code gateway.port} -- the TCP port this instance's {@code HttpServer}/{@code HttpsServer}
 *       binds on {@code 0.0.0.0}.
 *   <li>{@code gateway.routes} -- the route table, in {@link GatewayRouteConfig}'s own format.
 * </ul>
 *
 * <p>TLS itself is not a {@code ctx.config(...)} key: it is the same cluster-wide {@code
 * gimle.transport.protocol=tls} system property (plus {@code gimle.tls.certFile}/{@code
 * keyFile}/{@code caFile}) every other TLS-capable listener in this codebase reads via {@link
 * TransportProtocol#fromConfig()}/{@link TlsSettings#fromConfig()}. The agent supervising this
 * instance's worker JVM already forwards an operator's own {@code -D} flags onto every worker it
 * spawns, so a gateway instance picks this up the same way {@code gimle-fabric}'s own in-worker
 * listener already does, with no new switch needed.
 */
public final class GatewayHooks implements ModuleLifecycleHooks {

  private static final Logger log = LoggerFactory.getLogger(GatewayHooks.class);

  static final AtomicBoolean ready = new AtomicBoolean(false);

  // Every inbound gateway route ultimately fully buffers its request body into memory (see
  // handle/GatewayDispatcher) -- cap it rather than reading an unbounded stream whole, the same
  // "attacker-controlled surface" discipline Muninn/Saga's own ingest endpoints already apply.
  private static final long MAX_REQUEST_BODY_BYTES = 50L * 1024 * 1024;

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
      server = createHttpServer(port);
      // One context per distinct path, not one per route: a host-constrained route and a
      // host-unconstrained (or differently-host-constrained) sibling can now share the same path,
      // and HttpServer#createContext rejects a second context registered at a path already bound.
      // GatewayDispatcher itself resolves which of a path's routes actually serves a given request.
      List<String> distinctPaths = routes.stream().map(GatewayRoute::path).distinct().toList();
      for (String path : distinctPaths) {
        server.createContext(path, exchange -> handle(dispatcher, exchange));
      }
      // One virtual thread per request: an inbound gateway request blocks synchronously on a real
      // fabric round trip (possibly cross-machine), so a fixed-size platform-thread pool would
      // itself become the bottleneck this gateway exists not to be.
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.start();
      ready.set(true);
      log.info(
          "gimle-gateway listening on 0.0.0.0:{} ({}) with {} route(s)",
          port,
          TransportProtocol.fromConfig(),
          routes.size());
    } catch (IOException e) {
      throw new UncheckedIOException("gimle-gateway failed to bind port " + port, e);
    }
  }

  /**
   * {@link TransportProtocol#PLAINTEXT} (the default) is untouched: a plain {@link HttpServer},
   * exactly what every existing caller/test already gets. {@link TransportProtocol#TLS} swaps in
   * {@link HttpsServer} instead, built the same way {@code ApiServer}/{@code FafnirServer}/{@code
   * MuninnServer}/{@code AndvariServer} already build theirs: {@code wantClientAuth}, not {@code
   * needClientAuth}, since a north-south caller reaching this gateway from outside the cluster has
   * no cluster-issued client certificate to present, and {@link HttpsConfigurator}/{@link
   * HttpsParameters} negotiate once per *connection* anyway, before any request path is read.
   */
  private static HttpServer createHttpServer(int port) throws IOException {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return HttpServer.create(new InetSocketAddress(port), 0);
    }
    SSLContext sslContext = SslContexts.forMutualTls(TlsSettings.fromConfig());
    HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
    httpsServer.setHttpsConfigurator(
        new HttpsConfigurator(sslContext) {
          @Override
          public void configure(HttpsParameters params) {
            // Order matters: setSSLParameters(...) copies its argument's own wantClientAuth value
            // onto params, so setting it here -- not via params.setWantClientAuth(...) separately,
            // and not before this call -- is the only ordering that actually sticks (same
            // requirement as ApiServer/FafnirServer/MuninnServer/AndvariServer's own
            // createHttpServer).
            SSLParameters sslParameters = getSSLContext().getDefaultSSLParameters();
            sslParameters.setWantClientAuth(true);
            params.setSSLParameters(sslParameters);
          }
        });
    return httpsServer;
  }

  /**
   * The actual bound port -- distinct from the configured {@code gateway.port} when a test binds to
   * port {@code 0} (an ephemeral port), the same reason {@code ApiServer}/{@code
   * FafnirServer}/{@code MuninnServer}/{@code AndvariServer} each expose an equivalent accessor.
   */
  int port() {
    return server.getAddress().getPort();
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
      String body;
      try (InputStream requestBody =
          new SizeLimitedInputStream(
              exchange.getRequestBody(),
              MAX_REQUEST_BODY_BYTES,
              exceeded -> new RequestTooLargeException(MAX_REQUEST_BODY_BYTES))) {
        body = new String(requestBody.readAllBytes(), StandardCharsets.UTF_8);
      }
      String hostHeader = exchange.getRequestHeaders().getFirst("Host");
      GatewayDispatcher.GatewayResponse response =
          dispatcher.dispatch(
              exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body, hostHeader);
      respond(exchange, response.status(), response.body());
    } catch (RequestTooLargeException e) {
      respond(exchange, 413, e.getMessage());
    } catch (IOException e) {
      log.warn("gimle-gateway failed reading a request body: {}", e.getMessage());
      respond(exchange, 500, "failed reading request body");
    } finally {
      exchange.close();
    }
  }

  /**
   * Thrown by {@link #handle} once a request body has streamed past {@code MAX_REQUEST_BODY_BYTES}.
   */
  private static final class RequestTooLargeException extends RuntimeException {
    RequestTooLargeException(long maxBytes) {
      super("request body exceeds the maximum allowed size of " + maxBytes + " bytes");
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
