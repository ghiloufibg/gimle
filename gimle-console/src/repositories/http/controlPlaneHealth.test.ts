import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpControlPlaneHealthRepository } from "./controlPlaneHealth";
import { jsonResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("HttpControlPlaneHealthRepository", () => {
  it("GETs /health and normalizes every subsystem badge", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse({
          status: "UP",
          reconcilerLeader: true,
          subsystems: {
            scheduler: { status: "UP", lastRunAt: "2026-09-15T00:00:00Z" },
            quotaEnforcer: { status: "UP", lastRunAt: "2026-09-15T00:00:00Z" },
            heartbeatWorker: { status: "DOWN", lastRunAt: "2026-09-15T00:00:00Z", detail: "boom" },
            artifactResolver: {
              status: "UP",
              lastRunAt: "2026-09-15T00:00:00Z",
              detail: "no artifact registry configured (local paths only)",
            },
          },
        }),
    ]);
    const repo = new HttpControlPlaneHealthRepository();

    const status = await repo.fetch();

    expect(status).toEqual({
      reconcilerLeader: true,
      scheduler: { status: "UP", lastRunAt: "2026-09-15T00:00:00Z", detail: null },
      quotaEnforcer: { status: "UP", lastRunAt: "2026-09-15T00:00:00Z", detail: null },
      heartbeatWorker: { status: "DOWN", lastRunAt: "2026-09-15T00:00:00Z", detail: "boom" },
      artifactResolver: {
        status: "UP",
        lastRunAt: "2026-09-15T00:00:00Z",
        detail: "no artifact registry configured (local paths only)",
      },
    });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/health");
    expect(init.method).toBe("GET");
  });

  it("reports a subsystem STANDBY-worthy replica as UNKNOWN rather than throwing on a missing key", async () => {
    // A replica just past acquiring the reconciler-leader lease may answer before any tick has
    // completed -- `subsystems` present but a given key still absent.
    stubFetchSequence([
      () => jsonResponse({ status: "UP", reconcilerLeader: true, subsystems: {} }),
    ]);
    const repo = new HttpControlPlaneHealthRepository();

    const status = await repo.fetch();

    expect(status.scheduler).toEqual({ status: "UNKNOWN", lastRunAt: null, detail: null });
  });

  it("surfaces a 503 (store unreachable) as a rejected promise rather than a partial status", async () => {
    stubFetchSequence([() => new Response("store unreachable", { status: 503 })]);
    const repo = new HttpControlPlaneHealthRepository();

    await expect(repo.fetch()).rejects.toThrow();
  });
});
