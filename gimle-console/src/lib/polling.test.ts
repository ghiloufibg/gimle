import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AUTO_REFRESH_INTERVAL_MS, createPoller } from "./polling";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe("createPoller", () => {
  it("does not tick until started, then ticks once per interval", async () => {
    const tick = vi.fn();
    const poller = createPoller({ intervalMs: 1000, tick });

    await vi.advanceTimersByTimeAsync(5000);
    expect(tick).not.toHaveBeenCalled();

    poller.start();
    await vi.advanceTimersByTimeAsync(3000);
    expect(tick).toHaveBeenCalledTimes(3);
  });

  it("start is idempotent -- a second start does not add a second interval", async () => {
    const tick = vi.fn();
    const poller = createPoller({ intervalMs: 1000, tick });

    poller.start();
    poller.start();
    await vi.advanceTimersByTimeAsync(2000);

    expect(tick).toHaveBeenCalledTimes(2);
  });

  it("skips a tick whose predecessor is still in flight instead of stacking requests", async () => {
    const first = deferred<void>();
    const tick = vi.fn().mockReturnValueOnce(first.promise).mockResolvedValue(undefined);
    const poller = createPoller({ intervalMs: 1000, tick });
    poller.start();

    // The first tick never settles across the next three interval boundaries.
    await vi.advanceTimersByTimeAsync(4000);
    expect(tick).toHaveBeenCalledTimes(1);

    first.resolve();
    await vi.advanceTimersByTimeAsync(1000);
    expect(tick).toHaveBeenCalledTimes(2);
  });

  it("keeps polling after a tick rejects", async () => {
    const tick = vi
      .fn()
      .mockRejectedValueOnce(new Error("control plane unreachable"))
      .mockResolvedValue(undefined);
    const poller = createPoller({ intervalMs: 1000, tick });
    poller.start();

    await vi.advanceTimersByTimeAsync(3000);

    expect(tick).toHaveBeenCalledTimes(3);
  });

  it("keeps polling after a tick throws synchronously", async () => {
    const tick = vi.fn().mockImplementationOnce(() => {
      throw new Error("boom");
    });
    const poller = createPoller({ intervalMs: 1000, tick });
    poller.start();

    await vi.advanceTimersByTimeAsync(3000);

    expect(tick).toHaveBeenCalledTimes(3);
  });

  it("stops ticking once stopped -- the unmount path", async () => {
    const tick = vi.fn();
    const poller = createPoller({ intervalMs: 1000, tick });
    poller.start();
    await vi.advanceTimersByTimeAsync(2000);
    expect(tick).toHaveBeenCalledTimes(2);

    poller.stop();
    await vi.advanceTimersByTimeAsync(10_000);

    expect(tick).toHaveBeenCalledTimes(2);
    expect(poller.isRunning()).toBe(false);
  });

  it("a tick that settles after stop does not resurrect the poller", async () => {
    const pending = deferred<void>();
    const tick = vi.fn().mockReturnValue(pending.promise);
    const poller = createPoller({ intervalMs: 1000, tick });
    poller.start();
    await vi.advanceTimersByTimeAsync(1000);

    poller.stop();
    pending.resolve();
    await vi.advanceTimersByTimeAsync(10_000);

    expect(tick).toHaveBeenCalledTimes(1);
  });

  it("skips ticks while paused and resumes on the next tick once unpaused", async () => {
    let paused = true;
    const tick = vi.fn();
    const poller = createPoller({ intervalMs: 1000, tick, isPaused: () => paused });
    poller.start();

    await vi.advanceTimersByTimeAsync(3000);
    expect(tick).not.toHaveBeenCalled();

    paused = false;
    await vi.advanceTimersByTimeAsync(1000);
    expect(tick).toHaveBeenCalledTimes(1);
  });

  it("runNow ticks immediately but still honours the paused and in-flight guards", async () => {
    let paused = false;
    const pending = deferred<void>();
    const tick = vi.fn().mockReturnValueOnce(pending.promise).mockResolvedValue(undefined);
    const poller = createPoller({ intervalMs: 1000, tick, isPaused: () => paused });

    poller.runNow();
    expect(tick).toHaveBeenCalledTimes(1);

    // Still in flight: a second runNow is a no-op rather than a parallel request.
    poller.runNow();
    expect(tick).toHaveBeenCalledTimes(1);

    pending.resolve();
    await Promise.resolve();
    paused = true;
    poller.runNow();
    expect(tick).toHaveBeenCalledTimes(1);

    paused = false;
    poller.runNow();
    expect(tick).toHaveBeenCalledTimes(2);
  });

  it("polls at ten seconds by default", () => {
    expect(AUTO_REFRESH_INTERVAL_MS).toBe(10_000);
  });
});
