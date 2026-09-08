import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError, REQUEST_TIMEOUT_MS, requestJson } from "./apiClient";

/** Mimics real `fetch`: never settles on its own, but rejects with an AbortError the moment its
 * own signal is aborted -- exactly what a black-holed connection paired with a timeout looks like. */
function neverSettlingFetch() {
  return vi.fn(
    (_url: string, init?: RequestInit) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () =>
          reject(new DOMException("aborted", "AbortError")),
        );
      }),
  );
}

describe("apiClient request timeout", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("surfaces a black-holed request as a catchable error instead of hanging forever", async () => {
    vi.stubGlobal("fetch", neverSettlingFetch());

    const result = requestJson("/api/validate", { method: "POST", body: "{}" });
    const assertion = expect(result).rejects.toThrow(ApiError);
    await vi.advanceTimersByTimeAsync(REQUEST_TIMEOUT_MS);
    await assertion;
  });

  it("does not fire before the timeout elapses", async () => {
    vi.stubGlobal("fetch", neverSettlingFetch());

    let settled = false;
    void requestJson("/api/validate").catch(() => {
      settled = true;
    });
    await vi.advanceTimersByTimeAsync(REQUEST_TIMEOUT_MS - 1000);

    expect(settled).toBe(false);
  });
});
