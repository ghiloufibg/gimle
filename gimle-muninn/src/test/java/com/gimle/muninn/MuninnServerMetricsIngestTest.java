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
 * exercising the {@code /ingest/metrics/*} and {@code /metrics/*} routes (design doc Part B/O-9) --
 * mirroring {@code MuninnServerLogsIngestTest}'s own shape, since both ride the identical {@link
 * MuninnDayFileStore}-backed ingest/read plumbing.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-muninn-server-http")
class MuninnServerMetricsIngestTest {

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
  void an_ingested_counter_and_timer_batch_round_trips_with_measurements_intact() throws Exception {
    String counterLine =
        Json.write(
            Map.of(
                "timestamp", "2026-08-10T10:00:00Z",
                "name", "gimle.module.request.count",
                "type", "COUNTER",
                "tags", Map.of("module", "greeter-provider"),
                "measurements", Map.of("COUNT", 42.0)));
    String timerLine =
        Json.write(
            Map.of(
                "timestamp", "2026-08-10T10:00:01Z",
                "name", "gimle.module.request.latency",
                "type", "TIMER",
                "tags", Map.of("module", "greeter-provider"),
                "measurements", Map.of("COUNT", 3.0, "TOTAL_TIME", 0.045, "MAX", 0.02)));
    String ndjson = counterLine + "\n" + timerLine + "\n";

    HttpResponse<String> ingestResponse =
        post("/ingest/metrics/CONTROLPLANE/127.0.0.1:8080", ndjson);
    assertEquals(200, ingestResponse.statusCode());

    HttpResponse<String> readResponse = get("/metrics/CONTROLPLANE/127.0.0.1:8080");
    assertEquals(200, readResponse.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(readResponse.body()));
    List<Map<String, Object>> lines = Json.asObjectList(body.get("lines"));
    assertEquals(2, lines.size());
    assertEquals("gimle.module.request.count", lines.get(0).get("name"));
    Map<String, Object> counterMeasurements = Json.asObject(lines.get(0).get("measurements"));
    assertEquals(42.0, counterMeasurements.get("COUNT"));
    Map<String, Object> timerMeasurements = Json.asObject(lines.get(1).get("measurements"));
    assertEquals(0.045, timerMeasurements.get("TOTAL_TIME"));
  }

  @Test
  @Timeout(10)
  void a_malformed_metrics_batch_is_rejected_entirely() throws Exception {
    String validLine =
        Json.write(
            Map.of(
                "timestamp", "2026-08-10T10:00:00Z",
                "name", "gimle.ok",
                "type", "GAUGE",
                "measurements", Map.of("VALUE", 1.0)));
    String malformedLine = Json.write(Map.of("name", "no timestamp"));
    String ndjson = validLine + "\n" + malformedLine + "\n";

    HttpResponse<String> ingestResponse = post("/ingest/metrics/AGENT/node-a", ndjson);
    assertEquals(400, ingestResponse.statusCode());

    HttpResponse<String> readResponse = get("/metrics/AGENT/node-a");
    Map<String, Object> body = Json.asObject(Json.parse(readResponse.body()));
    assertTrue(Json.asObjectList(body.get("lines")).isEmpty());
  }

  @Test
  @Timeout(10)
  void an_invalid_process_kind_path_segment_is_rejected_before_touching_the_filesystem()
      throws Exception {
    HttpResponse<String> response = get("/metrics/..%2F..%2Fetc/node-a");
    assertEquals(400, response.statusCode());
  }
}
