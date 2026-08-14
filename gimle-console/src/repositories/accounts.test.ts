import { describe, expect, it } from "vitest";
import { MockAccountsRepository } from "./accounts";

describe("MockAccountsRepository", () => {
  const repo = new MockAccountsRepository();

  it("never exposes password material", async () => {
    await repo.savePassword("tmp-user", "s3cret");
    const a = await repo.fetchOne("tmp-user");
    expect(Object.keys(a)).toEqual(["username"]);
    await repo.remove("tmp-user");
    await expect(repo.fetchOne("tmp-user")).rejects.toThrow("Account not found: tmp-user");
  });
});
