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
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The agent's read-only log-serving HTTP surface, letting the control plane proxy a console/CLI log
 * request to whichever node actually hosts the target instance -- a proxy-and-tail design, the same
 * architecture {@code kubectl logs} uses (API server &rarr; kubelet &rarr; the node's own local log
 * file). Every route reads straight off this agent's own {@code logRoot}: node-level routes read
 * files this process writes directly, and instance routes read {@code
 * workers/<deploymentName>#<instanceIndex>/...} -- the per-worker subtree {@code AgentMain} points
 * each spawned worker JVM's own {@code -Dgimle.log.root} at, so every worker's platform/application
 * logs land somewhere this agent can find them and distinct instances never collide on one shared
 * filename.
 */
final class AgentLogServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(AgentLogServer.class);
  private static final Duration FOLLOW_POLL_INTERVAL = Duration.ofMillis(500);

  /**
   * Allow-list for the {@code deploymentName} path segment: must not contain {@code /} or {@code
   * \}, so it can never compose with the fixed {@code workers/<name>#<index>/...} shape below into
   * a path that escapes {@code logRoot} -- deployment names elsewhere in the system have no charset
   * restriction today, so this is enforced here rather than assumed.
   */
  private static final Pattern DEPLOYMENT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

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
        // for this node, merged by timestamp -- a reasonable interpretation, not the only one
        // that could apply here.
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
        for (Path file : files.filter(p -> fileNameString(p).endsWith("-system.log")).toList()) {
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
      // limit=3: deploymentName, instanceIndex, and an optional sub-path (e.g. "crashdumps" or
      // "crashdumps/<name>") -- a plain 2-way split on the first slash used to swallow anything
      // past the instanceIndex into a failed Integer.parseInt, breaking any sub-path entirely.
      String[] parts = tail.split("/", 3);
      if (parts.length < 2) {
        respond(exchange, 400, "expected /logs/instances/{deploymentName}/{instanceIndex}[/...]");
        return;
      }
      String deploymentName = parts[0];
      if (!DEPLOYMENT_NAME.matcher(deploymentName).matches()) {
        respond(exchange, 400, "invalid deploymentName: " + deploymentName);
        return;
      }
      int instanceIndex;
      try {
        instanceIndex = Integer.parseInt(parts[1]);
      } catch (NumberFormatException e) {
        respond(exchange, 400, "invalid instanceIndex");
        return;
      }
      String subPath = parts.length == 3 ? parts[2] : null;

      // AgentMain's per-worker key (workers/<key>/), vs. InstanceSiftingFileAppender's own
      // dash-joined key for the sifted file itself (see gimle-core's InstanceSiftingFileAppender)
      // -- both derived from the same deploymentName/instanceIndex, but not the same separator.
      String workerKey = deploymentName + "#" + instanceIndex;
      Path workerLogRoot = logRoot.resolve("workers").resolve(workerKey);

      if (subPath != null && subPath.startsWith("crashdumps")) {
        handleCrashDumps(exchange, workerLogRoot, subPath);
        return;
      }

      Map<String, String> query = parseQuery(exchange);
      String category = query.getOrDefault("category", "APPLICATION");
      boolean follow = "true".equals(query.get("follow"));
      String cursor = query.get("cursor");
      int limit = parseLimit(query.get("limit"));

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
      if (!isWithinRoot(file, workerLogRoot)) {
        respond(exchange, 400, "invalid log path");
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

  /**
   * Defense-in-depth alongside the {@code DEPLOYMENT_NAME} allow-list above: guards against a
   * symlink inside {@code root} pointing outside it, the same real-path discipline {@code
   * SpaStaticHandler.isWithinRoot} applies for static console files. {@code candidate} may not
   * exist yet (a brand-new instance with no log lines written), in which case only the lexical
   * check applies -- there is nothing on disk yet for a real-path check to see through.
   */
  private static boolean isWithinRoot(Path candidate, Path root) {
    Path normalized = candidate.normalize();
    if (!normalized.startsWith(root.normalize())) {
      return false;
    }
    if (!Files.isRegularFile(normalized)) {
      return true;
    }
    try {
      return normalized.toRealPath().startsWith(root.toRealPath());
    } catch (IOException e) {
      return false;
    }
  }

  // ---- /logs/instances/{deploymentName}/{instanceIndex}/crashdumps[/{name}] ----

  /**
   * {@code hs_err_pid*.log} crash dumps are inherently a directory listing, not an appendable
   * stream like every other log category here -- a JVM writes one on a native crash (see {@code
   * AgentMain}'s {@code -XX:ErrorFile=} flag pointing these at this same {@code workerLogRoot}),
   * independent of anything going through Logback. Whole-file fetch, not tail/cursor, matching that
   * shape rather than forcing it through {@link LogFileReader}.
   */
  private static final Pattern CRASH_DUMP_FILENAME = Pattern.compile("hs_err_pid\\d+\\.log");

  // getFileName() can only return null for a zero-element path, which never happens for a Path
  // yielded by Files.list() on a real directory -- centralizing the null-guard here, rather than
  // repeating it at each of this class's several getFileName().toString() call sites, is what
  // actually satisfies it.
  private static String fileNameString(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      throw new IllegalStateException("expected a regular file, got " + path);
    }
    return fileName.toString();
  }

  private void handleCrashDumps(HttpExchange exchange, Path workerLogRoot, String subPath)
      throws IOException {
    if (subPath.equals("crashdumps")) {
      listCrashDumps(exchange, workerLogRoot);
      return;
    }
    if (subPath.startsWith("crashdumps/")) {
      fetchCrashDump(exchange, workerLogRoot, subPath.substring("crashdumps/".length()));
      return;
    }
    respond(exchange, 400, "unknown instance logs sub-path: " + subPath);
  }

  private static void listCrashDumps(HttpExchange exchange, Path workerLogRoot) throws IOException {
    List<Map<String, Object>> dumps = new ArrayList<>();
    if (Files.isDirectory(workerLogRoot)) {
      try (var files = Files.list(workerLogRoot)) {
        for (Path file :
            files
                .filter(p -> CRASH_DUMP_FILENAME.matcher(fileNameString(p)).matches())
                .sorted()
                .toList()) {
          Map<String, Object> entry = new LinkedHashMap<>();
          entry.put("name", fileNameString(file));
          entry.put("sizeBytes", Files.size(file));
          entry.put("lastModified", Files.getLastModifiedTime(file).toInstant().toString());
          dumps.add(entry);
        }
      }
    }
    respondJson(exchange, 200, dumps);
  }

  /**
   * {@code name} is validated against the exact expected pattern before touching the filesystem --
   * the same path-traversal discipline {@code SpaStaticHandler} applies for static files, here as a
   * strict allow-list regex rather than a real-path containment check.
   */
  private static void fetchCrashDump(HttpExchange exchange, Path workerLogRoot, String name)
      throws IOException {
    if (!CRASH_DUMP_FILENAME.matcher(name).matches()) {
      respond(exchange, 400, "invalid crash dump filename: " + name);
      return;
    }
    Path file = workerLogRoot.resolve(name);
    if (!Files.isRegularFile(file)) {
      respond(exchange, 404, "no such crash dump: " + name);
      return;
    }
    byte[] content = Files.readAllBytes(file);
    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(200, content.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(content);
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
