import { describe, expect, it } from "vitest";

import { MockSecretsRepository } from "./secrets";

describe("MockSecretsRepository", () => {
  it("lists the seeded secrets for a known tenant", async () => {
    const repo = new MockSecretsRepository();

    const secrets = await repo.list("asgard");

    expect(secrets.map((s) => s.key).sort()).toEqual(["api/token", "db/password", "legacy/key"]);
    expect(secrets.find((s) => s.key === "legacy/key")?.deleted).toBe(true);
  });

  it("an unknown tenant has no secrets rather than throwing", async () => {
    const repo = new MockSecretsRepository();

    await expect(repo.list("unknown-tenant")).resolves.toEqual([]);
  });

  it("upsert then read round-trips the latest value and bumps the version", async () => {
    const repo = new MockSecretsRepository();

    const version = await repo.upsert("asgard", "db/password", "new-value");
    const read = await repo.read("asgard", "db/password");

    expect(version).toBe(3);
    expect(read).toEqual({
      tenantId: "asgard",
      key: "db/password",
      version: 3,
      value: "new-value",
    });
  });

  it("reading a specific older version returns that version, not the latest", async () => {
    const repo = new MockSecretsRepository();

    const first = await repo.read("asgard", "db/password", 1);

    expect(first.value).toBe("hunter2");
  });

  it("soft delete keeps the key listed as deleted; hard delete removes it entirely", async () => {
    const repo = new MockSecretsRepository();

    await repo.remove("asgard", "api/token", false);
    const afterSoft = await repo.list("asgard");
    expect(afterSoft.find((s) => s.key === "api/token")?.deleted).toBe(true);

    await repo.remove("asgard", "api/token", true);
    const afterHard = await repo.list("asgard");
    expect(afterHard.find((s) => s.key === "api/token")).toBeUndefined();
  });

  it("reading a nonexistent secret throws", async () => {
    const repo = new MockSecretsRepository();

    await expect(repo.read("asgard", "does/not/exist")).rejects.toThrow("no such secret");
  });

  it("rotateKey returns a strictly increasing active key id", async () => {
    const repo = new MockSecretsRepository();

    const first = await repo.rotateKey();
    const second = await repo.rotateKey();

    expect(second).toBe(first + 1);
  });

  it("versions returns one object per stored version, not a bare number", async () => {
    const repo = new MockSecretsRepository();

    const versions = await repo.versions("asgard", "db/password");

    expect(versions).toEqual([
      { version: 1, author: "mock", writtenAtEpochMilli: expect.any(Number) },
      { version: 2, author: "mock", writtenAtEpochMilli: expect.any(Number) },
    ]);
  });
});
