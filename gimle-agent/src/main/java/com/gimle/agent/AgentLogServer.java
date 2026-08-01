package com.gimle.agent;

import com.gimle.core.logging.LogFileReader;
import com.gimle.core.logging.LogFileReader.LogPage;
import com.gimle.core.protocol.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The agent's read-only log-serving HTTP surface, letting the control plane proxy a console/CLI log
 * request to whichever node actually hosts the target instance -- {@code log-explorer-design.md}
 * &sect;6's "proxy-and-tail" design, the same architecture {@code kubectl logs} uses (API server
 * &rarr; kubelet &rarr; the node's own local log file). Every route reads straight off this agent's
 * own {@code logRoot}: node-level routes read files this process writes directly, and instance
 * routes read {@code workers/<deploymentName>#<instanceIndex>/...} -- the per-worker subtree {@code
 * AgentMain} points each spawned worker JVM's own {@code -Dgimle.log.root} at, so every worker's
 * platform/application logs land somewhere this agent can find them and distinct instances never
 * collide on one shared filename.
 */
final class AgentLogServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(AgentLogServer.class);
  private static final Duration FOLLOW_POLL_INTERVAL = Duration.ofMillis(500);

  private final Path logRoot;
  private final HttpServer server;

  AgentLogServer(Path logRoot, int port) throws IOException {
    this.logRoot = logRoot;
    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/logs/nodes/", this::handleNodeLogs);
    server.createContext("/logs/instances/", this::handleInstanceLogs);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
  }

  void start() {
    server.start();
  }

  int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  // ---- /logs/nodes/{nodeId} ----

  private void handleNodeLogs(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Map<String, String> query = parseQuery(exchange);
      String category = query.getOrDefault("category", "PLATFORM");
      boolean follow = "true".equals(query.get("follow"));
      String cursor = query.get("cursor");
      int limit = parseLimit(query.get("limit"));

      if ("PLATFORM".equals(category)) {
        Path file = logRoot.resolve("agent-platform.log");
        if (follow) {
          streamFollow(exchange, file, LogFileReader.configuredMaxFiles(), cursor);
        } else {
          respondPage(exchange, readPage(file, LogFileReader.configuredMaxFiles(), query, limit));
        }
        return;
      }
      if ("SYSTEM".equals(category)) {
        // No single canonical "node system log" exists -- SYSTEM capture is keyed per instance
        // (workers/<deploymentName>#<instanceIndex>-system.log, per AgentMain). Reading "this
        // node's SYSTEM log" as the union of every instance's raw-capture file currently on disk
        // for this node, merged by timestamp -- a reasonable interpretation, flagged as one in the
        // design doc this implements.
        if (follow) {
          respond(
              exchange,
              400,
              "follow=true is not supported for node-level SYSTEM logs (multiple underlying files)");
          return;
        }
        respondPage(exchange, readMergedSystemLogs(query, limit));
        return;
      }
      respond(exchange, 400, "unknown category: " + category);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("node logs request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private LogPage readMergedSystemLogs(Map<String, String> query, int limit) throws IOException {
    String since = query.get("since");
    String cursor = query.get("cursor");
    Path workersDir = logRoot.resolve("workers");
    List<Map<String, Object>> merged = new ArrayList<>();
    if (Files.isDirectory(workersDir)) {
      try (var files = Files.list(workersDir)) {
        for (Path file :
            files.filter(p -> p.getFileName().toString().endsWith("-system.log")).toList()) {
          // These raw-capture files aren't rotated (WorkerProcessSupervisor writes them directly,
          // not through RollingFileAppenders), so there's exactly one file per instance --
          // maxFiles=1.
          merged.addAll(
              since != null
                  ? LogFileReader.readAfter(file, 1, since)
                  : LogFileReader.readOlder(file, 1, cursor, limit).lines());
        }
      }
    }
    merged.sort(
        (a, b) -> String.valueOf(a.get("timestamp")).compareTo(String.valueOf(b.get("timestamp"))));
    if (since != null) {
      String newerCursor =
          merged.isEmpty() ? since : String.valueOf(merged.get(merged.size() - 1).get("timestamp"));
      return new LogPage(List.copyOf(merged), null, newerCursor);
    }
    int fromIndex = Math.max(0, merged.size() - limit);
    List<Map<String, Object>> page = merged.subList(fromIndex, merged.size());
    String olderCursor = fromIndex > 0 ? String.valueOf(page.get(0).get("timestamp")) : null;
    String newerCursor =
        page.isEmpty() ? cursor : String.valueOf(page.get(page.size() - 1).get("timestamp"));
    return new LogPage(List.copyOf(page), olderCursor, newerCursor);
  }

  // ---- /logs/instances/{deploymentName}/{instanceIndex} ----

  private void handleInstanceLogs(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      String tail = exchange.getRequestURI().getPath().substring("/logs/instances/".length());
      int slash = tail.indexOf('/');
      if (slash < 0) {
        respond(exchange, 400, "expected /logs/instances/{deploymentName}/{instanceIndex}");
        return;
      }
      String deploymentName = tail.substring(0, slash);
      int instanceIndex;
      try {
        instanceIndex = Integer.parseInt(tail.substring(slash + 1));
      } catch (NumberFormatException e) {
        respond(exchange, 400, "invalid instanceIndex");
        return;
      }

      Map<String, String> query = parseQuery(exchange);
      String category = query.getOrDefault("category", "APPLICATION");
      boolean follow = "true".equals(query.get("follow"));
      String cursor = query.get("cursor");
      int limit = parseLimit(query.get("limit"));

      // AgentMain's per-worker key (workers/<key>/), vs. InstanceSiftingFileAppender's own
      // dash-joined key for the sifted file itself (see gimle-core's InstanceSiftingFileAppender)
      // -- both derived from the same deploymentName/instanceIndex, but not the same separator.
      String workerKey = deploymentName + "#" + instanceIndex;
      Path workerLogRoot = logRoot.resolve("workers").resolve(workerKey);
      Path file =
          "PLATFORM".equals(category)
              // Matches AgentMain's -Dgimle.log.root=<logRoot>/workers/<workerKey> override for
              // the spawned worker JVM -- each worker gets its own directory, so distinct
              // instances' platform logs never collide on one shared filename.
              ? workerLogRoot.resolve("worker-platform.log")
              : workerLogRoot
                  .resolve("instances")
                  .resolve(deploymentName + "-" + instanceIndex + ".log");
      if (!"APPLICATION".equals(category) && !"PLATFORM".equals(category)) {
        respond(exchange, 400, "unknown category: " + category);
        return;
      }

      if (follow) {
        streamFollow(exchange, file, LogFileReader.configuredMaxFiles(), cursor);
      } else {
        respondPage(exchange, readPage(file, LogFileReader.configuredMaxFiles(), query, limit));
      }
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("instance logs request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  // ---- shared plumbing ----

  private static int parseLimit(String raw) {
    if (raw == null) {
      return 200;
    }
    try {
      return Math.max(1, Integer.parseInt(raw));
    } catch (NumberFormatException e) {
      return 200;
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
      String key = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
      String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      result.put(key, value);
    }
    return result;
  }

  private static void respondPage(HttpExchange exchange, LogPage page) throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("lines", page.lines());
    body.put("olderCursor", page.olderCursor());
    body.put("newerCursor", page.newerCursor());
    respondJson(exchange, 200, body);
  }

  /**
   * {@code cursor} (backward paging via {@link LogFileReader#readOlder}, "Load older") and {@code
   * since} (forward polling via {@link LogFileReader#readAfter}, "what's new since my last poll")
   * are genuinely different operations that both took the query string's only cursor-shaped
   * parameter at first -- a real bug caught by an actual browser session sitting on "follow" and
   * re-receiving the same lines every poll tick, because {@code readOlder(cursor=<newest seen>)}
   * legitimately returns lines *older* than that, i.e. mostly the same batch again, never the new
   * ones after it. {@code since} is the fix: a distinct parameter wired to the operation the
   * poll-based {@code openFollow} (console) and non-follow {@code --since} (gimle-cli) callers
   * actually need.
   */
  private static LogPage readPage(Path file, int maxFiles, Map<String, String> query, int limit) {
    String since = query.get("since");
    if (since != null) {
      List<Map<String, Object>> lines = LogFileReader.readAfter(file, maxFiles, since);
      String newerCursor =
          lines.isEmpty() ? since : String.valueOf(lines.get(lines.size() - 1).get("timestamp"));
      return new LogPage(lines, null, newerCursor);
    }
    return LogFileReader.readOlder(file, maxFiles, query.get("cursor"), limit);
  }

  private static void streamFollow(HttpExchange exchange, Path file, int maxFiles, String cursor)
      throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson; charset=utf-8");
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream out = exchange.getResponseBody()) {
      LogFileReader.streamFollow(file, maxFiles, cursor, FOLLOW_POLL_INTERVAL, out);
    } catch (IOException e) {
      // The client disconnected -- the normal, only way a follow session ends. Not an error.
      log.debug("log follow session ended: {}", e.getMessage());
    }
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
