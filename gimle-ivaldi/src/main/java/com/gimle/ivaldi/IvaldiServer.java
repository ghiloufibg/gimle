package com.gimle.ivaldi;

import com.gimle.core.io.SizeLimitedInputStream;
import com.gimle.core.protocol.Json;
import com.gimle.core.web.HttpResponses;
import com.gimle.core.web.RootRedirectHandler;
import com.gimle.core.web.SpaStaticHandler;
import com.gimle.ivaldi.blueprint.BlueprintStore;
import com.gimle.ivaldi.blueprint.BlueprintSummary;
import com.gimle.ivaldi.cluster.ClusterHealth;
import com.gimle.ivaldi.cluster.ClusterStore;
import com.gimle.ivaldi.run.RunController;
import com.gimle.ivaldi.validate.FileSetValidator;
import com.gimle.ivaldi.validate.Finding;
import com.gimle.ivaldi.validate.RenderedFile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ivaldi's HTTP surface: Blueprint CRUD ({@code /api/blueprints[/{id}]}) over a flat-file {@link
 * BlueprintStore}, tier-2 validation ({@code POST /api/validate}) running the real Hilmir/Mimir
 * parsers against already-rendered YAML, saved cluster connections ({@code
 * /api/clusters[/{id}[/topology|/health]]}) over a flat-file {@link ClusterStore}, running a
 * Blueprint against one of them ({@code /api/runs*}) via {@link RunController}, remote shutdown,
 * and the bundled console SPA at {@code /console} when one is on the classpath. Deliberately no
 * authentication or TLS -- a local development tool, bound to loopback by default by {@link
 * IvaldiMain}, never one of a deployed cluster's own processes.
 */
