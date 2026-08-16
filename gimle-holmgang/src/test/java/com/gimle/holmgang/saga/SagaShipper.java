package com.gimle.holmgang.saga;

import com.gimle.core.protocol.Json;
import com.gimle.core.saga.SagaEvent;
import com.gimle.core.saga.SagaEventCodec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort delivery of a {@link SagaCollector}'s Gherkin/Fenrir/Surtr/topology results to a Saga
 * report server as {@link SagaEvent.Attachment}s, alongside the local JSON/HTML report {@link
 * SagaWriter} always writes -- a second output path, not a replacement for it. Dormant unless
 * {@code -Dgimle.saga.endpoint} is set (the same property {@code SagaTestListener} in gimle-core
 * checks); shipping failures are swallowed, never thrown, so a report server being down or absent
 * can never fail a validation run.
 *
 * <p>Only attachment events are sent, never a run-started/run-finished pair: a run those events
 * open is deleted and rebuilt if a run-started for the same run ID arrives twice ({@code
 * SagaStore#ingestRun}), which an accidental ID collision with a concurrently-shipping {@code
 * SagaTestListener} would turn into data loss. Attachment-only ingest has no such hazard -- it only
 * ever appends. The run ID defaults to the collector's own (shared with the local report's own
 * directory name), or can be pointed at an already-open run via {@code -Dgimle.saga.runId} so a
 * validation run's Gherkin/Fenrir/Surtr results land in the same Saga run as its per-test results.
 */
final class SagaShipper {

  private static final Logger log = LoggerFactory.getLogger(SagaShipper.class);

  static final String ENDPOINT_PROPERTY = "gimle.saga.endpoint";
  static final String RUN_ID_PROPERTY = "gimle.saga.runId";

  private static final String HOLMGANG_MODULE = "gimle-holmgang";
  private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

  private SagaShipper() {}

  /** Ships everything the collector holds as attachment events; a no-op unless configured. */
  static void ship(final SagaCollector collector) {
    final String endpoint = System.getProperty(ENDPOINT_PROPERTY);
    if (endpoint == null || endpoint.isBlank()) {
      return;
    }
    final String runId = resolveRunId(collector);
    final List<SagaEvent> events = buildAttachments(collector, runId);
    if (events.isEmpty()) {
      return;
    }
    post(endpoint.trim(), events);
  }

  private static String resolveRunId(final SagaCollector collector) {
    final String configured = System.getProperty(RUN_ID_PROPERTY);
    return configured == null || configured.isBlank() ? collector.runId() : configured.trim();
  }

  static List<SagaEvent> buildAttachments(final SagaCollector collector, final String runId) {
    final List<SagaEvent> events = new ArrayList<>();
    gherkinAttachment(collector, runId).ifPresent(events::add);
    for (final Map<String, Object> topology : collector.topologies()) {
      events.add(new SagaEvent.Attachment(runId, "topology", Json.write(topology)));
    }
    for (final Map<String, Object> fenrirRun : collector.fenrirRuns()) {
      final List<Map<String, Object>> strikes = chaosStrikes(fenrirRun);
      if (!strikes.isEmpty()) {
        events.add(new SagaEvent.Attachment(runId, "fenrir-ledger", Json.write(strikes)));
      }
    }
    for (final Map<String, Object> surtrRun : collector.surtrRuns()) {
      final List<Map<String, Object>> phases = surtrPhases(surtrRun);
      if (!phases.isEmpty()) {
        events.add(new SagaEvent.Attachment(runId, "surtr-result", Json.write(phases)));
      }
    }
    return events;
  }

  /**
   * One {@code gherkin-scenario} attachment carrying every recorded feature, each stamped with the
   * producing Maven module (always {@code gimle-holmgang} -- these scenarios exercise a real
   * cluster, not any one platform module) so the console's per-feature module chip has something to
   * show.
   */
  private static Optional<SagaEvent> gherkinAttachment(
      final SagaCollector collector, final String runId) {
    final List<Map<String, Object>> features = collector.featuresJson();
    if (features.isEmpty()) {
      return Optional.empty();
    }
    final List<Map<String, Object>> withModule = new ArrayList<>();
    for (final Map<String, Object> feature : features) {
      final Map<String, Object> copy = new LinkedHashMap<>(feature);
      copy.put("module", HOLMGANG_MODULE);
      withModule.add(copy);
    }
    return Optional.of(new SagaEvent.Attachment(runId, "gherkin-scenario", Json.write(withModule)));
  }

