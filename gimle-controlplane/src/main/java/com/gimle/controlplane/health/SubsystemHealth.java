package com.gimle.controlplane.health;

import java.time.Instant;
import java.util.Optional;

/**
 * One subsystem's own last-known health: the outcome, when it was last observed, and an optional
 * detail -- the failure reason on {@link SubsystemStatus#DOWN}, or an informational note on {@link
 * SubsystemStatus#UP} (e.g. "no artifact registry configured").
 */
public record SubsystemHealth(
    SubsystemStatus status, Optional<Instant> lastRunAt, Optional<String> detail) {

  public static SubsystemHealth unknown() {
    return new SubsystemHealth(SubsystemStatus.UNKNOWN, Optional.empty(), Optional.empty());
  }

  public static SubsystemHealth standby() {
    return new SubsystemHealth(SubsystemStatus.STANDBY, Optional.empty(), Optional.empty());
  }

  public static SubsystemHealth up(Instant at) {
    return new SubsystemHealth(SubsystemStatus.UP, Optional.of(at), Optional.empty());
  }

  public static SubsystemHealth up(Instant at, String note) {
    return new SubsystemHealth(SubsystemStatus.UP, Optional.of(at), Optional.ofNullable(note));
  }

  public static SubsystemHealth down(Instant at, String reason) {
    return new SubsystemHealth(SubsystemStatus.DOWN, Optional.of(at), Optional.ofNullable(reason));
  }
}
