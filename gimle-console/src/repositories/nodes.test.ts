import { describe, expect, it } from "vitest";
import { MockNodesRepository } from "./nodes";
import { isStale } from "@/lib/format";

describe("MockNodesRepository", () => {
  const repo = new MockNodesRepository();

  it("exact-multiple-of-pageSize boundary makes nextCursor become null", async () => {
    const everything = await repo.fetchPage({ cursor: null, pageSize: 10000 });
    const total = everything.items.length;
    expect(total).toBeGreaterThan(0);
    expect(everything.nextCursor).toBeNull();

    const oneLess = await repo.fetchPage({ cursor: null, pageSize: total - 1 });
    expect(oneLess.items).toHaveLength(total - 1);
    expect(oneLess.nextCursor).toBe(String(total - 1));

    const rest = await repo.fetchPage({ cursor: oneLess.nextCursor, pageSize: total });
    expect(rest.items).toHaveLength(1);
    expect(rest.nextCursor).toBeNull();
  });

  it("fetchOne throws for an unknown nodeId", async () => {
    await expect(repo.fetchOne("does-not-exist")).rejects.toThrow();
  });

  it("fetchOne resolves a node that exists", async () => {
    const everything = await repo.fetchPage({ cursor: null, pageSize: 1 });
    const target = everything.items[0];
    const found = await repo.fetchOne(target.nodeId);
    expect(found).toEqual(target);
  });

  it("fetchSummary counts stale nodes independently and lists the first 8 as recent", async () => {
    const everything = await repo.fetchPage({ cursor: null, pageSize: 10000 });
    const summary = await repo.fetchSummary();
    expect(summary.total).toBe(everything.items.length);
    expect(summary.stale).toBe(everything.items.filter((n) => isStale(n.status)).length);
    expect(summary.recent).toEqual(everything.items.slice(0, 8));
  });
});
