import { describe, expect, it } from "vitest";
import { MockAccountsRepository } from "./accounts";

describe("MockAccountsRepository", () => {
  const repo = new MockAccountsRepository();

  it("never exposes password material", async () => {
    await repo.savePassword("tmp-user", "s3cret");
    const a = await repo.fetchOne("tmp-user");
    expect(Object.keys(a).sort()).toEqual(["groups", "username"]);
    await repo.remove("tmp-user");
    await expect(repo.fetchOne("tmp-user")).rejects.toThrow("Account not found: tmp-user");
  });

  it("preserves existing groups when groups is omitted from a reset", async () => {
    await repo.savePassword("group-user", "s3cret", ["ops"]);
    await repo.savePassword("group-user", "new-secret");
    const a = await repo.fetchOne("group-user");
    expect(a.groups).toEqual(["ops"]);
    await repo.remove("group-user");
  });

  it("replaces groups when explicitly provided", async () => {
    await repo.savePassword("group-user-2", "s3cret", ["ops"]);
    await repo.savePassword("group-user-2", "s3cret", ["auditors"]);
    const a = await repo.fetchOne("group-user-2");
    expect(a.groups).toEqual(["auditors"]);
    await repo.remove("group-user-2");
  });
});
