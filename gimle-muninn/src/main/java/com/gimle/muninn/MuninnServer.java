package com.gimle.muninn;

import com.gimle.core.logging.LogFileReader.LogPage;
import com.gimle.core.protocol.Json;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.pki.Subjects;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Muninn's HTTP surface: {@code com.sun.net.httpserver.HttpServer}, JDK-bundled, no framework
 * dependency -- the same minimal stack {@code ApiServer}/{@code FafnirServer} already use, for the
 * same reason (CLAUDE.md's explicit non-goal of pulling in Spring/Netty/Quarkus for something this
 * small).
 *
 * <p>Log ingest/read routes are structurally the multi-node extension of {@code AgentLogServer}'s
 * own node-local surface: {@code AgentLogServer} serves exactly one node's own live files (no
 * {@code nodeId} in its own paths -- "this node" is implicit), Muninn serves many nodes'/instances'
 * shipped history, so {@code nodeId}/{@code deploymentName}/{@code instanceIndex} are explicit path
 * segments here.
 */
public final class MuninnServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(MuninnServer.class);

  /**
   * Allow-list for the {@code nodeId}/{@code deploymentName}/{@code category} path segments -- same
   * rationale as {@code AgentLogServer.DEPLOYMENT_NAME}: must not contain {@code /} or {@code \},
   * so none of them can compose with this class's own fixed directory shape into a path that
   * escapes {@code dataRoot}.
   */
  private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  /**
   * Same traversal defense as {@link #PATH_SEGMENT} (no {@code /} or {@code \}), but additionally
   * allows {@code :} -- unlike a node's plain {@code nodeId} (an agent-chosen token, always
   * alphanumeric), a metrics/traces {@code processId} is every process's own self-reported {@code
   * host:port} (the same value {@code gimle.node.id} already holds for the control plane/Fafnir/
   * Mimir), which is meaningless without the port. Colons are legal in a directory name on the
   * POSIX filesystems this cluster actually runs on.
   */
  private static final Pattern PROCESS_ID_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");

  private static final int DEFAULT_LIMIT = 200;

  // Read-only: Muninn re-runs its own independent Authorizer.authorize(...) check on proxied
  // reads rather than trusting ApiServer's forwarded-principal claim as proof by itself -- the same
  // defense-in-depth posture FafnirServer already established for /secrets/*. Also backs the
  // ingest-side identity check below (an instance-log ingest's calling node must currently own an
  // assignment for the deploymentName/instanceIndex it's writing into).
  private final StoreClient storeClient;
  private final MuninnDayFileStore dayFileStore;
  private final Instant startedAt = Instant.now();
  // Not final: a TLS rotation rebuilds this the same way FafnirServer's own #server field does --
  // see that class's field javadoc for why a rebuild, not a hot-swap, is the only supported path.
  private volatile HttpServer server;
  private final int boundPort;

  public MuninnServer(StoreClient storeClient, int port, Path dataRoot) throws IOException {
    this.storeClient = storeClient;
    this.dayFileStore = new MuninnDayFileStore(dataRoot);
    this.server = createHttpServer(port);
    this.boundPort = server.getAddress().getPort();
    registerContexts(server);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
  }

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
            // Same ordering requirement as ApiServer/FafnirServer's own createHttpServer:
            // setSSLParameters(...) copies its argument's wantClientAuth onto params, so this must
            // run through that call, not params.setWantClientAuth(...) directly.
            SSLParameters sslParameters = getSSLContext().getDefaultSSLParameters();
            sslParameters.setWantClientAuth(true);
            params.setSSLParameters(sslParameters);
          }
        });
    return httpsServer;
  }

  private void registerContexts(HttpServer target) {
    target.createContext("/status", this::handleStatus);
    target.createContext("/ingest/logs/nodes/", this::handleIngestNodeLogs);
    target.createContext("/ingest/logs/instances/", this::handleIngestInstanceLogs);
    target.createContext("/logs/nodes/", this::handleReadNodeLogs);
    target.createContext("/logs/instances/", this::handleReadInstanceLogs);
    target.createContext("/ingest/metrics/", this::handleIngestMetrics);
    target.createContext("/metrics/", this::handleReadMetrics);
    target.createContext("/ingest/traces/", this::handleIngestTraces);
    target.createContext("/traces/", this::handleReadTraces);
  }

  public void start() {
    server.start();
  }

  public int port() {
    return boundPort;
  }

  @Override
  public void close() {
    server.stop(0);
  }

  /**
   * Rebuilds the {@link HttpServer}/{@link HttpsServer} from whatever TLS material now sits at
   * {@code gimle.tls.certFile}/{@code keyFile} -- the same stop/rebuild/re-register/restart
   * contract {@code FafnirServer.reloadTlsMaterial} already established (see that method's own
   * javadoc for why a rebuild, not a hot-swap, is the only path the JDK actually supports).
   */
  public synchronized void reloadTlsMaterial() throws IOException {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return;
    }
    HttpServer previous = server;
    previous.stop(0);
    HttpServer rebuilt = createHttpServer(boundPort);
    registerContexts(rebuilt);
    rebuilt.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    rebuilt.start();
    server = rebuilt;
    log.info("reloaded TLS material and rebuilt HttpsServer on port {}", boundPort);
  }

  /**
   * The console's Overview-equivalent status surface: no RBAC gate -- nothing here is per-tenant or
   * data-bearing (never a shipped line's content), just process-level status every operator who can
   * reach this port is already trusted to see, the same posture {@code FafnirServer}'s own {@code
   * handleStatus} takes for its own read-only status surface.
   */
  private void handleStatus(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Map<String, Object> status = new LinkedHashMap<>();
      status.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds());
      status.put("transportProtocol", TransportProtocol.fromConfig().name());
      respondJson(exchange, 200, status);
    } catch (IOException e) {
      log.warn("status request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /** The body of a route handler, run once {@link #handle} has confirmed the request method. */
  @FunctionalInterface
  private interface RouteBody {
    void run(HttpExchange exchange) throws IOException;
  }

  /**
   * Common scaffolding for every ingest/read route below: rejects a mismatched HTTP method with
   * {@code 405}, runs {@code body} for the matching one, maps a thrown {@link
   * IllegalArgumentException} (e.g. a malformed ingest batch) to {@code 400} and any other {@link
   * IOException}/{@link RuntimeException} to a logged {@code 500}, and always closes {@code
   * exchange}. {@code logLabel} names the route in that failure log line (e.g. {@code "node log
   * ingest"}).
   */
  private void handle(
      HttpExchange exchange, String expectedMethod, String logLabel, RouteBody body) {
    try {
      if (!expectedMethod.equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      body.run(exchange);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("{} failed: {}", logLabel, e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  // ---- POST /ingest/logs/nodes/{nodeId}/{category} ----

  private void handleIngestNodeLogs(HttpExchange exchange) {
    handle(
        exchange,
        "POST",
        "node log ingest",
        ex -> {
          String[] parts =
              parseTwoSegments(
                  ex, "/ingest/logs/nodes/", PATH_SEGMENT, PATH_SEGMENT, "{nodeId}/{category}");
          if (parts == null) {
            return;
          }
          String nodeId = parts[0];
          String category = parts[1];
          if (!identityAllowedToIngestAsNode(ex, nodeId)) {
            respond(ex, 403, "certificate identity does not match nodeId");
            return;
          }
          ingest(ex, "logs/nodes/" + nodeId + "/" + category);
        });
  }

  // ---- POST /ingest/logs/instances/{deploymentName}/{instanceIndex}/{category} ----

  private void handleIngestInstanceLogs(HttpExchange exchange) {
    handle(
        exchange,
        "POST",
        "instance log ingest",
        ex -> {
          InstanceLogPath ref = parseInstanceLogPath(ex, "/ingest/logs/instances/");
          if (ref == null) {
            return;
          }
          if (!identityAllowedToIngestAsInstanceOwner(
              ex, ref.deploymentName(), ref.instanceIndex())) {
            respond(ex, 403, "certificate identity does not own this instance's assignment");
            return;
          }
          ingest(
              ex,
              "logs/instances/"
                  + ref.deploymentName()
                  + "#"
                  + ref.instanceIndex()
                  + "/"
                  + ref.category());
        });
  }

  /**
   * Parses the request body as NDJSON (one JSON object per line, matching {@code MuninnShipper}'s
   * own wire format), appends the whole batch via {@link MuninnDayFileStore#appendLines}, and
   * relies on that method's own all-or-nothing validation -- a malformed line rejects the entire
   * batch with 400 before anything is written, so a shipper's cursor never advances past data that
   * wasn't actually durable.
   */
  private void ingest(HttpExchange exchange, String subtreePath) throws IOException {
    List<Map<String, Object>> lines = new ArrayList<>();
    for (String line : readBody(exchange).split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      lines.add(Json.asObject(Json.parse(line)));
    }
    dayFileStore.appendLines(subtreePath, lines);
    respondJson(exchange, 200, Map.of("appended", lines.size()));
  }

  // ---- POST /ingest/metrics/{processKind}/{processId} ----

  private void handleIngestMetrics(HttpExchange exchange) {
    handle(
        exchange,
        "POST",
        "metrics ingest",
        ex -> {
          String[] parts =
              parseTwoSegments(
                  ex,
                  "/ingest/metrics/",
                  PATH_SEGMENT,
                  PROCESS_ID_SEGMENT,
                  "{processKind}/{processId}");
          if (parts == null) {
            return;
          }
          if (!identityAllowedToIngestMetricsOrTraces(ex)) {
            respond(ex, 403, "no verified client certificate");
            return;
          }
          ingest(ex, "metrics/" + parts[0] + "/" + parts[1]);
        });
  }

  // ---- POST /ingest/traces/{processKind}/{processId} ----

  private void handleIngestTraces(HttpExchange exchange) {
    handle(
        exchange,
        "POST",
        "traces ingest",
        ex -> {
          String[] parts =
              parseTwoSegments(
                  ex,
                  "/ingest/traces/",
                  PATH_SEGMENT,
                  PROCESS_ID_SEGMENT,
                  "{processKind}/{processId}");
          if (parts == null) {
            return;
          }
          if (!identityAllowedToIngestMetricsOrTraces(ex)) {
            respond(ex, 403, "no verified client certificate");
            return;
          }
          ingest(ex, "traces/" + parts[0] + "/" + parts[1]);
        });
  }

  // ---- GET /logs/nodes/{nodeId}/{category} ----

  private void handleReadNodeLogs(HttpExchange exchange) {
    handle(
        exchange,
        "GET",
        "node log read",
        ex -> {
          String[] parts =
              parseTwoSegments(
                  ex, "/logs/nodes/", PATH_SEGMENT, PATH_SEGMENT, "{nodeId}/{category}");
          if (parts == null) {
            return;
          }
          read(ex, "logs/nodes/" + parts[0] + "/" + parts[1]);
        });
  }

  // ---- GET /logs/instances/{deploymentName}/{instanceIndex}/{category} ----

  private void handleReadInstanceLogs(HttpExchange exchange) {
    handle(
        exchange,
        "GET",
        "instance log read",
        ex -> {
          InstanceLogPath ref = parseInstanceLogPath(ex, "/logs/instances/");
          if (ref == null) {
            return;
          }
          read(
              ex,
              "logs/instances/"
                  + ref.deploymentName()
                  + "#"
                  + ref.instanceIndex()
                  + "/"
                  + ref.category());
        });
  }

  // ---- GET /metrics/{processKind}/{processId} ----

  private void handleReadMetrics(HttpExchange exchange) {
    handle(
        exchange,
        "GET",
        "metrics read",
        ex -> {
          String[] parts =
              parseTwoSegments(
                  ex, "/metrics/", PATH_SEGMENT, PROCESS_ID_SEGMENT, "{processKind}/{processId}");
          if (parts == null) {
            return;
          }
          read(ex, "metrics/" + parts[0] + "/" + parts[1]);
        });
  }

  // ---- GET /traces/{processKind}/{processId} ----

  private void handleReadTraces(HttpExchange exchange) {
    handle(
        exchange,
        "GET",
        "traces read",
        ex -> {
          String[] parts =
              parseTwoSegments(
                  ex, "/traces/", PATH_SEGMENT, PROCESS_ID_SEGMENT, "{processKind}/{processId}");
          if (parts == null) {
            return;
          }
          read(ex, "traces/" + parts[0] + "/" + parts[1]);
        });
  }

  /**
   * {@code since} (forward, "what's new") and {@code cursor} (backward, "load older") are distinct
   * operations, the same split {@code AgentLogServer.readPage} already establishes -- see that
   * method's own javadoc for the concrete bug this avoids. {@code follow=true} is deliberately
   * rejected: Muninn only ever serves shipped history, never a live tail (there is nothing to poll
   * that could still be growing from Muninn's own point of view between polls in a way a client
   * couldn't already get by re-issuing {@code since}).
   */
  private void read(HttpExchange exchange, String subtreePath) throws IOException {
    Map<String, String> query = parseQuery(exchange);
    if ("true".equals(query.get("follow"))) {
      respond(exchange, 400, "follow=true is not supported -- Muninn only serves shipped history");
      return;
    }
    int limit = parseLimit(query.get("limit"));
    String since = query.get("since");
    if (since != null) {
      List<Map<String, Object>> lines = dayFileStore.readAfter(subtreePath, since);
      String newerCursor =
          lines.isEmpty() ? since : String.valueOf(lines.get(lines.size() - 1).get("timestamp"));
      respondPage(exchange, new LogPage(lines, null, newerCursor));
      return;
    }
    respondPage(exchange, dayFileStore.readOlder(subtreePath, query.get("cursor"), limit));
  }

  /**
   * In plaintext mode (the default), every ingest request is accepted with no identity check,
   * matching every other Gimlé surface's plaintext posture -- see {@code requireAuthorized}/{@code
   * authorizeSecrets}'s own identical short-circuit. In TLS mode, a node agent's own certificate
   * {@code CN} is its {@code nodeId} (the same identity {@code
   * FafnirServer#isTenantAssignedToNode}'s javadoc documents for the {@code gimle:nodes} group), so
   * this is a direct equality check -- defense-in-depth beyond bare mTLS, so one compromised node
   * can't overwrite another's log stream.
   */
  private boolean identityAllowedToIngestAsNode(HttpExchange exchange, String nodeId) {
    if (!(exchange instanceof HttpsExchange httpsExchange)) {
      return true;
    }
    return peerCertificateCommonName(httpsExchange).map(nodeId::equals).orElse(false);
  }

  /**
   * An instance's logs are shipped by the *agent* supervising it (workers have no outbound network
   * identity of their own), so the ingest caller's identity is that agent's {@code nodeId}, not the
   * instance's own deployment/index. Allowed iff the calling node currently has a live assignment
   * for exactly this {@code deploymentName}/{@code instanceIndex} -- the node-scoped defense-in-
   * depth check {@code FafnirServer#isTenantAssignedToNode} already established the same shape for,
   * reused here at instance rather than tenant granularity.
   */
  private boolean identityAllowedToIngestAsInstanceOwner(
      HttpExchange exchange, String deploymentName, int instanceIndex) {
    if (!(exchange instanceof HttpsExchange httpsExchange)) {
      return true;
    }
    Optional<String> callerNodeId = peerCertificateCommonName(httpsExchange);
    if (callerNodeId.isEmpty()) {
      return false;
    }
    return storeClient.listAssignments().stream()
        .anyMatch(
            a ->
                a.deploymentName().equals(deploymentName)
                    && a.instanceIndex() == instanceIndex
                    && a.nodeId().equals(callerNodeId.get()));
  }

  /**
   * Metrics and traces ingest comes from every process kind -- control plane, Fafnir, Mimir, and
   * the agent -- whose {@code processId} path segment is a self-reported {@code host:port}
   * (matching each process's own {@code gimle.node.id} value), not that process's own certificate
   * CN (a fixed per-role alias like {@code "controlplane"}/{@code "fafnir"} minted by {@code
   * PkiBootstrapMain}). A strict CN-equals-processId check the way node/instance log ingest uses
   * above therefore doesn't generalize here; the weaker-but-still- real check available is that TLS
   * mode requires *some* certificate the cluster CA actually issued to have completed the mTLS
   * handshake at all -- this only additionally rejects a handshake that somehow completed with no
   * peer certificate present.
   */
  private boolean identityAllowedToIngestMetricsOrTraces(HttpExchange exchange) {
    if (!(exchange instanceof HttpsExchange httpsExchange)) {
      return true;
    }
    return peerCertificateCommonName(httpsExchange).isPresent();
  }

  private static Optional<String> peerCertificateCommonName(HttpsExchange exchange) {
    try {
      Certificate[] certificates = exchange.getSSLSession().getPeerCertificates();
      if (certificates.length == 0 || !(certificates[0] instanceof X509Certificate leaf)) {
        return Optional.empty();
      }
      return Optional.of(Subjects.principalFrom(leaf).name());
    } catch (SSLPeerUnverifiedException | RuntimeException e) {
      return Optional.empty();
    }
  }

  private static String tailAfter(HttpExchange exchange, String prefix) {
    return exchange.getRequestURI().getPath().substring(prefix.length());
  }

  /**
   * Shared two-segment path parsing/validation for every {@code {nodeId}/{category}} and {@code
   * {processKind}/{processId}} route below: splits the request path's tail after {@code prefix}
   * into exactly two segments and checks each against its own pattern. On success returns the two
   * segments; on failure writes a {@code 400} response describing the expected shape (built from
   * {@code prefix} and {@code segmentLabels}, e.g. {@code "{nodeId}/{category}"}) and returns
   * {@code null} -- the caller must check for that and return without further processing.
   */
  private static String[] parseTwoSegments(
      HttpExchange exchange,
      String prefix,
      Pattern firstPattern,
      Pattern secondPattern,
      String segmentLabels)
      throws IOException {
    String[] parts = tailAfter(exchange, prefix).split("/", 2);
    if (parts.length != 2
        || !firstPattern.matcher(parts[0]).matches()
        || !secondPattern.matcher(parts[1]).matches()) {
      respond(exchange, 400, "expected " + prefix + segmentLabels);
      return null;
    }
    return parts;
  }

  /**
   * The parsed and validated tail of an instance log route: {@code
   * {deploymentName}/{instanceIndex}/{category}}.
   */
  private record InstanceLogPath(String deploymentName, int instanceIndex, String category) {}

  /**
   * Shared parsing/validation for the instance log routes' {@code
   * {deploymentName}/{instanceIndex}/{category}} tail, used by both the ingest and read handlers.
   * On failure writes the appropriate {@code 400} response itself and returns {@code null} -- the
   * caller must check for that and return without further processing.
   */
  private static InstanceLogPath parseInstanceLogPath(HttpExchange exchange, String prefix)
      throws IOException {
    String[] parts = tailAfter(exchange, prefix).split("/", 3);
    if (parts.length != 3 || !PATH_SEGMENT.matcher(parts[0]).matches()) {
      respond(exchange, 400, "expected " + prefix + "{deploymentName}/{instanceIndex}/{category}");
      return null;
    }
    int instanceIndex;
    try {
      instanceIndex = Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      respond(exchange, 400, "invalid instanceIndex");
      return null;
    }
    String category = parts[2];
    if (!PATH_SEGMENT.matcher(category).matches()) {
      respond(exchange, 400, "invalid category: " + category);
      return null;
    }
    return new InstanceLogPath(parts[0], instanceIndex, category);
  }

  private static int parseLimit(String raw) {
    if (raw == null) {
      return DEFAULT_LIMIT;
    }
    try {
      return Math.max(1, Integer.parseInt(raw));
    } catch (NumberFormatException e) {
      return DEFAULT_LIMIT;
    }
  }

  private static Map<String, String> parseQuery(HttpExchange exchange) {
    Map<String, String> result = new LinkedHashMap<>();
    String query = exchange.getRequestURI().getRawQuery();
    if (query == null || query.isBlank()) {
      return result;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq < 0) {
        continue;
      }
      String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
      String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      result.put(key, value);
    }
    return result;
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    try (InputStream body = exchange.getRequestBody()) {
      return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void respondPage(HttpExchange exchange, LogPage page) throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("lines", page.lines());
    body.put("olderCursor", page.olderCursor());
    body.put("newerCursor", page.newerCursor());
    respondJson(exchange, 200, body);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void respondJson(HttpExchange exchange, int status, Object value)
      throws IOException {
    byte[] bytes = Json.write(value).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void respondQuietly(HttpExchange exchange, int status, String body) {
    try {
      respond(exchange, status, body);
    } catch (IOException e) {
      log.warn("failed to write error response: {}", e.getMessage());
    }
  }
}
