import { describe, expect, it } from "vitest";
import { MockMetricsRepository } from "./metrics";

describe("MockMetricsRepository", () => {
  const repo = new MockMetricsRepository();

  it("returns one row per deployment", async () => {
    const rows = await repo.fetchRollup();
    expect(rows.map((r) => r.deploymentName)).toEqual(["greeter-provider", "greeter-consumer"]);
  });

  it("reports a real zero, not an absent average, for a deployment with no live instances", async () => {
    const rows = await repo.fetchRollup();
    const idle = rows.find((r) => r.deploymentName === "greeter-consumer");
    expect(idle?.instanceCount).toBe(0);
    expect(idle?.avgRequestRatePerSecond).toBe(0);
    expect(idle?.avgErrorRatePerSecond).toBe(0);
  });

  it("hands out a fresh copy per call so a caller cannot mutate the fixture", async () => {
    const first = await repo.fetchRollup();
    first[0].avgRequestRatePerSecond = 999;

    const second = await repo.fetchRollup();
    expect(second[0].avgRequestRatePerSecond).toBe(42.5);
  });

  it("carries no tenant on any row, matching the endpoint it stands in for", async () => {
    const rows = await repo.fetchRollup();
    for (const row of rows) {
      expect(Object.keys(row).sort()).toEqual([
        "avgErrorRatePerSecond",
        "avgRequestRatePerSecond",
        "deploymentName",
        "instanceCount",
      ]);
    }
  });
});
