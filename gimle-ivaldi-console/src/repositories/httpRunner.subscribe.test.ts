// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { HttpRunnerClient } from "./httpRunner";

import type { RunnerEvent } from "./contracts";

// Split out from httpRunner.test.ts (which runs in a plain Node environment, see that file's own
// comment): subscribe schedules its poll loop via window.setInterval, so this file alone opts into
// jsdom.

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

const runningSnapshot = {
  id: "run-1",
  clusterId: null,
  blueprintId: "bp-orders",
  status: "running",
  processes: [],
  error: null,
  startedAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

describe("HttpRunnerClient.subscribe", () => {
  const client = new HttpRunnerClient("http://127.0.0.1:8079");
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("skips an overlapping poll tick rather than running it alongside the one still in flight", async () => {
    let resolveLog!: (res: Response) => void;
    let resolveSnapshot!: (res: Response) => void;
    const firstLog = new Promise<Response>((resolve) => (resolveLog = resolve));
    const firstSnapshot = new Promise<Response>((resolve) => (resolveSnapshot = resolve));
    fetchMock.mockImplementation((url: string) =>
      url.includes("/log?cursor=") ? firstLog : firstSnapshot,
    );

    const events: RunnerEvent[] = [];
    const stop = client.subscribe("run-1", "bp-orders", (event) => events.push(event));

    // The first poll fires synchronously as far as its own first await -- both fetches already
    // dispatched before any timer advances.
    expect(fetchMock).toHaveBeenCalledTimes(2);

    // A tick lands while that poll is still unresolved: it must be skipped, not run concurrently.
    await vi.advanceTimersByTimeAsync(1200);
    expect(fetchMock).toHaveBeenCalledTimes(2);

    resolveLog(jsonResponse({ lines: ["line one"], nextCursor: 1 }));
    resolveSnapshot(jsonResponse(runningSnapshot));
    await vi.advanceTimersByTimeAsync(0);

    fetchMock.mockImplementation((url: string) =>
      Promise.resolve(
        url.includes("/log?cursor=")
          ? jsonResponse({ lines: [], nextCursor: 1 })
          : jsonResponse(runningSnapshot),
      ),
    );

    // The guard must not outlive the poll it guarded: the next tick goes through once it settles.
    await vi.advanceTimersByTimeAsync(1200);
    expect(fetchMock.mock.calls.length).toBeGreaterThan(2);

    const logEvents = events.filter((e) => e.type === "log");
    expect(logEvents).toHaveLength(1);
    expect(logEvents[0]).toMatchObject({ line: { text: "line one" } });

    stop();
  });

  it("clears the guard on a failed poll so the next tick is not permanently stalled", async () => {
    fetchMock.mockRejectedValue(new Error("network error"));

    const events: RunnerEvent[] = [];
    const stop = client.subscribe("run-1", "bp-orders", (event) => events.push(event));
    await vi.advanceTimersByTimeAsync(0);

    expect(events.some((e) => e.type === "error")).toBe(true);
    const callsAfterFailure = fetchMock.mock.calls.length;

    fetchMock.mockImplementation((url: string) =>
      Promise.resolve(
        url.includes("/log?cursor=")
          ? jsonResponse({ lines: [], nextCursor: 0 })
          : jsonResponse(runningSnapshot),
      ),
    );

    await vi.advanceTimersByTimeAsync(1200);
    expect(fetchMock.mock.calls.length).toBeGreaterThan(callsAfterFailure);

    stop();
  });
});
