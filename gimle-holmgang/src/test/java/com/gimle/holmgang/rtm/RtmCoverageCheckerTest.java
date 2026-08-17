package com.gimle.holmgang.rtm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.rtm.RtmCoverageChecker.CoverageResult;
import com.gimle.holmgang.rtm.RtmCoverageChecker.RtmDocument;
import com.gimle.holmgang.rtm.RtmCoverageChecker.RtmEntry;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers both the summary-table/coverage-gaps parsing and the coverage-checking logic. */
final class RtmCoverageCheckerTest {

  private static List<String> validLines() {
    return List.of(
        "# Some RTM",
        "",
        "## Summary Table",
        "",
        "| ID | Feature | Status | Coverage | Holmgang scenario reference |",
        "|---|---|---|---|---|",
        "| GIMLE-001 | Covered thing | Active | Covered | `x.feature` |",
        "| GIMLE-002 | Uncovered thing | Active | Not Covered | — |",
        "| GIMLE-003 | Excluded uncovered thing | Active | Not Covered | — |",
        "| GIMLE-004 | Removed thing | Removed | Not Covered | — |",
        "",
        "## Coverage Gaps",
        "",
        "| ID | Module | Feature | Category | Other test coverage (non-Holmgang) |",
        "|---|---|---|---|---|",
        "| GIMLE-002 | gimle-core | Uncovered thing | Widgetry | Yes |",
        "| GIMLE-003 | gimle-core | Excluded uncovered thing | Widgetry | None |",
        "| GIMLE-004 | gimle-core | Removed thing | Widgetry | None |");
  }

  @Test
  void parses_every_summary_table_row_with_its_own_fields() {
    final RtmDocument doc = RtmCoverageChecker.parseLines(validLines(), "test");
    assertEquals(4, doc.entries().size());
    final RtmEntry first = doc.entries().get(0);
    assertEquals("GIMLE-001", first.id());
    assertEquals("Covered thing", first.feature());
    assertEquals("Active", first.status());
    assertEquals("Covered", first.coverage());
    assertTrue(first.isCovered());
    assertTrue(first.isImplemented());
  }

  @Test
  void removed_status_is_not_implemented() {
    final RtmDocument doc = RtmCoverageChecker.parseLines(validLines(), "test");
    final RtmEntry removed =
        doc.entries().stream().filter(e -> e.id().equals("GIMLE-004")).findFirst().orElseThrow();
    assertFalse(removed.isImplemented());
  }

  @Test
  void category_lookup_resolves_from_the_coverage_gaps_table() {
    final RtmDocument doc = RtmCoverageChecker.parseLines(validLines(), "test");
    assertEquals("Widgetry", doc.categoryOf("GIMLE-002"));
  }

  @Test
  void category_lookup_falls_back_to_unknown_when_the_gaps_table_is_missing() {
    final List<String> lines =
        List.of(
            "## Summary Table",
            "| ID | Feature | Status | Coverage |",
            "|---|---|---|---|",
            "| GIMLE-001 | Thing | Active | Covered |");
    final RtmDocument doc = RtmCoverageChecker.parseLines(lines, "test");
    assertEquals("(category unknown)", doc.categoryOf("GIMLE-001"));
  }

  @Test
  void check_counts_only_implemented_and_non_excluded_entries() {
    final RtmDocument doc = RtmCoverageChecker.parseLines(validLines(), "test");
    final CoverageResult result = RtmCoverageChecker.check(doc, Set.of("GIMLE-003"));
    // implemented (not Removed): 001, 002, 003 -- minus the excluded 003 -- leaves 001, 002
    assertEquals(2, result.totalChecked());
  }

  @Test
  void check_reports_exactly_the_not_covered_implemented_non_excluded_entries() {
    final RtmDocument doc = RtmCoverageChecker.parseLines(validLines(), "test");
    final CoverageResult result = RtmCoverageChecker.check(doc, Set.of("GIMLE-003"));
    assertEquals(1, result.notCovered().size());
    assertEquals("GIMLE-002", result.notCovered().get(0).id());
    assertFalse(result.passed());
  }

  @Test
  void check_passes_once_every_remaining_implemented_entry_is_excluded_or_covered() {
    final RtmDocument doc = RtmCoverageChecker.parseLines(validLines(), "test");
    final CoverageResult result = RtmCoverageChecker.check(doc, Set.of("GIMLE-002", "GIMLE-003"));
    assertTrue(result.passed());
    assertEquals(1, result.totalChecked()); // just GIMLE-001
  }

  @Test
  void exclude_matching_is_case_insensitive() {
    final RtmDocument doc = RtmCoverageChecker.parseLines(validLines(), "test");
    final CoverageResult result = RtmCoverageChecker.check(doc, Set.of("gimle-002", "gimle-003"));
    assertTrue(result.passed());
  }

