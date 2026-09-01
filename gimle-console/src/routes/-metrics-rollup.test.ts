import { describe, expect, it } from "vitest";
import {
  ambiguousDeploymentNames,
  fmtRatePerSecond,
  rankRollupRows,
  rollupAmbiguityWarning,
} from "./metrics";
import type { DeploymentMetricsRollup } from "@/types";

// Pure ranking/ambiguity/formatting logic only -- this project's vitest config is deliberately
// node-environment (see vitest.config.ts); the JSX half of the rollup panel is exercised live in a
// real browser instead, not here.

function row(overrides: Partial<DeploymentMetricsRollup> = {}): DeploymentMetricsRollup {
  return {
    deploymentName: "greeter-provider",
    instanceCount: 1,
    avgRequestRatePerSecond: 0,
    avgErrorRatePerSecond: 0,
    ...overrides,
  };
}

describe("rankRollupRows", () => {
  it("puts the erroring deployments first, worst error rate leading", () => {
    const ranked = rankRollupRows([
      row({ deploymentName: "quiet" }),
      row({ deploymentName: "bad", avgErrorRatePerSecond: 4 }),
      row({ deploymentName: "worse", avgErrorRatePerSecond: 9 }),
    ]);

    expect(ranked.map((r) => r.deploymentName)).toEqual(["worse", "bad", "quiet"]);
  });

  it("orders equally-healthy deployments by request rate, busiest first", () => {
    const ranked = rankRollupRows([
      row({ deploymentName: "slow", avgRequestRatePerSecond: 1 }),
      row({ deploymentName: "fast", avgRequestRatePerSecond: 100 }),
    ]);

    expect(ranked.map((r) => r.deploymentName)).toEqual(["fast", "slow"]);
  });

  it("ranks an error rate above a request rate, so a busy-but-clean deployment never buries a broken quiet one", () => {
    const ranked = rankRollupRows([
      row({ deploymentName: "busy", avgRequestRatePerSecond: 5000 }),
      row({ deploymentName: "broken", avgRequestRatePerSecond: 0.1, avgErrorRatePerSecond: 0.01 }),
    ]);

    expect(ranked.map((r) => r.deploymentName)).toEqual(["broken", "busy"]);
  });

  it("surfaces a deployment reporting nothing ahead of an idle one that is genuinely at zero", () => {
    const ranked = rankRollupRows([
      row({ deploymentName: "idle", instanceCount: 3 }),
      row({ deploymentName: "silent", instanceCount: 0 }),
    ]);

    expect(ranked.map((r) => r.deploymentName)).toEqual(["silent", "idle"]);
    expect(ranked.map((r) => r.silent)).toEqual([true, false]);
  });

  it("falls back to the deployment name so the order is stable between polls", () => {
    const ranked = rankRollupRows([row({ deploymentName: "b" }), row({ deploymentName: "a" })]);

    expect(ranked.map((r) => r.deploymentName)).toEqual(["a", "b"]);
  });

  it("does not mutate the rows it was given", () => {
    const rows = [row({ deploymentName: "b" }), row({ deploymentName: "a" })];

    rankRollupRows(rows);

    expect(rows.map((r) => r.deploymentName)).toEqual(["b", "a"]);
  });

  it("returns an empty list for an empty rollup", () => {
    expect(rankRollupRows([])).toEqual([]);
  });
});

// GET /metrics keys each row by deployment name alone while the RBAC filter behind it is
// per-tenant, so a caller who may read two tenants can receive two rows nothing in the payload
// distinguishes. Both rows are kept and both are flagged; neither is dropped, merged, or
// attributed to a tenant the response never named.
describe("same deployment name across tenants", () => {
  const rows = [
    row({ deploymentName: "api", instanceCount: 2, avgRequestRatePerSecond: 10 }),
    row({ deploymentName: "api", instanceCount: 1, avgErrorRatePerSecond: 2 }),
    row({ deploymentName: "worker", instanceCount: 1 }),
  ];

  it("flags every row whose name is repeated, and no others", () => {
    const ranked = rankRollupRows(rows);

    expect(ranked.filter((r) => r.ambiguous).map((r) => r.deploymentName)).toEqual(["api", "api"]);
    expect(ranked.find((r) => r.deploymentName === "worker")?.ambiguous).toBe(false);
  });

  it("keeps both ambiguous rows rather than collapsing them into one average", () => {
    const ranked = rankRollupRows(rows);

    expect(ranked).toHaveLength(3);
    expect(ranked.filter((r) => r.deploymentName === "api").map((r) => r.instanceCount)).toEqual([
      1, 2,
    ]);
  });

  it("names the affected deployments in the warning, once each and sorted", () => {
    expect(rollupAmbiguityWarning([...rows, row({ deploymentName: "api" })])).toContain("api");
    expect(rollupAmbiguityWarning(rows)).toContain("no tenant");
  });

  it("warns about nothing when every deployment name is unique", () => {
    expect(
      rollupAmbiguityWarning([row({ deploymentName: "a" }), row({ deploymentName: "b" })]),
    ).toBeNull();
    expect(rollupAmbiguityWarning([])).toBeNull();
  });

  it("reports a repeated name exactly once regardless of how many rows carry it", () => {
    const repeated = ambiguousDeploymentNames([
      row({ deploymentName: "api" }),
      row({ deploymentName: "api" }),
      row({ deploymentName: "api" }),
    ]);

    expect([...repeated]).toEqual(["api"]);
  });
});

describe("fmtRatePerSecond", () => {
  it("renders a request rate to one decimal by default", () => {
    expect(fmtRatePerSecond(42.5)).toBe("42.5/s");
    expect(fmtRatePerSecond(42.48)).toBe("42.5/s");
    expect(fmtRatePerSecond(0)).toBe("0.0/s");
  });

  // A rate below the requested precision reads as "0.00/s"; the row's own error colouring, which
  // keys off the raw value rather than this string, is what keeps it distinguishable from a true
  // zero.
  it("renders an error rate to the requested precision", () => {
    expect(fmtRatePerSecond(0.25, 2)).toBe("0.25/s");
    expect(fmtRatePerSecond(0.004, 2)).toBe("0.00/s");
  });
});
