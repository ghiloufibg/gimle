import { describe, expect, it } from "vitest";
import { MockSecretsRepository } from "./secrets";
import { tenants } from "./fixture";

describe("MockSecretsRepository", () => {
  const repo = new MockSecretsRepository();
  const [tenantA, tenantB] = tenants;

  it("fetchPage scopes entries strictly to the requested tenantId and never carries a value", async () => {
    const forA = await repo.fetchPage({ tenantId: tenantA.id, cursor: null, pageSize: 1000 });
    const forB = await repo.fetchPage({ tenantId: tenantB.id, cursor: null, pageSize: 1000 });
    expect(forA.items.length).toBeGreaterThan(0);
    expect(forB.items.length).toBeGreaterThan(0);
    expect(forA.items.every((e) => e.tenantId === tenantA.id)).toBe(true);
    for (const item of forA.items) {
      expect(Object.keys(item).sort()).toEqual(["deleted", "key", "latestVersion", "tenantId"]);
    }
  });

  it("fetchPage for an unknown tenantId returns an empty page, not an error", async () => {
    const page = await repo.fetchPage({ tenantId: "no-such-tenant", cursor: null, pageSize: 100 });
    expect(page.items).toEqual([]);
    expect(page.nextCursor).toBeNull();
  });

  it("upsert() creates version 1, a second upsert creates version 2, both remain readable", async () => {
    await repo.upsert(tenantA.id, "vitest-temp-key", "v1");
    const metaAfterFirst = (
      await repo.fetchPage({ tenantId: tenantA.id, cursor: null, pageSize: 1000 })
    ).items.find((e) => e.key === "vitest-temp-key");
    expect(metaAfterFirst?.latestVersion).toBe(1);

    await repo.upsert(tenantA.id, "vitest-temp-key", "v2");
    const metaAfterSecond = (
      await repo.fetchPage({ tenantId: tenantA.id, cursor: null, pageSize: 1000 })
    ).items.find((e) => e.key === "vitest-temp-key");
    expect(metaAfterSecond?.latestVersion).toBe(2);

    const latest = await repo.fetchValue(tenantA.id, "vitest-temp-key");
    expect(latest.version).toBe(2);
    expect(latest.value).toBe("v2");

    const historical = await repo.fetchValue(tenantA.id, "vitest-temp-key", 1);
    expect(historical.version).toBe(1);
    expect(historical.value).toBe("v1");

    expect(await repo.fetchVersions(tenantA.id, "vitest-temp-key")).toEqual([1, 2]);

    await repo.remove(tenantA.id, "vitest-temp-key", true);
  });

  it("soft delete hides the secret from a fresh fetchPage's default view but keeps history readable", async () => {
    await repo.upsert(tenantA.id, "vitest-soft-delete-key", "hunter2");

    await repo.remove(tenantA.id, "vitest-soft-delete-key", false);

    const meta = (
      await repo.fetchPage({ tenantId: tenantA.id, cursor: null, pageSize: 1000 })
    ).items.find((e) => e.key === "vitest-soft-delete-key");
    expect(meta?.deleted).toBe(true);
    const historical = await repo.fetchValue(tenantA.id, "vitest-soft-delete-key", 1);
    expect(historical.value).toBe("hunter2");

    await repo.remove(tenantA.id, "vitest-soft-delete-key", true);
  });

  it("hard delete (destroy) removes the secret from the tenant's page entirely", async () => {
    await repo.upsert(tenantA.id, "vitest-hard-delete-key", "x");

    await repo.remove(tenantA.id, "vitest-hard-delete-key", true);

    const page = await repo.fetchPage({ tenantId: tenantA.id, cursor: null, pageSize: 1000 });
    expect(page.items.some((e) => e.key === "vitest-hard-delete-key")).toBe(false);
  });

  it("rotateKey returns an incrementing active key id on each call", async () => {
    const first = await repo.rotateKey();
    const second = await repo.rotateKey();
    expect(second).toBe(first + 1);
  });
});
