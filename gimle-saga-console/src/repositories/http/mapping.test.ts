import { describe, expect, it } from "vitest";
import type { TestResultEvent } from "@/domain/types";
import {
  collectAttachments,
  computeModuleProgress,
  diffRuns,
  mapRun,
  mapTestFinishedEvent,
  type RawEvent,
  type RawRunMeta,
} from "./mapping";

describe("mapRun", () => {
  it("maps a finished run's totals and timestamps", () => {
    const run = mapRun({
      runId: "r1",
      startedAtEpochMilli: 1_000,
      finishedAtEpochMilli: 5_000,
      gitBranch: "main",
      gitSha: "abc1234",
      command: "mvn verify",
      modules: ["gimle-core"],
      status: "passed",
      totals: { tests: 10, passed: 9, failed: 1, skipped: 0, flaky: 1 },
    });
    expect(run).toEqual({
      runId: "r1",
      startedAt: new Date(1_000).toISOString(),
      finishedAt: new Date(5_000).toISOString(),
      gitSha: "abc1234",
      branch: "main",
      command: "mvn verify",
      status: "passed",
      totals: { tests: 10, passed: 9, failed: 1, skipped: 0, flaky: 1 },
      modules: ["gimle-core"],
    });
  });

  it("leaves finishedAt undefined for a live run and defaults absent totals to zero", () => {
    const run = mapRun({
      runId: "r2",
      startedAtEpochMilli: 1_000,
      finishedAtEpochMilli: 0,
      gitBranch: "",
      gitSha: "",
      command: "",
      modules: [],
      status: "live",
    });
    expect(run.finishedAt).toBeUndefined();
    expect(run.totals).toEqual({ tests: 0, passed: 0, failed: 0, skipped: 0, flaky: 0 });
  });
});

describe("mapTestFinishedEvent", () => {
  it("maps a passing attempt", () => {
    const event = mapTestFinishedEvent({
      type: "test-finished",
      runId: "r1",
      testId: "gimle-core:JsonTest#round_trips",
      attempt: 1,
      outcome: "PASSED",
      durationMillis: 42,
    });
    expect(event?.testId).toBe("gimle-core:JsonTest#round_trips");
    expect(event?.outcome).toBe("PASSED");
    expect(event?.durationMillis).toBe(42);
    expect(event?.failureType).toBeUndefined();
  });

  it("carries failure fields for a failed attempt", () => {
    const event = mapTestFinishedEvent({
      type: "test-finished",
      runId: "r1",
      testId: "gimle-mimir:RaftClusterTest#leader_election_works",
      attempt: 1,
      outcome: "FAILED",
      durationMillis: 10,
      failureType: "java.util.concurrent.TimeoutException",
      failureMessage: "proposal not committed",
      failureSignature: "abc123",
    });
    expect(event).toMatchObject({
      failureType: "java.util.concurrent.TimeoutException",
      failureMessage: "proposal not committed",
      failureSignature: "abc123",
    });
  });

  it("returns null for non-test-finished event types", () => {
    expect(mapTestFinishedEvent({ type: "test-started", runId: "r1" })).toBeNull();
    expect(mapTestFinishedEvent({ type: "run-finished", runId: "r1" })).toBeNull();
  });
});

describe("computeModuleProgress", () => {
  const events: RawEvent[] = [
    { type: "test-finished", runId: "r1", testId: "gimle-core:A#a", outcome: "PASSED" },
    { type: "test-finished", runId: "r1", testId: "gimle-core:B#b", outcome: "FAILED" },
    { type: "test-finished", runId: "r1", testId: "gimle-mimir:C#c", outcome: "PASSED" },
    { type: "test-started", runId: "r1", testId: "gimle-mimir:D#d" },
  ];

  it("counts completed and failed per module from observed testIds, ignoring non-finished events", () => {
    const progress = computeModuleProgress(events, []);
    expect(progress).toEqual([
      { module: "gimle-core", total: 2, completed: 2, failed: 1 },
      { module: "gimle-mimir", total: 1, completed: 1, failed: 0 },
    ]);
  });

  it("includes a declared module with zero observed events", () => {
    const progress = computeModuleProgress([], ["gimle-fabric"]);
    expect(progress).toEqual([{ module: "gimle-fabric", total: 0, completed: 0, failed: 0 }]);
  });
});

