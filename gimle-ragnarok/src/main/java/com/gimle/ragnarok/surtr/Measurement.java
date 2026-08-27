package com.gimle.ragnarok.surtr;

import com.gimle.ragnarok.RagnarokException;

/** The measurements a workload can ask Surtr to collect over a run. */
public enum Measurement {
  /** Per-phase instance startup latency, sourced from the platform's own lifecycle event log. */
  INSTANCE_STARTUP_LATENCY("instanceStartupLatency"),
  /** Client-observed per-endpoint API latency and error counts. */
  API_LATENCY("apiLatency"),
  /** Rejected submissions and never-active instances -- the always-gated numbers. */
  FAILURES("failures"),
  /** Instances per node from the final cluster view. */
  PLACEMENT_SPREAD("placementSpread"),
  /** Named metric series from Muninn over the run window; requires a Muninn-enabled topology. */
  MUNINN_WINDOW("muninnWindow");

  private final String field;

  Measurement(final String field) {
    this.field = field;
  }

  public String field() {
    return field;
  }

  public static Measurement fromField(final String field) {
    for (final Measurement measurement : values()) {
      if (measurement.field.equals(field)) {
        return measurement;
      }
    }
    throw new RagnarokException("unknown measurement: " + field);
  }
}
