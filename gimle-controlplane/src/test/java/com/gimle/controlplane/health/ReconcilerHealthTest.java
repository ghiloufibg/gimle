package com.gimle.controlplane.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReconcilerHealthTest {

  private static Clock fixedClock(Instant at) {
    return Clock.fixed(at, ZoneOffset.UTC);
  }

  @Test
  void a_step_this_replica_has_never_run_reports_standby_while_not_the_leader() {
    ReconcilerHealth health = new ReconcilerHealth(fixedClock(Instant.EPOCH));

    assertEquals(SubsystemStatus.STANDBY, health.step("quota").status());
  }

  @Test
  void a_step_this_replica_has_never_run_reports_unknown_once_it_is_the_leader() {
    ReconcilerHealth health = new ReconcilerHealth(fixedClock(Instant.EPOCH));
    health.setLeader(true);

    assertEquals(SubsystemStatus.UNKNOWN, health.step("quota").status());
  }

  @Test
  void record_success_reports_up_with_the_run_time_once_leader() {
    Instant at = Instant.parse("2026-09-15T00:00:00Z");
    ReconcilerHealth health = new ReconcilerHealth(fixedClock(at));
    health.setLeader(true);

    health.recordSuccess("quota");

    SubsystemHealth step = health.step("quota");
    assertEquals(SubsystemStatus.UP, step.status());
    assertEquals(at, step.lastRunAt().orElseThrow());
    assertTrue(step.detail().isEmpty());
  }

  @Test
  void record_failure_reports_down_with_the_reason() {
    ReconcilerHealth health = new ReconcilerHealth(fixedClock(Instant.EPOCH));
    health.setLeader(true);

    health.recordFailure("quota", "store unreachable");

    SubsystemHealth step = health.step("quota");
    assertEquals(SubsystemStatus.DOWN, step.status());
    assertEquals("store unreachable", step.detail().orElseThrow());
  }

  @Test
  void losing_the_lease_reports_standby_even_after_a_recorded_success() {
    ReconcilerHealth health = new ReconcilerHealth(fixedClock(Instant.EPOCH));
    health.setLeader(true);
    health.recordSuccess("quota");

    health.setLeader(false);

    assertEquals(SubsystemStatus.STANDBY, health.step("quota").status());
  }

  @Test
  void combined_is_up_only_when_every_named_step_last_succeeded() {
    ReconcilerHealth health = new ReconcilerHealth(fixedClock(Instant.EPOCH));
    health.setLeader(true);
    health.recordSuccess("deployment");
    health.recordSuccess("job");
    health.recordSuccess("daemonSet");
    health.recordSuccess("statefulSet");

    assertEquals(
        SubsystemStatus.UP,
        health.combined("deployment", "job", "daemonSet", "statefulSet").status());
  }

  @Test
  void combined_is_down_if_any_named_step_last_failed_even_after_a_later_step_succeeds() {
    ReconcilerHealth health = new ReconcilerHealth(fixedClock(Instant.EPOCH));
    health.setLeader(true);
    health.recordFailure("deployment", "artifact registry unreachable");
    // A later step in the same tick succeeding must never mask the earlier step's own failure --
    // each step's outcome is tracked independently, not overwritten into one shared bucket.
    health.recordSuccess("job");
    health.recordSuccess("daemonSet");
    health.recordSuccess("statefulSet");

    SubsystemHealth combined = health.combined("deployment", "job", "daemonSet", "statefulSet");
    assertEquals(SubsystemStatus.DOWN, combined.status());
    assertEquals("artifact registry unreachable", combined.detail().orElseThrow());
  }

  @Test
  void combined_is_unknown_when_none_of_the_named_steps_have_run_yet() {
    ReconcilerHealth health = new ReconcilerHealth(fixedClock(Instant.EPOCH));
    health.setLeader(true);

    assertEquals(
        SubsystemStatus.UNKNOWN,
        health.combined("deployment", "job", "daemonSet", "statefulSet").status());
  }

  @Test
  void combined_is_standby_while_not_the_leader_regardless_of_past_recorded_outcomes() {
    ReconcilerHealth health = new ReconcilerHealth(fixedClock(Instant.EPOCH));
    health.setLeader(true);
    health.recordSuccess("deployment");
    health.setLeader(false);

    assertEquals(
        SubsystemStatus.STANDBY,
        health.combined("deployment", "job", "daemonSet", "statefulSet").status());
  }
}
