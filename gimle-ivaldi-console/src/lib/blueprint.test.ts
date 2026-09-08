import { describe, expect, it } from "vitest";

import {
  createBlueprint,
  defaultDataFor,
  restrictsIngress,
  type NetworkPolicyData,
  type TenantData,
} from "./blueprint";

describe("createBlueprint's default runtime.dataRoot", () => {
  it("scopes the default data root to this blueprint's own id", () => {
    const bp = createBlueprint("test");

    expect(bp.runtime.dataRoot).toBe(`~/.gimle/data/${bp.id}`);
  });

  it("gives two different blueprints distinct default data roots", () => {
    const a = createBlueprint("a");
    const b = createBlueprint("b");

    expect(a.runtime.dataRoot).not.toBe(b.runtime.dataRoot);
  });

  it("scopes the empty-starter blueprint's default data root the same way", () => {
    const bp = createBlueprint("test", { empty: true });

    expect(bp.runtime.dataRoot).toBe(`~/.gimle/data/${bp.id}`);
  });

  it("gives two empty-starter blueprints distinct default data roots too", () => {
    const a = createBlueprint("a", { empty: true });
    const b = createBlueprint("b", { empty: true });

    expect(a.runtime.dataRoot).not.toBe(b.runtime.dataRoot);
  });
});

describe("a new tenant's default isolation posture", () => {
  it("defaults to OPEN, matching the platform's own documented default", () => {
    const tenant = defaultDataFor("tenant") as TenantData;

    expect(tenant.isolationPosture).toBe("OPEN");
  });
});

function fafnirKeyFile(bp: ReturnType<typeof createBlueprint>): string | undefined {
  return (bp.nodes.find((n) => n.kind === "fafnir")?.data as { keyFile?: string }).keyFile;
}

describe("createBlueprint's default fafnir keyFile", () => {
  it("scopes the default key file to this blueprint's own id, like its data root", () => {
    const bp = createBlueprint("test");

    expect(fafnirKeyFile(bp)).toBe(`~/.gimle/data/${bp.id}/fafnir.key`);
  });

  it("gives two different blueprints distinct default key files", () => {
    const a = createBlueprint("a");
    const b = createBlueprint("b");

    expect(fafnirKeyFile(a)).not.toBe(fafnirKeyFile(b));
  });
});

describe("a new NetworkPolicy node's default restrictIngress", () => {
  it("defaults to false, matching the platform's no-restriction-by-default posture", () => {
    const data = defaultDataFor("networkPolicy") as NetworkPolicyData;

    expect(data.restrictIngress).toBe(false);
  });
});

describe("restrictsIngress migration inference for pre-existing saved data", () => {
  it("infers true for a policy saved before this field existed with callers already listed", () => {
    const legacy: NetworkPolicyData = {
      name: "old-policy",
      tenantId: "orders-platform",
      allowedCallerTenantIds: ["billing"],
    };

    expect(restrictsIngress(legacy)).toBe(true);
  });

  it("infers false for a policy saved before this field existed with no callers listed", () => {
    const legacy: NetworkPolicyData = {
      name: "old-policy",
      tenantId: "orders-platform",
      allowedCallerTenantIds: [],
    };

    expect(restrictsIngress(legacy)).toBe(false);
  });

  it("infers false for a policy saved before either field existed", () => {
    const legacy: NetworkPolicyData = { name: "old-policy", tenantId: "orders-platform" };

    expect(restrictsIngress(legacy)).toBe(false);
  });

  it("honors an explicit restrictIngress over the caller list either way", () => {
    expect(
      restrictsIngress({
        name: "p",
        tenantId: "t",
        restrictIngress: true,
        allowedCallerTenantIds: [],
      }),
    ).toBe(true);
    expect(
      restrictsIngress({
        name: "p",
        tenantId: "t",
        restrictIngress: false,
        allowedCallerTenantIds: ["billing"],
      }),
    ).toBe(false);
  });
});
