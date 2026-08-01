import { describe, expect, it } from "vitest";
import { MockConfigRepository } from "./config";
import { tenants } from "./fixture";

describe("MockConfigRepository", () => {
  const repo = new MockConfigRepository();
  const [tenantA, tenantB] = tenants;

  it("fetchPage scopes entries strictly to the requested tenantId", async () => {
    const forA = await repo.fetchPage({ tenantId: tenantA.id, cursor: null, pageSize: 1000 });
    const forB = await repo.fetchPage({ tenantId: tenantB.id, cursor: null, pageSize: 1000 });
    expect(forA.items.length).toBeGreaterThan(0);
    expect(forB.items.length).toBeGreaterThan(0);
    expect(forA.items.every((e) => e.tenantId === tenantA.id)).toBe(true);
    expect(forB.items.every((e) => e.tenantId === tenantB.id)).toBe(true);
    // No entry from tenant B's key set should ever appear in tenant A's page.
    const bKeys = new Set(forB.items.map((e) => e.key));
    expect(forA.items.some((e) => bKeys.has(e.key) && e.tenantId === tenantB.id)).toBe(false);
  });

  it("fetchPage for an unknown tenantId returns an empty page, not an error", async () => {
    const page = await repo.fetchPage({
      tenantId: "no-such-tenant",
      cursor: null,
      pageSize: 100,
    });
    expect(page.items).toEqual([]);
    expect(page.nextCursor).toBeNull();
  });

  it("upsert() is reflected in the very next fetchPage, then remove() cleans it up", async () => {
    const before = await repo.fetchPage({ tenantId: tenantA.id, cursor: null, pageSize: 1000 });
    const totalBefore = before.items.length;

    const entry = {
      tenantId: tenantA.id,
      key: "vitest-temp-key",
      value: "vitest-temp-value",
      encrypted: false,
    };
    await repo.upsert(entry);

    const after = await repo.fetchPage({ tenantId: tenantA.id, cursor: null, pageSize: 1000 });
    expect(after.items).toHaveLength(totalBefore + 1);
    expect(after.items.find((e) => e.key === "vitest-temp-key")).toEqual(entry);

    await repo.remove(tenantA.id, "vitest-temp-key");

    const restored = await repo.fetchPage({ tenantId: tenantA.id, cursor: null, pageSize: 1000 });
    expect(restored.items).toHaveLength(totalBefore);
    expect(restored.items.some((e) => e.key === "vitest-temp-key")).toBe(false);
  });
});
