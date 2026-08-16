import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, requestJson, streamNdjsonLines } from "./apiClient";
import { jsonResponse, ndjsonStreamResponse, stubFetchSequence, textResponse } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("requestJson", () => {
  it("returns the decoded JSON body on success", async () => {
    stubFetchSequence([() => jsonResponse({ status: "ok" })]);
    await expect(requestJson("/api/health")).resolves.toEqual({ status: "ok" });
  });

  it("throws an ApiError carrying the server's plain-text error body", async () => {
    stubFetchSequence([() => textResponse("no such run: r1", 404)]);
    await expect(requestJson("/api/runs/r1")).rejects.toBeInstanceOf(ApiError);
    stubFetchSequence([() => textResponse("no such run: r1", 404)]);
    await expect(requestJson("/api/runs/r1")).rejects.toMatchObject({
      status: 404,
      message: "no such run: r1",
    });
  });

  it("never surfaces an HTML error document as the message", async () => {
    stubFetchSequence([() => textResponse("<html><body>502 Bad Gateway</body></html>", 502)]);
    await expect(requestJson("/api/runs")).rejects.toMatchObject({
      message: "request failed (502)",
    });
  });
});

describe("streamNdjsonLines", () => {
  it("delivers each complete line as it is decoded", async () => {
    stubFetchSequence([() => ndjsonStreamResponse(['{"a":1}', '{"b":2}'])]);
    const lines: string[] = [];
    const controller = new AbortController();
    await streamNdjsonLines("/api/runs/r1/events", (line) => lines.push(line), controller.signal);
    expect(lines).toEqual(['{"a":1}', '{"b":2}']);
  });

  it("resolves quietly when the signal is already aborted", async () => {
    const controller = new AbortController();
    controller.abort();
    stubFetchSequence([
      () => {
        throw new DOMException("aborted", "AbortError");
      },
    ]);
    await expect(
      streamNdjsonLines("/api/runs/r1/events", () => {}, controller.signal),
    ).resolves.toBeUndefined();
  });
});
