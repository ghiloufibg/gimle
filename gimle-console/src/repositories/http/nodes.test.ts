import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpNodesRepository } from "./nodes";
import { jsonResponse, stubFetchSequence } from "./testUtil";
import { isStale } from "@/lib/format";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("HttpNodesRepository", () => {
  // Regression test for a real bug found during manual verification: ApiServer.java's
  // handleNodesList omits `lastHeartbeatAt`/`capacity` entirely (not `null`) for a node that has
  // never heartbeated. metrics.tsx does a strict `=== null` check to mean "never heartbeated,"
  // which `undefined` silently fails.
  it("normalizes a missing lastHeartbeatAt to null, not undefined, when a node has never heartbeated", async () => {
    stubFetchSequence([
      () =>
        jsonResponse([
          {
            nodeId: "node-fresh",
            capabilities: { supportedTiers: ["TIER_1"] },
            // lastHeartbeatAt and capacity both omitted, matching the real wire shape.
          },
        ]),
    ]);
    const repo = new HttpNodesRepository();

    const page = await repo.fetchPage({ cursor: null, pageSize: 10 });

    expect(page.items[0].lastHeartbeatAt).toBeNull();
    expect(isStale(page.items[0].lastHeartbeatAt)).toBe(true);
  });

  it("defaults capacity to zeroed values when the wire response omits it", async () => {
    stubFetchSequence([
      () => jsonResponse([{ nodeId: "node-fresh", capabilities: { supportedTiers: ["TIER_1"] } }]),
    ]);
    const repo = new HttpNodesRepository();

    const page = await repo.fetchPage({ cursor: null, pageSize: 10 });

    expect(page.items[0].capacity).toEqual({
      totalMemoryBytes: 0,
      assignedMemoryBytes: 0,
      totalCpuMillicores: 0,
      assignedCpuMillicores: 0,
    });
  });

  it("preserves a real lastHeartbeatAt and capacity when the wire response includes them", async () => {
    stubFetchSequence([
      () =>
        jsonResponse([
          {
            nodeId: "node-1",
            capabilities: { supportedTiers: ["TIER_1", "TIER_2"] },
            lastHeartbeatAt: "2026-08-01T12:00:00Z",
            capacity: {
              totalMemoryBytes: 1000,
              assignedMemoryBytes: 200,
              totalCpuMillicores: 4000,
              assignedCpuMillicores: 500,
            },
          },
        ]),
    ]);
    const repo = new HttpNodesRepository();

    const page = await repo.fetchPage({ cursor: null, pageSize: 10 });

    expect(page.items[0].lastHeartbeatAt).toBe("2026-08-01T12:00:00Z");
    expect(page.items[0].capacity.totalMemoryBytes).toBe(1000);
  });

  it("fetchOne throws for an unknown nodeId even after a forced refresh", async () => {
    stubFetchSequence([
      () => jsonResponse([{ nodeId: "node-1", capabilities: { supportedTiers: ["TIER_1"] } }]),
      () => jsonResponse([{ nodeId: "node-1", capabilities: { supportedTiers: ["TIER_1"] } }]),
    ]);
    const repo = new HttpNodesRepository();

    await expect(repo.fetchOne("node-does-not-exist")).rejects.toThrow();
  });
});