public final class IvaldiServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(IvaldiServer.class);
  private static final long MAX_BODY_BYTES = 8L * 1024 * 1024;

  private final BlueprintStore store;
  private final ClusterStore clusters;
  private final RunController runs;
  private final HttpServer server;
  private final ExecutorService executor;

  public IvaldiServer(
      BlueprintStore store,
      ClusterStore clusters,
      RunController runs,
      InetAddress address,
      int port)
      throws IOException {
    this.store = store;
    this.clusters = clusters;
    this.runs = runs;
    this.server = HttpServer.create(new InetSocketAddress(address, port), 0);
    server.createContext("/api/health", this::handleHealth);
    server.createContext("/api/blueprints", this::handleBlueprints);
    server.createContext("/api/validate", this::handleValidate);
    server.createContext("/api/clusters", this::handleClusters);
    server.createContext("/api/runs", this::handleRuns);
    server.createContext("/api/shutdown", this::handleShutdown);
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
  }

  /**
   * Registers the bundled console SPA (see {@code gimle-ivaldi-console}) at {@code /console}, plus
   * the same {@code /} redirect to {@code /console} every other bundled-console process has.
   */
  public void serveConsole(Path staticRoot) throws IOException {
    server.createContext("/console", new SpaStaticHandler(staticRoot, "index.html"));
    server.createContext("/", new RootRedirectHandler("/console"));
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    // HttpServer#stop never shuts down a caller-supplied executor -- it assumes the executor may
    // be shared -- so this virtual-thread-per-task executor, created solely for this server
    // instance, must be shut down explicitly or it leaks on every close.
    server.stop(0);
    executor.shutdownNow();
  }

  // ---- GET /api/health ----

  private void handleHealth(HttpExchange exchange) {
    try {
      respondJson(exchange, 200, Map.of("status", "ok"));
    } catch (IOException e) {
      log.debug("health response failed: {}", e.getMessage());
    } finally {
      exchange.close();
    }
  }

  // ---- /api/blueprints, /api/blueprints/{id} ----

  private void handleBlueprints(HttpExchange exchange) {
    try {
      String tail = exchange.getRequestURI().getPath().substring("/api/blueprints".length());
      String method = exchange.getRequestMethod();
      if (tail.isEmpty() || "/".equals(tail)) {
        handleBlueprintsCollection(exchange, method);
        return;
      }
      String id = URLDecoder.decode(tail.substring(1), StandardCharsets.UTF_8);
      handleOneBlueprint(exchange, method, id);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (BodyTooLargeException e) {
      respondQuietly(exchange, 413, String.valueOf(e.getMessage()));
    } catch (BlueprintStore.IdAlreadyExistsException e) {
      respondQuietly(exchange, 409, String.valueOf(e.getMessage()));
    } catch (RunController.DeploymentInUseException e) {
      respondQuietly(exchange, 409, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("blueprints request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleBlueprintsCollection(HttpExchange exchange, String method) throws IOException {
    switch (method) {
      case "GET" ->
          respondJson(
              exchange, 200, store.list().stream().map(BlueprintSummary::toJsonMap).toList());
      case "POST" -> {
        BlueprintSummary created = store.create(readBody(exchange));
        respondJson(exchange, 201, created.toJsonMap());
      }
      default -> respond(exchange, 405, "method not allowed");
    }
  }

  private void handleOneBlueprint(HttpExchange exchange, String method, String id)
      throws IOException {
    switch (method) {
      case "GET" -> {
        Optional<String> body = store.get(id);
        if (body.isEmpty()) {
          respond(exchange, 404, "no such blueprint: " + id);
        } else {
          respondRawJson(exchange, 200, body.get());
        }
      }
      case "PUT" -> respondJson(exchange, 200, store.save(id, readBody(exchange)).toJsonMap());
      case "DELETE" -> {
        runs.requireNoLiveRunForBlueprint(id);
        boolean deleted = store.delete(id);
        respond(exchange, deleted ? 200 : 404, deleted ? "deleted" : "no such blueprint: " + id);
      }
      default -> respond(exchange, 405, "method not allowed");
    }
  }

  // ---- /api/clusters, /api/clusters/{id}, /api/clusters/{id}/topology ----

  private void handleClusters(HttpExchange exchange) {
    try {
      String tail = exchange.getRequestURI().getPath().substring("/api/clusters".length());
      String method = exchange.getRequestMethod();
      if (tail.isEmpty() || "/".equals(tail)) {
        handleClustersCollection(exchange, method);
        return;
      }
      String[] segments = tail.substring(1).split("/", 2);
      String id = URLDecoder.decode(segments[0], StandardCharsets.UTF_8);
      if (segments.length == 1) {
        handleOneCluster(exchange, method, id);
      } else if ("topology".equals(segments[1])) {
        handleClusterTopology(exchange, method, id);
      } else if ("health".equals(segments[1])) {
        handleClusterHealth(exchange, method, id);
      } else {
        respond(exchange, 404, "no such route");
      }
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (BodyTooLargeException e) {
      respondQuietly(exchange, 413, String.valueOf(e.getMessage()));
    } catch (RunController.DeploymentInUseException e) {
      respondQuietly(exchange, 409, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("clusters request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleClustersCollection(HttpExchange exchange, String method) throws IOException {
    switch (method) {
      case "GET" -> respondJson(exchange, 200, clusters.list());
      case "POST" -> respondJson(exchange, 201, clusters.create(readBody(exchange)));
      default -> respond(exchange, 405, "method not allowed");
    }
  }

  private void handleOneCluster(HttpExchange exchange, String method, String id)
      throws IOException {
    switch (method) {
      case "GET" -> {
        Optional<String> body = clusters.get(id);
        if (body.isEmpty()) {
          respond(exchange, 404, "no such cluster: " + id);
        } else {
          respondRawJson(exchange, 200, body.get());
        }
      }
      case "PUT" -> respondJson(exchange, 200, clusters.save(id, readBody(exchange)));
      case "DELETE" -> {
        runs.requireNoLiveRun(id);
        boolean deleted = clusters.delete(id);
        respond(exchange, deleted ? 200 : 404, deleted ? "deleted" : "no such cluster: " + id);
      }
      default -> respond(exchange, 405, "method not allowed");
    }
  }

  private void handleClusterTopology(HttpExchange exchange, String method, String id)
      throws IOException {
    if (!"GET".equals(method)) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    if (clusters.get(id).isEmpty()) {
      respond(exchange, 404, "no such cluster: " + id);
      return;
    }
    Optional<String> topology = clusters.appliedTopology(id);
    // Map.of rejects a null value outright -- a cluster with nothing applied yet is exactly the
    // case this endpoint exists to report, so the response body carries a real null, not a
    // missing key.
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("topology", topology.orElse(null));
    respondJson(exchange, 200, body);
  }

  /**
   * Whether this cluster's own control plane answers, probed from here rather than from the
   * browser: a control plane sends no CORS headers, so the console cannot reach an arbitrary one
   * itself. Without this the console had nothing to ask and reported every cluster reachable,
   * including one whose port had nothing listening -- the single check whose whole purpose is to
   * find that out before a run does.
   */
  private void handleClusterHealth(HttpExchange exchange, String method, String id)
      throws IOException {
    if (!"GET".equals(method)) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    Optional<String> cluster = clusters.get(id);
    if (cluster.isEmpty()) {
      respond(exchange, 404, "no such cluster: " + id);
      return;
    }
    respondJson(exchange, 200, ClusterHealth.probe(cluster.get()));
  }

  // ---- POST /api/runs, GET /api/runs/current, GET /api/runs/{id}/log, DELETE /api/runs/current
  // ----

  private void handleRuns(HttpExchange exchange) {
    try {
      String tail = exchange.getRequestURI().getPath().substring("/api/runs".length());
      String method = exchange.getRequestMethod();
      if (tail.isEmpty() || "/".equals(tail)) {
        switch (method) {
          // The collection, not a singleton: this process can hold a run per cluster, and a
          // screen that shows which blueprints are running has to be able to ask for all of them.
          case "GET" -> respondJson(exchange, 200, runs.allSnapshotsJson());
          case "POST" -> respondJson(exchange, 201, parseAndStartRun(readBody(exchange)));
          default -> respond(exchange, 405, "method not allowed");
        }
        return;
      }
      String[] segments = tail.substring(1).split("/", 3);
      if ("current".equals(segments[0]) && segments.length == 1) {
        handleRunsCurrent(exchange, method);
      } else if ("for-blueprint".equals(segments[0]) && segments.length == 2) {
        handleRunForBlueprint(
            exchange, method, URLDecoder.decode(segments[1], StandardCharsets.UTF_8));
      } else if ("for-cluster".equals(segments[0]) && segments.length == 2) {
        handleRunForCluster(
            exchange, method, URLDecoder.decode(segments[1], StandardCharsets.UTF_8));
      } else if (segments.length == 2 && "log".equals(segments[1])) {
        handleRunLog(exchange, method, URLDecoder.decode(segments[0], StandardCharsets.UTF_8));
      } else {
        respond(exchange, 404, "no such route");
      }
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (BodyTooLargeException e) {
      respondQuietly(exchange, 413, String.valueOf(e.getMessage()));
    } catch (RunController.NotFoundException e) {
      respondQuietly(exchange, 404, String.valueOf(e.getMessage()));
    } catch (RunController.RunInProgressException | RunController.DeploymentInUseException e) {
      respondQuietly(exchange, 409, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("runs request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleRunsCurrent(HttpExchange exchange, String method) throws IOException {
    switch (method) {
      case "GET" -> respondJson(exchange, 200, runs.currentSnapshotJson());
      case "DELETE" -> respondJson(exchange, 200, runs.stop());
      default -> respond(exchange, 405, "method not allowed");
    }
  }

  /**
   * The run one blueprint owns: read it, or stop it. A Runner screen asks and stops by blueprint
   * rather than by "the current run" or by cluster, so a page never renders -- or tears down -- a
   * run that belongs to a different blueprint, which stays true even once several blueprints share
   * one cluster (see {@code RunController}'s "One cluster, many deployments" section).
   */
  private void handleRunForBlueprint(HttpExchange exchange, String method, String blueprintId)
      throws IOException {
    switch (method) {
      case "GET" -> respondJson(exchange, 200, runs.blueprintSnapshotJson(blueprintId));
      case "DELETE" -> respondJson(exchange, 200, runs.stopBlueprint(blueprintId));
      default -> respond(exchange, 405, "method not allowed");
    }
  }

  /** The run against one cluster: read it, or stop that one specifically. */
  private void handleRunForCluster(HttpExchange exchange, String method, String clusterId)
      throws IOException {
    switch (method) {
      case "GET" -> respondJson(exchange, 200, runs.clusterSnapshotJson(clusterId));
      case "DELETE" -> respondJson(exchange, 200, runs.stopCluster(clusterId));
      default -> respond(exchange, 405, "method not allowed");
    }
  }

  private void handleRunLog(HttpExchange exchange, String method, String runId) throws IOException {
    if (!"GET".equals(method)) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    int cursor = parseCursor(exchange.getRequestURI().getRawQuery());
    Optional<RunController.LogPage> page = runs.log(runId, cursor);
    if (page.isEmpty()) {
      respond(exchange, 404, "no such run: " + runId);
      return;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("lines", page.get().lines());
    body.put("nextCursor", page.get().nextCursor());
    respondJson(exchange, 200, body);
  }

  private static int parseCursor(String rawQuery) {
    if (rawQuery == null) {
      return 0;
    }
    for (String pair : rawQuery.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && "cursor".equals(pair.substring(0, eq))) {
        try {
          return Integer.parseInt(pair.substring(eq + 1));
        } catch (NumberFormatException ignored) {
          return 0;
        }
      }
    }
    return 0;
  }

  /** Parses {@code {clusterId, blueprintId?, files:[{path,content}], values?}} and starts a run. */
  private Map<String, Object> parseAndStartRun(String body) {
    Object parsed;
    try {
      parsed = Json.parse(body);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("request body is not valid JSON: " + e.getMessage(), e);
    }
    if (!(parsed instanceof Map<?, ?> root)
        || !(root.get("clusterId") instanceof String clusterId)
        || clusterId.isBlank()) {
      throw new IllegalArgumentException(
          "request body must be {\"clusterId\": string, \"files\": [...], \"values\"?: object}");
    }
    if (!(root.get("files") instanceof List<?>)) {
      throw new IllegalArgumentException("request body must include a \"files\" array");
    }
    List<RenderedFile> files = parseFiles(Json.write(Map.of("files", root.get("files"))));
    Optional<String> blueprintId =
        root.get("blueprintId") instanceof String s && !s.isBlank()
            ? Optional.of(s)
            : Optional.empty();
    Map<String, String> values = new LinkedHashMap<>();
    if (root.get("values") instanceof Map<?, ?> rawValues) {
      for (Map.Entry<?, ?> entry : rawValues.entrySet()) {
        values.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
      }
    }
    return runs.start(clusterId, blueprintId, files, values);
  }

  // ---- POST /api/validate ----

  private void handleValidate(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      List<RenderedFile> files = parseFiles(readBody(exchange));
      List<Finding> findings = FileSetValidator.validate(files);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("findings", findings.stream().map(Finding::toJsonMap).toList());
      respondJson(exchange, 200, result);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (BodyTooLargeException e) {
      respondQuietly(exchange, 413, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("validate request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /** Parses {@code {"files":[{"path":..,"content":..}]}}, rejecting any other shape outright. */
  private static List<RenderedFile> parseFiles(String body) {
    Object parsed;
    try {
      parsed = Json.parse(body);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("request body is not valid JSON: " + e.getMessage(), e);
    }
    if (!(parsed instanceof Map<?, ?> root) || !(root.get("files") instanceof List<?> rawFiles)) {
      throw new IllegalArgumentException(
          "request body must be {\"files\":[{\"path\": string, \"content\": string}]}");
    }
    List<RenderedFile> files = new ArrayList<>();
    for (Object entry : rawFiles) {
      if (!(entry instanceof Map<?, ?> fileJson)
          || !(fileJson.get("path") instanceof String path)
          || !(fileJson.get("content") instanceof String content)) {
        throw new IllegalArgumentException(
            "each files[] entry must be {\"path\": string, \"content\": string}");
      }
      files.add(new RenderedFile(path, content));
    }
    return files;
  }

  // ---- POST /api/shutdown ----

  /**
   * Acknowledges the request, then stops the server from a separate thread once the response has
   * gone out, so {@code IvaldiClient#shutdown} (and {@code gimle:ivaldi-stop}, its caller) sees a
   * 2xx before the process actually exits -- stopping inline here would race the exchange trying to
   * flush that very response.
   */
  private void handleShutdown(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(exchange, 200, Map.of("status", "stopping"));
    } catch (IOException e) {
      log.debug("shutdown response failed: {}", e.getMessage());
    } finally {
      exchange.close();
    }
    Thread.ofVirtual().start(this::close);
  }

  // ---- shared plumbing ----

  /** Thrown by {@link #readBody} once a request body has streamed past {@code maxBytes}. */
  private static final class BodyTooLargeException extends RuntimeException {
    BodyTooLargeException(long maxBytes) {
      super("request body exceeds the maximum allowed size of " + maxBytes + " bytes");
    }
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    try (InputStream body =
        new SizeLimitedInputStream(
            exchange.getRequestBody(),
            MAX_BODY_BYTES,
            exceeded -> new BodyTooLargeException(MAX_BODY_BYTES))) {
      return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    HttpResponses.respond(exchange, status, body);
  }

  private static void respondJson(HttpExchange exchange, int status, Object value)
      throws IOException {
    HttpResponses.respondJson(exchange, status, value);
  }

  /**
   * Writes a value that is already a JSON document, without re-encoding it through {@link Json}.
   */
  private static void respondRawJson(HttpExchange exchange, int status, String json)
      throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  private static void respondQuietly(HttpExchange exchange, int status, String body) {
    HttpResponses.respondQuietly(exchange, status, body);
  }
}
