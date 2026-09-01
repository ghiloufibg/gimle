import { useEffect, useRef } from "react";

import { AUTO_REFRESH_INTERVAL_MS, createPoller } from "@/lib/polling";
import { useDisplayStore } from "@/stores/useDisplayStore";

interface AutoRefreshOptions {
  /** Suspends ticks without tearing the poller down -- for a screen holding something a re-read
   * would disturb: a half-filled form, a confirmation the operator has not answered yet, an
   * irreversible action already in flight. */
  paused?: boolean;
}

/**
 * Re-reads the current screen's data on the global auto-refresh interval, for as long as the
 * screen is mounted, the operator has auto-refresh switched on, and the tab is actually being
 * looked at.
 *
 * `refresh` should be a store action that replaces what the screen already shows without blanking
 * it or resetting how much of it has been paged in -- a poll must be invisible unless something
 * genuinely changed, which is why this is deliberately not wired to the screens' "Refresh" button
 * action.
 */
export function useAutoRefresh(
  refresh: () => void | Promise<unknown>,
  { paused = false }: AutoRefreshOptions = {},
): void {
  const enabled = useDisplayStore((s) => s.autoRefresh);
  const init = useDisplayStore((s) => s.init);
  const refreshRef = useRef(refresh);
  const pausedRef = useRef(paused);

  // Kept in refs and read at tick time so neither a fresh closure nor a change of pause state
  // restarts the interval -- restarting it would reset its phase and, on a screen that toggles
  // often, could poll far more often than the interval says.
  useEffect(() => {
    refreshRef.current = refresh;
    pausedRef.current = paused;
  }, [refresh, paused]);

  useEffect(() => {
    init();
  }, [init]);

  useEffect(() => {
    if (!enabled) return;
    const poller = createPoller({
      intervalMs: AUTO_REFRESH_INTERVAL_MS,
      tick: () => refreshRef.current(),
      // Nobody is reading a hidden tab, so polling it only spends control-plane requests; the
      // listener below re-reads once as soon as it comes back, so it is never left stale.
      isPaused: () => pausedRef.current || document.hidden,
    });
    poller.start();
    const onVisibilityChange = () => {
      if (!document.hidden) poller.runNow();
    };
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => {
      document.removeEventListener("visibilitychange", onVisibilityChange);
      poller.stop();
    };
  }, [enabled]);
}
