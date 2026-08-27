package com.gimle.ragnarok.surtr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The result types a run produces, plus the pure aggregation from raw API-call samples to
 * per-endpoint statistics. Collection against the live cluster lives in {@link SurtrRunner}; these
 * are the immutable shapes the report is written from.
 */
public final class Measurements {

  private Measurements() {}

  /** One recorded API submission: its endpoint, verb, client-observed latency, and status. */
  public record ApiCall(String endpoint, String verb, long latencyMillis, int status) {}

  /** Aggregated latency and error counts for one endpoint+verb over the run. */
  public record ApiStat(
      String endpoint, String verb, long count, long errorCount, Percentiles latency) {}

  /** Per-phase instance startup latency, every phase sourced from platform event timestamps. */
  public record StartupLatency(Map<String, Percentiles> phases) {
    public StartupLatency {
      phases = Map.copyOf(phases);
    }
  }

  /** Instances per node and per worker from the final cluster state. */
  public record PlacementSpread(
      Map<String, Integer> instancesPerNode, Map<String, Integer> instancesPerWorker) {
    public PlacementSpread {
      instancesPerNode = Map.copyOf(instancesPerNode);
      instancesPerWorker = Map.copyOf(instancesPerWorker);
    }
  }

  /** The always-gated failure numbers. */
  public record FailureCounts(int failedSubmissions, int neverActive, int failedInstances) {}

  /** One metric series (or a skip with its reason) over the run window. */
  public record MetricSeries(
      String metric, String unit, Optional<String> skippedReason, List<MetricPoint> points) {
    public MetricSeries {
      points = List.copyOf(points);
    }
  }

  public record MetricPoint(String at, double value) {}

  /** Groups raw calls by endpoint+verb into per-endpoint statistics. */
  public static List<ApiStat> aggregate(final List<ApiCall> calls) {
    final Map<String, List<ApiCall>> byKey = new LinkedHashMap<>();
    for (final ApiCall call : calls) {
      byKey.computeIfAbsent(call.verb() + " " + call.endpoint(), k -> new ArrayList<>()).add(call);
    }
    final List<ApiStat> stats = new ArrayList<>();
    for (final List<ApiCall> group : byKey.values()) {
      final List<Long> latencies = new ArrayList<>();
      long errors = 0;
      for (final ApiCall call : group) {
        latencies.add(call.latencyMillis());
        if (call.status() != 200) {
          errors++;
        }
      }
      final ApiCall first = group.get(0);
      stats.add(
          new ApiStat(
              first.endpoint(), first.verb(), group.size(), errors, Percentiles.of(latencies)));
    }
    return stats;
  }
}
