package com.gimle.holmgang.surtr;

import com.gimle.holmgang.surtr.Measurements.ApiStat;
import com.gimle.holmgang.surtr.Measurements.FailureCounts;
import com.gimle.holmgang.surtr.Measurements.MetricSeries;
import com.gimle.holmgang.surtr.Measurements.PlacementSpread;
import com.gimle.holmgang.surtr.Measurements.StartupLatency;
import java.util.List;
import java.util.Optional;

/**
 * Everything one Surtr run produced: timing, per-job summaries, the collected measurements, and the
 * gate outcomes. The report is written from this; {@link #passed()} is the run's verdict.
 */
public record SurtrRunResult(
    String workload,
    double scaleFactor,
    String topology,
    long startedAtEpochMilli,
    long finishedAtEpochMilli,
    List<JobSummary> jobs,
    Optional<StartupLatency> startupLatency,
    List<ApiStat> apiLatency,
    Optional<PlacementSpread> placementSpread,
    FailureCounts failures,
    List<MetricSeries> muninnWindow,
    List<GateOutcome> gates) {

  /** One job's headline numbers. */
  public record JobSummary(
      String name, String type, int iterations, long wallMillis, int submitted, int failed) {}

  /** One gate's verdict: what it checked, its ceiling, the observed value, and whether it held. */
  public record GateOutcome(String name, long threshold, long observed, boolean passed) {}

  public SurtrRunResult {
    jobs = List.copyOf(jobs);
    apiLatency = List.copyOf(apiLatency);
    muninnWindow = List.copyOf(muninnWindow);
    gates = List.copyOf(gates);
  }

  public boolean passed() {
    return gates.stream().allMatch(GateOutcome::passed);
  }

  public long durationMillis() {
    return finishedAtEpochMilli - startedAtEpochMilli;
  }
}
