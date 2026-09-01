package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * {@code gimle metrics-history}/{@code traces-history} against a real control plane proxying onto a
 * real Muninn -- history the CLI reads is history a shipper genuinely wrote, not a stub's canned
 * answer. The lock is a read lock on the system properties every server here consults for its
 * transport mode; this class never writes one.
 */
@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ)
class HistoryCommandTest {

  private static final String CONTROL_PLANE_PROCESS_ID = "127.0.0.1:8080";

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessCluster cluster;
  private ByteArrayOutputStream outBuffer;
  private ByteArrayOutputStream errBuffer;
  private PrintStream out;
  private PrintStream err;

  @BeforeEach
  void startCluster() {
    cluster = InProcessCluster.startWithMuninn(tempDir);
    outBuffer = new ByteArrayOutputStream();
    errBuffer = new ByteArrayOutputStream();
    out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
    err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);
  }

  @AfterEach
  void stopCluster() {
    cluster.close();
  }

  private int run(String... args) {
    return runAgainst(cluster.address(), args);
  }

  private int runAgainst(String server, String... args) {
    String[] withServer = new String[args.length + 2];
    System.arraycopy(args, 0, withServer, 0, args.length);
    withServer[args.length] = "--server";
    withServer[args.length + 1] = server;
    return GimleCli.run(withServer, out, err);
  }

  private String stdout() {
    return outBuffer.toString(StandardCharsets.UTF_8);
  }

  private String stderr() {
    return errBuffer.toString(StandardCharsets.UTF_8);
  }

  /** Ships one NDJSON batch straight into Muninn, exactly as a real process's shipper would. */
  private void ship(
      String kind, String processKind, String processId, List<Map<String, Object>> lines)
      throws Exception {
    StringBuilder body = new StringBuilder();
    for (Map<String, Object> line : lines) {
      body.append(Json.write(line)).append('\n');
    }
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://"
                                + cluster.muninnAddress()
                                + "/ingest/"
                                + kind
                                + "/"
                                + processKind
                                + "/"
                                + processId))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode(), response.body());
  }

  private static Map<String, Object> metricLine(String timestamp, String name, double count) {
    return Map.of(
        "timestamp",
        timestamp,
        "name",
        name,
        "type",
        "COUNTER",
        "tags",
        Map.of("uri", "/deployments"),
        "measurements",
        Map.of("COUNT", count));
  }

  private static Map<String, Object> spanLine(String timestamp, String traceId, String name) {
    return Map.of(
        "timestamp",
        timestamp,
        "traceId",
        traceId,
        "spanId",
        "span-" + traceId,
        "parentSpanId",
        "",
        "name",
        name,
        "kind",
        "SERVER",
        "status",
        "OK");
  }

  private List<Map<String, Object>> linesFromJson() {
    return Json.asObjectList(Json.parse(stdout()));
  }

  @Test
  void metrics_history_reads_back_what_a_shipper_wrote() throws Exception {
    ship(
        "metrics",
        "CONTROLPLANE",
        CONTROL_PLANE_PROCESS_ID,
        List.of(metricLine("2026-08-30T10:00:00Z", "api.requests", 7)));

    assertEquals(
        0,
        run("-o", "json", "metrics-history", "CONTROLPLANE", CONTROL_PLANE_PROCESS_ID),
        stderr());

    List<Map<String, Object>> lines = linesFromJson();
    assertEquals(1, lines.size(), stdout());
    assertEquals("api.requests", lines.get(0).get("name"));
  }

  @Test
  void traces_history_reads_back_what_a_shipper_wrote() throws Exception {
    ship(
        "traces",
        "CONTROLPLANE",
        CONTROL_PLANE_PROCESS_ID,
        List.of(spanLine("2026-08-30T10:00:00Z", "trace-1", "GET /deployments")));

    assertEquals(
        0, run("-o", "json", "traces-history", "CONTROLPLANE", CONTROL_PLANE_PROCESS_ID), stderr());

    List<Map<String, Object>> lines = linesFromJson();
    assertEquals(1, lines.size(), stdout());
    assertEquals("trace-1", lines.get(0).get("traceId"));
  }

  @Test
  void a_worker_process_id_is_the_node_and_worker_pair() throws Exception {
    ship(
        "metrics",
        "WORKER",
        "node-a:worker-3",
        List.of(metricLine("2026-08-30T10:00:00Z", "module.invocations", 3)));

    assertEquals(0, run("-o", "json", "metrics-history", "WORKER", "node-a:worker-3"), stderr());

    assertEquals("module.invocations", linesFromJson().get(0).get("name"));
  }

  @Test
  void since_returns_only_what_arrived_after_that_cursor() throws Exception {
    ship(
        "metrics",
        "CONTROLPLANE",
        CONTROL_PLANE_PROCESS_ID,
        List.of(
            metricLine("2026-08-30T10:00:00Z", "older", 1),
            metricLine("2026-08-30T11:00:00Z", "newer", 2)));

    assertEquals(
        0,
        run(
            "-o",
            "json",
            "metrics-history",
            "CONTROLPLANE",
            CONTROL_PLANE_PROCESS_ID,
            "--since",
            "2026-08-30T10:30:00Z"),
        stderr());

    List<Map<String, Object>> lines = linesFromJson();
    assertEquals(1, lines.size(), stdout());
    assertEquals("newer", lines.get(0).get("name"));
  }

  /** The proxy forwards only {@code since}, so {@code --limit} keeps the newest N here instead. */
  @Test
  void limit_keeps_the_most_recent_lines() throws Exception {
    ship(
        "metrics",
        "CONTROLPLANE",
        CONTROL_PLANE_PROCESS_ID,
        List.of(
            metricLine("2026-08-30T10:00:00Z", "oldest", 1),
            metricLine("2026-08-30T11:00:00Z", "middle", 2),
            metricLine("2026-08-30T12:00:00Z", "newest", 3)));

    assertEquals(
        0,
        run(
            "-o",
            "json",
            "metrics-history",
            "CONTROLPLANE",
            CONTROL_PLANE_PROCESS_ID,
            "--limit",
            "2"),
        stderr());

    List<Map<String, Object>> lines = linesFromJson();
    assertEquals(2, lines.size(), stdout());
    assertEquals("middle", lines.get(0).get("name"));
    assertEquals("newest", lines.get(1).get("name"));
  }

  @Test
  void the_table_output_names_the_cursor_to_resume_from() throws Exception {
    ship(
        "metrics",
        "CONTROLPLANE",
        CONTROL_PLANE_PROCESS_ID,
        List.of(metricLine("2026-08-30T10:00:00Z", "api.requests", 7)));

    assertEquals(0, run("metrics-history", "CONTROLPLANE", CONTROL_PLANE_PROCESS_ID), stderr());

    assertTrue(stdout().contains("resume with --since 2026-08-30T10:00:00Z"), stdout());
  }

  @Test
  void json_output_stays_a_single_parseable_document() throws Exception {
    ship(
        "metrics",
        "CONTROLPLANE",
        CONTROL_PLANE_PROCESS_ID,
        List.of(metricLine("2026-08-30T10:00:00Z", "api.requests", 7)));

    assertEquals(
        0,
        run("-o", "json", "metrics-history", "CONTROLPLANE", CONTROL_PLANE_PROCESS_ID),
        stderr());

    assertFalse(stdout().contains("resume with"), stdout());
    assertEquals(1, linesFromJson().size(), stdout());
  }

  @Test
  void a_process_id_nothing_ever_shipped_for_reads_back_empty_rather_than_failing() {
    assertEquals(0, run("metrics-history", "AGENT", "node-that-never-existed"), stderr());

    assertTrue(stdout().contains("No resources found."), stdout());
  }

  @Test
  void an_unknown_process_kind_lists_the_kinds_that_exist() {
    int exit = run("metrics-history", "MIMIR", CONTROL_PLANE_PROCESS_ID);

    assertNotEquals(0, exit);
    assertTrue(stderr().contains("unknown process kind: MIMIR"), stderr());
    assertTrue(stderr().contains("CONTROLPLANE, FAFNIR, STORE, AGENT, WORKER"), stderr());
  }

  @Test
  void a_worker_process_id_missing_its_worker_half_is_rejected() {
    int exit = run("traces-history", "WORKER", "node-a");

    assertNotEquals(0, exit);
    assertTrue(stderr().contains("{nodeId}:{workerId}"), stderr());
  }

  @Test
  void a_missing_process_id_prints_the_verb_usage() {
    int exit = run("metrics-history", "CONTROLPLANE");

    assertNotEquals(0, exit);
    assertTrue(stderr().contains("usage: gimle metrics-history"), stderr());
  }

  @Test
  void a_non_numeric_limit_is_rejected() {
    int exit =
        run("metrics-history", "CONTROLPLANE", CONTROL_PLANE_PROCESS_ID, "--limit", "plenty");

    assertNotEquals(0, exit);
    assertTrue(stderr().contains("--limit must be a number"), stderr());
  }

  @Test
  void a_control_plane_with_no_muninn_behind_it_says_so() {
    try (InProcessCluster noMuninn = InProcessCluster.start(tempDir.resolve("no-muninn"))) {
      int exit =
          runAgainst(
              noMuninn.address(), "metrics-history", "CONTROLPLANE", CONTROL_PLANE_PROCESS_ID);

      assertNotEquals(0, exit);
      assertTrue(stderr().contains("no muninn endpoint configured"), stderr());
    }
  }

  @Test
  void an_unreachable_control_plane_produces_a_clear_error_and_nonzero_exit() {
    int exit =
        runAgainst("localhost:1", "traces-history", "CONTROLPLANE", CONTROL_PLANE_PROCESS_ID);

    assertNotEquals(0, exit);
    assertTrue(stderr().contains("could not reach control plane"), stderr());
  }
}