  /** Fenrir's own ledger row shape, reduced to the console's chaos-strike-timeline fields. */
  static List<Map<String, Object>> chaosStrikes(final Map<String, Object> fenrirRun) {
    final Object ledgerRaw = fenrirRun.get("ledger");
    if (!(ledgerRaw instanceof List<?>)) {
      return List.of();
    }
    final List<Map<String, Object>> strikes = new ArrayList<>();
    for (final Map<String, Object> entry : Json.asObjectList(ledgerRaw)) {
      final Map<String, Object> strike = new LinkedHashMap<>();
      strike.put("atMillis", longOf(entry.get("firedAtOffsetMillis")));
      strike.put("kind", kebabCase(String.valueOf(entry.get("kind"))));
      strike.put("target", entry.get("victim") == null ? "" : String.valueOf(entry.get("victim")));
      final long recoveryMillis = longOf(entry.get("recoveryMillis"));
      strike.put("recoveryMillis", Math.max(recoveryMillis, 0L));
      strike.put("recovered", "RECOVERED".equals(entry.get("outcome")));
      strikes.add(strike);
    }
    return strikes;
  }

  /**
   * Surtr's per-job summary (real wall time, iteration count, and failure count) treated as one
   * "phase" row apiece, best-effort-enriched with that job's own p50/p99 latency when the run's
   * {@code apiLatency} stats happen to carry an entry keyed by the same name -- the two are
   * recorded independently, so the join is opportunistic, not guaranteed.
   */
  static List<Map<String, Object>> surtrPhases(final Map<String, Object> surtrRun) {
    final Object jobsRaw = surtrRun.get("jobs");
    if (!(jobsRaw instanceof List<?>)) {
      return List.of();
    }
    final Map<String, Map<String, Object>> apiLatencyByEndpoint = apiLatencyByEndpoint(surtrRun);
    final List<Map<String, Object>> phases = new ArrayList<>();
    for (final Map<String, Object> job : Json.asObjectList(jobsRaw)) {
      final String name = String.valueOf(job.get("name"));
      final long wallMillis = longOf(job.get("wallMillis"));
      final int iterations = intOf(job.get("iterations"));
      final Map<String, Object> matchingStat = apiLatencyByEndpoint.get(name);
      final Map<String, Object> phase = new LinkedHashMap<>();
      phase.put("phase", name);
      phase.put("durationMillis", wallMillis);
      phase.put("p50Millis", matchingStat == null ? 0L : longOf(matchingStat.get("p50")));
      phase.put("p99Millis", matchingStat == null ? 0L : longOf(matchingStat.get("p99")));
      phase.put("failures", intOf(job.get("failed")));
      phase.put("throughput", wallMillis > 0 ? iterations * 1000.0 / wallMillis : 0.0);
      phases.add(phase);
    }
    return phases;
  }

  private static Map<String, Map<String, Object>> apiLatencyByEndpoint(
      final Map<String, Object> surtrRun) {
    if (!(surtrRun.get("measurements") instanceof Map<?, ?> measurementsRaw)) {
      return Map.of();
    }
    final Object apiLatencyRaw = Json.asObject(measurementsRaw).get("apiLatency");
    if (!(apiLatencyRaw instanceof List<?>)) {
      return Map.of();
    }
    final Map<String, Map<String, Object>> byEndpoint = new LinkedHashMap<>();
    for (final Map<String, Object> stat : Json.asObjectList(apiLatencyRaw)) {
      byEndpoint.put(String.valueOf(stat.get("endpoint")), stat);
    }
    return byEndpoint;
  }

  private static String kebabCase(final String enumName) {
    return enumName.toLowerCase(Locale.ROOT).replace('_', '-');
  }

  private static long longOf(final Object value) {
    return value instanceof Number n ? n.longValue() : 0L;
  }

  private static int intOf(final Object value) {
    return value instanceof Number n ? n.intValue() : 0;
  }

  private static void post(final String endpoint, final List<SagaEvent> events) {
    final StringBuilder body = new StringBuilder();
    for (final SagaEvent event : events) {
      body.append(SagaEventCodec.encode(event)).append('\n');
    }
    final URI ingestUri = URI.create(endpoint.replaceAll("/+$", "") + "/api/ingest");
    try {
      final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
      final HttpRequest request =
          HttpRequest.newBuilder(ingestUri)
              .timeout(REQUEST_TIMEOUT)
              .header("Content-Type", "application/x-ndjson")
              .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
              .build();
      client.send(request, HttpResponse.BodyHandlers.discarding());
      log.info("shipped {} saga attachment(s) to {}", events.size(), ingestUri);
    } catch (final Exception e) {
      // A report server being down or unreachable must never fail a validation run.
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.debug("saga attachment shipping skipped: {}", e.toString());
    }
  }
}
