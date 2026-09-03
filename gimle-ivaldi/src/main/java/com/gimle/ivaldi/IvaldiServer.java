package com.gimle.ivaldi;

import com.gimle.core.io.SizeLimitedInputStream;
import com.gimle.core.protocol.Json;
import com.gimle.core.web.HttpResponses;
import com.gimle.core.web.RootRedirectHandler;
import com.gimle.core.web.SpaStaticHandler;
import com.gimle.ivaldi.blueprint.BlueprintStore;
import com.gimle.ivaldi.blueprint.BlueprintSummary;
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
 * parsers against already-rendered YAML, remote shutdown, and the bundled console SPA at {@code
 * /console} when one is on the classpath. Deliberately no authentication or TLS -- a local
 * development tool, bound to loopback by default by {@link IvaldiMain}, never one of a deployed
 * cluster's own processes.
 *
 * <p>The run-locally surface ({@code /api/runs/*}) is deliberately not implemented yet: driving a
 * real {@code hilmir up}/{@code deploy} subprocess is scoped to land once the console's own {@code
 * RunsRepository} contract is settled, rather than guessing at its shape twice.
 */
public final class IvaldiServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(IvaldiServer.class);
  private static final long MAX_BODY_BYTES = 8L * 1024 * 1024;

  private final BlueprintStore store;
  private final HttpServer server;
  private final ExecutorService executor;

  public IvaldiServer(BlueprintStore store, InetAddress address, int port) throws IOException {
    this.store = store;
    this.server = HttpServer.create(new InetSocketAddress(address, port), 0);
    server.createContext("/api/health", this::handleHealth);
    server.createContext("/api/blueprints", this::handleBlueprints);
    server.createContext("/api/validate", this::handleValidate);
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
        boolean deleted = store.delete(id);
        respond(exchange, deleted ? 200 : 404, deleted ? "deleted" : "no such blueprint: " + id);
      }
      default -> respond(exchange, 405, "method not allowed");
    }
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
