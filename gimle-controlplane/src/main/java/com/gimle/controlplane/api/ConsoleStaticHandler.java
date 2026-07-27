package com.gimle.controlplane.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves a built single-page-app's static output under whatever context path it's registered on
 * (design doc §6): a real file under {@code staticRoot} is streamed back with a guessed content
 * type; anything else falls back to {@code shellFileName} with {@code 200} so the SPA's own
 * client-side router handles the path -- the standard SPA-fallback pattern, needed because {@code
 * com.sun.net.httpserver.SimpleFileServer}'s handler has no hook to intercept its own 404s with a
 * fallback.
 */
final class ConsoleStaticHandler implements HttpHandler {

  private final Path staticRoot;
  private final Path shellFile;

  ConsoleStaticHandler(Path staticRoot, String shellFileName) throws IOException {
    this.staticRoot = staticRoot.toRealPath();
    this.shellFile = this.staticRoot.resolve(shellFileName);
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      String requestPath =
          URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);
      String relative = requestPath.substring(exchange.getHttpContext().getPath().length());
      while (relative.startsWith("/")) {
        relative = relative.substring(1);
      }
      Path resolved = relative.isEmpty() ? staticRoot : staticRoot.resolve(relative).normalize();
      if (!resolved.startsWith(staticRoot)) {
        respond(
            exchange,
            400,
            "text/plain; charset=utf-8",
            "invalid path".getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (Files.isRegularFile(resolved) && isWithinRoot(resolved)) {
        String contentType = Files.probeContentType(resolved);
        respond(
            exchange,
            200,
            contentType != null ? contentType : "application/octet-stream",
            Files.readAllBytes(resolved));
        return;
      }
      if (Files.isRegularFile(shellFile)) {
        respond(exchange, 200, "text/html; charset=utf-8", Files.readAllBytes(shellFile));
        return;
      }
      respond(
          exchange, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
    } finally {
      exchange.close();
    }
  }

  /**
   * Guards against a symlink inside {@code staticRoot} pointing outside it: {@code resolved}
   * already passed the lexical {@code normalize()}/{@code startsWith} check above, but that check
   * can't see through a symlink -- only a real-path comparison can (audit finding F-02, third pass).
   */
  private boolean isWithinRoot(Path candidate) {
    try {
      return candidate.toRealPath().startsWith(staticRoot);
    } catch (IOException e) {
      return false;
    }
  }

  private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
