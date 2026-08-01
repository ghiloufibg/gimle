import { describe, expect, it } from "vitest";
import { MockTenantsRepository } from "./tenants";

// fixture.ts's tenants array has no `create`, and `remove`/`updateQuota` mutate it in place
// (in-memory, shared across every test in this file) -- non-mutating assertions run first, the
// one test that permanently shrinks the array (remove) runs last, matching the same "restore
// what you can, order what you can't" discipline deployments.test.ts already uses.
describe("MockTenantsRepository", () => {
  const repo = new MockTenantsRepository();

  it("exact-multiple-of-pageSize boundary makes nextCursor become null", async () => {
    const everything = await repo.fetchPage({ cursor: null, pageSize: 10000 });
    const total = everything.items.length;
    expect(total).toBeGreaterThan(0);
    expect(everything.nextCursor).toBeNull();

    const oneLess = await repo.fetchPage({ cursor: null, pageSize: total - 1 });
    expect(oneLess.items).toHaveLength(total - 1);
    expect(oneLess.nextCursor).toBe(String(total - 1));
  });

  it("fetchOne throws for an unknown id", async () => {
    await expect(repo.fetchOne("does-not-exist")).rejects.toThrow();
  });

  it("fetchSummary.total matches the full page count", async () => {
    const everything = await repo.fetchPage({ cursor: null, pageSize: 10000 });
    const summary = await repo.fetchSummary();
    expect(summary.total).toBe(everything.items.length);
  });

  it("updateQuota is reflected in the very next fetchOne", async () => {
    const everything = await repo.fetchPage({ cursor: null, pageSize: 1 });
    const target = everything.items[0];
    const newQuota = {
      maxMemoryBytes: 123,
      maxCpuMillicores: 456,
      maxInstances: 7,
    };
    const updated = await repo.updateQuota(target.id, newQuota);
    expect(updated.quota).toEqual(newQuota);

    const refetched = await repo.fetchOne(target.id);
    expect(refetched.quota).toEqual(newQuota);
  });

  it("remove() takes the tenant out of the next fetchPage, then fetchOne throws", async () => {
    const before = await repo.fetchPage({ cursor: null, pageSize: 10000 });
    const totalBefore = before.items.length;
    const target = before.items[before.items.length - 1];

    await repo.remove(target.id);

    const after = await repo.fetchPage({ cursor: null, pageSize: 10000 });
    expect(after.items).toHaveLength(totalBefore - 1);
    expect(after.items.some((t) => t.id === target.id)).toBe(false);
    await expect(repo.fetchOne(target.id)).rejects.toThrow();
  });
});
