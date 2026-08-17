package com.gimle.holmgang.rtm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.rtm.RtmCoverageChecker.CoverageResult;
import com.gimle.holmgang.rtm.RtmCoverageChecker.RtmDocument;
import com.gimle.holmgang.rtm.RtmCoverageChecker.RtmEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers both {@code rtm.json} parsing and the coverage-checking logic. */
final class RtmCoverageCheckerTest {

  private static String validJson() {
    return
"""
        {
          "requirements": [
            {"id": "GIMLE-001", "feature": "Covered thing", "category": "Widgetry", \
"status": "Active", "coverage": "Covered"},
            {"id": "GIMLE-002", "feature": "Uncovered thing", "category": "Widgetry", \
"status": "Active", "coverage": "Not Covered"},
            {"id": "GIMLE-003", "feature": "Excluded uncovered thing", "category": "Widgetry", \
"status": "Active", "coverage": "Not Covered"},
            {"id": "GIMLE-004", "feature": "Removed thing", "category": "Widgetry", \
"status": "Removed", "coverage": "Not Covered"}
          ]
        }
""";
  }

  @Test
  void parses_every_requirement_with_its_own_fields() {
    final RtmDocument doc = RtmCoverageChecker.parseContent(validJson(), "test");
    assertEquals(4, doc.entries().size());
    final RtmEntry first = doc.entries().get(0);
    assertEquals("GIMLE-001", first.id());
    assertEquals("Covered thing", first.feature());
    assertEquals("Widgetry", first.category());
    assertEquals("Active", first.status());
    assertEquals("Covered", first.coverage());
    assertTrue(first.isCovered());
    assertTrue(first.isImplemented());
  }

  @Test
  void removed_status_is_not_implemented() {
    final RtmDocument doc = RtmCoverageChecker.parseContent(validJson(), "test");
    final RtmEntry removed =
        doc.entries().stream().filter(e -> e.id().equals("GIMLE-004")).findFirst().orElseThrow();
    assertFalse(removed.isImplemented());
  }

  @Test
  void category_is_read_directly_from_each_entry() {
    final RtmDocument doc = RtmCoverageChecker.parseContent(validJson(), "test");
    final RtmEntry entry =
        doc.entries().stream().filter(e -> e.id().equals("GIMLE-002")).findFirst().orElseThrow();
    assertEquals("Widgetry", entry.category());
  }

  @Test
  void check_counts_only_implemented_and_non_excluded_entries() {
    final RtmDocument doc = RtmCoverageChecker.parseContent(validJson(), "test");
    final CoverageResult result = RtmCoverageChecker.check(doc, Set.of("GIMLE-003"));
    // implemented (not Removed): 001, 002, 003 -- minus the excluded 003 -- leaves 001, 002
    assertEquals(2, result.totalChecked());
  }

  @Test
  void check_reports_exactly_the_not_covered_implemented_non_excluded_entries() {
    final RtmDocument doc = RtmCoverageChecker.parseContent(validJson(), "test");
    final CoverageResult result = RtmCoverageChecker.check(doc, Set.of("GIMLE-003"));
    assertEquals(1, result.notCovered().size());
    assertEquals("GIMLE-002", result.notCovered().get(0).id());
    assertFalse(result.passed());
  }

  @Test
  void check_passes_once_every_remaining_implemented_entry_is_excluded_or_covered() {
    final RtmDocument doc = RtmCoverageChecker.parseContent(validJson(), "test");
    final CoverageResult result = RtmCoverageChecker.check(doc, Set.of("GIMLE-002", "GIMLE-003"));
    assertTrue(result.passed());
    assertEquals(1, result.totalChecked()); // just GIMLE-001
  }

  @Test
  void exclude_matching_is_case_insensitive() {
    final RtmDocument doc = RtmCoverageChecker.parseContent(validJson(), "test");
    final CoverageResult result = RtmCoverageChecker.check(doc, Set.of("gimle-002", "gimle-003"));
    assertTrue(result.passed());
  }

  @Test
  void check_reports_an_excluded_id_that_never_matched_any_entry() {
    final RtmDocument doc = RtmCoverageChecker.parseContent(validJson(), "test");
    final CoverageResult result = RtmCoverageChecker.check(doc, Set.of("GIMLE-999"));
    assertEquals(List.of("GIMLE-999"), result.unknownExcludedIds());
  }

