export type ThemeMode = "light" | "dark";

const STORAGE_KEY = "andvari.theme";

export function getStoredTheme(): ThemeMode | null {
  if (typeof window === "undefined") return null;
  const value = window.localStorage.getItem(STORAGE_KEY);
  return value === "light" || value === "dark" ? value : null;
}

export function getSystemTheme(): ThemeMode {
  if (typeof window === "undefined") return "light";
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

export function resolveTheme(): ThemeMode {
  return getStoredTheme() ?? getSystemTheme();
}

export function applyTheme(mode: ThemeMode) {
  document.documentElement.classList.toggle("dark", mode === "dark");
  window.localStorage.setItem(STORAGE_KEY, mode);
}

export function applyStoredTheme() {
  if (typeof document === "undefined") return;
  document.documentElement.classList.toggle("dark", resolveTheme() === "dark");
}
