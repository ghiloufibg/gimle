import { create } from "zustand";

export type DisplayMode = "hud" | "signal";
export type Density = "compact" | "roomy";

interface State {
  mode: DisplayMode;
  density: Density;
  initialized: boolean;
  init(): void;
  setMode(m: DisplayMode): void;
  setDensity(d: Density): void;
}

const KEY = "gimle.display";

function persist(mode: DisplayMode, density: Density) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(KEY, JSON.stringify({ mode, density }));
}

export const useDisplayStore = create<State>((set, get) => ({
  // Tactical HUD stays the default display.
  mode: "hud",
  density: "compact",
  initialized: false,
  init() {
    if (get().initialized || typeof window === "undefined") return;
    try {
      const raw = window.localStorage.getItem(KEY);
      if (raw) {
        const parsed = JSON.parse(raw) as Partial<{ mode: DisplayMode; density: Density }>;
        set({
          mode: parsed.mode === "signal" ? "signal" : "hud",
          density: parsed.density === "roomy" ? "roomy" : "compact",
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
    persist(mode, get().density);
    set({ mode });
  },
  setDensity(density) {
    persist(get().mode, density);
    set({ density });
  },
}));
