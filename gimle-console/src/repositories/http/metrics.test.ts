import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpMetricsRepository } from "./metrics";
import { jsonResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("HttpMetricsRepository", () => {
  it("fetchRollup GETs /metrics with no query parameters", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse([
          {
            deploymentName: "greeter-provider",
            instanceCount: 2,
            avgRequestRatePerSecond: 42.5,
            avgErrorRatePerSecond: 0.25,
          },
        ]),
    ]);
    const repo = new HttpMetricsRepository();

    const rows = await repo.fetchRollup();

    expect(rows).toHaveLength(1);
    expect(rows[0].avgRequestRatePerSecond).toBe(42.5);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/metrics");
    expect(init.method).toBe("GET");
  });

  it("accepts an empty rollup from a cluster with no readable deployments", async () => {
    stubFetchSequence([() => jsonResponse([])]);
    const repo = new HttpMetricsRepository();

    expect(await repo.fetchRollup()).toEqual([]);
  });

  it("surfaces a denied read rather than swallowing it into an empty rollup", async () => {
    stubFetchSequence([() => new Response("forbidden", { status: 403 })]);
    const repo = new HttpMetricsRepository();

    await expect(repo.fetchRollup()).rejects.toThrow("403");
  });
});
