package com.gimle.core.vessel;

import java.util.Optional;

/**
 * A vessel's liveness/readiness probe ladder: either field absent means "process still running"
 * only -- the always-available default rung, since every vessel is a supervised OS process whether
 * or not it declares anything richer.
 */
public record VesselProbes(
    Optional<VesselProbeSpec> liveness, Optional<VesselProbeSpec> readiness) {

  public static final VesselProbes NONE = new VesselProbes(Optional.empty(), Optional.empty());

  public VesselProbes {
    if (liveness == null) {
      throw new IllegalArgumentException("liveness must be Optional.empty(), not null");
    }
    if (readiness == null) {
      throw new IllegalArgumentException("readiness must be Optional.empty(), not null");
    }
  }
}
