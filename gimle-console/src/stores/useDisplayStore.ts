import { create } from "zustand";

export type DisplayMode = "hud" | "signal";
export type Density = "compact" | "roomy";

interface State {
  mode: DisplayMode;
  density: Density;
  /** Whether screens re-read their data on their own. The one switch for every automatic read the
   * console makes -- list/topology polling and the Metrics/Traces/Logs live tails alike. */
  autoRefresh: boolean;
  initialized: boolean;
  init(): void;
  setMode(m: DisplayMode): void;
  setDensity(d: Density): void;
  setAutoRefresh(on: boolean): void;
}

const KEY = "gimle.display";

interface Preferences {
  mode: DisplayMode;
  density: Density;
  autoRefresh: boolean;
}

function persist(prefs: Preferences) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(KEY, JSON.stringify(prefs));
}

export const useDisplayStore = create<State>((set, get) => ({
  // Tactical HUD stays the default display.
  mode: "hud",
  density: "compact",
  // On by default: a dashboard whose job is showing the current state of a cluster is wrong the
  // moment it stops keeping up, so an operator has to opt out of that, not into it.
  autoRefresh: true,
  initialized: false,
  init() {
    if (get().initialized || typeof window === "undefined") return;
    try {
      const raw = window.localStorage.getItem(KEY);
      if (raw) {
        const parsed = JSON.parse(raw) as Partial<Preferences>;
        set({
          mode: parsed.mode === "signal" ? "signal" : "hud",
          density: parsed.density === "roomy" ? "roomy" : "compact",
          autoRefresh: parsed.autoRefresh !== false,
          initialized: true,
        });
        return;
      }
    } catch {
      /* ignore malformed preference */
    }
    set({ initialized: true });
  },
  setMode(mode) {
    persist({ ...preferencesOf(get()), mode });
    set({ mode });
  },
  setDensity(density) {
    persist({ ...preferencesOf(get()), density });
    set({ density });
  },
  setAutoRefresh(autoRefresh) {
    persist({ ...preferencesOf(get()), autoRefresh });
    set({ autoRefresh });
  },
}));

function preferencesOf(state: State): Preferences {
  return { mode: state.mode, density: state.density, autoRefresh: state.autoRefresh };
}
