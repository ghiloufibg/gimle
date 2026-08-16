import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpTestHistoryRepository } from "./testHistory";
import { jsonResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

const TEST_ID = "gimle-mimir:RaftClusterTest#a_redirected_write_returns_the_leader";

const HISTORY_ENTRIES = [
  {
    runId: "r1",
    startedAtEpochMilli: 1_000,
    outcome: "PASSED",
    durationMillis: 400,
    attempts: 1,
    flaky: false,
  },
  {
    runId: "r2",
    startedAtEpochMilli: 2_000,
    outcome: "FAILED",
    durationMillis: 500,
    attempts: 1,
    flaky: false,
  },
];

const RUNS_FOR_BRANCH_LOOKUP = [
  {
    runId: "r1",
    startedAtEpochMilli: 1_000,
    finishedAtEpochMilli: 1_400,
    gitBranch: "main",
    gitSha: "a",
    command: "",
    modules: [],
    status: "passed",
  },
  {
    runId: "r2",
    startedAtEpochMilli: 2_000,
    finishedAtEpochMilli: 2_500,
    gitBranch: "feature-x",
    gitSha: "b",
    command: "",
    modules: [],
    status: "failed",
  },
];

describe("HttpTestHistoryRepository", () => {
  it("maps history points with branch looked up from the runs list, and no flaky-board match", async () => {
    stubFetchSequence([
      () => jsonResponse(HISTORY_ENTRIES),
      () => jsonResponse({ entries: [], budgetAllowance: 120 }),
      () => jsonResponse(RUNS_FOR_BRANCH_LOOKUP),
    ]);

    const history = await new HttpTestHistoryRepository().getHistory(TEST_ID);

    expect(history.testId).toBe(TEST_ID);
    expect(history.module).toBe("gimle-mimir");
    expect(history.quarantined).toBe(false);
    expect(history.flakeRate).toBe(0);
    expect(history.points).toEqual([
      {
        runId: "r1",
        startedAt: new Date(1_000).toISOString(),
        branch: "main",
        outcome: "PASSED",
        attempts: 1,
        durationMillis: 400,
      },
      {
        runId: "r2",
        startedAt: new Date(2_000).toISOString(),
        branch: "feature-x",
        outcome: "FAILED",
        attempts: 1,
        durationMillis: 500,
      },
    ]);
    expect(history.signatures).toEqual([]);
  });

  it("enriches a flaky-board signature's exception type and message from a sampled failing run", async () => {
    const flakyEntry = {
      entries: [
        {
          testId: TEST_ID,
          module: "gimle-mimir",
          occurrences: 1,
          runsSeen: 5,
          flakeRate: 0.2,
          score: 1.0,
          signatures: { sig1: 1 },
          firstSeen: 2_000,
          lastSeen: 2_000,
          quarantined: true,
        },
      ],
      budgetAllowance: 120,
    };
    const failingRunEvents = {
      events: [
        {
          type: "test-finished",
          runId: "r2",
          testId: TEST_ID,
          attempt: 1,
          outcome: "FAILED",
          durationMillis: 500,
          failureType: "java.lang.AssertionError",
          failureMessage: "expected leader address",
          failureSignature: "sig1",
        },
      ],
      nextCursor: 1,
    };
    stubFetchSequence([
      () => jsonResponse(HISTORY_ENTRIES),
      () => jsonResponse(flakyEntry),
      () => jsonResponse(RUNS_FOR_BRANCH_LOOKUP),
      () => jsonResponse(failingRunEvents),
    ]);

    const history = await new HttpTestHistoryRepository().getHistory(TEST_ID);

    expect(history.quarantined).toBe(true);
    expect(history.flakeRate).toBe(0.2);
    expect(history.score).toBe(1.0);
    expect(history.signatures).toEqual([
      {
        hash: "sig1",
        count: 1,
        exceptionType: "java.lang.AssertionError",
        message: "expected leader address",
      },
    ]);
  });
});
