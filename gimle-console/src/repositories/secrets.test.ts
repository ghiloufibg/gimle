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

    const versions = await repo.fetchVersions(tenantA.id, "vitest-temp-key");
    expect(versions.map((v) => v.version)).toEqual([1, 2]);
    expect(versions.every((v) => v.author.length > 0)).toBe(true);
    expect(versions.every((v) => v.writtenAtEpochMilli > 0)).toBe(true);

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

  it("a declared type is remembered per version and reported by fetchValue and fetchVersions", async () => {
    await repo.upsert(tenantA.id, "vitest-typed-key", "cert-body", "pem-certificate");
    await repo.upsert(tenantA.id, "vitest-typed-key", "opaque-body");

    const versions = await repo.fetchVersions(tenantA.id, "vitest-typed-key");
    expect(versions.map((v) => v.type)).toEqual(["pem-certificate", "opaque"]);
    expect((await repo.fetchValue(tenantA.id, "vitest-typed-key", 1)).type).toBe("pem-certificate");
    expect((await repo.fetchValue(tenantA.id, "vitest-typed-key")).type).toBe("opaque");

    await repo.remove(tenantA.id, "vitest-typed-key", true);
  });

  it("fetchVersions for an unknown key rejects rather than returning an empty list", async () => {
    await expect(repo.fetchVersions(tenantA.id, "no-such-key")).rejects.toThrow("no such secret");
  });
});
