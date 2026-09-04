import { create } from "zustand";

export type DrawerKind = "problems" | "files" | "run" | null;
export type Theme = "dark" | "light";

interface UiState {
  drawer: DrawerKind;
  drawerHeight: number;
  inspectorWidth: number;
  theme: Theme;
  paletteQuery: string;
  paletteCollapsed: boolean;
  inspectorCollapsed: boolean;
  togglePalette: () => void;
  toggleInspector: () => void;
  setDrawer: (drawer: DrawerKind) => void;
  toggleDrawer: (drawer: Exclude<DrawerKind, null>) => void;
  setDrawerHeight: (height: number) => void;
  setInspectorWidth: (width: number) => void;
  setTheme: (theme: Theme) => void;
  toggleTheme: () => void;
  setPaletteQuery: (query: string) => void;
}

const THEME_KEY = "ivaldi.theme";

export function applyTheme(theme: Theme): void {
  if (typeof document === "undefined") return;
  document.documentElement.classList.toggle("dark", theme === "dark");
  try {
    window.localStorage.setItem(THEME_KEY, theme);
  } catch {
    /* ignore */
  }
}

export function storedTheme(): Theme {
  if (typeof window === "undefined") return "dark";
  try {
    return window.localStorage.getItem(THEME_KEY) === "light" ? "light" : "dark";
  } catch {
    return "dark";
  }
}

export const useUiStore = create<UiState>((set, get) => ({
  drawer: null,
  drawerHeight: 280,
  inspectorWidth: 360,
  theme: "dark",
  paletteQuery: "",
  paletteCollapsed: false,
  inspectorCollapsed: false,
  togglePalette: () => set({ paletteCollapsed: !get().paletteCollapsed }),
  toggleInspector: () => set({ inspectorCollapsed: !get().inspectorCollapsed }),
  setDrawer: (drawer) => set({ drawer }),
  toggleDrawer: (drawer) => set({ drawer: get().drawer === drawer ? null : drawer }),
  setDrawerHeight: (height) => set({ drawerHeight: Math.min(Math.max(height, 140), 620) }),
  setInspectorWidth: (width) => set({ inspectorWidth: Math.min(Math.max(width, 280), 620) }),
  setTheme: (theme) => {
    applyTheme(theme);
    set({ theme });
  },
  toggleTheme: () => {
    const next = get().theme === "dark" ? "light" : "dark";
    applyTheme(next);
    set({ theme: next });
  },
  setPaletteQuery: (paletteQuery) => set({ paletteQuery }),
}));
