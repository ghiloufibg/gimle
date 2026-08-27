package com.gimle.ragnarok.surtr;

import com.gimle.core.protocol.Json;
import com.gimle.ragnarok.RagnarokException;
import com.gimle.ragnarok.surtr.Measurements.ApiStat;
import com.gimle.ragnarok.surtr.Measurements.MetricSeries;
import com.gimle.ragnarok.surtr.Measurements.PlacementSpread;
import com.gimle.ragnarok.surtr.Measurements.StartupLatency;
import com.gimle.ragnarok.surtr.SurtrRunResult.GateOutcome;
import com.gimle.ragnarok.surtr.SurtrRunResult.JobSummary;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a run's {@link SurtrRunResult} to a timestamped directory: a {@code summary.json} with the
 * environment fingerprint that makes cross-run diffs honest, plus per-measurement NDJSON. Two
 * summaries diff with {@code jq}; there is deliberately no dashboard.
 */
public final class SurtrReport {

  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private SurtrReport() {}

  /**
   * Writes the report under {@code outputRoot/<workload>-<timestamp>/} and returns that directory.
   */
  public static Path write(final SurtrRunResult result, final Path outputRoot) {
    final Path runDir =
        outputRoot.resolve(
            result.workload()
                + "-"
                + STAMP.format(Instant.ofEpochMilli(result.finishedAtEpochMilli())));
    try {
      Files.createDirectories(runDir);
      Files.writeString(runDir.resolve("summary.json"), Json.write(summary(result)));
      writeStartupNdjson(result, runDir);
      writeApiNdjson(result, runDir);
    } catch (final IOException e) {
      throw new RagnarokException("failed writing Surtr report under " + runDir, e);
    }
    return runDir;
  }

  /**
   * The report shape for one run, without the top-level envelope -- used to embed a Surtr run
   * inside a larger aggregate report alongside other results.
   */
  public static Map<String, Object> reportRun(final SurtrRunResult result) {
    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("workload", result.workload());
    run.put("scaleFactor", result.scaleFactor());
    run.put("topology", result.topology());
    run.put("outcome", result.passed() ? "PASSED" : "FAILED");
    run.put("jobs", jobs(result));
    run.put("measurements", measurements(result));
    run.put("gates", gates(result));
    return run;
  }

  private static Map<String, Object> summary(final SurtrRunResult result) {
    final Map<String, Object> root = new LinkedHashMap<>();
    root.put("schemaVersion", 1);
    root.put("workload", result.workload());
    root.put("topology", result.topology());
    root.put("scaleFactor", result.scaleFactor());
    root.put("startedAtEpochMilli", result.startedAtEpochMilli());
    root.put("finishedAtEpochMilli", result.finishedAtEpochMilli());
    root.put("durationMillis", result.durationMillis());
    root.put("passed", result.passed());
    root.put("environment", environment());
    root.put("jobs", jobs(result));
    root.put("measurements", measurements(result));
    root.put("gates", gates(result));
    return root;
  }

  private static Map<String, Object> environment() {
    final Map<String, Object> env = new LinkedHashMap<>();
    env.put("jdk", System.getProperty("java.version"));
    env.put(
        "os",
        System.getProperty("os.name")
            + " "
            + System.getProperty("os.version")
            + " "
            + System.getProperty("os.arch"));
    env.put("host", hostName());
    env.put("gitCommit", System.getProperty("gimle.surtr.gitCommit", "unknown"));
    return env;
  }

