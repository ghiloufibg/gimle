package com.gimle.core.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Serves a built single-page-app's static output under whatever context path it's registered on: a
 * real file under {@code staticRoot} is streamed back with a guessed content type; anything else
 * falls back to {@code shellFileName} with {@code 200} so the SPA's own client-side router handles
 * the path -- the standard SPA-fallback pattern, needed because {@code
 * com.sun.net.httpserver.SimpleFileServer}'s handler has no hook to intercept its own 404s with a
 * fallback. Shared by every process that bundles its own console ({@code gimle-controlplane}'s
 * {@code ApiServer}, {@code gimle-fafnir}'s {@code FafnirServer}) rather than each carrying its own
 * copy of this logic.
 */
public final class SpaStaticHandler implements HttpHandler {

  /**
   * Vite's build-output directory: files under it are content-hashed assets with no client-side
   * route of their own, so a missing one is a genuine 404, never the SPA shell -- unlike every
   * other path, which might legitimately be a client-side route the router will handle once the
   * shell loads.
   */
  private static final String ASSETS_PREFIX = "assets/";

  /**
   * {@link Files#probeContentType} delegates to the platform's installed {@code FileTypeDetector}
   * chain, which returns {@code null} with no working detector -- the case on slim/{@code
   * jlink}-produced Linux runtime images, which lack the {@code /etc/mime.types} a distro package
   * would normally supply. A {@code null} content type falls back to {@code
   * application/octet-stream}, and browsers refuse to execute a {@code <script type="module">}
   * served with that MIME type -- a blank console on exactly the deployment shape this project
   * promotes. An explicit map for the handful of types Vite actually emits is deterministic on
   * every platform and image, and removes a filesystem probe from the hot path.
   */
  private static final Map<String, String> CONTENT_TYPES =
      Map.ofEntries(
          Map.entry("html", "text/html; charset=utf-8"),
          Map.entry("js", "text/javascript; charset=utf-8"),
          Map.entry("mjs", "text/javascript; charset=utf-8"),
          Map.entry("css", "text/css; charset=utf-8"),
          Map.entry("json", "application/json; charset=utf-8"),
          Map.entry("svg", "image/svg+xml"),
          Map.entry("ico", "image/x-icon"),
          Map.entry("woff2", "font/woff2"),
          Map.entry("png", "image/png"),
          Map.entry("map", "application/json; charset=utf-8"));

  private final Path staticRoot;
  private final Path shellFile;

  public SpaStaticHandler(Path staticRoot, String shellFileName) throws IOException {
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
        respond(exchange, 200, contentTypeFor(resolved), Files.readAllBytes(resolved));
        return;
      }
      if (!relative.startsWith(ASSETS_PREFIX) && Files.isRegularFile(shellFile)) {
        respond(exchange, 200, "text/html; charset=utf-8", Files.readAllBytes(shellFile));
        return;
      }
      respond(
          exchange, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
    } finally {
      exchange.close();
    }
  }

  private static String contentTypeFor(Path file) {
    Path fileName = file.getFileName();
    String name = fileName == null ? "" : fileName.toString();
    int dot = name.lastIndexOf('.');
    String extension = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
  }

  /**
   * Guards against a symlink inside {@code staticRoot} pointing outside it: {@code resolved}
   * already passed the lexical {@code normalize()}/{@code startsWith} check above, but that check
   * can't see through a symlink -- only a real-path comparison can.
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