  @Test
  void check_reports_an_excluded_id_that_never_matched_any_entry() {
    final RtmDocument doc = RtmCoverageChecker.parseLines(validLines(), "test");
    final CoverageResult result = RtmCoverageChecker.check(doc, Set.of("GIMLE-999"));
    assertEquals(List.of("GIMLE-999"), result.unknownExcludedIds());
  }

  @Test
  void a_feature_cell_with_an_escaped_pipe_round_trips_to_a_literal_pipe() {
    final List<String> lines =
        List.of(
            "## Summary Table",
            "| ID | Feature | Status | Coverage |",
            "|---|---|---|---|",
            "| GIMLE-001 | Push\\|pull thing | Active | Covered |");
    final RtmDocument doc = RtmCoverageChecker.parseLines(lines, "test");
    assertEquals("Push|pull thing", doc.entries().get(0).feature());
  }

  @Test
  void header_columns_may_be_reordered_and_carry_extra_columns() {
    final List<String> lines =
        List.of(
            "## Summary Table",
            "| Coverage | Status | Extra | ID | Feature |",
            "|---|---|---|---|---|",
            "| Covered | Active | whatever | GIMLE-001 | Reordered thing |");
    final RtmDocument doc = RtmCoverageChecker.parseLines(lines, "test");
    final RtmEntry entry = doc.entries().get(0);
    assertEquals("GIMLE-001", entry.id());
    assertEquals("Reordered thing", entry.feature());
    assertEquals("Active", entry.status());
    assertEquals("Covered", entry.coverage());
  }

  @Test
  void missing_file_fails_loudly(@TempDir final Path tempDir) {
    final Path missing = tempDir.resolve("does-not-exist.md");
    final HolmgangException e =
        assertThrows(HolmgangException.class, () -> RtmCoverageChecker.parse(missing));
    assertTrue(e.getMessage().contains("was not found"));
    assertTrue(e.getMessage().contains("rtmCheck.enabled=false"));
  }

  @Test
  void a_file_with_no_summary_table_heading_fails_loudly() {
    final List<String> lines = List.of("# Not an RTM file", "", "Just some prose.");
    final HolmgangException e =
        assertThrows(HolmgangException.class, () -> RtmCoverageChecker.parseLines(lines, "test"));
    assertTrue(e.getMessage().contains("Summary Table"));
  }

  @Test
  void a_summary_table_missing_a_required_column_fails_loudly() {
    final List<String> lines =
        List.of(
            "## Summary Table",
            "| ID | Feature | Status |", // no Coverage column
            "|---|---|---|",
            "| GIMLE-001 | Thing | Active |");
    final HolmgangException e =
        assertThrows(HolmgangException.class, () -> RtmCoverageChecker.parseLines(lines, "test"));
    assertTrue(e.getMessage().contains("unrecognizable"));
  }

  @Test
  void a_summary_table_with_zero_data_rows_fails_loudly() {
    final List<String> lines =
        List.of(
            "## Summary Table",
            "| ID | Feature | Status | Coverage |",
            "|---|---|---|---|",
            "",
            "## Next Section");
    final HolmgangException e =
        assertThrows(HolmgangException.class, () -> RtmCoverageChecker.parseLines(lines, "test"));
    assertTrue(e.getMessage().contains("zero rows"));
  }

  @Test
  void a_row_with_a_malformed_id_fails_loudly() {
    final List<String> lines =
        List.of(
            "## Summary Table",
            "| ID | Feature | Status | Coverage |",
            "|---|---|---|---|",
            "| NOT-AN-ID | Thing | Active | Covered |");
    final HolmgangException e =
        assertThrows(HolmgangException.class, () -> RtmCoverageChecker.parseLines(lines, "test"));
    assertTrue(e.getMessage().contains("unrecognizable requirement ID"));
  }

  @Test
  void a_row_with_an_unrecognized_coverage_value_fails_loudly() {
    final List<String> lines =
        List.of(
            "## Summary Table",
            "| ID | Feature | Status | Coverage |",
            "|---|---|---|---|",
            "| GIMLE-001 | Thing | Active | Maybe |");
    final HolmgangException e =
        assertThrows(HolmgangException.class, () -> RtmCoverageChecker.parseLines(lines, "test"));
    assertTrue(e.getMessage().contains("unrecognized Coverage value"));
  }

  @Test
  void a_row_with_fewer_columns_than_the_header_declared_fails_loudly() {
    final List<String> lines =
        List.of(
            "## Summary Table",
            "| ID | Feature | Status | Coverage |",
            "|---|---|---|---|",
            "| GIMLE-001 | Thing |");
    final HolmgangException e =
        assertThrows(HolmgangException.class, () -> RtmCoverageChecker.parseLines(lines, "test"));
    assertTrue(e.getMessage().contains("fewer columns"));
  }
}
