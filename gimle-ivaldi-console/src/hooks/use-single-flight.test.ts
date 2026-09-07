// @vitest-environment jsdom
import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { useSingleFlight } from "./use-single-flight";

/** Resolves once someone calls its own `resolve`, letting a test control completion order. */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

describe("useSingleFlight", () => {
  it("drops a second call made while the first is still in flight", async () => {
    const pending = deferred<string>();
    const action = vi.fn<(clusterId: string) => Promise<string>>().mockReturnValue(pending.promise);
    const { result } = renderHook(() => useSingleFlight(action));

    let first: Promise<string | undefined> | undefined;
    let second: Promise<string | undefined> | undefined;
    act(() => {
      // The exact "rapid double-click" shape: both calls fire before either's async work settles.
      first = result.current("a");
      second = result.current("a");
    });

    expect(action).toHaveBeenCalledTimes(1);

    pending.resolve("done");
    await expect(first).resolves.toBe("done");
    await expect(second).resolves.toBe("done");
  });

  it("runs a fresh call once the previous one has completed", async () => {
    const action = vi.fn().mockResolvedValueOnce("first").mockResolvedValueOnce("second");
    const { result } = renderHook(() => useSingleFlight(action));

    await act(async () => {
      await expect(result.current()).resolves.toBe("first");
    });
    await act(async () => {
      await expect(result.current()).resolves.toBe("second");
    });

    expect(action).toHaveBeenCalledTimes(2);
  });
});
