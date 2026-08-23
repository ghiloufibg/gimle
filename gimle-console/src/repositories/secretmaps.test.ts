import { describe, expect, it } from "vitest";
import { MockSecretMapsRepository } from "./secretmaps";
import { tenants } from "./fixture";

describe("MockSecretMapsRepository", () => {
  const repo = new MockSecretMapsRepository();
  const [tenantA] = tenants;

  it("fetchNames returns just the tenant's SecretMap names", async () => {
    const names = await repo.fetchNames(tenantA.id);
    expect(names).toContain("db-creds");
  });

  it("fetchNames for an unknown tenantId returns an empty list, not an error", async () => {
    expect(await repo.fetchNames("no-such-tenant")).toEqual([]);
  });

  it("fetchOne returns per-key metadata, never a value", async () => {
    const secretMap = await repo.fetchOne(tenantA.id, "db-creds");
    expect(secretMap.keys.map((k) => k.key)).toEqual(
      expect.arrayContaining(["username", "password"]),
    );
    for (const key of secretMap.keys) {
      expect(key).not.toHaveProperty("value");
    }
  });

  it("fetchOne for an unknown name rejects", async () => {
    await expect(repo.fetchOne(tenantA.id, "no-such-map")).rejects.toThrow();
  });

  it("set creates a brand-new SecretMap at version 1 for each key", async () => {
    const results = await repo.set(tenantA.id, "vitest-temp-map", { a: "1", b: "2" });
    expect(results).toEqual(
      expect.arrayContaining([
        { key: "a", version: 1 },
        { key: "b", version: 1 },
      ]),
    );

    const fetched = await repo.fetchOne(tenantA.id, "vitest-temp-map");
    expect(fetched.keys.map((k) => k.key).sort()).toEqual(["a", "b"]);

    await repo.remove(tenantA.id, "vitest-temp-map");
  });

  it("set on an existing key bumps only that key's own version, independent of its siblings", async () => {
    await repo.set(tenantA.id, "vitest-versioned-map", { a: "1", b: "1" });
    await repo.set(tenantA.id, "vitest-versioned-map", { a: "2" });

    const fetched = await repo.fetchOne(tenantA.id, "vitest-versioned-map");
    const a = fetched.keys.find((k) => k.key === "a")!;
    const b = fetched.keys.find((k) => k.key === "b")!;
    expect(a.latestVersion).toBe(2);
    expect(b.latestVersion).toBe(1);

    await repo.remove(tenantA.id, "vitest-versioned-map");
  });

  it("remove deletes the SecretMap so a subsequent fetchOne rejects", async () => {
    await repo.set(tenantA.id, "vitest-removed-map", { a: "1" });
    await repo.remove(tenantA.id, "vitest-removed-map");
    await expect(repo.fetchOne(tenantA.id, "vitest-removed-map")).rejects.toThrow();
  });

  it("fetchGroupVersions stamps one group version per set(), each recording every member key", async () => {
    await repo.set(tenantA.id, "vitest-history-map", { a: "1" });
    await repo.set(tenantA.id, "vitest-history-map", { b: "1" });

    const versions = await repo.fetchGroupVersions(tenantA.id, "vitest-history-map");

    expect(versions.map((v) => v.groupVersion)).toEqual([1, 2]);
    expect(versions[1].keys.map((k) => k.key).sort()).toEqual(["a", "b"]);

    await repo.remove(tenantA.id, "vitest-history-map");
  });

  it("rollback restores a changed key's version and records a brand-new group version", async () => {
    await repo.set(tenantA.id, "vitest-rollback-map", { a: "1" }); // group version 1
    await repo.set(tenantA.id, "vitest-rollback-map", { a: "2" }); // group version 2

    const result = await repo.rollback(tenantA.id, "vitest-rollback-map", 1);

    expect(result.groupVersion).toBe(3);
    const versions = await repo.fetchGroupVersions(tenantA.id, "vitest-rollback-map");
    expect(versions.at(-1)?.rollbackOfGroupVersion).toBe(1);

    await repo.remove(tenantA.id, "vitest-rollback-map");
  });

  it("rollback to an unknown group version rejects", async () => {
    await repo.set(tenantA.id, "vitest-rollback-unknown", { a: "1" });

    await expect(repo.rollback(tenantA.id, "vitest-rollback-unknown", 99)).rejects.toThrow();

    await repo.remove(tenantA.id, "vitest-rollback-unknown");
  });
});
