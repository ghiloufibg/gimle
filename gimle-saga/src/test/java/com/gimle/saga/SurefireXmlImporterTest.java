package com.gimle.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.saga.SagaEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurefireXmlImporterTest {

  private static final String SAMPLE_XML =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="com.example.WidgetTest" tests="4" failures="1" errors="0" skipped="1"
                 time="1.5" timestamp="2026-08-16T10:15:30">
        <testcase name="adds_widgets" classname="com.example.WidgetTest" time="0.4"/>
        <testcase name="rejects_bad_widgets" classname="com.example.WidgetTest" time="0.3">
          <failure type="java.lang.AssertionError" message="expected 3 but was 4">
      java.lang.AssertionError: expected 3 but was 4
      \tat com.example.WidgetTest.rejects_bad_widgets(WidgetTest.java:42)
          </failure>
        </testcase>
        <testcase name="skipped_widget" classname="com.example.WidgetTest" time="0.0">
          <skipped message="not on this platform"/>
        </testcase>
        <testcase name="flaky_widget" classname="com.example.WidgetTest" time="0.8">
          <flakyFailure type="java.net.BindException" message="Address already in use">
      java.net.BindException: Address already in use
      \tat com.example.WidgetTest.flaky_widget(WidgetTest.java:77)
          </flakyFailure>
        </testcase>
      </testsuite>
      """;

  private static List<SagaEvent.TestFinished> finishesFor(List<SagaEvent> events, String testId) {
    return events.stream()
        .filter(e -> e instanceof SagaEvent.TestFinished f && f.testId().equals(testId))
        .map(e -> (SagaEvent.TestFinished) e)
        .toList();
  }

  @Test
  void parses_pass_fail_skip_and_flaky_testcases_into_attempt_events() {
    SurefireXmlImporter.ImportResult result = SurefireXmlImporter.fromXmlDocument(SAMPLE_XML, null);
    List<SagaEvent> events = result.events();

    assertTrue(events.get(0) instanceof SagaEvent.RunStarted);
    assertTrue(events.get(events.size() - 1) instanceof SagaEvent.RunFinished);

    List<SagaEvent.TestFinished> pass = finishesFor(events, "imported:WidgetTest#adds_widgets");
    assertEquals(1, pass.size());
    assertEquals(SagaEvent.Outcome.PASSED, pass.get(0).outcome());
    assertEquals(400L, pass.get(0).durationMillis());

    List<SagaEvent.TestFinished> fail =
        finishesFor(events, "imported:WidgetTest#rejects_bad_widgets");
    assertEquals(1, fail.size());
    assertEquals(SagaEvent.Outcome.FAILED, fail.get(0).outcome());
    assertEquals("java.lang.AssertionError", fail.get(0).failureType().orElseThrow());
    assertEquals("expected 3 but was 4", fail.get(0).failureMessage().orElseThrow());
    assertTrue(fail.get(0).failureSignature().isPresent());

    List<SagaEvent.TestFinished> skip = finishesFor(events, "imported:WidgetTest#skipped_widget");
    assertEquals(1, skip.size());
    assertEquals(SagaEvent.Outcome.SKIPPED, skip.get(0).outcome());
  }

  @Test
  void a_flaky_failure_becomes_a_failed_attempt_followed_by_a_passing_one() {
    List<SagaEvent> events = SurefireXmlImporter.fromXmlDocument(SAMPLE_XML, null).events();

    List<SagaEvent.TestFinished> flaky = finishesFor(events, "imported:WidgetTest#flaky_widget");
    assertEquals(2, flaky.size());
    assertEquals(SagaEvent.Outcome.FAILED, flaky.get(0).outcome());
    assertEquals(1, flaky.get(0).attempt());
    assertEquals("java.net.BindException", flaky.get(0).failureType().orElseThrow());
    assertEquals(SagaEvent.Outcome.PASSED, flaky.get(1).outcome());
    assertEquals(2, flaky.get(1).attempt());
  }

  @Test
  void totals_count_final_outcomes_and_flaky_testcases() {
    List<SagaEvent> events = SurefireXmlImporter.fromXmlDocument(SAMPLE_XML, null).events();

    SagaEvent.RunFinished finished = (SagaEvent.RunFinished) events.get(events.size() - 1);
    assertEquals(4, finished.totals().tests());
    assertEquals(2, finished.totals().passed());
    assertEquals(1, finished.totals().failed());
    assertEquals(1, finished.totals().skipped());
    assertEquals(1, finished.totals().flaky());
  }

  @Test
  void the_run_start_time_comes_from_the_testsuite_timestamp() {
    SagaEvent.RunStarted started =
        (SagaEvent.RunStarted)
            SurefireXmlImporter.fromXmlDocument(SAMPLE_XML, null).events().get(0);

    // 2026-08-16T10:15:30 UTC.
    assertEquals(1786875330000L, started.startedAtEpochMilli());
  }

  @Test
  void the_derived_run_id_is_deterministic_for_identical_content() {
    String first = SurefireXmlImporter.fromXmlDocument(SAMPLE_XML, null).runId();
    String second = SurefireXmlImporter.fromXmlDocument(SAMPLE_XML, null).runId();
    String different =
        SurefireXmlImporter.fromXmlDocument(SAMPLE_XML.replace("adds_widgets", "other"), null)
            .runId();

    assertEquals(first, second);
    assertNotEquals(first, different);
    assertTrue(first.startsWith("import-"));
  }

  @Test
  void an_explicit_run_id_overrides_the_derived_one() {
    assertEquals("my-run", SurefireXmlImporter.fromXmlDocument(SAMPLE_XML, "my-run").runId());
  }

  @Test
  void unparseable_xml_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SurefireXmlImporter.fromXmlDocument("<testsuite><unclosed>", null));
  }

  @Test
  void from_paths_scans_directories_and_derives_the_module_from_the_target_segment(
      @TempDir Path tempDir) throws Exception {
    Path reports = tempDir.resolve("gimle-widget").resolve("target").resolve("surefire-reports");
    Files.createDirectories(reports);
    Files.writeString(reports.resolve("TEST-com.example.WidgetTest.xml"), SAMPLE_XML);

    SurefireXmlImporter.ImportResult result = SurefireXmlImporter.fromPaths(List.of(reports), null);

    assertTrue(
        result.events().stream()
            .anyMatch(
                e ->
                    e instanceof SagaEvent.TestFinished f
                        && f.testId().equals("gimle-widget:WidgetTest#adds_widgets")));
    SagaEvent.RunStarted started = (SagaEvent.RunStarted) result.events().get(0);
    assertEquals(List.of("gimle-widget"), started.modules());
  }

  @Test
  void a_missing_path_is_rejected(@TempDir Path tempDir) {
    assertThrows(
        IllegalArgumentException.class,
        () -> SurefireXmlImporter.fromPaths(List.of(tempDir.resolve("nope")), null));
  }

  @Test
  void a_directory_with_no_xml_files_is_rejected(@TempDir Path tempDir) {
    assertThrows(
        IllegalArgumentException.class,
        () -> SurefireXmlImporter.fromPaths(List.of(tempDir), null));
  }
}
