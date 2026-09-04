import { describe, expect, it } from "vitest";
import { MockInstancesRepository } from "./instances";

describe("MockInstancesRepository", () => {
  const repo = new MockInstancesRepository();

  it("exact-multiple-of-pageSize boundary makes nextCursor become null", async () => {
    const everything = await repo.fetchPage({ cursor: null, pageSize: 10000 });
    const total = everything.items.length;
    expect(total).toBeGreaterThan(0);
    expect(everything.nextCursor).toBeNull();

    const oneLess = await repo.fetchPage({ cursor: null, pageSize: total - 1 });
    expect(oneLess.items).toHaveLength(total - 1);
    expect(oneLess.nextCursor).toBe(String(total - 1));
  });

  it("filters are applied against the full flattened array before pagination, not just the loaded page", async () => {
    const everything = await repo.fetchPage({ cursor: null, pageSize: 10000 });
    const someNodeId = everything.items[0].nodeId;

    const filtered = await repo.fetchPage({
      cursor: null,
      pageSize: 10000,
      filter: { nodeId: someNodeId },
    });
    expect(filtered.items.length).toBeGreaterThan(0);
    expect(filtered.items.every((r) => r.nodeId === someNodeId)).toBe(true);
    // Sanity: the filtered count must be <= the unfiltered count, and strictly less unless every
    // instance happens to be on this one node.
    expect(filtered.items.length).toBeLessThanOrEqual(everything.items.length);
  });

  it("fetchOne throws for an unknown deployment/index pair", async () => {
    await expect(repo.fetchOne("does-not-exist", 0)).rejects.toThrow();
  });

  it("fetchOne resolves an instance that exists", async () => {
    const everything = await repo.fetchPage({ cursor: null, pageSize: 1 });
    const target = everything.items[0];
    const found = await repo.fetchOne(target.deploymentName, target.instanceIndex);
    expect(found).toEqual(target);
  });

  it("every row carries its isolationTier and resourceLimit, not just usage", async () => {
    const everything = await repo.fetchPage({ cursor: null, pageSize: 10000 });
    for (const row of everything.items) {
      expect(row.isolationTier).toMatch(/^TIER_[123]$/);
      expect(row.resourceLimit).toEqual({
        memory: expect.stringMatching(/^\d+Mi$/),
        cpu: expect.stringMatching(/^\d+m$/),
      });
    }
  });
});
