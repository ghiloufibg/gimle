import { useRef } from "react";

/**
 * Wraps an async action so a second call made while the first is still running is dropped rather
 * than started -- a synchronous ref, not state, since a fast double-click (or two buttons wired to
 * the same handler) can fire both calls before either's async work, or a re-render reflecting it,
 * has had a chance to run. Returns the same in-flight promise to a caller who happens to await it,
 * so a dropped call still resolves once the original one does.
 */
export function useSingleFlight<Args extends unknown[], T>(
  action: (...args: Args) => Promise<T>,
): (...args: Args) => Promise<T | undefined> {
  const inFlight = useRef<Promise<T> | undefined>(undefined);
  return async (...args: Args) => {
    if (inFlight.current) return inFlight.current;
    const promise = action(...args).finally(() => {
      inFlight.current = undefined;
    });
    inFlight.current = promise;
    return promise;
  };
}
