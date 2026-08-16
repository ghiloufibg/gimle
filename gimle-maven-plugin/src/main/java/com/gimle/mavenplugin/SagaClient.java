package com.gimle.mavenplugin;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.apache.maven.plugin.MojoExecutionException;

/**
 * Thin HTTP client for a Saga test-report server's own surface ({@code /api/health}, {@code
 * /api/ingest}, {@code /api/import}, {@code /api/shutdown}). Deliberately talks only HTTP -- this
 * plugin never links against the server's own module, so the two ship and evolve independently and
 * the plugin works against any already-running server build.
 */
final class SagaClient {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final String endpoint;
  private final HttpClient http;

  SagaClient(String endpoint) {
    this.endpoint =
        endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  String endpoint() {
    return endpoint;
  }

  /** Never throws: an unreachable or unhappy server is simply "not healthy". */
  boolean isHealthy() {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint + "/api/health"))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
    try {
      return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Posts one NDJSON event line to {@code /api/ingest}. */
  void ingest(String eventLine) throws MojoExecutionException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint + "/api/ingest"))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/x-ndjson")
            .POST(HttpRequest.BodyPublishers.ofString(eventLine + "\n", StandardCharsets.UTF_8))
            .build();
    sendExpecting2xx(request, "event ingest");
  }

  /** Posts one surefire report XML file to {@code /api/import} under {@code runId}. */
  void importReport(String runId, Path reportXml) throws MojoExecutionException {
    String query = "runId=" + URLEncoder.encode(runId, StandardCharsets.UTF_8);
    String module = moduleNameFor(reportXml);
    if (module != null) {
      query += "&module=" + URLEncoder.encode(module, StandardCharsets.UTF_8);
    }
    URI uri = URI.create(endpoint + "/api/import?" + query);
    HttpRequest.BodyPublisher body;
    try {
      body = HttpRequest.BodyPublishers.ofFile(reportXml);
    } catch (FileNotFoundException e) {
      throw new MojoExecutionException("report file disappeared before import: " + reportXml, e);
    }
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/xml")
            .POST(body)
            .build();
    sendExpecting2xx(request, "import of " + reportXml);
  }

  /** Best-effort {@code POST /api/shutdown}; false when unreachable or not implemented. */
  boolean shutdown() {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint + "/api/shutdown"))
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    try {
      int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
      return status / 100 == 2;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private void sendExpecting2xx(HttpRequest request, String what) throws MojoExecutionException {
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new MojoExecutionException(what + " failed against " + endpoint, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MojoExecutionException(what + " interrupted", e);
    }
    if (response.statusCode() / 100 != 2) {
      throw new MojoExecutionException(
          what + " failed: HTTP " + response.statusCode() + " from " + endpoint);
    }
  }

  /**
   * The Maven module a report belongs to, read from its path (the segment before {@code target}) --
   * the server receives only the XML body, which carries fully-qualified classnames but no module,
   * and the module is the first segment of every testId. Null when the path has no {@code target}
   * segment; the server then falls back to its own default.
   */
  private static String moduleNameFor(Path reportXml) {
    Path absolute = reportXml.toAbsolutePath();
    for (int i = 1; i < absolute.getNameCount(); i++) {
      if ("target".equals(absolute.getName(i).toString())) {
        return absolute.getName(i - 1).toString();
      }
    }
    return null;
  }
}
