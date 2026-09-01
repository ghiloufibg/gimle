import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpLogsRepository } from "./logs";
import { ApiError } from "./apiClient";
import { jsonResponse, stubFetchSequence, textResponse } from "./testUtil";
import type { LogTarget } from "@/types";
import { EMPTY_LOG_FILTER, toLogFilter } from "@/lib/log-filter";

afterEach(() => {
  vi.unstubAllGlobals();
});

const NODE_TARGET: LogTarget = { kind: "node", nodeId: "node-1", category: "PLATFORM" };

describe("HttpLogsRepository.fetchPage", () => {
  it("maps {lines, olderCursor} to {items, nextCursor} and builds the query string", async () => {
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse({
          lines: [{ timestamp: "2026-08-01T00:00:00Z", level: "INFO", message: "hi" }],
          olderCursor: "2026-08-01T00:00:00Z",
          newerCursor: "2026-08-01T00:00:00Z",
        }),
    ]);
    const repo = new HttpLogsRepository();

    const page = await repo.fetchPage({
      target: NODE_TARGET,
      filter: EMPTY_LOG_FILTER,
      cursor: null,
      pageSize: 50,
    });

    expect(page.items).toHaveLength(1);
    expect(page.nextCursor).toBe("2026-08-01T00:00:00Z");
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/logs/nodes/node-1?category=PLATFORM&limit=50");
  });

  it("includes cursor (backward paging, 'Load older') when one is given", async () => {
    const fetchMock = stubFetchSequence([
      () => jsonResponse({ lines: [], olderCursor: null, newerCursor: null }),
    ]);
    const repo = new HttpLogsRepository();

    await repo.fetchPage({
      target: NODE_TARGET,
      filter: EMPTY_LOG_FILTER,
      cursor: "2026-08-01T00:00:00Z",
      pageSize: 50,
    });

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe(
      "/logs/nodes/node-1?category=PLATFORM&limit=50&cursor=2026-08-01T00%3A00%3A00Z",
    );
  });

  it("throws ApiError on a non-2xx response", async () => {
    stubFetchSequence([() => textResponse("nope", 500)]);
    const repo = new HttpLogsRepository();

    await expect(
      repo.fetchPage({
        target: NODE_TARGET,
        filter: EMPTY_LOG_FILTER,
        cursor: null,
        pageSize: 50,
      }),
    ).rejects.toMatchObject(new ApiError(500, "nope"));
  });
});

describe("HttpLogsRepository.openFollow", () => {
  it("seeds silently (no onLine call for what's already on disk), then polls with since=, not cursor=", async () => {
    const seedTimestamp = "2026-08-01T00:00:00Z";
    const newTimestamp = "2026-08-01T00:00:05Z";
    const fetchMock = stubFetchSequence([
      // Seed call: pageSize 1, establishes the cursor at "now" without emitting it.
      () =>
        jsonResponse({
          lines: [{ timestamp: seedTimestamp, level: "INFO", message: "already on disk" }],
          olderCursor: null,
          newerCursor: seedTimestamp,
        }),
      // First poll: only a genuinely new line should come back.
      () =>
        jsonResponse({
          lines: [{ timestamp: newTimestamp, level: "INFO", message: "genuinely new" }],
          olderCursor: seedTimestamp,
          newerCursor: newTimestamp,
        }),
    ]);
    const repo = new HttpLogsRepository();
    const onLine = vi.fn();

    const stop = repo.openFollow(NODE_TARGET, EMPTY_LOG_FILTER, onLine);
    try {
      await vi.waitFor(() => expect(onLine).toHaveBeenCalledTimes(1));
      expect(onLine).toHaveBeenCalledWith(expect.objectContaining({ message: "genuinely new" }));
      expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(2);
      const pollUrl = fetchMock.mock.calls[1]?.[0] as string;
      expect(pollUrl).toContain(`since=${encodeURIComponent(seedTimestamp)}`);
      expect(pollUrl).not.toContain("cursor=");
    } finally {
      stop();
    }
  });
});

describe("HttpLogsRepository filtering", () => {
  it("sends level and contains as query parameters, url-encoded", async () => {
    const fetchMock = stubFetchSequence([
      () => jsonResponse({ lines: [], olderCursor: null, newerCursor: null }),
    ]);
    const repo = new HttpLogsRepository();

    await repo.fetchPage({
      target: NODE_TARGET,
      filter: toLogFilter("WARN", "timed out"),
      cursor: null,
      pageSize: 50,
    });

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/logs/nodes/node-1?category=PLATFORM&limit=50&level=WARN&contains=timed+out");
  });

  it("omits both parameters entirely when nothing is filtered", async () => {
    const fetchMock = stubFetchSequence([
      () => jsonResponse({ lines: [], olderCursor: null, newerCursor: null }),
    ]);
    const repo = new HttpLogsRepository();

    await repo.fetchPage({
      target: NODE_TARGET,
      filter: EMPTY_LOG_FILTER,
      cursor: null,
      pageSize: 50,
    });

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).not.toContain("level=");
    expect(url).not.toContain("contains=");
  });

  it("surfaces a zero-match filtered page as an empty page, not an error", async () => {
    stubFetchSequence([() => jsonResponse({ lines: [], olderCursor: null, newerCursor: null })]);
    const repo = new HttpLogsRepository();

    const page = await repo.fetchPage({
      target: NODE_TARGET,
      filter: toLogFilter("ERROR", "nothing matches this"),
      cursor: null,
      pageSize: 50,
    });

    expect(page.items).toEqual([]);
    expect(page.nextCursor).toBeNull();
  });

  it("carries the filter into both the follow seed request and every poll", async () => {
    const seedTimestamp = "2026-08-01T00:00:00Z";
    const fetchMock = stubFetchSequence([
      () =>
        jsonResponse({
          lines: [{ timestamp: seedTimestamp, level: "ERROR", message: "already on disk" }],
          olderCursor: null,
          newerCursor: seedTimestamp,
        }),
      () =>
        jsonResponse({
          lines: [{ timestamp: "2026-08-01T00:00:05Z", level: "ERROR", message: "genuinely new" }],
          olderCursor: seedTimestamp,
          newerCursor: "2026-08-01T00:00:05Z",
        }),
    ]);
    const repo = new HttpLogsRepository();
    const onLine = vi.fn();

    const stop = repo.openFollow(NODE_TARGET, toLogFilter("ERROR", null), onLine);
    try {
      await vi.waitFor(() => expect(onLine).toHaveBeenCalledTimes(1));
      const seedUrl = fetchMock.mock.calls[0]?.[0] as string;
      const pollUrl = fetchMock.mock.calls[1]?.[0] as string;
      // The seed has to be filtered too, or the cursor would start past a line the tail itself
      // would have shown.
      expect(seedUrl).toContain("level=ERROR");
      expect(pollUrl).toContain("level=ERROR");
    } finally {
      stop();
    }
  });
});
