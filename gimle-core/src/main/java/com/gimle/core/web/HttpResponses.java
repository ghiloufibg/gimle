package com.gimle.core.web;

import com.gimle.core.protocol.Json;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared HTTP response-writing helpers for the small {@code com.sun.net.httpserver.HttpServer}
 * based servers each Gimlé process kind runs (Fafnir, Muninn, Saga, the node agent's log/gossip
 * servers, Andvari) -- one copy of the plain-text/JSON/error-response plumbing instead of every
 * process-kind server carrying its own.
 */
public final class HttpResponses {

  private static final Logger log = LoggerFactory.getLogger(HttpResponses.class);

  private HttpResponses() {}

  public static void respond(HttpExchange exchange, int status, String body) throws IOException {
    respondBytes(
        exchange, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
  }

  public static void respondJson(HttpExchange exchange, int status, Object value)
      throws IOException {
    respondBytes(
        exchange,
        status,
        "application/json; charset=utf-8",
        Json.write(value).getBytes(StandardCharsets.UTF_8));
  }

  public static void respondBytes(
      HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  public static void respondQuietly(HttpExchange exchange, int status, String body) {
    try {
      respond(exchange, status, body);
    } catch (IOException e) {
      log.warn("failed to write error response: {}", e.getMessage());
    }
  }
}
