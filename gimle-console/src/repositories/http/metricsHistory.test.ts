import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpMetricsHistoryRepository } from "./metricsHistory";
import { ApiError } from "./apiClient";
import { resetHistoryBackendAvailability } from "./historyAvailability";
import { jsonResponse, stubFetchSequence, textResponse } from "./testUtil";
import type { ProcessTarget } from "@/types";

afterEach(() => {
  vi.unstubAllGlobals();
  // The 404-remembering flag in historyAvailability.ts is module-level (shared session-wide by
  // design) -- reset it after every test so one test's 404 doesn't short-circuit fetch() in the
  // next, which would otherwise depend on run order.
  resetHistoryBackendAvailability();
});

const TARGET: ProcessTarget = { processKind: "CONTROLPLANE", processId: "controlplane" };

describe("HttpMetricsHistoryRepository.fetchPage", () => {
  it("builds the query string from cursor+limit and maps the envelope through unchanged", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse({
          lines: [
            {
              timestamp: "2026-08-01T00:00:00Z",
              name: "jvm.memory.used",
              type: "GAUGE",
              tags: {},
              measurements: { VALUE: 1 },
            },
          ],
          olderCursor: "1000",
          newerCursor: "2000",
        }),
    ]);
    const repo = new HttpMetricsHistoryRepository();

    const env = await repo.fetchPage({ target: TARGET, cursor: null, limit: 120 });

    expect(env.lines).toHaveLength(1);
    expect(env.olderCursor).toBe("1000");
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/metrics-history/CONTROLPLANE/controlplane?limit=120");
  });

  it("includes cursor for backward paging", async () => {
    const fetchMock = stubFetchSequence([
      () => jsonResponse({ lines: [], olderCursor: null, newerCursor: null }),
    ]);
    const repo = new HttpMetricsHistoryRepository();

    await repo.fetchPage({ target: TARGET, cursor: "1000", limit: 120 });

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/metrics-history/CONTROLPLANE/controlplane?limit=120&cursor=1000");
  });

  it("throws ApiError on a non-2xx response", async () => {
    stubFetchSequence([() => textResponse("no muninn endpoint configured", 404)]);
    const repo = new HttpMetricsHistoryRepository();

    await expect(
      repo.fetchPage({ target: TARGET, cursor: null, limit: 120 }),
    ).rejects.toMatchObject(new ApiError(404, "no muninn endpoint configured"));
  });
});

describe("HttpMetricsHistoryRepository.fetchSince", () => {
  it("uses since=, never combined with cursor/limit, and encodes the process id", async () => {
    const agentTarget: ProcessTarget = { processKind: "AGENT", processId: "node-1" };
    const fetchMock = stubFetchSequence([
      () => jsonResponse({ lines: [], olderCursor: null, newerCursor: null }),
    ]);
    const repo = new HttpMetricsHistoryRepository();

    await repo.fetchSince({ target: agentTarget, since: "2026-08-01T00:00:00Z" });

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/metrics-history/AGENT/node-1?since=2026-08-01T00%3A00%3A00Z");
  });
});

describe("HttpMetricsHistoryRepository session-wide 404 memory", () => {
  it("does not re-fetch once a 404 has been seen, and reuses that error for a later call", async () => {
    const fetchMock = stubFetchSequence([() => textResponse("no muninn endpoint configured", 404)]);
    const repo = new HttpMetricsHistoryRepository();

    await expect(
      repo.fetchPage({ target: TARGET, cursor: null, limit: 120 }),
    ).rejects.toMatchObject(new ApiError(404, "no muninn endpoint configured"));
    expect(fetchMock).toHaveBeenCalledTimes(1);

    await expect(
      repo.fetchSince({ target: TARGET, since: "2026-08-01T00:00:00Z" }),
    ).rejects.toMatchObject(new ApiError(404, "no muninn endpoint configured"));
    // Still 1 -- the second call never hit the network.
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe("HttpMetricsHistoryRepository.openPoll", () => {
  it("polls fetchSince on an interval and forwards only newly-seen lines", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse({
          lines: [
            {
              timestamp: "2026-08-01T00:00:05Z",
              name: "process.cpu.usage",
              type: "GAUGE",
              tags: {},
              measurements: { VALUE: 0.4 },
            },
          ],
          olderCursor: null,
          newerCursor: "2026-08-01T00:00:05Z",
        }),
    ]);
    const repo = new HttpMetricsHistoryRepository();
    const onLines = vi.fn();

    const stop = repo.openPoll(TARGET, onLines, 10);
    try {
      await vi.waitFor(() => expect(onLines).toHaveBeenCalledTimes(1));
      expect(onLines).toHaveBeenCalledWith(
        expect.arrayContaining([expect.objectContaining({ name: "process.cpu.usage" })]),
      );
      expect(fetchMock).toHaveBeenCalled();
    } finally {
      stop();
    }
  });
});
