import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpRunsRepository } from "./runs";
import { jsonResponse, ndjsonStreamResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

async function waitUntil(condition: () => boolean, timeoutMs = 1000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!condition()) {
    if (Date.now() > deadline) throw new Error("condition not met within " + timeoutMs + "ms");
    await new Promise((r) => setTimeout(r, 5));
  }
}

const RUN_META = {
  runId: "r1",
  startedAtEpochMilli: 1_000,
  finishedAtEpochMilli: 5_000,
  gitBranch: "main",
  gitSha: "abc1234",
  command: "mvn verify",
  modules: [],
  status: "passed",
  totals: { tests: 2, passed: 2, failed: 0, skipped: 0, flaky: 0 },
};

const EVENTS_PAGE = {
  events: [
    { type: "run-started", runId: "r1" },
    {
      type: "test-finished",
      runId: "r1",
      testId: "gimle-core:A#a",
      attempt: 1,
      outcome: "PASSED",
      durationMillis: 5,
    },
    {
      type: "test-finished",
      runId: "r1",
      testId: "gimle-core:B#b",
      attempt: 1,
      outcome: "FAILED",
      durationMillis: 5,
    },
    { type: "run-finished", runId: "r1" },
  ],
  nextCursor: 4,
};

describe("HttpRunsRepository", () => {
  it("listRuns fetches /api/runs and maps every entry", async () => {
    const fetch = stubFetchSequence([() => jsonResponse([RUN_META])]);

    const runs = await new HttpRunsRepository().listRuns();

    expect(fetch.mock.calls[0]?.[0]).toBe("/api/runs");
    expect(runs).toEqual([
      expect.objectContaining({ runId: "r1", branch: "main", status: "passed" }),
    ]);
  });

  it("getRun fetches meta and events together and derives module progress plus attachments", async () => {
    stubFetchSequence([() => jsonResponse(RUN_META), () => jsonResponse(EVENTS_PAGE)]);

    const detail = await new HttpRunsRepository().getRun("r1");

    expect(detail.run.runId).toBe("r1");
    expect(detail.moduleProgress).toEqual([
      { module: "gimle-core", total: 2, completed: 2, failed: 1 },
    ]);
    expect(detail.attachments).toEqual({});
  });

  it("getRunEvents returns only finished-test events, mapped", async () => {
    stubFetchSequence([() => jsonResponse(EVENTS_PAGE)]);

    const events = await new HttpRunsRepository().getRunEvents("r1");

    expect(events).toHaveLength(2);
    expect(events.map((e) => e.testId)).toEqual(["gimle-core:A#a", "gimle-core:B#b"]);
  });

  it("followRunEvents streams new finished-test events and skips the already-known count", async () => {
    const lines = [
      JSON.stringify({
        type: "test-finished",
        runId: "r1",
        testId: "a:A#a",
        attempt: 1,
        outcome: "PASSED",
        durationMillis: 1,
      }),
      JSON.stringify({ type: "test-started", runId: "r1", testId: "a:B#b" }),
      JSON.stringify({
        type: "test-finished",
        runId: "r1",
        testId: "a:B#b",
        attempt: 1,
        outcome: "FAILED",
        durationMillis: 1,
      }),
    ];
    stubFetchSequence([() => ndjsonStreamResponse(lines)]);

    const received: string[] = [];
    const unsubscribe = new HttpRunsRepository().followRunEvents("r1", 1, (e) =>
      received.push(e.testId),
    );
    await waitUntil(() => received.length > 0);

    expect(received).toEqual(["a:B#b"]);
    unsubscribe();
  });

  it("compareRuns fetches both runs and diffs their events", async () => {
    const baseMeta = { ...RUN_META, runId: "base" };
    const headMeta = { ...RUN_META, runId: "head" };
    const baseEvents = {
      events: [
        {
          type: "test-finished",
          runId: "base",
          testId: "a:A#a",
          attempt: 1,
          outcome: "PASSED",
          durationMillis: 5,
        },
      ],
      nextCursor: 1,
    };
    const headEvents = {
      events: [
        {
          type: "test-finished",
          runId: "head",
          testId: "a:A#a",
          attempt: 1,
          outcome: "FAILED",
          durationMillis: 5,
        },
      ],
      nextCursor: 1,
    };
    stubFetchSequence([
      () => jsonResponse(baseMeta),
      () => jsonResponse(headMeta),
      () => jsonResponse(baseEvents),
      () => jsonResponse(headEvents),
    ]);

    const result = await new HttpRunsRepository().compareRuns("base", "head");

    expect(result.base.runId).toBe("base");
    expect(result.head.runId).toBe("head");
    expect(result.newlyFailing).toEqual(["a:A#a"]);
  });
});
