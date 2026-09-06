import { describe, expect, it } from "vitest";
import { scopedTenantId, tenantScopeSearch } from "./tenant-scope";

describe("tenantScopeSearch", () => {
  it("keeps a tenant named in the query string", () => {
    expect(tenantScopeSearch({ tenant: "acme" })).toEqual({ tenant: "acme" });
  });

  it("drops an absent, empty or non-string tenant rather than scoping to nothing", () => {
    expect(tenantScopeSearch({})).toEqual({});
    expect(tenantScopeSearch({ tenant: "" })).toEqual({});
    expect(tenantScopeSearch({ tenant: 7 })).toEqual({});
  });
});

describe("scopedTenantId", () => {
  it("prefers the tenant the URL names over the first one the cluster reports", () => {
    expect(scopedTenantId("acme", ["beta", "acme"])).toBe("acme");
  });

  it("honours a URL tenant the cluster doesn't list rather than substituting another", () => {
    expect(scopedTenantId("gone", ["beta"])).toBe("gone");
  });

  it("falls back to the first known tenant for a bare navigation", () => {
    expect(scopedTenantId(undefined, ["beta", "acme"])).toBe("beta");
  });

  it("resolves to nothing while no tenant is known at all", () => {
    expect(scopedTenantId(undefined, [])).toBeNull();
  });
});