  private static String hostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (final Exception e) {
      return "unknown";
    }
  }

  private static List<Object> jobs(final SurtrRunResult result) {
    final List<Object> jobs = new ArrayList<>();
    for (final JobSummary job : result.jobs()) {
      final Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("name", job.name());
      entry.put("type", job.type());
      entry.put("iterations", job.iterations());
      entry.put("wallMillis", job.wallMillis());
      entry.put("submitted", job.submitted());
      entry.put("failed", job.failed());
      jobs.add(entry);
    }
    return jobs;
  }

  private static Map<String, Object> measurements(final SurtrRunResult result) {
    final Map<String, Object> measurements = new LinkedHashMap<>();
    result
        .startupLatency()
        .ifPresent(
            startup -> {
              final Map<String, Object> phases = new LinkedHashMap<>();
              startup.phases().forEach((phase, p) -> phases.put(phase, percentiles(p)));
              measurements.put("instanceStartupLatency", Map.of("phases", phases));
            });
    if (!result.apiLatency().isEmpty()) {
      final List<Object> stats = new ArrayList<>();
      for (final ApiStat stat : result.apiLatency()) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("endpoint", stat.endpoint());
        entry.put("verb", stat.verb());
        entry.put("count", stat.count());
        entry.put("errorCount", stat.errorCount());
        entry.putAll(percentiles(stat.latency()));
        stats.add(entry);
      }
      measurements.put("apiLatency", stats);
    }
    result.placementSpread().ifPresent(p -> measurements.put("placementSpread", placement(p)));
    final Map<String, Object> failures = new LinkedHashMap<>();
    failures.put("failedSubmissions", result.failures().failedSubmissions());
    failures.put("neverActive", result.failures().neverActive());
    failures.put("failedInstances", result.failures().failedInstances());
    measurements.put("failures", failures);
    if (!result.muninnWindow().isEmpty()) {
      final List<Object> series = new ArrayList<>();
      for (final MetricSeries s : result.muninnWindow()) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("metric", s.metric());
        entry.put("unit", s.unit());
        entry.put("skippedReason", s.skippedReason().orElse(null));
        series.add(entry);
      }
      measurements.put("muninnWindow", series);
    }
    return measurements;
  }

  private static Map<String, Object> placement(final PlacementSpread spread) {
    final Map<String, Object> placement = new LinkedHashMap<>();
    placement.put("instancesPerNode", instanceCounts(spread.instancesPerNode()));
    placement.put("instancesPerWorker", instanceCounts(spread.instancesPerWorker()));
    return placement;
  }

  private static List<Object> instanceCounts(final Map<String, Integer> counts) {
    final List<Object> list = new ArrayList<>();
    counts.forEach((id, count) -> list.add(Map.of("id", id, "instances", count)));
    return list;
  }

  private static List<Object> gates(final SurtrRunResult result) {
    final List<Object> gates = new ArrayList<>();
    for (final GateOutcome gate : result.gates()) {
      final Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("name", gate.name());
      entry.put("threshold", gate.threshold());
      entry.put("observed", gate.observed());
      entry.put("passed", gate.passed());
      gates.add(entry);
    }
    return gates;
  }

  private static Map<String, Object> percentiles(final Percentiles p) {
    final Map<String, Object> map = new LinkedHashMap<>();
    map.put("count", p.count());
    map.put("p50", p.p50());
    map.put("p95", p.p95());
    map.put("p99", p.p99());
    map.put("max", p.max());
    return map;
  }

  private static void writeStartupNdjson(final SurtrRunResult result, final Path runDir)
      throws IOException {
    if (result.startupLatency().isEmpty()) {
      return;
    }
    final StartupLatency startup = result.startupLatency().get();
    final List<String> lines = new ArrayList<>();
    startup
        .phases()
        .forEach(
            (phase, p) -> {
              final Map<String, Object> row = new LinkedHashMap<>();
              row.put("phase", phase);
              row.putAll(percentiles(p));
              lines.add(Json.write(row));
            });
    Files.write(runDir.resolve("startup-latency.ndjson"), lines);
  }

  private static void writeApiNdjson(final SurtrRunResult result, final Path runDir)
      throws IOException {
    if (result.apiLatency().isEmpty()) {
      return;
    }
    final List<String> lines = new ArrayList<>();
    for (final ApiStat stat : result.apiLatency()) {
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("endpoint", stat.endpoint());
      row.put("verb", stat.verb());
      row.put("count", stat.count());
      row.put("errorCount", stat.errorCount());
      row.putAll(percentiles(stat.latency()));
      lines.add(Json.write(row));
    }
    Files.write(runDir.resolve("api-latency.ndjson"), lines);
  }

  /** A compact human-readable summary for the run log. */
  public static String render(final SurtrRunResult result) {
    final StringBuilder out = new StringBuilder();
    out.append("Surtr workload '")
        .append(result.workload())
        .append("' on ")
        .append(result.topology())
        .append(" (scale ")
        .append(result.scaleFactor())
        .append(") -> ")
        .append(result.passed() ? "PASSED" : "FAILED")
        .append('\n');
    result
        .startupLatency()
        .ifPresent(
            startup ->
                startup
                    .phases()
                    .forEach(
                        (phase, p) ->
                            out.append(
                                String.format(
                                    "  startup %-20s p50=%-6d p95=%-6d p99=%-6d max=%-6d (n=%d)%n",
                                    phase, p.p50(), p.p95(), p.p99(), p.max(), p.count()))));
    for (final GateOutcome gate : result.gates()) {
      out.append(
          String.format(
              "  gate %-24s %s observed=%d threshold=%d%n",
              gate.name(), gate.passed() ? "PASS" : "FAIL", gate.observed(), gate.threshold()));
    }
    return out.toString();
  }

  /**
   * The same human-readable shape as {@link #render}, read back from an already-written {@code
   * summary.json} rather than a live {@link SurtrRunResult} -- used by {@code ragnarok report
   * --surtr-report}, which has no live result object, only the file this class itself wrote via
   * {@link #write}. Kept as one shared formatter rather than a second hand-rolled printer so the
   * two can never drift the way {@code ragnarok report}'s own once did (silently dropping the scale
   * factor and the whole startup-latency percentile table that {@link #render} always included).
   */
  public static String renderSummary(final Map<String, Object> summary) {
    final StringBuilder out = new StringBuilder();
    out.append("Surtr workload '")
        .append(summary.get("workload"))
        .append("' on ")
        .append(summary.get("topology"))
        .append(" (scale ")
        .append(summary.get("scaleFactor"))
        .append(") -> ")
        .append(Boolean.TRUE.equals(summary.get("passed")) ? "PASSED" : "FAILED")
        .append('\n');
    final Map<String, Object> measurements = objectOrEmpty(summary, "measurements");
    final Object startupLatency = measurements.get("instanceStartupLatency");
    if (startupLatency != null) {
      final Map<String, Object> phases = objectOrEmpty(Json.asObject(startupLatency), "phases");
      phases.forEach(
          (phase, raw) -> {
            final Map<String, Object> p = Json.asObject(raw);
            out.append(
                String.format(
                    "  startup %-20s p50=%-6s p95=%-6s p99=%-6s max=%-6s (n=%s)%n",
                    phase, p.get("p50"), p.get("p95"), p.get("p99"), p.get("max"), p.get("count")));
          });
    }
    for (final Object gate : arrayOrEmpty(summary, "gates")) {
      final Map<String, Object> row = Json.asObject(gate);
      out.append(
          String.format(
              "  gate %-24s %s observed=%s threshold=%s%n",
              row.get("name"),
              Boolean.TRUE.equals(row.get("passed")) ? "PASS" : "FAIL",
              row.get("observed"),
              row.get("threshold")));
    }
    return out.toString();
  }

  /**
   * {@code map.get(key)}, defaulting to an empty map for both an absent key and one explicitly
   * mapped to {@code null} -- unlike {@link Map#getOrDefault}, which only falls back on absence, so
   * an externally-authored or corrupted {@code summary.json} carrying a literal {@code null} for a
   * section {@link #renderSummary} reads (a hand-edited file, or a future schema version that omits
   * one) degrades to "nothing there" instead of a {@code NullPointerException}.
   */
  private static Map<String, Object> objectOrEmpty(
      final Map<String, Object> map, final String key) {
    final Object value = map.get(key);
    return value != null ? Json.asObject(value) : Map.of();
  }

  /** Same null-vs-absent fix as {@link #objectOrEmpty}, for a JSON array field. */
  private static List<Object> arrayOrEmpty(final Map<String, Object> map, final String key) {
    final Object value = map.get(key);
    return value != null ? Json.asArray(value) : List.of();
  }
}
