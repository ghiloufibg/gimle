package com.gimle.saga;

import static com.gimle.saga.testsupport.TestEvents.failed;
import static com.gimle.saga.testsupport.TestEvents.passed;
import static com.gimle.saga.testsupport.TestEvents.runFinished;
import static com.gimle.saga.testsupport.TestEvents.runStarted;
import static com.gimle.saga.testsupport.TestEvents.testStarted;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.saga.SagaEvent;
import com.gimle.core.saga.SagaEventCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SagaStoreTest {

  private static final String TEST_ID = "gimle-core:JsonTest#round_trips";

  @TempDir Path tempDir;

  private SagaStore store;

  @BeforeEach
  void setUp() {
    store = new SagaStore(tempDir);
  }

  private Path eventsFile(String runId) {
    return tempDir.resolve("runs").resolve(runId).resolve("events.ndjson");
  }

  private Path ledgerFile() {
    return tempDir.resolve("index").resolve("flake-ledger.ndjson");
  }

  private void ingestFlakyRun(String runId, long startedAt) {
    store.ingest(
        List.of(
            runStarted(runId, startedAt),
            testStarted(runId, TEST_ID, 1),
            failed(runId, TEST_ID, 1, "deadbeef00000001"),
            testStarted(runId, TEST_ID, 2),
            passed(runId, TEST_ID, 2, 12L),
            runFinished(runId, startedAt + 100, 1, 1, 0, 1)));
  }

  @Test
  void ingest_then_read_round_trips_events_and_meta() {
    store.ingest(
        List.of(
            runStarted("run-1", 1000L),
            testStarted("run-1", TEST_ID, 1),
            passed("run-1", TEST_ID, 1, 42L),
            runFinished("run-1", 2000L, 1, 1, 0, 0)));

    SagaStore.EventsPage page = store.readEvents("run-1", 0);
    assertEquals(4, page.lines().size());
    assertEquals(4, page.nextCursor());
    assertTrue(page.lines().get(0).contains("run-started"));
    assertTrue(page.lines().get(3).contains("run-finished"));

    RunMeta meta = store.run("run-1").orElseThrow();
    assertEquals(1000L, meta.startedAtEpochMilli());
    assertEquals(2000L, meta.finishedAtEpochMilli());
    assertEquals("master", meta.gitBranch());
    assertEquals(RunMeta.RunStatus.PASSED, meta.status());
    assertEquals(1, meta.totals().orElseThrow().passed());
  }

  @Test
  void a_finished_run_with_failures_reports_status_failed() {
    store.ingest(
        List.of(
            runStarted("run-f", 1000L),
            testStarted("run-f", TEST_ID, 1),
            failed("run-f", TEST_ID, 1, "deadbeef00000001"),
            runFinished("run-f", 2000L, 1, 0, 1, 0)));

    assertEquals(RunMeta.RunStatus.FAILED, store.run("run-f").orElseThrow().status());
  }

  @Test
  void the_events_cursor_resumes_from_a_line_offset() {
    store.ingest(List.of(runStarted("run-c", 1000L), testStarted("run-c", TEST_ID, 1)));

    SagaStore.EventsPage first = store.readEvents("run-c", 0);
    assertEquals(2, first.nextCursor());

    store.ingest(List.of(passed("run-c", TEST_ID, 1, 5L)));
    SagaStore.EventsPage rest = store.readEvents("run-c", first.nextCursor());
    assertEquals(1, rest.lines().size());
    assertTrue(rest.lines().get(0).contains("test-finished"));
  }

  @Test
  void a_torn_trailing_line_is_skipped_on_read() throws Exception {
    store.ingest(List.of(runStarted("run-t", 1000L)));
    Files.writeString(eventsFile("run-t"), "{\"type\":\"test-sta", StandardOpenOption.APPEND);

    SagaStore.EventsPage page = store.readEvents("run-t", 0);
    assertEquals(1, page.lines().size());
    assertTrue(page.lines().get(0).contains("run-started"));
  }

  @Test
  void an_append_after_a_torn_line_never_fuses_two_events_into_one() throws Exception {
    store.ingest(List.of(runStarted("run-t2", 1000L)));
    Files.writeString(eventsFile("run-t2"), "{\"type\":\"test-sta", StandardOpenOption.APPEND);

    store.ingest(List.of(testStarted("run-t2", TEST_ID, 1)));

    SagaStore.EventsPage page = store.readEvents("run-t2", 0);
    assertEquals(2, page.lines().size());
    // Every stored line must decode cleanly -- the torn fragment was truncated, not fused.
    for (SagaEvent event : page.lines().stream().map(SagaEventCodec::decode).toList()) {
      assertEquals("run-t2", event.runId());
    }
  }

  @Test
  void a_failed_attempt_followed_by_a_passing_retry_yields_one_flake_observation() {
    ingestFlakyRun("run-flake", 1000L);

    List<FlakeObservation> observations = store.flakeObservations();
    assertEquals(1, observations.size());
    assertEquals(TEST_ID, observations.get(0).testId());
    assertEquals("run-flake", observations.get(0).runId());
    assertEquals("deadbeef00000001", observations.get(0).failureSignature());
    assertEquals(1100L, observations.get(0).dateEpochMilli());
  }

  @Test
  void a_test_that_fails_every_attempt_yields_no_flake_observation() {
    store.ingest(
        List.of(
            runStarted("run-hard", 1000L),
            testStarted("run-hard", TEST_ID, 1),
            failed("run-hard", TEST_ID, 1, "deadbeef00000001"),
            testStarted("run-hard", TEST_ID, 2),
            failed("run-hard", TEST_ID, 2, "deadbeef00000001"),
            runFinished("run-hard", 2000L, 1, 0, 1, 0)));

    assertTrue(store.flakeObservations().isEmpty());
  }

  @Test
  void re_ingesting_a_whole_run_replaces_it_without_double_counting_the_ledger() {
    ingestFlakyRun("run-again", 1000L);
    ingestFlakyRun("run-again", 1000L);

    assertEquals(6, store.readEvents("run-again", 0).lines().size());
    assertEquals(1, store.flakeObservations().size());
    assertEquals(RunMeta.RunStatus.PASSED, store.run("run-again").orElseThrow().status());
  }

  @Test
  void rebuild_ledger_reproduces_the_derived_observations_from_scratch() throws Exception {
    ingestFlakyRun("run-a", 1000L);
    ingestFlakyRun("run-b", 2000L);
    List<FlakeObservation> before = store.flakeObservations();
    Files.delete(ledgerFile());

    store.rebuildLedger();

    assertEquals(before, store.flakeObservations());
  }

  @Test
  void an_unparseable_ledger_line_is_skipped_not_fatal() throws Exception {
    ingestFlakyRun("run-ok", 1000L);
    Files.writeString(ledgerFile(), "not json at all\n", StandardOpenOption.APPEND);

    List<FlakeObservation> observations = store.flakeObservations();
    assertEquals(1, observations.size());
    assertEquals("run-ok", observations.get(0).runId());
  }

  @Test
  void a_live_run_is_marked_abandoned_at_startup() {
    store.ingest(List.of(runStarted("run-live", 1000L), testStarted("run-live", TEST_ID, 1)));
    assertEquals(RunMeta.RunStatus.LIVE, store.run("run-live").orElseThrow().status());

    SagaStore reopened = new SagaStore(tempDir);

    assertEquals(RunMeta.RunStatus.ABANDONED, reopened.run("run-live").orElseThrow().status());
  }

  @Test
  void runs_list_newest_first_and_honors_the_limit() {
    store.ingest(List.of(runStarted("run-old", 1000L)));
    store.ingest(List.of(runStarted("run-mid", 2000L)));
    store.ingest(List.of(runStarted("run-new", 3000L)));

    List<RunMeta> all = store.listRuns(10);
    assertEquals(
        List.of("run-new", "run-mid", "run-old"), all.stream().map(RunMeta::runId).toList());
    assertEquals(2, store.listRuns(2).size());
  }

  @Test
  void test_history_reports_final_outcome_and_flakiness_per_run_newest_first() {
    ingestFlakyRun("run-1", 1000L);
    store.ingest(
        List.of(
            runStarted("run-2", 2000L),
            testStarted("run-2", TEST_ID, 1),
            passed("run-2", TEST_ID, 1, 9L),
            runFinished("run-2", 2100L, 1, 1, 0, 0)));

    List<SagaStore.TestHistoryEntry> history = store.testHistory(TEST_ID);
    assertEquals(2, history.size());
    assertEquals("run-2", history.get(0).runId());
    assertEquals(SagaEvent.Outcome.PASSED, history.get(0).outcome());
    assertFalse(history.get(0).flaky());
    assertEquals("run-1", history.get(1).runId());
    assertEquals(2, history.get(1).attempts());
    assertTrue(history.get(1).flaky());
  }

  @Test
  void the_flaky_scoreboard_counts_runs_seen_and_ranks_by_score() {
    ingestFlakyRun("run-1", 1000L);
    store.ingest(
        List.of(
            runStarted("run-2", 2000L),
            testStarted("run-2", TEST_ID, 1),
            passed("run-2", TEST_ID, 1, 9L),
            runFinished("run-2", 2100L, 1, 1, 0, 0)));

    List<SagaStore.FlakySummary> scoreboard = store.flakyScoreboard(0L);
    assertEquals(1, scoreboard.size());
    SagaStore.FlakySummary summary = scoreboard.get(0);
    assertEquals(TEST_ID, summary.testId());
    assertEquals("gimle-core", summary.module());
    assertEquals(1, summary.occurrences());
    assertEquals(2, summary.runsSeen());
    assertEquals(0.5, summary.flakeRate());
    assertEquals(Integer.valueOf(1), summary.signatures().get("deadbeef00000001"));
    assertEquals(1100L, summary.firstSeen());
    assertEquals(1100L, summary.lastSeen());
  }

  @Test
  void the_flaky_scoreboard_window_excludes_older_observations() {
    ingestFlakyRun("run-ancient", 1000L);

    assertFalse(store.flakyScoreboard(0L).isEmpty());
    assertTrue(store.flakyScoreboard(50_000L).isEmpty());
  }

  @Test
  void a_run_id_that_could_escape_the_store_directory_is_rejected() {
    assertThrows(
        IllegalArgumentException.class, () -> store.ingest(List.of(runStarted("../evil", 1000L))));
    assertThrows(IllegalArgumentException.class, () -> store.readEvents("a/b", 0));
  }

  @Test
  void reading_an_unknown_run_returns_an_empty_page_and_no_meta() {
    assertTrue(store.readEvents("run-none", 0).lines().isEmpty());
    assertTrue(store.run("run-none").isEmpty());
  }

  @Test
  void a_run_with_events_but_no_meta_file_is_reconstructed_from_its_events() throws Exception {
    store.ingest(List.of(runStarted("run-m", 1000L), runFinished("run-m", 1500L, 0, 0, 0, 0)));
    Files.delete(tempDir.resolve("runs").resolve("run-m").resolve("meta.json"));

    RunMeta meta = store.run("run-m").orElseThrow();
    assertEquals(1000L, meta.startedAtEpochMilli());
    assertEquals(RunMeta.RunStatus.PASSED, meta.status());
  }

  @Test
  void an_undecodable_interior_event_line_is_skipped_when_deriving_state() throws Exception {
    store.ingest(List.of(runStarted("run-g", 1000L)));
    Files.writeString(eventsFile("run-g"), "{\"type\":\"garbage\"}\n", StandardOpenOption.APPEND);
    store.ingest(List.of(runFinished("run-g", 1500L, 0, 0, 0, 0)));

    RunMeta meta = store.run("run-g").orElseThrow();
    assertEquals(RunMeta.RunStatus.PASSED, meta.status());
    assertEquals(3, store.readEvents("run-g", 0).lines().size());
    assertTrue(store.flakeObservations().isEmpty());
  }

  @Test
  void fold_appends_only_test_ids_the_live_stream_never_finished_and_drops_framing() {
    String otherTest = "gimle-core:OtherTest#covers_the_dead_jvm_case";
    store.ingest(
        List.of(
            runStarted("run-f", 1000L),
            testStarted("run-f", TEST_ID, 1),
            passed("run-f", TEST_ID, 1, 5L)));

    int appended =
        store.fold(
            "run-f",
            List.of(
                runStarted("run-f", 1000L),
                testStarted("run-f", TEST_ID, 1),
                passed("run-f", TEST_ID, 1, 5L),
                testStarted("run-f", otherTest, 1),
                passed("run-f", otherTest, 1, 9L),
                runFinished("run-f", 2000L, 2, 2, 0, 0)));

    assertEquals(2, appended);
    List<String> lines = store.readEvents("run-f", 0).lines();
    assertEquals(5, lines.size());
    long duplicates =
        lines.stream()
            .map(SagaEventCodec::decode)
            .filter(e -> e instanceof SagaEvent.TestFinished f && f.testId().equals(TEST_ID))
            .count();
    assertEquals(1, duplicates);
    assertEquals(RunMeta.RunStatus.LIVE, store.run("run-f").orElseThrow().status());
  }

  @Test
  void fold_without_an_existing_run_ingests_the_batch_unmodified() {
    int appended =
        store.fold(
            "run-i",
            List.of(
                runStarted("run-i", 1000L),
                testStarted("run-i", TEST_ID, 1),
                passed("run-i", TEST_ID, 1, 5L),
                runFinished("run-i", 2000L, 1, 1, 0, 0)));

    assertEquals(4, appended);
    assertEquals(4, store.readEvents("run-i", 0).lines().size());
    assertEquals(RunMeta.RunStatus.PASSED, store.run("run-i").orElseThrow().status());
  }
}
