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
const LAYOUT_KEY = "ivaldi.layout";

interface StoredLayout {
  drawerHeight?: number;
  inspectorWidth?: number;
  paletteCollapsed?: boolean;
  inspectorCollapsed?: boolean;
}

function readLayout(): StoredLayout {
  if (typeof window === "undefined") return {};
  try {
    const raw = window.localStorage.getItem(LAYOUT_KEY);
    return raw ? (JSON.parse(raw) as StoredLayout) : {};
  } catch {
    return {};
  }
}

function writeLayout(patch: StoredLayout): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(LAYOUT_KEY, JSON.stringify({ ...readLayout(), ...patch }));
  } catch {
    /* ignore */
  }
}

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

const layout = readLayout();

export const useUiStore = create<UiState>((set, get) => ({
  drawer: null,
  drawerHeight: layout.drawerHeight ?? 280,
  inspectorWidth: layout.inspectorWidth ?? 360,
  theme: "dark",
  paletteQuery: "",
  paletteCollapsed: layout.paletteCollapsed ?? false,
  inspectorCollapsed: layout.inspectorCollapsed ?? false,
  togglePalette: () => {
    const paletteCollapsed = !get().paletteCollapsed;
    writeLayout({ paletteCollapsed });
    set({ paletteCollapsed });
  },
  toggleInspector: () => {
    const inspectorCollapsed = !get().inspectorCollapsed;
    writeLayout({ inspectorCollapsed });
    set({ inspectorCollapsed });
  },
  setDrawer: (drawer) => set({ drawer }),
  toggleDrawer: (drawer) => set({ drawer: get().drawer === drawer ? null : drawer }),
  setDrawerHeight: (height) => {
    const drawerHeight = Math.min(Math.max(height, 140), 620);
    writeLayout({ drawerHeight });
    set({ drawerHeight });
  },
  setInspectorWidth: (width) => {
    const inspectorWidth = Math.min(Math.max(width, 280), 620);
    writeLayout({ inspectorWidth });
    set({ inspectorWidth });
  },
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
