package com.gimle.mimir.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CronScheduleTest {

  @Test
  void every_minute_expression_matches_the_last_whole_minute_before_now() {
    CronSchedule schedule = CronSchedule.parse("* * * * *");
    Instant now = Instant.parse("2026-01-01T10:15:42Z");
    Instant after = Instant.parse("2026-01-01T10:10:00Z");

    Optional<Instant> due = schedule.mostRecentDueInstant(after, now);

    assertEquals(Instant.parse("2026-01-01T10:15:00Z"), due.orElseThrow());
  }

  @Test
  void an_exact_field_expression_matches_only_its_own_minute() {
    // Every day at 03:30.
    CronSchedule schedule = CronSchedule.parse("30 3 * * *");
    Instant after = Instant.parse("2026-01-01T00:00:00Z");
    Instant now = Instant.parse("2026-01-02T00:00:00Z");

    Optional<Instant> due = schedule.mostRecentDueInstant(after, now);

    assertEquals(Instant.parse("2026-01-01T03:30:00Z"), due.orElseThrow());
  }

  @Test
  void nothing_due_since_after_returns_empty() {
    CronSchedule schedule = CronSchedule.parse("30 3 * * *");
    // Already past today's 03:30 firing.
    Instant after = Instant.parse("2026-01-01T04:00:00Z");
    Instant now = Instant.parse("2026-01-01T10:00:00Z");

    assertTrue(schedule.mostRecentDueInstant(after, now).isEmpty());
  }

  @Test
  void after_not_before_up_to_returns_empty() {
    CronSchedule schedule = CronSchedule.parse("* * * * *");
    Instant t = Instant.parse("2026-01-01T00:00:00Z");

    assertTrue(schedule.mostRecentDueInstant(t, t).isEmpty());
    assertTrue(schedule.mostRecentDueInstant(t.plusSeconds(60), t).isEmpty());
  }

  @Test
  void comma_list_matches_any_listed_value() {
    CronSchedule schedule = CronSchedule.parse("0 6,18 * * *");
    Instant after = Instant.parse("2026-01-01T00:00:00Z");
    Instant now = Instant.parse("2026-01-01T20:00:00Z");

    assertEquals(
        Instant.parse("2026-01-01T18:00:00Z"),
        schedule.mostRecentDueInstant(after, now).orElseThrow());
  }

  @Test
  void range_and_step_combine() {
    // Every 15 minutes between :00 and :45, hour 9 only.
    CronSchedule schedule = CronSchedule.parse("0-45/15 9 * * *");
    Instant after = Instant.parse("2026-01-01T09:00:00Z");
    Instant now = Instant.parse("2026-01-01T09:50:00Z");

    assertEquals(
        Instant.parse("2026-01-01T09:45:00Z"),
        schedule.mostRecentDueInstant(after, now).orElseThrow());
  }

  @Test
  void star_slash_n_step_starts_from_the_fields_own_minimum() {
    CronSchedule schedule = CronSchedule.parse("*/20 * * * *");
    Instant after = Instant.parse("2026-01-01T00:00:00Z");
    Instant now = Instant.parse("2026-01-01T01:05:00Z");

    // Matches :00, :20, :40 every hour -- the most recent before 01:05 is 01:00.
    assertEquals(
        Instant.parse("2026-01-01T01:00:00Z"),
        schedule.mostRecentDueInstant(after, now).orElseThrow());
  }

  @Test
  void day_of_month_and_day_of_week_both_restricted_combine_with_or() {
    // The 1st of the month, OR any Monday (1) -- standard cron's historical OR quirk.
    CronSchedule schedule = CronSchedule.parse("0 0 1 * 1");
    // 2026-01-05 is a Monday, not the 1st -- should still match via the day-of-week side.
    Instant after = Instant.parse("2026-01-05T00:00:00Z").minusSeconds(1);
    Instant now = Instant.parse("2026-01-05T00:00:01Z");

    assertEquals(
        Instant.parse("2026-01-05T00:00:00Z"),
        schedule.mostRecentDueInstant(after, now).orElseThrow());
  }

  @Test
  void day_of_month_restricted_alone_uses_plain_and_with_an_unrestricted_day_of_week() {
    // Only the 15th of the month -- day-of-week is "*", so no OR quirk applies.
    CronSchedule schedule = CronSchedule.parse("0 0 15 * *");
    Instant after = Instant.parse("2026-01-01T00:00:00Z");
    Instant now = Instant.parse("2026-01-20T00:00:00Z");

    assertEquals(
        Instant.parse("2026-01-15T00:00:00Z"),
        schedule.mostRecentDueInstant(after, now).orElseThrow());
  }

  @Test
  void wrong_field_count_throws() {
    assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("* * * *"));
  }

  @Test
  void out_of_range_value_throws() {
    assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("60 * * * *"));
  }

  @Test
  void non_numeric_value_throws() {
    assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("* * * JAN *"));
  }

  @Test
  void inverted_range_throws() {
    assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("* * 20-10 * *"));
  }

  @Test
  void zero_step_throws() {
    assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("*/0 * * * *"));
  }
}
