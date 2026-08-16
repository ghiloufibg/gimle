package com.gimle.core.saga;

import java.util.List;
import java.util.Optional;

/**
 * One observation in a test run's event stream, shipped by a test JVM (or folded in from Surefire
 * XML after the fact) to a Saga report server and stored there as one NDJSON line per event. The
 * stream for a run is append-only and replayable: every consumer-visible fact about a run --
 * totals, per-test outcomes, flake observations -- is derived from these events, never stored as a
 * separately-mutated counter.
 *
 * <p>A {@code testId} is {@code module:ClassName#methodName} (e.g. {@code
 * gimle-mimir:RaftClusterTest#leader_election_works}), unique within a run together with {@code
 * attempt}. Attempts count from 1; a {@link TestFinished} with {@link Outcome#FAILED} followed by
 * one with {@link Outcome#PASSED} for the same {@code testId} in the same run is precisely a flake
 * observation, matching Surefire's own rerun semantics ({@code <flakyFailure>}).
 */
public sealed interface SagaEvent {

  /** The run this event belongs to. Format: {@code <ISO-8601 basic timestamp>_<short git sha>}. */
  String runId();

  /** How a single test attempt ended. Mirrors the JUnit Platform's terminal states plus skips. */
  enum Outcome {
    PASSED,
    FAILED,
    ABORTED,
    SKIPPED
  }

  /** Opens a run. Emitted once, by the orchestrator (not the test JVMs), before any test event. */
  record RunStarted(
      String runId,
      long startedAtEpochMilli,
      String gitBranch,
      String gitSha,
      String command,
      List<String> modules)
      implements SagaEvent {
    public RunStarted {
      modules = List.copyOf(modules);
    }
  }

  /** A test attempt began. */
  record TestStarted(String runId, String testId, List<String> tags, int attempt)
      implements SagaEvent {
    public TestStarted {
      tags = List.copyOf(tags);
    }
  }

  /**
   * A test attempt ended. Failure fields are present only for {@link Outcome#FAILED}: {@code
   * failureSignature} is {@link FailureSignature#of}'s stable hash, present so the server can
   * cluster correlated failures without re-deriving normalization rules client-side.
   */
  record TestFinished(
      String runId,
      String testId,
      int attempt,
      Outcome outcome,
      long durationMillis,
      Optional<String> failureType,
      Optional<String> failureMessage,
      Optional<String> failureSignature)
      implements SagaEvent {}

  /**
   * An opaque, typed payload attached to a run: a Gherkin scenario result, a Fenrir chaos ledger, a
   * Surtr measurement set, a booted topology. {@code payloadJson} is carried and stored verbatim --
   * the server never interprets it, only the console does, keyed on {@code kind} -- so new
   * attachment kinds need no codec or server change.
   */
  record Attachment(String runId, String kind, String payloadJson) implements SagaEvent {}

  /**
   * Closes a run. Totals are the orchestrator's summary; the event stream remains authoritative.
   */
  record RunFinished(String runId, long finishedAtEpochMilli, RunTotals totals)
      implements SagaEvent {}

  /** Summary counts for a finished run. {@code flaky} counts failed-then-passed tests. */
  record RunTotals(int tests, int passed, int failed, int skipped, int flaky) {}
}
