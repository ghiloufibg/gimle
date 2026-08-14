import { describe, expect, it } from "vitest";
import { MockRoleBindingsRepository } from "./roleBindings";

describe("MockRoleBindingsRepository", () => {
  const repo = new MockRoleBindingsRepository();

  it("lists, upserts and deletes", async () => {
    const before = await repo.fetchAll();
    await repo.save("tmp-binding", { subject: "group:qa", roleName: "readonly" });
    const one = await repo.fetchOne("tmp-binding");
    expect(one).toEqual({ id: "tmp-binding", subject: "group:qa", roleName: "readonly" });
    await repo.remove("tmp-binding");
    expect(await repo.fetchAll()).toHaveLength(before.length);
  });
});
