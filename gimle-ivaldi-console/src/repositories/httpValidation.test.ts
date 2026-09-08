import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { REQUEST_TIMEOUT_MS } from "./apiClient";
import { HttpValidationRepository } from "./httpValidation";

describe("HttpValidationRepository.validate", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("surfaces a black-holed POST /api/validate as a clear report error instead of hanging forever", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        (_url: string, init?: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener("abort", () =>
              reject(new DOMException("aborted", "AbortError")),
            );
          }),
      ),
    );

    const call = new HttpValidationRepository().validate([{ path: "topology.yaml", content: "" }]);
    const assertion = call.then((report) => {
      expect(report.ok).toBe(false);
      expect(report.error).toContain("timed out");
    });
    await vi.advanceTimersByTimeAsync(REQUEST_TIMEOUT_MS);
    await assertion;
  });
});
