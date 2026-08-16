import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpFlakyRepository } from "./flaky";
import { jsonResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

const RESPONSE = {
  entries: [
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
      quarantined: true,
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
      quarantined: false,
    },
  ],
  budgetAllowance: 50,
};

describe("HttpFlakyRepository", () => {
  it("fetches the window from /api/flaky and ranks by score descending", async () => {
    const fetch = stubFetchSequence([() => jsonResponse(RESPONSE)]);

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
    stubFetchSequence([() => jsonResponse(RESPONSE)]);

    const board = await new HttpFlakyRepository().getBoard({
      module: "gimle-fabric",
      window: 30,
      quarantinedOnly: false,
    });

    expect(board.entries).toHaveLength(1);
    expect(board.entries[0]?.testId).toBe("gimle-fabric:GossipTest#b");
  });

  it("reads the real per-entry quarantine flag from the server", async () => {
    stubFetchSequence([() => jsonResponse(RESPONSE)]);

    const board = await new HttpFlakyRepository().getBoard({
      module: "all",
      window: 30,
      quarantinedOnly: false,
    });

    expect(
      board.entries.find((e) => e.testId === "gimle-mimir:RaftClusterTest#a")?.quarantined,
    ).toBe(true);
    expect(board.entries.find((e) => e.testId === "gimle-fabric:GossipTest#b")?.quarantined).toBe(
      false,
    );
    expect(board.summary.activeFlaky).toBe(1);
  });

  it("quarantinedOnly filters to just the quarantined entries", async () => {
    stubFetchSequence([() => jsonResponse(RESPONSE)]);

    const board = await new HttpFlakyRepository().getBoard({
      module: "all",
      window: 30,
      quarantinedOnly: true,
    });

    expect(board.entries.map((e) => e.testId)).toEqual(["gimle-mimir:RaftClusterTest#a"]);
  });

  it("computes budget spent from real occurrences and identifies the worst offender", async () => {
    stubFetchSequence([() => jsonResponse(RESPONSE)]);

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

  it("reads the server-configured budget allowance instead of a hardcoded reference value", async () => {
    stubFetchSequence([() => jsonResponse(RESPONSE)]);

    const board = await new HttpFlakyRepository().getBoard({
      module: "all",
      window: 30,
      quarantinedOnly: false,
    });

    expect(board.summary.budgetAllowance).toBe(50);
    expect(board.summary.budgetUsedPct).toBe(Math.round((4 / 50) * 100));
  });

  it("never divides by zero when the server reports a zero budget allowance", async () => {
    stubFetchSequence([() => jsonResponse({ entries: RESPONSE.entries, budgetAllowance: 0 })]);

    const board = await new HttpFlakyRepository().getBoard({
      module: "all",
      window: 30,
      quarantinedOnly: false,
    });

    expect(board.summary.budgetAllowance).toBe(0);
    expect(board.summary.budgetUsedPct).toBe(0);
  });
});
