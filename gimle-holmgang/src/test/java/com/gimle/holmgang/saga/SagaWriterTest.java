package com.gimle.holmgang.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.holmgang.surtr.Measurements.FailureCounts;
import com.gimle.holmgang.surtr.SurtrRunResult;
import com.gimle.holmgang.surtr.SurtrRunResult.GateOutcome;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The report the collector produces matches the schema the Saga console consumes: schemaVersion 1,
 * scenario totals derived from the recorded scenarios, a FAILED run whenever any part failed, and
 * optional Surtr/Fenrir sections present only when recorded.
 */
final class SagaWriterTest {

  private static Map<String, Object> scenario(final String name, final String status) {
    final Map<String, Object> s = new LinkedHashMap<>();
    s.put("name", name);
    s.put("status", status);
    s.put("durationMillis", 1200);
    s.put("topology", "minimal");
    s.put("tags", List.of());
    s.put("steps", List.of());
    s.put(
        "failure",
        status.equals("FAILED") ? mapOf("message", "boom", "forensicReportPath", null) : null);
    return s;
  }

  private static Map<String, Object> mapOf(
      final String k1, final Object v1, final String k2, final Object v2) {
    final Map<String, Object> m = new LinkedHashMap<>();
    m.put(k1, v1);
    m.put(k2, v2);
    return m;
  }

  private static SurtrRunResult surtrResult() {
    return new SurtrRunResult(
        "module-density",
        1.0,
        "surtr-density",
        1000L,
        2000L,
        List.of(),
        Optional.empty(),
        List.of(),
        Optional.empty(),
        new FailureCounts(0, 0, 0),
        List.of(),
        List.of(new GateOutcome("maxFailedSubmissions", 0, 0, true)));
  }

  @Test
  @SuppressWarnings("unchecked")
  void the_report_carries_totals_and_optional_sections() {
    final SagaCollector c = SagaCollector.forTest();
    c.addScenario(
        "Deployment lifecycle",
        "deployment-lifecycle.feature",
        scenario("a becomes active", "PASSED"));
    c.addScenario(
        "Rolling update", "rolling-update.feature", scenario("drains old version", "FAILED"));
    c.recordSurtr(surtrResult());

    final Map<String, Object> report = SagaWriter.report(c);

    assertEquals(1, report.get("schemaVersion"));
    final Map<String, Object> run = (Map<String, Object>) report.get("run");
    assertEquals("FAILED", run.get("outcome"), "a failed scenario makes the run FAILED");
    assertNotNull(run.get("id"));
    assertTrue(String.valueOf(run.get("invocation")).contains("-Pvalidation"));

    final Map<String, Object> scenarios = (Map<String, Object>) report.get("scenarios");
    assertEquals(2, scenarios.get("total"));
    assertEquals(1, scenarios.get("passed"));
    assertEquals(1, scenarios.get("failed"));
    assertEquals(2, ((List<?>) scenarios.get("features")).size());

    final Map<String, Object> surtr = (Map<String, Object>) report.get("surtr");
    final List<Map<String, Object>> runs = (List<Map<String, Object>>) surtr.get("runs");
    assertEquals(1, runs.size());
    assertEquals("module-density", runs.get(0).get("workload"));
    assertEquals("PASSED", runs.get(0).get("outcome"));

    assertFalse(report.containsKey("fenrir"), "no Fenrir soak recorded, so no fenrir section");

    // The whole thing must serialize to JSON without error (the console reads exactly this).
    final String json = Json.write(report);
    assertTrue(json.contains("\"schemaVersion\""));
    assertTrue(json.contains("module-density"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void an_all_passing_run_is_passed() {
    final SagaCollector c = SagaCollector.forTest();
    c.addScenario("Deployment lifecycle", "deployment-lifecycle.feature", scenario("a", "PASSED"));
    final Map<String, Object> report = SagaWriter.report(c);
    assertEquals("PASSED", ((Map<String, Object>) report.get("run")).get("outcome"));
  }
}
