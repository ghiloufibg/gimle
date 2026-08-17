package com.gimle.saga;

import com.gimle.core.exception.GimleCodecException;
import com.gimle.core.io.SizeLimitedInputStream;
import com.gimle.core.protocol.Json;
import com.gimle.core.saga.SagaEvent;
import com.gimle.core.saga.SagaEventCodec;
import com.gimle.core.web.HttpResponses;
import com.gimle.core.web.SpaStaticHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saga's HTTP surface: NDJSON ingest ({@code POST /api/ingest}), Surefire XML import ({@code POST
 * /api/import}), and JSON reads over the store -- runs, per-run event streams (with an {@code
 * AgentLogServer}-style chunked NDJSON live tail on {@code follow=true}), the flaky scoreboard, and
 * per-test history -- plus the bundled console SPA at {@code /console} when one is on the
 * classpath. Deliberately no authentication or TLS: this is a local development tool, bound to
 * loopback by default by {@link SagaMain}, never one of a deployed cluster's own processes.
 */
public final class SagaServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SagaServer.class);
  private static final Duration FOLLOW_POLL_INTERVAL = Duration.ofMillis(200);
  private static final int DEFAULT_RUNS_LIMIT = 50;
  private static final int DEFAULT_FLAKY_WINDOW_DAYS = 30;
  private static final int DEFAULT_FLAKE_BUDGET_ALLOWANCE = 120;

  private final SagaStore store;
  private final HttpServer server;
  private final int flakeBudgetAllowance;

  public SagaServer(SagaStore store, InetAddress address, int port) throws IOException {
    this(store, address, port, DEFAULT_FLAKE_BUDGET_ALLOWANCE);
  }

  public SagaServer(SagaStore store, InetAddress address, int port, int flakeBudgetAllowance)
      throws IOException {
    this.store = store;
    this.flakeBudgetAllowance = flakeBudgetAllowance;
    this.server = HttpServer.create(new InetSocketAddress(address, port), 0);
    server.createContext("/api/health", this::handleHealth);
    server.createContext("/api/ingest", this::handleIngest);
    server.createContext("/api/import", this::handleImport);
    server.createContext("/api/runs", this::handleRuns);
    server.createContext("/api/flaky", this::handleFlaky);
    server.createContext("/api/tests/", this::handleTestHistory);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
  }

  /** Registers the bundled console SPA (see {@code gimle-saga-console}) at {@code /console}. */
  public void serveConsole(Path staticRoot) throws IOException {
    server.createContext("/console", new SpaStaticHandler(staticRoot, "index.html"));
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  // ---- POST /api/ingest ----

  private void handleIngest(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      String body = readBody(exchange);
      List<SagaEvent> events = new ArrayList<>();
      int lineNumber = 0;
      for (String line : body.split("\n", -1)) {
        lineNumber++;
        if (line.isBlank()) {
          continue;
        }
        try {
          events.add(SagaEventCodec.decode(line.strip()));
        } catch (GimleCodecException e) {
          respond(exchange, 400, "malformed event on line " + lineNumber + ": " + e.getMessage());
          return;
        }
      }
      List<String> runIds = store.ingest(events);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("ingested", events.size());
      result.put("runIds", runIds);
      respondJson(exchange, 200, result);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (BodyTooLargeException e) {
      respondQuietly(exchange, 413, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("ingest request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  // ---- POST /api/import ----

  private void handleImport(HttpExchange exchange) {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      Map<String, String> query = parseQuery(exchange);
      String explicitRunId = query.get("runId");
      String body = readBody(exchange);
      SurefireXmlImporter.ImportResult imported;
      if (body.stripLeading().startsWith("<")) {
        imported = SurefireXmlImporter.fromXmlDocument(body, explicitRunId, query.get("module"));
      } else {
        Object parsed = Json.parse(body);
        if (!(parsed instanceof Map<?, ?>)
            || !(Json.asObject(parsed).get("paths") instanceof List<?> rawPaths)) {
          throw new IllegalArgumentException(
              "import body must be a Surefire XML document or {\"paths\":[...]}");
        }
        List<Path> paths = rawPaths.stream().map(p -> Path.of(String.valueOf(p))).toList();
        imported = SurefireXmlImporter.fromPaths(paths, explicitRunId);
      }
      int appended =
          explicitRunId == null
              ? store.ingest(imported.events()).isEmpty() ? 0 : imported.events().size()
              : store.fold(imported.runId(), imported.events());
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("runId", imported.runId());
      result.put("events", appended);
      respondJson(exchange, 200, result);
    } catch (IllegalArgumentException | GimleCodecException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (BodyTooLargeException e) {
      respondQuietly(exchange, 413, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("import request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
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

  // ---- GET /api/runs, /api/runs/{id}, /api/runs/{id}/events ----

  private void handleRuns(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      String tail = exchange.getRequestURI().getPath().substring("/api/runs".length());
      Map<String, String> query = parseQuery(exchange);
      if (tail.isEmpty() || "/".equals(tail)) {
        int limit = parseIntOr(query.get("limit"), DEFAULT_RUNS_LIMIT);
        respondJson(exchange, 200, store.listRuns(limit).stream().map(RunMeta::toJsonMap).toList());
        return;
      }
      String[] parts = tail.substring(1).split("/", 2);
      String runId = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      if (parts.length == 1) {
        Optional<RunMeta> meta = store.run(runId);
        if (meta.isEmpty()) {
          respond(exchange, 404, "no such run: " + runId);
          return;
        }
        respondJson(exchange, 200, meta.get().toJsonMap());
        return;
      }
      if (!"events".equals(parts[1])) {
        respond(exchange, 404, "unknown runs sub-path: " + parts[1]);
        return;
      }
      if (store.run(runId).isEmpty()) {
        respond(exchange, 404, "no such run: " + runId);
        return;
      }
      long cursor = parseLongOr(query.get("cursor"), 0L);
      if ("true".equals(query.get("follow"))) {
        streamFollow(exchange, runId, cursor);
        return;
      }
      SagaStore.EventsPage page = store.readEvents(runId, cursor);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("events", page.lines().stream().map(Json::parse).toList());
      result.put("nextCursor", page.nextCursor());
      respondJson(exchange, 200, result);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("runs request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /**
   * Chunked NDJSON live tail of one run's event stream, polling the store the same way {@code
   * AgentLogServer} tails a log file. Ends when the client disconnects, or once the run has reached
   * a terminal status and every line already on disk has been delivered -- the terminal check runs
   * only after an empty read, so the run-finished line itself (appended before the status flips) is
   * always streamed before the tail closes.
   */
  private void streamFollow(HttpExchange exchange, String runId, long cursor) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson; charset=utf-8");
    exchange.sendResponseHeaders(200, 0);
    long next = cursor;
    try (OutputStream out = exchange.getResponseBody()) {
      while (true) {
        SagaStore.EventsPage page = store.readEvents(runId, next);
        for (String line : page.lines()) {
          out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        next = page.nextCursor();
        if (!page.lines().isEmpty()) {
          out.flush();
        } else if (isTerminal(runId)) {
          return;
        }
        try {
          Thread.sleep(FOLLOW_POLL_INTERVAL);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    } catch (IOException e) {
      // The client disconnected -- the normal way a live tail on a still-live run ends.
      log.debug("event follow session ended: {}", e.getMessage());
    }
  }

  private boolean isTerminal(String runId) {
    Optional<RunMeta> meta = store.run(runId);
    return meta.isPresent() && meta.get().status() != RunMeta.RunStatus.LIVE;
  }

  // ---- GET /api/flaky ----

  private void handleFlaky(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      int windowDays = parseIntOr(parseQuery(exchange).get("window"), DEFAULT_FLAKY_WINDOW_DAYS);
      long since = System.currentTimeMillis() - Duration.ofDays(windowDays).toMillis();
      List<Map<String, Object>> entries = new ArrayList<>();
      for (SagaStore.FlakySummary summary : store.flakyScoreboard(since)) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("testId", summary.testId());
        json.put("module", summary.module());
        json.put("occurrences", summary.occurrences());
        json.put("runsSeen", summary.runsSeen());
        json.put("flakeRate", summary.flakeRate());
        json.put("score", summary.score());
        json.put("signatures", summary.signatures());
        json.put("firstSeen", summary.firstSeen());
        json.put("lastSeen", summary.lastSeen());
        json.put("quarantined", store.quarantined(summary.testId()));
        entries.add(json);
      }
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("entries", entries);
      result.put("budgetAllowance", flakeBudgetAllowance);
      respondJson(exchange, 200, result);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("flaky request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  // ---- GET /api/tests/{testId}/history ----

  private void handleTestHistory(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      String tail = exchange.getRequestURI().getPath().substring("/api/tests/".length());
      if (!tail.endsWith("/history")) {
        respond(exchange, 404, "expected /api/tests/{testId}/history");
        return;
      }
      String testId =
          URLDecoder.decode(
              tail.substring(0, tail.length() - "/history".length()), StandardCharsets.UTF_8);
      List<Map<String, Object>> result = new ArrayList<>();
      for (SagaStore.TestHistoryEntry entry : store.testHistory(testId)) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("runId", entry.runId());
        json.put("startedAtEpochMilli", entry.startedAtEpochMilli());
        json.put("outcome", entry.outcome().name());
        json.put("durationMillis", entry.durationMillis());
        json.put("attempts", entry.attempts());
        json.put("flaky", entry.flaky());
        result.add(json);
      }
      respondJson(exchange, 200, result);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("test history request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  // ---- shared plumbing ----

  private static int parseIntOr(String raw, int fallback) {
    if (raw == null) {
      return fallback;
    }
    try {
      return Math.max(1, Integer.parseInt(raw));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static long parseLongOr(String raw, long fallback) {
    if (raw == null) {
      return fallback;
    }
    try {
      return Math.max(0L, Long.parseLong(raw));
    } catch (NumberFormatException e) {
      return fallback;
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

  // Ingest/import are the most network-facing, attacker-controlled surfaces Saga exposes -- cap
  // the request body it will buffer in memory rather than reading an unbounded stream whole.
  private static final long MAX_INGEST_BODY_BYTES = 50L * 1024 * 1024;

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
            MAX_INGEST_BODY_BYTES,
            exceeded -> new BodyTooLargeException(MAX_INGEST_BODY_BYTES))) {
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

  private static void respondQuietly(HttpExchange exchange, int status, String body) {
    HttpResponses.respondQuietly(exchange, status, body);
  }
}