describe("collectAttachments", () => {
  it("groups attachment events by kind and skips unparseable or unrecognized payloads", () => {
    const gherkin = { name: "Rolling update", module: "gimle-smoke-tests", scenarios: [] };
    const events: RawEvent[] = [
      {
        type: "attachment",
        runId: "r1",
        kind: "gherkin-scenario",
        payloadJson: JSON.stringify(gherkin),
      },
      { type: "attachment", runId: "r1", kind: "fenrir-ledger", payloadJson: "not json" },
      { type: "attachment", runId: "r1", kind: "unknown-kind", payloadJson: "{}" },
      { type: "test-finished", runId: "r1", testId: "a:b#c", outcome: "PASSED" },
    ];
    expect(collectAttachments(events)).toEqual({ gherkin: [gherkin] });
  });

  it("returns an empty object when no attachments are present", () => {
    expect(collectAttachments([])).toEqual({});
  });

  it("accepts a payload shipped as an array of the shape", () => {
    const strikes = [
      { atMillis: 0, kind: "worker-kill", target: "n1", recoveryMillis: 500, recovered: true },
    ];
    const events: RawEvent[] = [
      {
        type: "attachment",
        runId: "r1",
        kind: "fenrir-ledger",
        payloadJson: JSON.stringify(strikes),
      },
    ];
    expect(collectAttachments(events).chaosLedger).toEqual(strikes);
  });
});

describe("diffRuns", () => {
  const event = (over: Partial<TestResultEvent>): TestResultEvent => ({
    runId: "r",
    testId: "gimle-core:A#a",
    attempt: 1,
    outcome: "PASSED",
    durationMillis: 100,
    finishedAt: new Date().toISOString(),
    ...over,
  });

  it("finds newly failing, newly passing, and duration regressions over 25%", () => {
    const base: TestResultEvent[] = [
      event({ testId: "t-fail", outcome: "PASSED" }),
      event({ testId: "t-pass", outcome: "FAILED" }),
      event({ testId: "t-slow", outcome: "PASSED", durationMillis: 300 }),
    ];
    const head: TestResultEvent[] = [
      event({ testId: "t-fail", outcome: "FAILED" }),
      event({ testId: "t-pass", outcome: "PASSED" }),
      event({ testId: "t-slow", outcome: "PASSED", durationMillis: 700 }),
    ];
    const diff = diffRuns(base, head);
    expect(diff.newlyFailing).toEqual(["t-fail"]);
    expect(diff.newlyPassing).toEqual(["t-pass"]);
    expect(diff.regressions).toEqual([
      { testId: "t-slow", baseMillis: 300, headMillis: 700, deltaPct: ((700 - 300) / 300) * 100 },
    ]);
  });

  it("finds a test that recovered from a failure in the head run but not the base run", () => {
    const base: TestResultEvent[] = [event({ testId: "t-flaky", attempt: 1, outcome: "PASSED" })];
    const head: TestResultEvent[] = [
      event({ testId: "t-flaky", attempt: 1, outcome: "FAILED" }),
      event({ testId: "t-flaky", attempt: 2, outcome: "PASSED" }),
    ];
    expect(diffRuns(base, head).newlyFlaky).toEqual(["t-flaky"]);
  });

  it("ignores tests that only appear in one of the two runs", () => {
    const base = [event({ testId: "only-base" })];
    const head = [event({ testId: "only-head" })];
    expect(diffRuns(base, head)).toEqual({
      newlyFailing: [],
      newlyPassing: [],
      newlyFlaky: [],
      regressions: [],
    });
  });
});
