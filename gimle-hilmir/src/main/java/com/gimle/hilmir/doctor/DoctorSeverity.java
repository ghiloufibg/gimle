package com.gimle.hilmir.doctor;

/**
 * How serious a {@link DoctorFinding} is. Unlike {@code TopologyValidator}'s own two-level {@code
 * Severity}, {@code doctor} needs a third, non-blocking level: {@code INFO} for a fact worth
 * surfacing (the platform has no ingress story for a plain module's own bound port; a launcher
 * archive should be redeployed as a vessel instead) that should never fail the command's exit code.
 */
public enum DoctorSeverity {
  ERROR,
  WARNING,
  INFO
}
