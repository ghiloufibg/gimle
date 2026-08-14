import { describe, expect, it } from "vitest";
import { MockRolesRepository } from "./roles";

describe("MockRolesRepository", () => {
  const repo = new MockRolesRepository();

  it("lists seeded roles", async () => {
    const roles = await repo.fetchAll();
    expect(roles.map((r) => r.name)).toContain("cluster-admin");
  });

  it("fetches one and throws for unknown", async () => {
    const r = await repo.fetchOne("readonly");
    expect(r.permissions.length).toBeGreaterThan(0);
    await expect(repo.fetchOne("nope")).rejects.toThrow("Role not found: nope");
  });

  it("creates, replaces and deletes", async () => {
    await repo.save("tmp-role", [{ resource: "LOGS", verb: "READ" }]);
    expect((await repo.fetchOne("tmp-role")).permissions).toHaveLength(1);
    await repo.save("tmp-role", [
      { resource: "LOGS", verb: "READ" },
      { resource: "CONFIG", verb: "WRITE", tenantScope: "acme" },
    ]);
    expect((await repo.fetchOne("tmp-role")).permissions).toHaveLength(2);
    await repo.remove("tmp-role");
    await expect(repo.fetchOne("tmp-role")).rejects.toThrow();
  });
});
