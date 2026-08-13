package com.gimle.muninn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.muninn.testsupport.InProcessStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Real inbound HTTP traffic against a real {@link MuninnServer} in plaintext mode (the default),
 * exercising the {@code /ingest/traces/*} and {@code /traces/*} routes -- mirrors {@code
 * MuninnServerMetricsIngestTest}'s own shape, since both ride the identical {@link
 * MuninnDayFileStore}-backed ingest/read plumbing.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-muninn-server-http")
class MuninnServerTracesIngestTest {

  @TempDir Path tempDir;

  private InProcessStore store;
  private MuninnServer server;
  private final HttpClient client = HttpClient.newHttpClient();
  private String baseUrl;

  @BeforeEach
  void setUp() throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
    server = new MuninnServer(store.client(), 0, tempDir.resolve("data"));
    server.start();
    baseUrl = "http://127.0.0.1:" + server.port();
  }

  @AfterEach
  void tearDown() {
    server.close();
    store.close();
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Content-Type", "application/x-ndjson")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  @Timeout(10)
  void an_ingested_span_line_round_trips_with_attributes_intact() throws Exception {
    String spanLine =
        Json.write(
            Map.of(
                "timestamp", "2026-08-10T10:00:00Z",
                "traceId", "0af7651916cd43dd8448eb211c80319c",
                "spanId", "b7ad6b7169203331",
                "parentSpanId", "0000000000000000",
                "name", "do-something",
                "kind", "INTERNAL",
                "status", "OK",
                "http.method", "GET"));

    HttpResponse<String> ingestResponse =
        post("/ingest/traces/CONTROLPLANE/127.0.0.1:8080", spanLine + "\n");
    assertEquals(200, ingestResponse.statusCode());

    HttpResponse<String> readResponse = get("/traces/CONTROLPLANE/127.0.0.1:8080");
    assertEquals(200, readResponse.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(readResponse.body()));
    List<Map<String, Object>> lines = Json.asObjectList(body.get("lines"));
    assertEquals(1, lines.size());
    assertEquals("do-something", lines.get(0).get("name"));
    assertEquals("GET", lines.get(0).get("http.method"));
  }

  @Test
  @Timeout(10)
  void a_malformed_traces_batch_is_rejected_entirely() throws Exception {
    String validLine =
        Json.write(Map.of("timestamp", "2026-08-10T10:00:00Z", "name", "ok", "traceId", "t1"));
    String malformedLine = Json.write(Map.of("name", "no timestamp"));
    String ndjson = validLine + "\n" + malformedLine + "\n";

    HttpResponse<String> ingestResponse = post("/ingest/traces/AGENT/node-a", ndjson);
    assertEquals(400, ingestResponse.statusCode());

    HttpResponse<String> readResponse = get("/traces/AGENT/node-a");
    Map<String, Object> body = Json.asObject(Json.parse(readResponse.body()));
    assertTrue(Json.asObjectList(body.get("lines")).isEmpty());
  }

  @Test
  @Timeout(10)
  void an_invalid_process_kind_path_segment_is_rejected_before_touching_the_filesystem()
      throws Exception {
    HttpResponse<String> response = get("/traces/..%2F..%2Fetc/node-a");
    assertEquals(400, response.statusCode());
  }
}
