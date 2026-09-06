import { configDefaults, defineConfig } from "vitest/config";

// Deliberately separate from vite.config.ts: store/repository logic tests, plus the occasional
// static-markup render (react-dom/server) where a rendered attribute -- a deep link's href, an
// accessible name -- is itself what is under test. None of the SPA build's plugins
// (tanstackRouter, viteReact, tailwindcss) are needed for either -- just the "@" path alias and a
// plain Node environment (no DOM/jsdom required).
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
