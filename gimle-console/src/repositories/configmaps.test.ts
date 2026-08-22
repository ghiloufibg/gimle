import { describe, expect, it } from "vitest";
import { ConfigMapConflict, MockConfigMapsRepository } from "./configmaps";
import { tenants } from "./fixture";

describe("MockConfigMapsRepository", () => {
  const repo = new MockConfigMapsRepository();
  const [tenantA] = tenants;

  it("fetchNames returns just the tenant's ConfigMap names", async () => {
    const names = await repo.fetchNames(tenantA.id);
    expect(names).toContain("app-config");
  });

  it("fetchNames for an unknown tenantId returns an empty list, not an error", async () => {
    expect(await repo.fetchNames("no-such-tenant")).toEqual([]);
  });

  it("upsert with no expectedVersion creates version 1, a second unconditional upsert creates version 2", async () => {
    await repo.upsert(tenantA.id, "vitest-temp-map", { a: "1" });
    const first = await repo.fetchOne(tenantA.id, "vitest-temp-map");
    expect(first.version).toBe(1);
    expect(first.data).toEqual({ a: "1" });

    await repo.upsert(tenantA.id, "vitest-temp-map", { a: "2" });
    const second = await repo.fetchOne(tenantA.id, "vitest-temp-map");
    expect(second.version).toBe(2);
    expect(second.data).toEqual({ a: "2" });

    await repo.remove(tenantA.id, "vitest-temp-map");
  });

  it("upsert with a stale expectedVersion throws ConfigMapConflict carrying the current snapshot", async () => {
    await repo.upsert(tenantA.id, "vitest-conflict-map", { a: "1" });

    await expect(
      repo.upsert(tenantA.id, "vitest-conflict-map", { a: "2" }, 99),
    ).rejects.toBeInstanceOf(ConfigMapConflict);
    try {
      await repo.upsert(tenantA.id, "vitest-conflict-map", { a: "2" }, 99);
    } catch (e) {
      expect((e as ConfigMapConflict).currentVersion).toBe(1);
      expect((e as ConfigMapConflict).currentData).toEqual({ a: "1" });
    }

    await repo.remove(tenantA.id, "vitest-conflict-map");
  });

  it("upsert with expectedVersion=0 against a never-created name is the create case, not a conflict", async () => {
    const created = await repo.upsert(tenantA.id, "vitest-create-map", { a: "1" }, 0);
    expect(created.version).toBe(1);
    await repo.remove(tenantA.id, "vitest-create-map");
  });

  it("remove deletes the ConfigMap so a subsequent fetchOne rejects", async () => {
    await repo.upsert(tenantA.id, "vitest-removed-map", { a: "1" });
    await repo.remove(tenantA.id, "vitest-removed-map");
    await expect(repo.fetchOne(tenantA.id, "vitest-removed-map")).rejects.toThrow();
  });
});
