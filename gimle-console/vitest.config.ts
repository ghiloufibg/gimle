import { configDefaults, defineConfig } from "vitest/config";

// Deliberately separate from vite.config.ts: these are pure store/repository logic tests, not
// component/route rendering tests, so none of the SPA build's plugins
// (tanstackRouter, viteReact, tailwindcss) are needed here -- just the "@" path alias and a plain
// Node environment (no DOM/jsdom required).
export default defineConfig({
  resolve: {
    alias: { "@": `${process.cwd()}/src` },
  },
  test: {
    environment: "node",
    // e2e/ is Playwright's own suite (a real browser against a real backend, see
    // playwright.config.ts) -- Vitest's default *.spec.ts glob would otherwise also pick it up,
    // and importing @playwright/test's `test` inside a Vitest run collides with Vitest's own.
    exclude: [...configDefaults.exclude, "e2e/**"],
  },
});
