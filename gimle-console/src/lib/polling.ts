/** How often an auto-refreshing screen re-reads its data. Slow enough that a browser left open on
 * a list screen costs the control plane one cheap read per screen per 10s, fast enough that a
 * rollout, a cordon, or any other reconciler-driven change shows up while an operator is still
 * looking at the screen that should show it. */
export const AUTO_REFRESH_INTERVAL_MS = 10_000;

export interface PollerOptions {
  intervalMs: number;
  /** Runs on every tick. May be async; its rejection is swallowed, not fatal to the poller. */
  tick: () => void | Promise<unknown>;
  /** Consulted at each tick rather than captured once, so a screen can suspend polling (a dirty
   * form, a hidden tab) without tearing the poller down and losing its interval phase. */
  isPaused?: () => boolean;
}

export interface Poller {
  start(): void;
  stop(): void;
  /** Runs a tick immediately, subject to the same in-flight and paused guards as a timed tick. */
  runNow(): void;
  isRunning(): boolean;
}

/**
 * The one polling primitive in this app: a fixed-interval timer that never lets two of its own
 * ticks be in flight at once. Every automatic re-read -- the global screen auto-refresh, the
 * Metrics/Traces live tail -- runs on this, so "the console is polling" means one mechanism with
 * one set of guarantees rather than a per-screen setInterval each with its own bugs.
 */
export function createPoller({ intervalMs, tick, isPaused }: PollerOptions): Poller {
  let timer: ReturnType<typeof setInterval> | null = null;
  let inFlight = false;

  function fire(): void {
    // A tick still running when the next one comes due is skipped outright, not queued: against a
    // slow control plane a queue would grow one identical read per interval and then land them all
    // at once. Skipping means at most one outstanding request per poller, always.
    if (inFlight || isPaused?.()) return;
    inFlight = true;
    let result: void | Promise<unknown>;
    try {
      result = tick();
    } catch {
      inFlight = false;
      return;
    }
    const done = () => {
      inFlight = false;
    };
    Promise.resolve(result).then(done, done);
  }

  return {
    start() {
      if (timer === null) timer = setInterval(fire, intervalMs);
    },
    stop() {
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
    },
    runNow() {
      fire();
    },
    isRunning() {
      return timer !== null;
    },
  };
}
