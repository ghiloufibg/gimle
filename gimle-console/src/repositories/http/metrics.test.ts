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
            tenantId: "acme",
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

  it("keeps both rows, each with its own tenant, when two tenants run a deployment of the same name", async () => {
    stubFetchSequence([
      () =>
        jsonResponse([
          {
            tenantId: "acme",
            deploymentName: "api",
            instanceCount: 2,
            avgRequestRatePerSecond: 10,
            avgErrorRatePerSecond: 0,
          },
          {
            tenantId: "globex",
            deploymentName: "api",
            instanceCount: 1,
            avgRequestRatePerSecond: 3,
            avgErrorRatePerSecond: 1.5,
          },
        ]),
    ]);
    const repo = new HttpMetricsRepository();

    const rows = await repo.fetchRollup();

    expect(rows.map((r) => r.deploymentName)).toEqual(["api", "api"]);
    expect(rows.map((r) => r.tenantId)).toEqual(["acme", "globex"]);
    expect(rows.map((r) => r.instanceCount)).toEqual([2, 1]);
  });

  // An untenanted deployment's row carries an explicit null rather than dropping the key, so a
  // consumer never has to guess whether an absent tenant means untenanted or an older server.
  it("passes an untenanted row's null tenant through as null", async () => {
    stubFetchSequence([
      () =>
        jsonResponse([
          {
            tenantId: null,
            deploymentName: "api",
            instanceCount: 1,
            avgRequestRatePerSecond: 1,
            avgErrorRatePerSecond: 0,
          },
        ]),
    ]);
    const repo = new HttpMetricsRepository();

    expect((await repo.fetchRollup())[0].tenantId).toBeNull();
  });

  it("surfaces a denied read rather than swallowing it into an empty rollup", async () => {
    stubFetchSequence([() => new Response("forbidden", { status: 403 })]);
    const repo = new HttpMetricsRepository();

    await expect(repo.fetchRollup()).rejects.toThrow("403");
  });
});
