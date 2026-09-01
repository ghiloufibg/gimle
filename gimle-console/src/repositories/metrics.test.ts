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
});
