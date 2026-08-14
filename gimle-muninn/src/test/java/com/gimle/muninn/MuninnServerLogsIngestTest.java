package com.gimle.muninn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real inbound HTTP traffic against a real {@link MuninnServer} in plaintext mode (the default),
 * exercising the {@code /ingest/logs/*} and {@code /logs/*} routes end to end -- see {@link
 * AbstractIngestTest} for the shared server/client scaffolding.
 */
class MuninnServerLogsIngestTest extends AbstractIngestTest {

  @Test
  @Timeout(10)
  void an_ingested_node_log_line_is_readable_back() throws Exception {
    String ndjson =
        Json.write(Map.of("timestamp", "2026-08-10T10:00:00Z", "message", "hello from node"))
            + "\n";

    HttpResponse<String> ingestResponse = post("/ingest/logs/nodes/n1/PLATFORM", ndjson);
    assertEquals(200, ingestResponse.statusCode());

    HttpResponse<String> readResponse = get("/logs/nodes/n1/PLATFORM");
    assertEquals(200, readResponse.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(readResponse.body()));
    List<Map<String, Object>> lines = Json.asObjectList(body.get("lines"));
    assertEquals(1, lines.size());
    assertEquals("hello from node", lines.get(0).get("message"));
  }

  @Test
  @Timeout(10)
  void an_ingested_instance_log_line_is_readable_back() throws Exception {
    String ndjson =
        Json.write(Map.of("timestamp", "2026-08-10T10:00:00Z", "message", "hello from instance"))
            + "\n";

    HttpResponse<String> ingestResponse =
        post("/ingest/logs/instances/greeter/0/APPLICATION", ndjson);
    assertEquals(200, ingestResponse.statusCode());

    HttpResponse<String> readResponse = get("/logs/instances/greeter/0/APPLICATION");
    assertEquals(200, readResponse.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(readResponse.body()));
    List<Map<String, Object>> lines = Json.asObjectList(body.get("lines"));
    assertEquals(1, lines.size());
    assertEquals("hello from instance", lines.get(0).get("message"));
  }

  @Test
  @Timeout(10)
  void a_malformed_batch_is_rejected_entirely_and_nothing_from_it_is_readable() throws Exception {
    String validLine = Json.write(Map.of("timestamp", "2026-08-10T10:00:00Z", "message", "ok"));
    String malformedLine = Json.write(Map.of("message", "no timestamp"));
    String ndjson = validLine + "\n" + malformedLine + "\n";

    HttpResponse<String> ingestResponse = post("/ingest/logs/nodes/n2/PLATFORM", ndjson);
    assertEquals(400, ingestResponse.statusCode());

    HttpResponse<String> readResponse = get("/logs/nodes/n2/PLATFORM");
    Map<String, Object> body = Json.asObject(Json.parse(readResponse.body()));
    assertTrue(Json.asObjectList(body.get("lines")).isEmpty());
  }

  @Test
  @Timeout(10)
  void an_invalid_node_id_path_segment_is_rejected_before_touching_the_filesystem()
      throws Exception {
    HttpResponse<String> response = get("/logs/nodes/..%2F..%2Fetc/PLATFORM");
    assertEquals(400, response.statusCode());
  }

  @Test
  @Timeout(10)
  void follow_true_is_rejected_since_muninn_only_serves_shipped_history() throws Exception {
    HttpResponse<String> response = get("/logs/nodes/n1/PLATFORM?follow=true");
    assertEquals(400, response.statusCode());
  }
}
