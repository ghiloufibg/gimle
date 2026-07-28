import { describe, expect, it } from "vitest";
import { MockDeploymentsRepository } from "./deployments";

// The fixture (fixture.ts) generates a fixed 42-deployment array once per test-file module
// instantiation -- never literally empty, so boundary conditions are asserted relative to the
// actual returned total rather than a hardcoded count.
describe("MockDeploymentsRepository", () => {
  const repo = new MockDeploymentsRepository();

  it("exact-multiple-of-pageSize boundary makes nextCursor become null", async () => {
    const everything = await repo.fetchPage({ cursor: null, pageSize: 1000 });
    const total = everything.items.length;
    expect(total).toBeGreaterThan(0);
    expect(everything.nextCursor).toBeNull();

    const exact = await repo.fetchPage({ cursor: null, pageSize: total });
    expect(exact.items).toHaveLength(total);
    expect(exact.nextCursor).toBeNull();

    const oneLess = await repo.fetchPage({ cursor: null, pageSize: total - 1 });
    expect(oneLess.items).toHaveLength(total - 1);
    expect(oneLess.nextCursor).toBe(String(total - 1));

    const rest = await repo.fetchPage({ cursor: oneLess.nextCursor, pageSize: total });
    expect(rest.items).toHaveLength(1);
    expect(rest.nextCursor).toBeNull();
  });

  it("create() is reflected in the very next fetchPage, then removes cleanly", async () => {
    const before = await repo.fetchPage({ cursor: null, pageSize: 1000 });
    const totalBefore = before.items.length;

    const created = await repo.create({
      name: "vitest-temp-deployment",
      moduleId: { name: "vitest-module", version: "0.0.1" },
      artifactPath: "s3://test-bucket/vitest-module/0.0.1/vitest-module-0.0.1.jar",
      replicas: 1,
      tenantId: null,
    });
    expect(created.spec.name).toBe("vitest-temp-deployment");
    expect(created.unplacedCount).toBe(1);

    const after = await repo.fetchPage({ cursor: null, pageSize: 1000 });
    expect(after.items).toHaveLength(totalBefore + 1);
    expect(after.items.some((d) => d.spec.name === "vitest-temp-deployment")).toBe(true);

    await repo.remove("vitest-temp-deployment");

    const restored = await repo.fetchPage({ cursor: null, pageSize: 1000 });
    expect(restored.items).toHaveLength(totalBefore);
    expect(restored.items.some((d) => d.spec.name === "vitest-temp-deployment")).toBe(false);
  });

  it("fetchOne throws for an unknown name", async () => {
    await expect(repo.fetchOne("does-not-exist")).rejects.toThrow();
  });

  it("fetchSummary aggregates over the full array, not just one page", async () => {
    const all = await repo.fetchPage({ cursor: null, pageSize: 1000 });
    const summary = await repo.fetchSummary();
    expect(summary.total).toBe(all.items.length);
    expect(summary.unplacedInstances).toBe(all.items.reduce((s, d) => s + d.unplacedCount, 0));
    expect(summary.quotaViolating).toBe(all.items.filter((d) => d.quotaViolating).length);
  });
});
