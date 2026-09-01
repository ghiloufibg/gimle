package com.gimle.muninn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.logging.LogFileReader;
import com.gimle.core.logging.LogFilter;
import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The control plane answers a log read from the owning node's live agent when it can and from
 * Muninn's shipped history when that node is gone. An operator's {@code level}/{@code contains}
 * query has to mean the same thing either way, or the same request returns different lines
 * depending on whether the node happens to still be alive -- so these tests run the identical lines
 * and the identical filters through both readers and assert the results match.
 */
class MuninnLogFilterParityTest extends AbstractIngestTest {

  private static final List<Map<String, Object>> SAMPLE =
      List.of(
          line("2026-08-10T10:00:00Z", "DEBUG", "cache warmed"),
          line("2026-08-10T10:00:01Z", "INFO", "agent registered with control plane"),
          line("2026-08-10T10:00:02Z", "WARN", "heartbeat delayed by 4s"),
          line("2026-08-10T10:00:03Z", "ERROR", "downstream call timed out"));

  private static Map<String, Object> line(String timestamp, String level, String message) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("timestamp", timestamp);
    line.put("level", level);
    line.put("logger", "com.gimle.agent.AgentMain");
    line.put("message", message);
    return line;
  }

  private static List<String> messagesOf(List<Map<String, Object>> lines) {
    return lines.stream().map(l -> String.valueOf(l.get("message"))).toList();
  }

  /** The live-agent side: the same NDJSON-on-disk shape {@code LogFileReader} tails on a node. */
  private Path writeNodeLogFile() {
    Path file = tempDir.resolve("agent-platform.log");
    StringBuilder text = new StringBuilder();
    for (Map<String, Object> line : SAMPLE) {
      text.append(Json.write(line)).append('\n');
    }
    try {
      Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return file;
  }

  /** The fallback side: the same lines, shipped and stored in Muninn's own day-file layout. */
  private void ingestSample(String subtreePath) throws Exception {
    StringBuilder ndjson = new StringBuilder();
    for (Map<String, Object> line : SAMPLE) {
      ndjson.append(Json.write(line)).append('\n');
    }
    HttpResponse<String> response = post("/ingest" + subtreePath, ndjson.toString());
    assertEquals(200, response.statusCode(), response.body());
  }

  private List<String> readFromMuninn(String path) throws Exception {
    HttpResponse<String> response = get(path);
    assertEquals(200, response.statusCode(), response.body());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    return messagesOf(Json.asObjectList(body.get("lines")));
  }

  private List<String> readFromLiveAgentFile(LogFilter filter) {
    return messagesOf(LogFileReader.readOlder(writeNodeLogFile(), 1, null, 200, filter).lines());
  }

  @Test
  @Timeout(20)
  void a_level_threshold_returns_the_same_lines_from_shipped_history_as_from_a_live_agent()
      throws Exception {
    ingestSample("/logs/nodes/n1/PLATFORM");

    assertEquals(
        readFromLiveAgentFile(LogFilter.of("WARN", null)),
        readFromMuninn("/logs/nodes/n1/PLATFORM?level=WARN"));
    assertEquals(
        List.of("heartbeat delayed by 4s", "downstream call timed out"),
        readFromMuninn("/logs/nodes/n1/PLATFORM?level=WARN"));
  }

  @Test
  @Timeout(20)
  void a_text_filter_returns_the_same_lines_from_shipped_history_as_from_a_live_agent()
      throws Exception {
    ingestSample("/logs/nodes/n1/PLATFORM");

    assertEquals(
        readFromLiveAgentFile(LogFilter.of(null, "TIMED OUT")),
        readFromMuninn(
            "/logs/nodes/n1/PLATFORM?contains="
                + URLEncoder.encode("TIMED OUT", StandardCharsets.UTF_8)));
  }

  @Test
  @Timeout(20)
  void both_filters_together_with_the_since_cursor_agree_across_both_readers() throws Exception {
    ingestSample("/logs/instances/greeter/0/APPLICATION");

    List<String> viaMuninn =
        readFromMuninn(
            "/logs/instances/greeter/0/APPLICATION"
                + "?since=2026-08-10T10:00:01Z&level=WARN&contains=heartbeat");
    List<String> viaAgent =
        messagesOf(
            LogFileReader.readAfter(
                writeNodeLogFile(), 1, "2026-08-10T10:00:01Z", LogFilter.of("WARN", "heartbeat")));

    assertEquals(viaAgent, viaMuninn);
    assertEquals(List.of("heartbeat delayed by 4s"), viaMuninn);
  }

  @Test
  @Timeout(20)
  void a_zero_match_filter_yields_an_empty_page_on_both_sides_rather_than_an_error()
      throws Exception {
    ingestSample("/logs/nodes/n1/PLATFORM");

    HttpResponse<String> response =
        get(
            "/logs/nodes/n1/PLATFORM?level=ERROR&contains="
                + URLEncoder.encode("no such text anywhere", StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertTrue(Json.asObjectList(body.get("lines")).isEmpty());
    assertTrue(body.containsKey("olderCursor"));
    assertTrue(body.containsKey("newerCursor"));
    assertTrue(
        readFromLiveAgentFile(LogFilter.of("ERROR", "no such text anywhere")).isEmpty(),
        "the live-agent reader must be just as empty for the same query");
  }

  @Test
  @Timeout(20)
  void an_unrecognized_level_is_rejected_here_the_same_way_the_agent_rejects_it() throws Exception {
    ingestSample("/logs/nodes/n1/PLATFORM");

    HttpResponse<String> response = get("/logs/nodes/n1/PLATFORM?level=SEVERE");

    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains("SEVERE"), response.body());
  }

  @Test
  @Timeout(20)
  void a_metrics_read_ignores_the_log_filter_parameters_entirely() throws Exception {
    // Metrics lines carry no level and no human-readable message; a level threshold applied to
    // them would silently return nothing, so those routes never build a filter from the query.
    String ndjson =
        Json.write(Map.of("timestamp", "2026-08-10T10:00:00Z", "name", "http.requests", "value", 7))
            + "\n";
    assertEquals(200, post("/ingest/metrics/CONTROLPLANE/127.0.0.1:8080", ndjson).statusCode());

    HttpResponse<String> response = get("/metrics/CONTROLPLANE/127.0.0.1:8080?level=ERROR");

    assertEquals(200, response.statusCode(), response.body());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertEquals(1, Json.asObjectList(body.get("lines")).size());
  }
}
