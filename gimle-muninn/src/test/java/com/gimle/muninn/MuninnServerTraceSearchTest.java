package com.gimle.muninn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The cross-process {@code /traces-by-id/{traceId}} search: the one route that can answer "show me
 * every span of this trace" without the caller having to name, and then page backwards through,
 * every process that might hold one.
 */
class MuninnServerTraceSearchTest extends AbstractIngestTest {

  private static final String HUNTED_TRACE = "0af7651916cd43dd8448eb211c80319c";
  private static final String OTHER_TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

  private static String spanLine(
      String timestamp, String traceId, String spanId, String parentSpanId, String name) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("timestamp", timestamp);
    line.put("traceId", traceId);
    line.put("spanId", spanId);
    line.put("parentSpanId", parentSpanId);
    line.put("name", name);
    line.put("kind", parentSpanId.isEmpty() ? "CLIENT" : "SERVER");
    line.put("status", "OK");
    return Json.write(line);
  }

  private static String path(String processId) {
    return "WORKER/" + URLEncoder.encode(processId, StandardCharsets.UTF_8);
  }

  /**
   * The exact request the control-plane proxy issues on a caller's behalf: it forwards neither the
   * {@code cursor} nor the {@code limit} a paging client sends, so this is the whole window any
   * such client can actually see of one process's history.
   */
  private List<Map<String, Object>> defaultProcessReadOf(String processId) throws Exception {
    HttpResponse<String> response = get("/traces/" + path(processId));
    assertEquals(200, response.statusCode());
    return Json.asObjectList(Json.asObject(Json.parse(response.body())).get("lines"));
  }

  private List<Map<String, Object>> searchFor(String traceId) throws Exception {
    HttpResponse<String> response = get("/traces-by-id/" + traceId);
    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertEquals(traceId, body.get("traceId"));
    return Json.asObjectList(body.get("spans"));
  }

  /**
   * A busy worker pushes an older span out of every window a per-process read can offer, so the
   * span is provably still stored and still unreachable that way -- which reads from the outside
   * exactly like a span that was never created.
   */
  @Test
  @Timeout(30)
  void a_span_a_busy_worker_has_paged_past_is_still_found_by_trace_id() throws Exception {
    // Deliberately carries both a colon and an underscore: the on-disk directory name has to
    // escape the colon (Windows reserves it), and this asserts the escape is reversible rather
    // than collapsing onto a character a processId may legitimately contain.
    String processId = "node_a:worker-1";
    Instant base = Instant.parse("2026-08-10T10:00:00Z");
    StringBuilder ndjson = new StringBuilder();
    ndjson.append(
        spanLine(base.toString(), HUNTED_TRACE, "b7ad6b7169203331", "", "Greeter#greet") + "\n");
    for (int i = 1; i <= 400; i++) {
      ndjson.append(
          spanLine(
                  base.plusMillis(i).toString(),
                  OTHER_TRACE,
                  String.format("%016x", i),
                  "",
                  "Noise#call")
              + "\n");
    }
    assertEquals(200, post("/ingest/traces/" + path(processId), ndjson.toString()).statusCode());

    List<Map<String, Object>> visible = defaultProcessReadOf(processId);
    assertFalse(
        visible.stream().anyMatch(line -> HUNTED_TRACE.equals(line.get("traceId"))),
        "the span should already be out of reach of a per-process read for this test to mean anything");

    List<Map<String, Object>> found = searchFor(HUNTED_TRACE);
    assertEquals(1, found.size());
    assertEquals("WORKER", found.get(0).get("processKind"));
    assertEquals(processId, found.get(0).get("processId"));
    assertEquals("Greeter#greet", Json.asObject(found.get(0).get("span")).get("name"));
  }

  /**
   * The per-process route does page correctly when it is actually given a cursor -- so a caller
   * that reaches this store's history through something that drops the cursor loses the span here,
   * not in the store. Pinned so a change to either paging or the search can be told apart.
   */
  @Test
  @Timeout(30)
  void paging_back_with_the_cursor_reaches_the_span_the_first_page_could_not() throws Exception {
    String processId = "node-a:worker-1";
    Instant base = Instant.parse("2026-08-10T10:00:00Z");
    StringBuilder ndjson = new StringBuilder();
    ndjson.append(
        spanLine(base.toString(), HUNTED_TRACE, "b7ad6b7169203331", "", "Greeter#greet") + "\n");
    for (int i = 1; i <= 400; i++) {
      ndjson.append(
          spanLine(
                  base.plusMillis(i).toString(),
                  OTHER_TRACE,
                  String.format("%016x", i),
                  "",
                  "Noise#call")
              + "\n");
    }
    assertEquals(200, post("/ingest/traces/" + path(processId), ndjson.toString()).statusCode());

    String cursor = null;
    boolean reached = false;
    for (int page = 0; page < 5 && !reached; page++) {
      HttpResponse<String> response =
          get(
              "/traces/"
                  + path(processId)
                  + "?limit=200"
                  + (cursor == null ? "" : "&cursor=" + cursor));
      assertEquals(200, response.statusCode());
      Map<String, Object> body = Json.asObject(Json.parse(response.body()));
      reached =
          Json.asObjectList(body.get("lines")).stream()
              .anyMatch(line -> HUNTED_TRACE.equals(line.get("traceId")));
      Object olderCursor = body.get("olderCursor");
      if (olderCursor == null) {
        break;
      }
      cursor = String.valueOf(olderCursor);
    }
    assertTrue(reached, "a cursor-driven backward walk should reach the span");
  }

  /** Both hops of a cross-worker call, in one request, without naming either worker. */
  @Test
  @Timeout(30)
  void both_hops_of_a_cross_worker_call_come_back_from_one_search() throws Exception {
    assertEquals(
        200,
        post(
                "/ingest/traces/" + path("node-a:worker-1"),
                spanLine(
                        "2026-08-10T10:00:00Z",
                        HUNTED_TRACE,
                        "aaaaaaaaaaaaaaaa",
                        "",
                        "Greeter#greet")
                    + "\n")
            .statusCode());
    assertEquals(
        200,
        post(
                "/ingest/traces/" + path("node-b:worker-2"),
                spanLine(
                        "2026-08-10T10:00:01Z",
                        HUNTED_TRACE,
                        "bbbbbbbbbbbbbbbb",
                        "aaaaaaaaaaaaaaaa",
                        "Greeter#greet")
                    + "\n")
            .statusCode());

    List<Map<String, Object>> found = searchFor(HUNTED_TRACE);

    List<String> processIds = new ArrayList<>();
    for (Map<String, Object> entry : found) {
      processIds.add(String.valueOf(entry.get("processId")));
    }
    assertEquals(List.of("node-a:worker-1", "node-b:worker-2"), processIds);
    assertEquals("CLIENT", Json.asObject(found.get(0).get("span")).get("kind"));
    assertEquals("SERVER", Json.asObject(found.get(1).get("span")).get("kind"));
    assertEquals(
        Json.asObject(found.get(0).get("span")).get("spanId"),
        Json.asObject(found.get(1).get("span")).get("parentSpanId"));
  }

  @Test
  @Timeout(30)
  void a_trace_nothing_ever_shipped_a_span_for_comes_back_empty_rather_than_missing()
      throws Exception {
    assertTrue(searchFor(HUNTED_TRACE).isEmpty());
  }

  @Test
  @Timeout(30)
  void a_trace_id_that_is_not_32_hex_characters_is_rejected_before_touching_the_filesystem()
      throws Exception {
    assertEquals(400, get("/traces-by-id/not-a-trace-id").statusCode());
    assertEquals(400, get("/traces-by-id/..%2F..%2Fetc").statusCode());
  }

  @Test
  @Timeout(30)
  void a_search_that_hits_its_limit_says_so_rather_than_quietly_returning_a_slice()
      throws Exception {
    String ndjson =
        spanLine("2026-08-10T10:00:00Z", HUNTED_TRACE, "aaaaaaaaaaaaaaaa", "", "one")
            + "\n"
            + spanLine("2026-08-10T10:00:01Z", HUNTED_TRACE, "bbbbbbbbbbbbbbbb", "", "two")
            + "\n";
    assertEquals(200, post("/ingest/traces/" + path("node-a:worker-1"), ndjson).statusCode());

    Map<String, Object> body =
        Json.asObject(Json.parse(get("/traces-by-id/" + HUNTED_TRACE + "?limit=1").body()));
    assertEquals(1, Json.asObjectList(body.get("spans")).size());
    assertEquals(Boolean.TRUE, body.get("truncated"));

    Map<String, Object> whole =
        Json.asObject(Json.parse(get("/traces-by-id/" + HUNTED_TRACE).body()));
    assertEquals(2, Json.asObjectList(whole.get("spans")).size());
    assertEquals(Boolean.FALSE, whole.get("truncated"));
  }
}
