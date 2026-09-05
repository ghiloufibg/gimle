package com.gimle.gateway;

import com.gimle.core.exception.GimleTlsException;
import com.gimle.core.io.SizeLimitedInputStream;
import com.gimle.core.tenant.Tenant;
import com.gimle.core.tls.HostCertificate;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.SslContexts.ReloadableSniContext;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.net.ssl.SSLParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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
 * <p>Configuration keys, the first two required (there is no fixed default port -- see this
 * module's own README/deployment.yaml comment: an operator picks a non-colliding port across
 * co-located DaemonSet instances the same way {@code greeter-load-generator}'s own hardcoded port
 * already accepts that gap, just config-driven here instead of hardcoded):
 *
 * <ul>
 *   <li>{@code gateway.port} -- the TCP port this instance's {@code HttpServer}/{@code HttpsServer}
 *       binds on {@code 0.0.0.0}. Read once, at {@code onStart}: unlike the route table, changing
 *       the listen port of a running instance is a rebind, not a route-table swap, and stays a
 *       redeploy the same way it always has.
 *   <li>{@code gateway.controlPlaneEndpoint} -- {@code host:port} of the control plane whose
 *       declared {@code Ingress} resources make up this instance's route table. There is no config
 *       key carrying routes themselves: a route table written as opaque text could only ever be
 *       checked when a gateway happened to parse it, so a typo reached the cluster as an accepted
 *       write and surfaced seconds later as a route that silently never matched. An {@code Ingress}
 *       is validated where it is submitted, and is listed and RBAC-gated like any other resource.
 *       Re-read on a fixed background interval ({@link #DEFAULT_ROUTE_RELOAD_INTERVAL}) for as long
 *       as this instance runs, not just once at {@code onStart} -- see {@link
 *       #reloadRoutesIfChanged} for why a route table baked in once at startup was a real gap for a
 *       {@code DaemonSet}-deployed, independently-restarting fleet of gateway instances.
 *   <li>{@code gateway.tenantId} -- optional; whose {@code Ingress} resources this instance serves,
 *       defaulting to the default tenant.
 *   <li>{@code gateway.tlsCertificates} -- optional; per-hostname certificate bindings in {@link
 *       GatewayTlsConfig}'s own format, used only when TLS is on. Re-read on the same background
 *       interval the route table is (see {@link #reloadRoutesIfChanged}): unlike {@code
 *       gateway.port}, swapping which certificate SNI selection resolves a hostname to is not a
 *       rebind -- selection already happens fresh on every new handshake (see {@code SniKeyManager}
 *       in {@code gimle-core}) -- so a config change reaches an already-running instance the same
 *       way a route-table change does.
 * </ul>
 *
 * <p>Whether TLS is on at all is not a {@code ctx.config(...)} key: it is the same cluster-wide
 * {@code gimle.transport.protocol=tls} system property (plus {@code gimle.tls.certFile}/{@code
 * keyFile}/{@code caFile}) every other TLS-capable listener in this codebase reads via {@link
 * TransportProtocol#fromConfig()}/{@link TlsSettings#fromConfig()}. Only the extra per-virtual-host
 * certificates are a config key, because which hostnames one gateway fronts is that gateway's own
 * routing concern rather than a cluster-wide setting. The agent supervising this instance's worker
 * JVM forwards its own already-resolved {@code gimle.transport.protocol}/{@code
 * gimle.tls.certFile}/{@code keyFile}/{@code caFile} onto every worker it spawns (see {@code
 * AgentMain.stableWorkerFlags()}), so a gateway instance picks this up the same way {@code
 * gimle-fabric}'s own in-worker listener already does, with no new switch needed.
 */
public final class GatewayHooks implements ModuleLifecycleHooks {

  private static final Logger log = LoggerFactory.getLogger(GatewayHooks.class);

  static final AtomicBoolean ready = new AtomicBoolean(false);

  // Every inbound gateway route ultimately fully buffers its request body into memory (see
  // handle/GatewayDispatcher) -- cap it rather than reading an unbounded stream whole, the same
  // "attacker-controlled surface" discipline Muninn/Saga's own ingest endpoints already apply.
  private static final long MAX_REQUEST_BODY_BYTES = 50L * 1024 * 1024;

  private static final Duration DEFAULT_ROUTE_RELOAD_INTERVAL = Duration.ofSeconds(5);

  private final Duration routeReloadInterval;

  private HttpServer server;
  private ExecutorService executor;
  private ScheduledExecutorService routeReloadScheduler;

  // Read by every inbound request's handler on every request (see the createContext lambdas
  // below), written only from onStart/reloadRoutesIfChanged -- volatile, not a lock, since a
  // request racing a reload only ever sees either the old or the new dispatcher, both fully built,
  // never a half-constructed one (GatewayDispatcher's own fields are all final).
  private volatile GatewayDispatcher dispatcher;

  // Both accessed only from onStart and from reloadRoutesIfChanged, each of which holds this
  // instance's own monitor for the whole of its access -- ordinary fields, not volatile, are
  // enough, and every read of either one sees the last write under that same monitor.
  // appliedRoutesConfig is the fingerprint of the route table currently serving traffic, left null
  // by onStart so the first reload tick applies the declared Ingresses rather than deciding nothing
  // has changed since it bound with none.
  private String appliedRoutesConfig;
  private Set<String> registeredPaths = Set.of();

  // Same synchronized-access discipline as appliedRoutesConfig/registeredPaths above. tlsContext is
  // null for a plaintext listener (nothing to reload) and otherwise fixed once onStart sets it --
  // only its own live-mutable certificate bindings change on a reload tick, never this reference
  // itself, so no rebind of the underlying HttpsServer is ever needed for a certificate update to
  // take effect.
  private ReloadableSniContext tlsContext;
  private String appliedTlsCertificatesConfig = "";

  public GatewayHooks() {
    this(DEFAULT_ROUTE_RELOAD_INTERVAL);
  }

  /**
   * Test-only seam: lets a test shrink the reload interval far below {@link
   * #DEFAULT_ROUTE_RELOAD_INTERVAL} so a route-config change is observed in test time rather than
   * real wall-clock seconds, the same reason {@code NetworkPolicyRelay}/{@code ConfigRelay} each
   * take their own poll interval as a constructor parameter instead of hardcoding it.
   */
  GatewayHooks(Duration routeReloadInterval) {
    this.routeReloadInterval = routeReloadInterval;
  }

  @Override
  public void onInstall(ModuleContext ctx) {}

  // Synchronized against reloadRoutesIfChanged: the reload scheduler this method starts can tick
  // before the method returns, and both touch appliedRoutesConfig/registeredPaths. Holding the
  // monitor across the bind keeps the first tick waiting for a fully-applied route table rather
  // than reconciling contexts against a half-populated one.
  @Override
  public synchronized void onStart(ModuleContext ctx) {
    int port = requiredIntConfig(ctx, "gateway.port");
    requiredConfig(ctx, "gateway.controlPlaneEndpoint");
    String tlsCertificatesConfig = ctx.config("gateway.tlsCertificates").orElse("");
    List<HostCertificate> hostCertificates = GatewayTlsConfig.parse(tlsCertificatesConfig);
    // Bound with no routes at all: every route this instance will serve is declared as an Ingress,
    // and the first reload tick is what fetches them. Binding first anyway means the listener is up
    // (answering 404) while the control plane is still being reached, rather than the whole module
    // failing to start because a route table was momentarily unreadable.
    List<GatewayRoute> routes = List.of();
    dispatcher = new GatewayDispatcher(ctx, routes);
    appliedRoutesConfig = null;
    registeredPaths = distinctPaths(routes);
    appliedTlsCertificatesConfig = tlsCertificatesConfig;

    try {
      BoundServer bound = createHttpServer(port, hostCertificates);
      server = bound.server();
      tlsContext = bound.tlsContext();
      // One context per distinct path, not one per route: a host-constrained route and a
      // host-unconstrained (or differently-host-constrained) sibling can now share the same path,
      // and HttpServer#createContext rejects a second context registered at a path already bound.
      // GatewayDispatcher itself resolves which of a path's routes actually serves a given request.
      // The lambda reads the dispatcher field itself, not a captured snapshot, so a route-table
      // reload later takes effect on this same context with no need to re-register it.
      for (String path : registeredPaths) {
        server.createContext(path, exchange -> handle(dispatcher, exchange));
      }
      // One virtual thread per request: an inbound gateway request blocks synchronously on a real
      // fabric round trip (possibly cross-machine), so a fixed-size platform-thread pool would
      // itself become the bottleneck this gateway exists not to be.
      executor = Executors.newVirtualThreadPerTaskExecutor();
      server.setExecutor(executor);
      server.start();
      ready.set(true);
      log.info(
          "gimle-gateway listening on 0.0.0.0:{} ({}) with {} route(s) and {} per-host"
              + " certificate(s)",
          port,
          TransportProtocol.fromConfig(),
          routes.size(),
          hostCertificates.size());
      startRouteReload(ctx);
    } catch (IOException e) {
      throw new UncheckedIOException("gimle-gateway failed to bind port " + port, e);
    }
  }

  private void startRouteReload(ModuleContext ctx) {
    routeReloadScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-gateway-route-reload").unstarted(r));
    // Captured on the onStart thread, which the platform has already tagged with this instance's
    // identity, and re-applied on every tick below. A thread this module starts for itself carries
    // none of that, so without it every line this loop logs -- including the one saying a route
    // table was rejected -- landed in the worker's shared platform log instead of this instance's
    // own, where an operator watching the gateway they just reconfigured would actually see it.
    Map<String, String> instanceTags = MDC.getCopyOfContextMap();
    routeReloadScheduler.scheduleAtFixedRate(
        () -> reloadRoutesSafely(ctx, instanceTags),
        routeReloadInterval.toMillis(),
        routeReloadInterval.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  private void reloadRoutesSafely(ModuleContext ctx, Map<String, String> instanceTags) {
    Map<String, String> previous = MDC.getCopyOfContextMap();
    if (instanceTags != null) {
      MDC.setContextMap(instanceTags);
    }
    try {
      reloadRoutesIfChanged(ctx);
    } catch (RuntimeException e) {
      log.warn(
          "gimle-gateway route reload tick failed unexpectedly, keeping the previous route"
              + " table: {}",
          e.getMessage(),
          e);
    } finally {
      if (previous == null) {
        MDC.clear();
      } else {
        MDC.setContextMap(previous);
      }
    }
  }

  /**
   * Re-reads the declared Ingresses and, if they differ from what this instance last applied,
   * rebuilds the route table and swaps it in -- closing the gap the original implementation had:
   * {@code onStart} read the route table exactly once and baked it into a fixed set of {@code
   * HttpServer} contexts, with no listener, poll, or re-parse anywhere afterward, unlike {@code
   * NetworkPolicyRule}s or TLS material, both of which already have an explicit push/reload path.
   * Since this module is deployed as a {@code DaemonSet} across every edge-labeled node for real
   * multi-instance HA behind one external entry point, and DaemonSet instances restart
   * independently (crash, node maintenance, a manual bounce), a route-config update that never
   * reaches an already-running instance is a <i>potentially indefinite</i> window where different
   * edge nodes behind the same load balancer silently serve different route tables -- a real
   * split-brain in the platform's own documented ingress pattern.
   *
   * <p>{@code synchronized} against itself only -- one gateway instance's reload scheduler runs a
   * single thread on a fixed rate, so overlap is impossible in production; the guard exists purely
   * so a test can call this directly without racing a concurrently-scheduled tick.
   *
   * <p>A route table is swapped by rebuilding the {@link GatewayDispatcher} outright (its own
   * per-target caches reset and simply refill on the next request through them -- a negligible
   * cost, not a correctness concern) and reconciling {@code HttpServer}'s registered contexts
   * against the new path set: a path no longer present is removed ({@link
   * HttpServer#removeContext}, so a request to it gets the server's own ordinary 404 rather than a
   * stale route ever matching again), a genuinely new path is registered, and a path present in
   * both the old and new route sets keeps its already-registered context untouched -- that
   * context's handler reads {@link #dispatcher} on every request, so it observes the swapped-in
   * dispatcher on its very next request with no re-registration needed. A malformed update is
   * rejected the same way {@code onStart} already rejects one -- logged and skipped, keeping
   * whatever route table is already serving traffic, rather than tearing down a working gateway
   * over an operator's typo.
   */
  /**
   * Routes declared as {@code Ingress} resources, or empty when no control-plane endpoint is
   * configured (the gateway then behaves exactly as it always has, serving only its config) or when
   * the control plane could not be reached. An unreachable control plane must never tear down a
   * route table that is currently serving traffic, so a failed fetch is indistinguishable from "no
   * Ingresses declared" only in the sense that both leave the previous table in place.
   */
  private Optional<List<GatewayRoute>> fetchIngressRoutes(ModuleContext ctx) {
    Optional<String> endpoint = ctx.config("gateway.controlPlaneEndpoint");
    if (endpoint.isEmpty()) {
      return Optional.empty();
    }
    String tenantId = ctx.config("gateway.tenantId").orElse(Tenant.DEFAULT_TENANT_ID);
    try {
      HttpIngressSource source =
          new HttpIngressSource(
              HttpClient.newHttpClient(), URI.create("http://" + endpoint.get() + "/"));
      return source.fetch(tenantId).map(IngressRoutes::toGatewayRoutes);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (IOException | RuntimeException e) {
      log.warn("gimle-gateway could not read declared ingresses: {}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Re-reads {@code gateway.tlsCertificates} and, if it differs from what this instance last
   * applied, replaces the live per-hostname certificate bindings a TLS listener's SNI selection
   * picks from -- called from the same reload tick {@link #reloadRoutesIfChanged} runs on, so both
   * config keys reach an already-running instance on the same schedule. A no-op on a plaintext
   * listener ({@link #tlsContext} is null -- there is no certificate selection to update) and
   * silently a no-op the very first time it would apply nothing new, since {@link #onStart} already
   * applied whatever {@code gateway.tlsCertificates} held at boot. A malformed update, or one
   * naming unreadable certificate material, is rejected the same way a malformed route table is --
   * logged and skipped, keeping whichever bindings are already selecting traffic.
   */
  private void reloadTlsCertificatesIfChanged(ModuleContext ctx) {
    if (tlsContext == null) {
      return;
    }
    String tlsCertificatesConfig = ctx.config("gateway.tlsCertificates").orElse("");
    if (tlsCertificatesConfig.equals(appliedTlsCertificatesConfig)) {
      return;
    }
    List<HostCertificate> hostCertificates;
    try {
      hostCertificates = GatewayTlsConfig.parse(tlsCertificatesConfig);
      tlsContext.reloadHostCertificates(hostCertificates);
    } catch (GatewayConfigException | GimleTlsException e) {
      log.warn(
          "gimle-gateway rejected a gateway.tlsCertificates update, keeping the previous per-host"
              + " certificate bindings: {}",
          e.getMessage());
      return;
    }
    appliedTlsCertificatesConfig = tlsCertificatesConfig;
    log.info(
        "gimle-gateway reloaded its per-host TLS certificate bindings ({} binding(s))",
        hostCertificates.size());
  }

  private synchronized void reloadRoutesIfChanged(ModuleContext ctx) {
    reloadTlsCertificatesIfChanged(ctx);
    // Empty means the control plane could not be read, which is emphatically not the same as it
    // declaring no routes: emptying the table on an unreachable control plane would take the whole
    // gateway fleet down for the duration of a control-plane restart. A control plane that answers
    // with no ingresses does empty the table, because that is what it was asked.
    Optional<List<GatewayRoute>> declared = fetchIngressRoutes(ctx);
    if (declared.isEmpty()) {
      return;
    }
    List<GatewayRoute> newRoutes = declared.get();
    String fingerprint = newRoutes.toString();
    if (fingerprint.equals(appliedRoutesConfig)) {
      return;
    }
    Set<String> newPaths = distinctPaths(newRoutes);
    for (String path : newPaths) {
      if (!registeredPaths.contains(path)) {
        server.createContext(path, exchange -> handle(dispatcher, exchange));
      }
    }
    for (String path : registeredPaths) {
      if (!newPaths.contains(path)) {
        server.removeContext(path);
      }
    }
    dispatcher = new GatewayDispatcher(ctx, newRoutes);
    appliedRoutesConfig = fingerprint;
    registeredPaths = newPaths;
    log.info("gimle-gateway reloaded its route table ({} route(s))", newRoutes.size());
  }

  private static Set<String> distinctPaths(List<GatewayRoute> routes) {
    return routes.stream()
        .map(GatewayRoute::path)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * {@link TransportProtocol#PLAINTEXT} (the default) is untouched: a plain {@link HttpServer},
   * exactly what every existing caller/test already gets. {@link TransportProtocol#TLS} swaps in
   * {@link HttpsServer} instead, built the same way {@code ApiServer}/{@code FafnirServer}/{@code
   * MuninnServer}/{@code AndvariServer} already build theirs: {@code wantClientAuth}, not {@code
   * needClientAuth}, since a north-south caller reaching this gateway from outside the cluster has
   * no cluster-issued client certificate to present, and {@link HttpsConfigurator}/{@link
   * HttpsParameters} negotiate once per *connection* anyway, before any request path is read.
   *
   * <p>{@code hostCertificates} (empty unless {@code gateway.tlsCertificates} declares any) makes
   * that certificate a per-connection choice driven by the client's SNI extension rather than a
   * single one fixed at startup, which is what lets this gateway's own host-constrained routing
   * actually be reachable: a client verifies the presented certificate against the hostname *it*
   * dialed, so with one certificate every routed hostname outside that certificate's SAN fails TLS
   * before its route is ever consulted. No {@code SNIMatcher} is installed alongside it -- a
   * matcher refuses an unrecognized name outright, which would take the host-unconstrained fallback
   * routes down with it; an unmatched name gets the default certificate instead. The returned
   * {@link BoundServer#tlsContext} is what {@link #reloadTlsCertificatesIfChanged} later mutates in
   * place on a config change -- {@code null} for a plaintext listener, which has no certificate
   * selection to reload.
   */
  private static BoundServer createHttpServer(int port, List<HostCertificate> hostCertificates)
      throws IOException {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      if (!hostCertificates.isEmpty()) {
        log.warn(
            "gimle-gateway is listening in plaintext, so its {} configured per-host TLS"
                + " certificate(s) are unused -- set gimle.transport.protocol=tls to terminate TLS",
            hostCertificates.size());
      }
      return new BoundServer(HttpServer.create(new InetSocketAddress(port), 0), null);
    }
    ReloadableSniContext tlsContext =
        SslContexts.forMutualTls(TlsSettings.fromConfig(), hostCertificates);
    HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
    httpsServer.setHttpsConfigurator(
        new HttpsConfigurator(tlsContext.sslContext()) {
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
    return new BoundServer(httpsServer, tlsContext);
  }

  /**
   * What {@link #createHttpServer} binds: the listener itself, plus the reloadable TLS context it
   * was built on ({@code null} for a plaintext listener) -- kept together since {@link #onStart}
   * needs both, one to register request contexts on and the other to hand later reload ticks.
   */
  private record BoundServer(HttpServer server, ReloadableSniContext tlsContext) {}

  /**
   * The actual bound port -- distinct from the configured {@code gateway.port} when a test binds to
   * port {@code 0} (an ephemeral port), the same reason {@code ApiServer}/{@code
   * FafnirServer}/{@code MuninnServer}/{@code AndvariServer} each expose an equivalent accessor.
   */
  synchronized int port() {
    return server.getAddress().getPort();
  }

  // Synchronized for the same reason onStart is: a reload tick may still be in flight, and it
  // reads the very server this method stops. Taking the monitor lets that tick finish against a
  // live server rather than reconciling contexts on one being torn down underneath it.
  @Override
  public synchronized void onStop(ModuleContext ctx) {
    ready.set(false);
    if (routeReloadScheduler != null) {
      routeReloadScheduler.shutdownNow();
    }
    if (server != null) {
      // HttpServer#stop never shuts down a caller-supplied executor -- it assumes the executor may
      // be shared -- so this virtual-thread-per-task executor, created solely for this server
      // instance, must be shut down explicitly or it leaks on every start/stop cycle.
      server.stop(0);
    }
    if (executor != null) {
      executor.shutdownNow();
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
