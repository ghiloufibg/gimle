package com.gimle.saga.testsupport;

import com.gimle.core.saga.SagaEvent;
import java.util.List;
import java.util.Optional;

/** Compact factories for the event shapes the saga store/server tests ingest repeatedly. */
public final class TestEvents {

  private TestEvents() {}

  public static SagaEvent.RunStarted runStarted(String runId, long startedAtEpochMilli) {
    return new SagaEvent.RunStarted(
        runId, startedAtEpochMilli, "master", "abc1234", "mvn verify", List.of("gimle-core"));
  }

  public static SagaEvent.TestStarted testStarted(String runId, String testId, int attempt) {
    return new SagaEvent.TestStarted(runId, testId, List.of(), attempt);
  }

  public static SagaEvent.TestStarted testStartedWithTags(
      String runId, String testId, int attempt, List<String> tags) {
    return new SagaEvent.TestStarted(runId, testId, tags, attempt);
  }

  public static SagaEvent.TestFinished passed(
      String runId, String testId, int attempt, long durationMillis) {
    return new SagaEvent.TestFinished(
        runId,
        testId,
        attempt,
        SagaEvent.Outcome.PASSED,
        durationMillis,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  public static SagaEvent.TestFinished failed(
      String runId, String testId, int attempt, String signature) {
    return new SagaEvent.TestFinished(
        runId,
        testId,
        attempt,
        SagaEvent.Outcome.FAILED,
        7L,
        Optional.of("java.lang.AssertionError"),
        Optional.of("expected true"),
        Optional.of(signature));
  }

  public static SagaEvent.RunFinished runFinished(
      String runId, long finishedAtEpochMilli, int tests, int passed, int failed, int flaky) {
    return new SagaEvent.RunFinished(
        runId, finishedAtEpochMilli, new SagaEvent.RunTotals(tests, passed, failed, 0, flaky));
  }
}
