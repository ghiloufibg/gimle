import { create } from "zustand";

type Theme = "light" | "dark";

interface State {
  theme: Theme;
  initialized: boolean;
  init(): void;
  setTheme(t: Theme): void;
  toggle(): void;
}

const KEY = "gimle.theme";

function apply(theme: Theme) {
  if (typeof document === "undefined") return;
  document.documentElement.classList.toggle("dark", theme === "dark");
  document.documentElement.style.colorScheme = theme;
}

export const useThemeStore = create<State>((set, get) => ({
  theme: "dark",
  initialized: false,
  init() {
    if (get().initialized || typeof window === "undefined") return;
    const stored = window.localStorage.getItem(KEY) as Theme | null;
    const theme: Theme =
      stored === "light" || stored === "dark"
        ? stored
        : window.matchMedia("(prefers-color-scheme: dark)").matches
          ? "dark"
          : "light";
    apply(theme);
    set({ theme, initialized: true });
  },
  setTheme(theme) {
    apply(theme);
    if (typeof window !== "undefined") window.localStorage.setItem(KEY, theme);
    set({ theme });
  },
  toggle() {
    get().setTheme(get().theme === "dark" ? "light" : "dark");
  },
}));
