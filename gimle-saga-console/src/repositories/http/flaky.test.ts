import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpFlakyRepository } from "./flaky";
import { jsonResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

const ENTRIES = [
  {
    testId: "gimle-mimir:RaftClusterTest#a",
    module: "gimle-mimir",
    occurrences: 3,
    runsSeen: 10,
    flakeRate: 0.3,
    score: 3.0,
    signatures: { abc123: 3 },
    firstSeen: 1_000,
    lastSeen: 9_000,
  },
  {
    testId: "gimle-fabric:GossipTest#b",
    module: "gimle-fabric",
    occurrences: 1,
    runsSeen: 10,
    flakeRate: 0.1,
    score: 1.0,
    signatures: { def456: 1 },
    firstSeen: 2_000,
    lastSeen: 2_000,
  },
];

describe("HttpFlakyRepository", () => {
  it("fetches the window from /api/flaky and ranks by score descending", async () => {
    const fetch = stubFetchSequence([() => jsonResponse(ENTRIES)]);

    const board = await new HttpFlakyRepository().getBoard({
      module: "all",
      window: 30,
      quarantinedOnly: false,
    });

    expect(fetch.mock.calls[0]?.[0]).toBe("/api/flaky?window=30");
    expect(board.entries.map((e) => e.testId)).toEqual([
      "gimle-mimir:RaftClusterTest#a",
      "gimle-fabric:GossipTest#b",
    ]);
    expect(board.modules).toEqual(["gimle-fabric", "gimle-mimir"]);
  });

  it("filters by module client-side", async () => {
    stubFetchSequence([() => jsonResponse(ENTRIES)]);

    const board = await new HttpFlakyRepository().getBoard({
      module: "gimle-fabric",
      window: 30,
      quarantinedOnly: false,
    });

    expect(board.entries).toHaveLength(1);
    expect(board.entries[0]?.testId).toBe("gimle-fabric:GossipTest#b");
  });

  it("marks every entry unquarantined, since the server has no such signal yet", async () => {
    stubFetchSequence([() => jsonResponse(ENTRIES)]);

    const board = await new HttpFlakyRepository().getBoard({
      module: "all",
      window: 30,
      quarantinedOnly: false,
    });

    expect(board.entries.every((e) => !e.quarantined)).toBe(true);
    expect(board.summary.activeFlaky).toBe(board.entries.length);
  });

  it("quarantinedOnly filters to an empty board today", async () => {
    stubFetchSequence([() => jsonResponse(ENTRIES)]);

    const board = await new HttpFlakyRepository().getBoard({
      module: "all",
      window: 30,
      quarantinedOnly: true,
    });

    expect(board.entries).toEqual([]);
  });

  it("computes budget spent from real occurrences and identifies the worst offender", async () => {
    stubFetchSequence([() => jsonResponse(ENTRIES)]);

    const board = await new HttpFlakyRepository().getBoard({
      module: "all",
      window: 30,
      quarantinedOnly: false,
    });

    expect(board.summary.budgetSpent).toBe(4);
    expect(board.summary.worstOffender).toEqual({
      testId: "gimle-mimir:RaftClusterTest#a",
      score: 3.0,
    });
  });
});
