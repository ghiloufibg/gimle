package com.gimle.holmgang.surtr;

import com.gimle.holmgang.HolmgangException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An immutable, validated scale workload: the topology to boot, the jobs to run in order, the
 * measurements to collect, the gates that fail the run, and whether to garbage-collect everything
 * created at the end. Parsed from YAML by {@link SurtrWorkloadParser}.
 */
public record SurtrWorkload(
    String name,
    String topology,
    List<SurtrJob> jobs,
    List<Measurement> measurements,
    Gates gates,
    boolean gc) {

  /**
   * The pass/fail gates. Failure counts are gated by default; latency gates are opt-in tripwires,
   * keyed by a measurement-specific name (e.g. {@code instanceActiveP99Millis}) mapped to a
   * ceiling.
   */
  public record Gates(int maxFailedSubmissions, int maxNeverActive, Map<String, Long> latency) {
    public Gates {
      latency = Map.copyOf(latency);
    }

    public static Gates defaults() {
      return new Gates(0, 0, Map.of());
    }
  }

  public SurtrWorkload {
    if (name == null || name.isBlank()) {
      throw new HolmgangException("a workload must have a name");
    }
    if (topology == null || topology.isBlank()) {
      throw new HolmgangException("a workload must name a topology");
    }
    jobs = List.copyOf(jobs);
    measurements = List.copyOf(measurements);
    if (jobs.isEmpty()) {
      throw new HolmgangException("a workload must declare at least one job");
    }
    if (gates == null) {
      gates = Gates.defaults();
    }
  }

  /** The scale factor applied to every create job's iteration count; 1.0 when unset. */
  public static double scaleFactor() {
    final String raw = System.getProperty("gimle.surtr.scale");
    if (raw == null || raw.isBlank()) {
      return 1.0;
    }
    try {
      final double scale = Double.parseDouble(raw);
      if (scale <= 0) {
        throw new HolmgangException("gimle.surtr.scale must be positive, got " + raw);
      }
      return scale;
    } catch (final NumberFormatException e) {
      throw new HolmgangException("gimle.surtr.scale must be a number, got " + raw, e);
    }
  }

  /** The create job whose objects a churn/delete job targets. */
  public Optional<SurtrJob> jobNamed(final String jobName) {
    return jobs.stream().filter(job -> job.name().equals(jobName)).findFirst();
  }
}
