import { describe, expect, it } from "vitest";
import { MockNetworkPoliciesRepository } from "./networkPolicies";

describe("MockNetworkPoliciesRepository", () => {
  const repo = new MockNetworkPoliciesRepository();

  it("lists seeded policies", async () => {
    const policies = await repo.fetchAll();
    expect(policies.map((p) => p.name)).toContain("acme-billing-policy");
  });

  it("fetches one and throws for unknown", async () => {
    const p = await repo.fetchOne("acme-billing-policy");
    expect(p.allowedCallerTenantIds).toEqual(["partner"]);
    await expect(repo.fetchOne("nope")).rejects.toThrow("NetworkPolicy not found: nope");
  });

  it("creates, replaces and deletes", async () => {
    await repo.save({
      name: "tmp-policy",
      tenantId: "acme",
      deploymentNames: [],
      allowedCallerTenantIds: [],
    });
    expect((await repo.fetchOne("tmp-policy")).allowedCallerTenantIds).toEqual([]);
    await repo.save({
      name: "tmp-policy",
      tenantId: "acme",
      deploymentNames: [],
      allowedCallerTenantIds: ["partner"],
    });
    expect((await repo.fetchOne("tmp-policy")).allowedCallerTenantIds).toEqual(["partner"]);
    await repo.remove("tmp-policy");
    await expect(repo.fetchOne("tmp-policy")).rejects.toThrow();
  });
});
