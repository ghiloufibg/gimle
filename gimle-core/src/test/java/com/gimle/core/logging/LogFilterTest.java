package com.gimle.core.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.logging.LogFilter.Level;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LogFilterTest {

  private static Map<String, Object> line(String level, String message) {
    return Map.of(
        "timestamp",
        "2026-08-10T10:00:00Z",
        "level",
        level,
        "logger",
        "com.example.Handler",
        "message",
        message);
  }

  private static Map<String, Object> rawLine(String text) {
    return Map.of("timestamp", "2026-08-10T10:00:00Z", "category", "SYSTEM", "raw", text);
  }

  @Test
  void none_matches_every_line_and_reports_itself_empty() {
    assertTrue(LogFilter.NONE.isEmpty());
    assertTrue(LogFilter.NONE.matches(line("TRACE", "anything")));
    assertTrue(LogFilter.NONE.matches(rawLine("kernel: link up")));
  }

  @Test
  void level_is_a_threshold_matching_that_level_and_everything_above_it() {
    LogFilter warn = LogFilter.of("WARN", null);
    assertTrue(warn.matches(line("WARN", "queue depth high")));
    assertTrue(warn.matches(line("ERROR", "call timed out")));
  }

  @Test
  void level_threshold_excludes_every_level_below_it() {
    LogFilter warn = LogFilter.of("WARN", null);
    assertFalse(warn.matches(line("INFO", "order accepted")));
    assertFalse(warn.matches(line("DEBUG", "cache hit")));
    assertFalse(warn.matches(line("TRACE", "entering")));
  }

  @Test
  void lowest_threshold_keeps_everything_and_highest_keeps_only_errors() {
    LogFilter trace = LogFilter.of("trace", null);
    for (Level level : Level.values()) {
      assertTrue(trace.matches(line(level.name(), "x")), "TRACE threshold dropped " + level);
    }
    LogFilter error = LogFilter.of("ERROR", null);
    assertTrue(error.matches(line("ERROR", "x")));
    assertFalse(error.matches(line("WARN", "x")));
  }

  @Test
  void a_line_with_no_rankable_level_never_satisfies_a_threshold() {
    // A raw SYSTEM capture has no level at all, and a custom Logback level isn't on this scale --
    // neither can be placed against a threshold, so both are excluded rather than admitted.
    LogFilter trace = LogFilter.of("TRACE", null);
    assertFalse(trace.matches(rawLine("kernel: cgroup limit reached")));
    assertFalse(trace.matches(line("NOTICE", "custom level")));
  }

  @Test
  void level_parsing_is_case_insensitive_and_tolerates_surrounding_space() {
    assertEquals(Level.ERROR, LogFilter.of(" error ", null).minLevel());
  }

  @Test
  void an_unknown_level_is_rejected_with_the_accepted_values_named() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> LogFilter.of("SEVERE", null));
    assertTrue(thrown.getMessage().contains("SEVERE"), thrown.getMessage());
    for (Level level : Level.values()) {
      assertTrue(thrown.getMessage().contains(level.name()), thrown.getMessage());
    }
  }

  @Test
  void a_blank_level_or_contains_means_no_constraint_at_all() {
    assertTrue(LogFilter.of("", "   ").isEmpty());
    assertTrue(LogFilter.of(null, null).isEmpty());
  }

  @Test
  void contains_is_a_case_insensitive_substring_over_message_logger_and_raw_text() {
    assertTrue(LogFilter.of(null, "TIMED out").matches(line("ERROR", "downstream call timed out")));
    assertTrue(LogFilter.of(null, "example.Handler").matches(line("INFO", "ok")));
    assertTrue(LogFilter.of(null, "cgroup").matches(rawLine("dmesg: cgroup limit reached")));
    assertFalse(LogFilter.of(null, "nothing here").matches(line("ERROR", "downstream timed out")));
  }

  @Test
  void contains_matches_a_stack_trace_body_too() {
    Map<String, Object> withTrace =
        Map.of(
            "timestamp",
            "2026-08-10T10:00:00Z",
            "level",
            "ERROR",
            "message",
            "failed",
            "stackTrace",
            "java.lang.IllegalStateException: worker gone\n\tat com.example.A.run(A.java:1)");
    assertTrue(LogFilter.of(null, "IllegalStateException").matches(withTrace));
  }

  @Test
  void contains_is_literal_text_not_a_regular_expression() {
    Map<String, Object> literal = line("INFO", "handled request in 42ms (a.b)");
    assertTrue(LogFilter.of(null, "(a.b)").matches(literal));
    // As a regex "a.b" would match "a<any>b"; as a literal substring it does not appear here.
    assertFalse(LogFilter.of(null, "a.b").matches(line("INFO", "axb")));
  }

  @Test
  void contains_ignores_machine_identifier_fields_so_a_query_cannot_match_a_whole_node() {
    Map<String, Object> tagged =
        Map.of(
            "timestamp",
            "2026-08-10T10:00:00Z",
            "level",
            "INFO",
            "message",
            "ok",
            "nodeId",
            "node-a",
            "thread",
            "worker-3");
    assertFalse(LogFilter.of(null, "node-a").matches(tagged));
    assertFalse(LogFilter.of(null, "worker-3").matches(tagged));
  }

  @Test
  void both_constraints_must_hold_together() {
    LogFilter both = LogFilter.of("WARN", "timed out");
    assertTrue(both.matches(line("ERROR", "downstream call timed out")));
    assertFalse(both.matches(line("INFO", "downstream call timed out")));
    assertFalse(both.matches(line("ERROR", "quota violation")));
  }

  @Test
  void from_query_reads_the_level_and_contains_parameters_both_surfaces_accept() {
    LogFilter filter = LogFilter.fromQuery(Map.of("level", "warn", "contains", "boom"));
    assertEquals(Level.WARN, filter.minLevel());
    assertEquals("boom", filter.contains());
    assertTrue(LogFilter.fromQuery(Map.of("limit", "200")).isEmpty());
  }

  @Test
  void describe_names_whichever_constraints_are_actually_set() {
    assertEquals("no filter", LogFilter.NONE.describe());
    assertEquals("level >= WARN", LogFilter.of("WARN", null).describe());
    assertEquals("containing \"boom\"", LogFilter.of(null, "boom").describe());
    assertEquals("level >= ERROR, containing \"boom\"", LogFilter.of("ERROR", "boom").describe());
  }

  @Test
  void levels_are_declared_lowest_to_highest_since_ordinal_order_is_what_ranks_them() {
    assertEquals(
        List.of(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR),
        List.of(Level.values()));
  }
}
