import { describe, expect, it } from "vitest";
import { skaldDnsName, SKALD_ZONE_SUFFIX } from "./dns";

describe("skaldDnsName", () => {
  it("qualifies a tenant-scoped Service with its tenant label", () => {
    expect(skaldDnsName({ name: "orders-api", tenantId: "acme" })).toBe(
      "orders-api.acme.svc.gimle.local",
    );
  });

  it("leaves an untenanted Service unqualified", () => {
    expect(skaldDnsName({ name: "orders-api", tenantId: undefined })).toBe(
      `orders-api${SKALD_ZONE_SUFFIX}`,
    );
  });

  it("lowercases the name, as a resolver's own lookup key is", () => {
    expect(skaldDnsName({ name: "Orders-API", tenantId: "ACME" })).toBe(
      "orders-api.acme.svc.gimle.local",
    );
  });
});
