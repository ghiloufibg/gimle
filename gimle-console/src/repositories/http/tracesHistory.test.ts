import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpTracesHistoryRepository } from "./tracesHistory";
import { ApiError } from "./apiClient";
import { jsonResponse, stubFetchSequence, textResponse } from "./testUtil";
import type { ProcessTarget } from "@/types";

afterEach(() => {
  vi.unstubAllGlobals();
});

const TARGET: ProcessTarget = { processKind: "STORE", processId: "store-1" };

describe("HttpTracesHistoryRepository.fetchPage", () => {
  it("builds the query string from cursor+limit and maps the envelope through unchanged", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse({
          lines: [
            {
              timestamp: "2026-08-01T00:00:00Z",
              traceId: "abc",
              spanId: "1",
              parentSpanId: "",
              name: "GET /deployments",
              kind: "SERVER",
              status: "OK",
            },
          ],
          olderCursor: "1000",
          newerCursor: "2000",
        }),
    ]);
    const repo = new HttpTracesHistoryRepository();

    const env = await repo.fetchPage({ target: TARGET, cursor: null, limit: 60 });

    expect(env.lines).toHaveLength(1);
    expect(env.olderCursor).toBe("1000");
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/traces-history/STORE/store-1?limit=60");
  });

  it("throws ApiError on a non-2xx response", async () => {
    stubFetchSequence([() => textResponse("no muninn endpoint configured", 404)]);
    const repo = new HttpTracesHistoryRepository();

    await expect(
      repo.fetchPage({ target: TARGET, cursor: null, limit: 60 }),
    ).rejects.toMatchObject(new ApiError(404, "no muninn endpoint configured"));
  });
});

describe("HttpTracesHistoryRepository.fetchSince", () => {
  it("uses since= only", async () => {
    const fetchMock = stubFetchSequence([
      () => jsonResponse({ lines: [], olderCursor: null, newerCursor: null }),
    ]);
    const repo = new HttpTracesHistoryRepository();

    await repo.fetchSince({ target: TARGET, since: "2026-08-01T00:00:00Z" });

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/traces-history/STORE/store-1?since=2026-08-01T00%3A00%3A00Z");
  });
});

describe("HttpTracesHistoryRepository.fetchPage for a WORKER target", () => {
  it("encodes the nodeId:workerId processId as a single path segment", async () => {
    const workerTarget: ProcessTarget = { processKind: "WORKER", processId: "node-1:worker-4242" };
    const fetchMock = stubFetchSequence([
      () => jsonResponse({ lines: [], olderCursor: null, newerCursor: null }),
    ]);
    const repo = new HttpTracesHistoryRepository();

    await repo.fetchPage({ target: workerTarget, cursor: null, limit: 60 });

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/traces-history/WORKER/node-1%3Aworker-4242?limit=60");
  });
});

describe("HttpTracesHistoryRepository.openPoll", () => {
  it("polls fetchSince on an interval and forwards newly-seen spans", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse({
          lines: [
            {
              timestamp: "2026-08-01T00:00:05Z",
              traceId: "abc",
              spanId: "2",
              parentSpanId: "1",
              name: "scheduler.place",
              kind: "INTERNAL",
              status: "OK",
            },
          ],
          olderCursor: null,
          newerCursor: "2026-08-01T00:00:05Z",
        }),
    ]);
    const repo = new HttpTracesHistoryRepository();
    const onLines = vi.fn();

    const stop = repo.openPoll(TARGET, onLines, 10);
    try {
      await vi.waitFor(() => expect(onLines).toHaveBeenCalledTimes(1));
      expect(onLines).toHaveBeenCalledWith(
        expect.arrayContaining([expect.objectContaining({ spanId: "2" })]),
      );
      expect(fetchMock).toHaveBeenCalled();
    } finally {
      stop();
    }
  });
});
