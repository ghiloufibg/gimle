package com.example.webui.provider;

import com.example.inventory.InventoryLevels;
import com.example.orders.OrderCatalog;
import com.gimle.core.exception.GimleClusterException;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Boots a real Spring {@link AnnotationConfigApplicationContext}, then a real embedded {@link
 * HttpServer} on a fixed port -- serving this module's own static page at {@code /} and a small
 * JSON REST API at {@code /api/inventory}/{@code /api/orders} -- consuming orders-service and
 * inventory-service purely over Gimlé's own fabric the same way {@code OrdersReportJobHooks}
 * does, never sharing a classloader or a compile-time jar with either.
 *
 * <p>The fixed port ({@link #PORT}) is a deliberate simplification, not a platform feature: a
 * plain Gimlé module has no port-allocation mechanism of its own (that exists only for {@code
 * VesselSpec}-hosted processes, which in turn have no fabric access at all -- neither hosting mode
 * offers both). What *is* a platform feature now is {@code ctx.reportPort} in {@link
 * #onStart}: it folds this instance's chosen port into the same per-tick metrics report a
 * Vessel's own agent-allocated port already rides, which is what lets a control-plane-declared
 * {@code Service} fronting this deployment resolve a live endpoint for it -- see ../README.md's
 * "Reaching the web UI from outside the cluster" section for the {@code gimle-gateway}
 * {@code SERVICE}-route path that endpoint resolution makes possible, in place of the old
 * per-environment manual port-publish story.
 *
 * <p>Every fabric lookup here is retried per-request the same graceful-degradation way {@code
 * InventoryServiceHooks}'s reconcile loop and {@code OrdersReportJobHooks}'s retry helper already
 * are: {@code ctx.lookupService} throws {@link GimleClusterException} rather than returning empty
 * when nobody in the cluster has ever exported the interface yet, which is exactly the ordinary
 * state right after a fresh cluster boot -- caught and treated as "temporarily unavailable" so a
 * user hitting the page during that window sees a clear status instead of a stack trace.
 *
 * <p>{@code POST /api/orders} is gated behind {@link #ADMIN_TOKEN_CONFIG_KEY}, a real secret
 * delivered by Fafnir -- the first orders-platform module to consume {@code ctx.config(...)} at
 * all. Delivery is tenant-scoped end to end: {@code AgentMain#deliverConfig} returns immediately
 * for an untenanted instance, before ever calling Fafnir, so this module's {@code deployment.yaml}
 * declares a real {@code tenantId} purely to make that delivery happen -- see this module's own
 * README section for the {@code gimle set tenant}/{@code gimle secret set} commands that have to
 * run first. Read once here at {@link #onStart}, not re-fetched per request: config is delivered
 * over the control channel before {@code StartModule} even arrives, so it's already current by the
 * time a hook runs, the same "backed by real values from the moment it starts" guarantee every
 * other module's own {@code ctx.config} call could already rely on.
 */
public final class WebUiHooks implements ModuleLifecycleHooks {

  private static final Logger log = LoggerFactory.getLogger(WebUiHooks.class);

  /** See this class's own javadoc for why this is fixed rather than platform-allocated. */
  private static final int PORT = 8090;

  /** The {@code gimle secret set orders-platform ...} key gating {@code POST /api/orders} -- see
   * this class's own javadoc. */
  static final String ADMIN_TOKEN_CONFIG_KEY = "orders.admin-token";

  /** The header a caller must present, matching this instance's own delivered admin token, to
   * place an order. */
  static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

  static final AtomicBoolean ready = new AtomicBoolean(false);

  private AnnotationConfigApplicationContext springContext;
  private HttpServer httpServer;

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    springContext = new AnnotationConfigApplicationContext(WebUiConfiguration.class);
    WebUiService webUiService = springContext.getBean(WebUiService.class);
    byte[] indexHtml = loadIndexHtml();
    Optional<String> adminToken = ctx.config(ADMIN_TOKEN_CONFIG_KEY);
    if (adminToken.isEmpty()) {
      log.warn(
          "no {} delivered (missing tenantId, or the secret was never set) -- POST /api/orders"
              + " will refuse every request until it is",
          ADMIN_TOKEN_CONFIG_KEY);
    }

    try {
      httpServer = HttpServer.create(new InetSocketAddress(PORT), 0);
    } catch (IOException e) {
      throw new UncheckedIOException("web-ui could not bind port " + PORT, e);
    }
    httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    httpServer.createContext("/", new StaticPageHandler(indexHtml));
    httpServer.createContext("/api/inventory", new InventoryHandler(ctx, webUiService));
    httpServer.createContext("/api/orders", new PlaceOrderHandler(ctx, webUiService, adminToken));
    httpServer.start();
    // Folds into this instance's own per-tick MetricsReport the identical way a Vessel's own
    // agent-allocated port already does, landing in InstanceObservation.ports() -- what a
    // control-plane-declared Service fronting this deployment needs to resolve a live endpoint
    // for it. See ../README.md's "Reaching the web UI from outside the cluster" section for the
    // Service/gateway path this makes possible.
    ctx.reportPort("http", PORT);

    ready.set(true);
    log.info("web-ui serving on port {} (GET /, GET /api/inventory, POST /api/orders)", PORT);
  }

  @Override
  public void onStop(ModuleContext ctx) {
    ready.set(false);
    if (httpServer != null) {
      httpServer.stop(0);
      httpServer = null;
    }
    if (springContext != null) {
      springContext.close();
      springContext = null;
    }
  }

  @Override
  public void onUninstall(ModuleContext ctx) {}

  private static byte[] loadIndexHtml() {
    try (InputStream in = WebUiHooks.class.getResourceAsStream("/web/index.html")) {
      if (in == null) {
        throw new IllegalStateException("web/index.html missing from this module's own jar");
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read this module's own bundled index.html", e);
    }
  }

  /** Serves the one bundled static page, loaded once at {@link #onStart} rather than re-read from
   * the jar on every request -- it never changes for the lifetime of this instance. */
  private static final class StaticPageHandler implements HttpHandler {
    private final byte[] indexHtml;

    StaticPageHandler(byte[] indexHtml) {
      this.indexHtml = indexHtml;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, indexHtml.length);
      try (OutputStream body = exchange.getResponseBody()) {
        body.write(indexHtml);
      }
    }
  }

  /** {@code GET /api/inventory} -- current stock and total-ordered per tracked SKU, straight from
   * a fresh fabric lookup every request (never cached): the whole point of this page is watching
   * real numbers move as {@code InventoryServiceHooks}'s own background reconciler and other
   * clients placing orders change them. */
  private static final class InventoryHandler implements HttpHandler {
    private final ModuleContext ctx;
    private final WebUiService webUiService;

    InventoryHandler(ModuleContext ctx, WebUiService webUiService) {
      this.ctx = ctx;
      this.webUiService = webUiService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"GET".equals(exchange.getRequestMethod())) {
        sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
        return;
      }
      Optional<OrderCatalog> orderCatalog = lookupQuietly(() -> ctx.lookupService(OrderCatalog.class));
      Optional<InventoryLevels> inventoryLevels =
          lookupQuietly(() -> ctx.lookupService(InventoryLevels.class));
      sendJson(exchange, 200, webUiService.inventoryJson(orderCatalog, inventoryLevels));
    }
  }

  /** {@code POST /api/orders} -- places one order via {@code OrderCatalog#placeOrder}, the same
   * fabric-published method the platform's own greeter/report-job examples already prove out
   * cross-worker; this is simply the first client that lets a person outside the cluster drive it
   * from a browser instead of from another hosted module's own code. Requires {@link
   * #ADMIN_TOKEN_HEADER} to match this instance's own delivered {@link #ADMIN_TOKEN_CONFIG_KEY} --
   * see this class's own javadoc for why, and where that value comes from. */
  private static final class PlaceOrderHandler implements HttpHandler {
    // A fixed, tiny, known request shape -- {"sku":"widget","quantity":5} -- so two small regexes
    // are simpler and lighter than pulling in a JSON library for one request type.
    private static final Pattern SKU_FIELD = Pattern.compile("\"sku\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern QUANTITY_FIELD = Pattern.compile("\"quantity\"\\s*:\\s*(-?\\d+)");

    private final ModuleContext ctx;
    private final WebUiService webUiService;
    private final Optional<String> adminToken;

    PlaceOrderHandler(ModuleContext ctx, WebUiService webUiService, Optional<String> adminToken) {
      this.ctx = ctx;
      this.webUiService = webUiService;
      this.adminToken = adminToken;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"POST".equals(exchange.getRequestMethod())) {
        sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
        return;
      }
      // Fail closed on a misconfigured cluster (no tenant, or the secret was never set) rather
      // than silently letting every order through -- a demo app is still no excuse for "open by
      // default when the auth mechanism itself is broken".
      if (adminToken.isEmpty()) {
        sendJson(exchange, 503, "{\"error\":\"admin token not configured on this instance\"}");
        return;
      }
      String presentedToken = exchange.getRequestHeaders().getFirst(ADMIN_TOKEN_HEADER);
      if (!adminToken.get().equals(presentedToken)) {
        sendJson(exchange, 401, "{\"error\":\"missing or incorrect " + ADMIN_TOKEN_HEADER + "\"}");
        return;
      }
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      Optional<String> sku = firstGroup(SKU_FIELD, body);
      Optional<String> quantityText = firstGroup(QUANTITY_FIELD, body);
      if (sku.isEmpty() || quantityText.isEmpty()) {
        sendJson(exchange, 400, "{\"error\":\"expected {\\\"sku\\\":..,\\\"quantity\\\":N}\"}");
        return;
      }
      int quantity;
      try {
        quantity = Integer.parseInt(quantityText.get());
      } catch (NumberFormatException e) {
        sendJson(exchange, 400, "{\"error\":\"quantity must be an integer\"}");
        return;
      }
      Optional<String> validationError = webUiService.validateOrderRequest(sku.get(), quantity);
      if (validationError.isPresent()) {
        sendJson(exchange, 400, "{\"error\":\"" + validationError.get() + "\"}");
        return;
      }
      Optional<OrderCatalog> orderCatalog = lookupQuietly(() -> ctx.lookupService(OrderCatalog.class));
      if (orderCatalog.isEmpty()) {
        sendJson(exchange, 503, "{\"error\":\"OrderCatalog unavailable\"}");
        return;
      }
      String orderId = orderCatalog.get().placeOrder(sku.get(), quantity);
      sendJson(exchange, 201, "{\"orderId\":\"" + orderId + "\"}");
    }

    private static Optional<String> firstGroup(Pattern pattern, String text) {
      Matcher matcher = pattern.matcher(text);
      return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
  }

  /** Shared by both API handlers: treats "nobody has ever exported this yet" the same as "not
   * currently available" rather than a 500 -- see this class's own javadoc. */
  private static <T> Optional<T> lookupQuietly(java.util.function.Supplier<Optional<T>> lookup) {
    try {
      return lookup.get();
    } catch (GimleClusterException e) {
      return Optional.empty();
    }
  }

  private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream body = exchange.getResponseBody()) {
      body.write(bytes);
    }
  }
}
