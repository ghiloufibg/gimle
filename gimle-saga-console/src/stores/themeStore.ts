import { create } from "zustand";

type Theme = "light" | "dark";

interface ThemeState {
  theme: Theme;
  hydrate: () => void;
  toggle: () => void;
}

const apply = (theme: Theme) => {
  if (typeof document === "undefined") return;
  document.documentElement.classList.toggle("dark", theme === "dark");
};

export const useThemeStore = create<ThemeState>((set, get) => ({
  theme: "dark",
  hydrate: () => {
    const stored =
      typeof localStorage !== "undefined"
        ? (localStorage.getItem("saga.theme") as Theme | null)
        : null;
    const theme: Theme = stored ?? "dark";
    apply(theme);
    set({ theme });
  },
  toggle: () => {
    const theme: Theme = get().theme === "dark" ? "light" : "dark";
    apply(theme);
    if (typeof localStorage !== "undefined") localStorage.setItem("saga.theme", theme);
    set({ theme });
  },
}));
