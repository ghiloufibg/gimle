package com.gimle.saga;

import static com.gimle.saga.testsupport.TestEvents.failed;
import static com.gimle.saga.testsupport.TestEvents.passed;
import static com.gimle.saga.testsupport.TestEvents.runFinished;
import static com.gimle.saga.testsupport.TestEvents.runStarted;
import static com.gimle.saga.testsupport.TestEvents.testStarted;
import static com.gimle.saga.testsupport.TestEvents.testStartedWithTags;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.core.saga.SagaEvent;
import com.gimle.core.saga.SagaEventCodec;
import com.gimle.core.web.BundledSpa;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real inbound HTTP traffic against a real {@link SagaServer} on an ephemeral loopback port: the
 * ingest/read round trip, the malformed-line rejection, the chunked live tail, Surefire import, and
 * the bundled-console static path.
 */
class SagaServerTest {

  private static final String TEST_ID = "gimle-core:JsonTest#round_trips";

  @TempDir Path tempDir;

  private SagaStore store;
  private SagaServer server;
  private final HttpClient client = HttpClient.newHttpClient();
  private String baseUrl;

  @BeforeEach
  void setUp() throws Exception {
    store = new SagaStore(tempDir);
    server = new SagaServer(store, InetAddress.getLoopbackAddress(), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.port();
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> ingest(List<SagaEvent> events) throws Exception {
    String body =
        events.stream().map(SagaEventCodec::encode).collect(Collectors.joining("\n", "", "\n"));
    return post("/api/ingest", body);
  }

  @Test
  @Timeout(10)
  void health_answers_ok() throws Exception {
    HttpResponse<String> response = get("/api/health");

    assertEquals(200, response.statusCode());
    assertEquals("ok", Json.asObject(Json.parse(response.body())).get("status"));
  }

  @Test
  @Timeout(10)
  void ingested_events_round_trip_through_the_runs_and_events_apis() throws Exception {
    HttpResponse<String> ingested =
        ingest(
            List.of(
                runStarted("run-1", 1000L),
                testStarted("run-1", TEST_ID, 1),
                passed("run-1", TEST_ID, 1, 42L),
                runFinished("run-1", 2000L, 1, 1, 0, 0)));
    assertEquals(200, ingested.statusCode());
    assertEquals(
        4, ((Number) Json.asObject(Json.parse(ingested.body())).get("ingested")).intValue());

    HttpResponse<String> runs = get("/api/runs?limit=10");
    assertEquals(200, runs.statusCode());
    List<Map<String, Object>> runList = Json.asObjectList(Json.parse(runs.body()));
    assertEquals(1, runList.size());
    assertEquals("run-1", runList.get(0).get("runId"));
    assertEquals("passed", runList.get(0).get("status"));

    HttpResponse<String> one = get("/api/runs/run-1");
    assertEquals(200, one.statusCode());
    assertEquals("run-1", Json.asObject(Json.parse(one.body())).get("runId"));

    HttpResponse<String> events = get("/api/runs/run-1/events?cursor=0");
    assertEquals(200, events.statusCode());
    Map<String, Object> page = Json.asObject(Json.parse(events.body()));
    assertEquals(4, ((Number) page.get("nextCursor")).intValue());
    assertEquals(4, Json.asObjectList(page.get("events")).size());
    assertEquals("run-started", Json.asObjectList(page.get("events")).get(0).get("type"));
  }

  @Test
  @Timeout(10)
  void a_malformed_ingest_line_is_rejected_with_its_line_number() throws Exception {
    String body = SagaEventCodec.encode(runStarted("run-bad", 1000L)) + "\n" + "this is not json\n";

    HttpResponse<String> response = post("/api/ingest", body);

    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains("line 2"));
  }

  @Test
  @Timeout(10)
  void an_unknown_run_returns_404() throws Exception {
    assertEquals(404, get("/api/runs/run-none").statusCode());
    assertEquals(404, get("/api/runs/run-none/events").statusCode());
  }

  @Test
  @Timeout(20)
  void follow_streams_new_lines_as_they_arrive_and_ends_when_the_run_finishes() throws Exception {
    ingest(List.of(runStarted("run-f", 1000L)));

    HttpResponse<java.io.InputStream> follow =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/api/runs/run-f/events?follow=true"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, follow.statusCode());
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(follow.body(), StandardCharsets.UTF_8))) {
      String first = reader.readLine();
      assertNotNull(first);
      assertTrue(first.contains("run-started"));

      ingest(List.of(testStarted("run-f", TEST_ID, 1)));
      String second = reader.readLine();
      assertNotNull(second);
      assertTrue(second.contains("test-started"));

      ingest(List.of(runFinished("run-f", 2000L, 1, 1, 0, 0)));
      String third = reader.readLine();
      assertNotNull(third);
      assertTrue(third.contains("run-finished"));

      // The run is terminal and fully delivered, so the server closes the tail.
      assertNull(reader.readLine());
    }
  }

  @Test
  @Timeout(10)
  void importing_surefire_xml_with_a_flaky_failure_lands_a_run_and_a_flake_observation()
      throws Exception {
    String xml =
        """
        <testsuite name="com.example.FlakyTest" tests="1" failures="0" errors="0" skipped="0"
                   time="0.9" timestamp="2026-08-16T09:00:00">
          <testcase name="sometimes_binds" classname="com.example.FlakyTest" time="0.9">
            <flakyFailure type="java.net.BindException" message="Address already in use">
        java.net.BindException: Address already in use
        \tat com.example.FlakyTest.sometimes_binds(FlakyTest.java:12)
            </flakyFailure>
          </testcase>
        </testsuite>
        """;

    HttpResponse<String> imported = post("/api/import?runId=import-run", xml);
    assertEquals(200, imported.statusCode());
    assertEquals("import-run", Json.asObject(Json.parse(imported.body())).get("runId"));

    Map<String, Object> run = Json.asObject(Json.parse(get("/api/runs/import-run").body()));
    assertEquals("passed", run.get("status"));
    assertEquals(1, ((Number) Json.asObject(run.get("totals")).get("flaky")).intValue());

    Map<String, Object> flaky = Json.asObject(Json.parse(get("/api/flaky?window=36500").body()));
    List<Map<String, Object>> scoreboard = Json.asObjectList(flaky.get("entries"));
    assertEquals(1, scoreboard.size());
    assertEquals("imported:FlakyTest#sometimes_binds", scoreboard.get(0).get("testId"));
    assertEquals(1, ((Number) scoreboard.get(0).get("occurrences")).intValue());
  }

  @Test
  @Timeout(10)
  void flaky_entries_carry_quarantine_status_and_the_response_carries_the_budget_allowance()
      throws Exception {
    ingest(
        List.of(
            runStarted("run-q", 1000L),
            testStartedWithTags("run-q", TEST_ID, 1, List.of("flaky")),
            failed("run-q", TEST_ID, 1, "deadbeef00000001"),
            testStartedWithTags("run-q", TEST_ID, 2, List.of("flaky")),
            passed("run-q", TEST_ID, 2, 5L),
            runFinished("run-q", 2000L, 1, 1, 0, 1)));

    Map<String, Object> flaky = Json.asObject(Json.parse(get("/api/flaky?window=36500").body()));
    assertEquals(120, ((Number) flaky.get("budgetAllowance")).intValue());
    List<Map<String, Object>> entries = Json.asObjectList(flaky.get("entries"));
    assertEquals(1, entries.size());
    assertEquals(true, entries.get(0).get("quarantined"));
  }

  @Test
  @Timeout(10)
  void the_flake_budget_allowance_is_configurable_per_server() throws Exception {
    SagaServer custom = new SagaServer(store, InetAddress.getLoopbackAddress(), 0, 5);
    custom.start();
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + custom.port() + "/api/flaky"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      Map<String, Object> flaky = Json.asObject(Json.parse(response.body()));
      assertEquals(5, ((Number) flaky.get("budgetAllowance")).intValue());
    } finally {
      custom.close();
    }
  }

  @Test
  @Timeout(10)
  void an_import_body_that_is_neither_xml_nor_a_paths_object_is_rejected() throws Exception {
    assertEquals(400, post("/api/import", "{\"paths\":[\"/no/such/dir\"]}").statusCode());
  }

  @Test
  @Timeout(10)
  void test_history_answers_per_run_outcomes_for_an_encoded_test_id() throws Exception {
    ingest(
        List.of(
            runStarted("run-h", 1000L),
            testStarted("run-h", TEST_ID, 1),
            failed("run-h", TEST_ID, 1, "deadbeef00000001"),
            testStarted("run-h", TEST_ID, 2),
            passed("run-h", TEST_ID, 2, 5L),
            runFinished("run-h", 2000L, 1, 1, 0, 1)));

    String encoded = URLEncoder.encode(TEST_ID, StandardCharsets.UTF_8);
    HttpResponse<String> response = get("/api/tests/" + encoded + "/history");

    assertEquals(200, response.statusCode());
    List<Map<String, Object>> history = Json.asObjectList(Json.parse(response.body()));
    assertEquals(1, history.size());
    assertEquals("run-h", history.get(0).get("runId"));
    assertEquals("PASSED", history.get(0).get("outcome"));
    assertEquals(true, history.get(0).get("flaky"));
    assertEquals(2, ((Number) history.get(0).get("attempts")).intValue());
  }

  @Test
  @Timeout(10)
  void the_bundled_console_is_served_at_console() throws Exception {
    Path consoleRoot =
        BundledSpa.resolve(SagaServerTest.class.getClassLoader(), "saga-console/index.html")
            .orElseThrow();
    server.serveConsole(consoleRoot);

    HttpResponse<String> response = get("/console");

    assertEquals(200, response.statusCode());
    assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
    assertTrue(response.body().toLowerCase(java.util.Locale.ROOT).contains("<!doctype html"));
  }
}