  @Test
  void extra_unrecognized_json_fields_are_ignored() {
    final String json =
"""
        {
          "metadata": {"scanDate": "2026-08-17"},
          "requirements": [
            {"id": "GIMLE-001", "feature": "Thing", "category": "Widgetry", "status": "Active", \
"coverage": "Covered", "gapNote": "", "sourceLocations": ["a.java"]}
          ]
        }
""";
    final RtmDocument doc = RtmCoverageChecker.parseContent(json, "test");
    assertEquals(1, doc.entries().size());
  }

  @Test
  void missing_file_fails_loudly(@TempDir final Path tempDir) {
    final Path missing = tempDir.resolve("does-not-exist.json");
    final HolmgangException e =
        assertThrows(HolmgangException.class, () -> RtmCoverageChecker.parse(missing));
    assertTrue(e.getMessage().contains("was not found"));
    assertTrue(e.getMessage().contains("rtmCheck.enabled=false"));
  }

  @Test
  void reads_a_real_file_from_disk(@TempDir final Path tempDir) throws Exception {
    final Path file = tempDir.resolve("rtm.json");
    Files.writeString(file, validJson());
    final RtmDocument doc = RtmCoverageChecker.parse(file);
    assertEquals(4, doc.entries().size());
  }

  @Test
  void malformed_json_fails_loudly() {
    final HolmgangException e =
        assertThrows(
            HolmgangException.class, () -> RtmCoverageChecker.parseContent("{ not json", "test"));
    assertTrue(e.getMessage().contains("not valid JSON"));
  }

  @Test
  void a_json_array_at_the_top_level_fails_loudly() {
    final HolmgangException e =
        assertThrows(HolmgangException.class, () -> RtmCoverageChecker.parseContent("[]", "test"));
    assertTrue(e.getMessage().contains("does not parse to a JSON object"));
  }

  @Test
  void a_document_with_no_requirements_array_fails_loudly() {
    final HolmgangException e =
        assertThrows(HolmgangException.class, () -> RtmCoverageChecker.parseContent("{}", "test"));
    assertTrue(e.getMessage().contains("unrecognizable"));
  }

  @Test
  void a_requirements_array_with_zero_entries_fails_loudly() {
    final HolmgangException e =
        assertThrows(
            HolmgangException.class,
            () -> RtmCoverageChecker.parseContent("{\"requirements\": []}", "test"));
    assertTrue(e.getMessage().contains("zero entries"));
  }

  @Test
  void an_entry_with_a_malformed_id_fails_loudly() {
    final String json =
"""
        {"requirements": [{"id": "NOT-AN-ID", "feature": "Thing", "category": "Widgetry", \
"status": "Active", "coverage": "Covered"}]}
""";
    final HolmgangException e =
        assertThrows(HolmgangException.class, () -> RtmCoverageChecker.parseContent(json, "test"));
    assertTrue(e.getMessage().contains("unrecognizable requirement ID"));
  }

  @Test
  void an_entry_with_an_unrecognized_coverage_value_fails_loudly() {
    final String json =
"""
        {"requirements": [{"id": "GIMLE-001", "feature": "Thing", "category": "Widgetry", \
"status": "Active", "coverage": "Maybe"}]}
""";
    final HolmgangException e =
        assertThrows(HolmgangException.class, () -> RtmCoverageChecker.parseContent(json, "test"));
    assertTrue(e.getMessage().contains("unrecognized coverage value"));
  }

  @Test
  void an_entry_missing_a_required_field_fails_loudly() {
    final String json =
"""
        {"requirements": [{"id": "GIMLE-001", "feature": "Thing", "status": "Active", \
"coverage": "Covered"}]}
"""; // no "category"
    final HolmgangException e =
        assertThrows(HolmgangException.class, () -> RtmCoverageChecker.parseContent(json, "test"));
    assertTrue(e.getMessage().contains("category"));
  }

  @Test
  void an_entry_with_a_blank_required_field_fails_loudly() {
    final String json =
"""
        {"requirements": [{"id": "GIMLE-001", "feature": "  ", "category": "Widgetry", \
"status": "Active", "coverage": "Covered"}]}
""";
    final HolmgangException e =
        assertThrows(HolmgangException.class, () -> RtmCoverageChecker.parseContent(json, "test"));
    assertTrue(e.getMessage().contains("feature"));
  }
}
