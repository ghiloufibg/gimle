package com.gimle.holmgang.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.core.saga.SagaEvent;
import com.gimle.holmgang.topology.ClusterSpec;
import com.gimle.holmgang.topology.ClusterTopology;
import com.gimle.holmgang.topology.Transport;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The attachment shapes {@link SagaShipper} builds match what the console's duck-typed parser
 * ({@code mapping.ts}'s {@code isGherkinFeature}/{@code isChaosStrike}/{@code isSurtrPhase})
 * expects, and shipping itself is dormant unless {@code -Dgimle.saga.endpoint} is configured and
 * never throws when the configured endpoint is unreachable.
 */
final class SagaShipperTest {

  @AfterEach
  void tearDown() {
    System.clearProperty(SagaShipper.ENDPOINT_PROPERTY);
    System.clearProperty(SagaShipper.RUN_ID_PROPERTY);
  }

  private static Map<String, Object> scenario(final String name, final String status) {
    final Map<String, Object> s = new LinkedHashMap<>();
    s.put("name", name);
    s.put("status", status);
    s.put("durationMillis", 1200);
    s.put("topology", "minimal");
    s.put("tags", List.of());
    s.put("steps", List.of());
    s.put("failure", null);
    return s;
  }

  @Test
  void a_gherkin_attachment_carries_every_feature_stamped_with_the_producing_module() {
    final SagaCollector c = SagaCollector.forTest();
    c.addScenario(
        "Deployment lifecycle", "deployment.feature", scenario("a becomes active", "PASSED"));

    final List<SagaEvent> events = SagaShipper.buildAttachments(c, "run-1");

    final SagaEvent.Attachment gherkin = onlyOfKind(events, "gherkin-scenario");
    final List<Map<String, Object>> parsed = Json.asObjectList(Json.parse(gherkin.payloadJson()));
    assertEquals(1, parsed.size());
    assertEquals("Deployment lifecycle", parsed.get(0).get("name"));
    assertEquals("gimle-holmgang", parsed.get(0).get("module"));
    assertEquals(1, Json.asArray(parsed.get(0).get("scenarios")).size());
  }

  @Test
  void no_gherkin_attachment_is_built_when_no_scenarios_were_recorded() {
    final SagaCollector c = SagaCollector.forTest();
    c.recordTopology(minimalTopology());

    final List<SagaEvent> events = SagaShipper.buildAttachments(c, "run-1");

    assertTrue(
        events.stream()
            .noneMatch(
                e -> e instanceof SagaEvent.Attachment a && "gherkin-scenario".equals(a.kind())));
  }

  @Test
  void a_topology_attachment_is_shipped_as_a_single_object_per_topology() {
    final SagaCollector c = SagaCollector.forTest();
    c.recordTopology(minimalTopology());

    final SagaEvent.Attachment topology =
        onlyOfKind(SagaShipper.buildAttachments(c, "run-1"), "topology");

    final Map<String, Object> payload = Json.asObject(Json.parse(topology.payloadJson()));
    assertEquals("minimal", payload.get("name"));
    assertEquals("PLAINTEXT", payload.get("transport"));
  }

  @Test
  void chaos_strikes_map_fenrir_ledger_rows_to_the_console_shape() {
    final Map<String, Object> ledgerRow = new LinkedHashMap<>();
    ledgerRow.put("index", 0);
    ledgerRow.put("kind", "WORKER_KILL");
    ledgerRow.put("victim", "node-1:worker-2");
    ledgerRow.put("firedAtOffsetMillis", 5_000L);
    ledgerRow.put("outcome", "RECOVERED");
    ledgerRow.put("recoveryMillis", 1_200L);
    ledgerRow.put("skipReason", null);
    final Map<String, Object> fenrirRun = new LinkedHashMap<>();
    fenrirRun.put("ledger", List.of(ledgerRow));

    final List<Map<String, Object>> strikes = SagaShipper.chaosStrikes(fenrirRun);

    assertEquals(1, strikes.size());
    final Map<String, Object> strike = strikes.get(0);
    assertEquals(5_000L, strike.get("atMillis"));
    assertEquals("worker-kill", strike.get("kind"));
    assertEquals("node-1:worker-2", strike.get("target"));
    assertEquals(1_200L, strike.get("recoveryMillis"));
    assertEquals(true, strike.get("recovered"));
  }

  @Test
  void a_skipped_strike_with_no_victim_still_renders_with_an_empty_target() {
    final Map<String, Object> ledgerRow = new LinkedHashMap<>();
    ledgerRow.put("index", 0);
    ledgerRow.put("kind", "STORE_BOUNCE");
    ledgerRow.put("victim", null);
    ledgerRow.put("firedAtOffsetMillis", 1_000L);
    ledgerRow.put("outcome", "SKIPPED");
    ledgerRow.put("recoveryMillis", -1L);
    ledgerRow.put("skipReason", "quorum guard");
    final Map<String, Object> fenrirRun = new LinkedHashMap<>();
    fenrirRun.put("ledger", List.of(ledgerRow));

    final List<Map<String, Object>> strikes = SagaShipper.chaosStrikes(fenrirRun);

    assertEquals("", strikes.get(0).get("target"));
    assertEquals(0L, strikes.get(0).get("recoveryMillis"));
    assertEquals(false, strikes.get(0).get("recovered"));
  }

  @Test
  void surtr_phases_are_built_from_jobs_and_enriched_with_matching_api_latency() {
    final Map<String, Object> job = new LinkedHashMap<>();
    job.put("name", "POST /deployments");
    job.put("wallMillis", 2_000L);
    job.put("iterations", 100);
    job.put("failed", 3);
    final Map<String, Object> apiStat = new LinkedHashMap<>();
    apiStat.put("endpoint", "POST /deployments");
    apiStat.put("p50", 40L);
    apiStat.put("p99", 90L);
    final Map<String, Object> measurements = new LinkedHashMap<>();
    measurements.put("apiLatency", List.of(apiStat));
    final Map<String, Object> surtrRun = new LinkedHashMap<>();
    surtrRun.put("jobs", List.of(job));
    surtrRun.put("measurements", measurements);

    final List<Map<String, Object>> phases = SagaShipper.surtrPhases(surtrRun);

    assertEquals(1, phases.size());
    final Map<String, Object> phase = phases.get(0);
    assertEquals("POST /deployments", phase.get("phase"));
    assertEquals(2_000L, phase.get("durationMillis"));
    assertEquals(40L, phase.get("p50Millis"));
    assertEquals(90L, phase.get("p99Millis"));
    assertEquals(3, phase.get("failures"));
    assertEquals(50.0, phase.get("throughput"));
  }

  @Test
  void surtr_phases_default_percentiles_to_zero_when_no_matching_api_stat_exists() {
    final Map<String, Object> job = new LinkedHashMap<>();
    job.put("name", "churn");
    job.put("wallMillis", 0L);
    job.put("iterations", 10);
    job.put("failed", 0);
    final Map<String, Object> surtrRun = new LinkedHashMap<>();
    surtrRun.put("jobs", List.of(job));

    final List<Map<String, Object>> phases = SagaShipper.surtrPhases(surtrRun);

    assertEquals(0L, phases.get(0).get("p50Millis"));
    assertEquals(0L, phases.get(0).get("p99Millis"));
    assertEquals(0.0, phases.get(0).get("throughput"), "zero wall time never divides by zero");
  }

  @Test
  void shipping_is_a_no_op_when_no_endpoint_is_configured() {
    System.clearProperty(SagaShipper.ENDPOINT_PROPERTY);
    final SagaCollector c = SagaCollector.forTest();
    c.addScenario("F", "f.feature", scenario("s", "PASSED"));

    // Must return without throwing and without touching the network.
    SagaShipper.ship(c);
  }

  @Test
  void shipping_never_throws_when_the_endpoint_is_unreachable() {
    System.setProperty(SagaShipper.ENDPOINT_PROPERTY, "http://127.0.0.1:1");
    final SagaCollector c = SagaCollector.forTest();
    c.addScenario("F", "f.feature", scenario("s", "PASSED"));

    SagaShipper.ship(c);
  }

  @Test
  void a_configured_endpoint_receives_the_attachments_as_ndjson() throws Exception {
    final BlockingQueue<String> bodies = new ArrayBlockingQueue<>(1);
    final HttpServer stub =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    stub.createContext(
        "/api/ingest",
        exchange -> {
          final String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          bodies.offer(body);
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    stub.start();
    try {
      System.setProperty(
          SagaShipper.ENDPOINT_PROPERTY, "http://127.0.0.1:" + stub.getAddress().getPort());
      System.setProperty(SagaShipper.RUN_ID_PROPERTY, "run-shipped");
      final SagaCollector c = SagaCollector.forTest();
      c.addScenario("F", "f.feature", scenario("s", "PASSED"));
      c.recordTopology(minimalTopology());

      SagaShipper.ship(c);

      final String body = bodies.poll(5, TimeUnit.SECONDS);
      assertTrue(body != null && !body.isBlank(), "the stub server must have received a request");
      final List<String> lines = body.lines().toList();
      assertFalse(lines.isEmpty());
      assertTrue(lines.stream().allMatch(line -> line.contains("\"runId\":\"run-shipped\"")));
      assertTrue(lines.stream().anyMatch(line -> line.contains("\"kind\":\"gherkin-scenario\"")));
      assertTrue(lines.stream().anyMatch(line -> line.contains("\"kind\":\"topology\"")));
    } finally {
      stub.stop(0);
    }
  }

  private static ClusterSpec minimalTopology() {
    return ClusterTopology.named("minimal").transport(Transport.PLAINTEXT).nodes(1).build();
  }

  private static SagaEvent.Attachment onlyOfKind(final List<SagaEvent> events, final String kind) {
    return events.stream()
        .filter(e -> e instanceof SagaEvent.Attachment a && kind.equals(a.kind()))
        .map(e -> (SagaEvent.Attachment) e)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no attachment of kind " + kind));
  }
}
