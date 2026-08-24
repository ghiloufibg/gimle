import { ApiError } from "./apiClient";

/**
 * Muninn (the observability sink) is optional per cluster -- a cluster with none configured 404s
 * every /metrics-history/* and /traces-history/* request. That's expected and already handled
 * (each screen renders a clear inline message from the resulting error), but Chrome's devtools
 * still logs a "Failed to load resource" line for the underlying failed fetch() regardless of
 * whether app code catches it, and nothing here can suppress that browser-level logging.
 *
 * What this module can control is not re-triggering it: once either /metrics-history/* or
 * /traces-history/* has 404'd once this session, both repositories reuse that outcome instead of
 * firing a second doomed request -- whether the repeat attempt is revisiting the same screen or
 * visiting the other one for the first time. A fresh page load re-probes once, which is fine.
 */
let unavailable: ApiError | null = null;

export function historyBackendUnavailable(): ApiError | null {
  return unavailable;
}

/** Test-only: production code never needs to un-discover this within a session. */
export function resetHistoryBackendAvailability(): void {
  unavailable = null;
}

/**
 * Shared GET used by both HttpMetricsHistoryRepository and HttpTracesHistoryRepository -- fetches
 * `url` and returns the parsed JSON envelope, short-circuiting to the remembered ApiError instead
 * of calling fetch() again once a prior call has already discovered a 404.
 */
export async function fetchHistoryEnvelope<T>(url: string): Promise<T> {
  if (unavailable) throw unavailable;
  const res = await fetch(url);
  if (!res.ok) {
    const err = new ApiError(res.status, await res.text());
    if (res.status === 404) unavailable = err;
    throw err;
  }
  return (await res.json()) as T;
}
