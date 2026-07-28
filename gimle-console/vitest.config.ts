import { defineConfig } from "vitest/config";

// Deliberately separate from vite.config.ts: these are pure store/repository logic tests (§12 of
// web-console-design.md), not component/route rendering tests, so none of the SPA build's plugins
// (tanstackRouter, viteReact, tailwindcss) are needed here -- just the "@" path alias and a plain
// Node environment (no DOM/jsdom required).
export default defineConfig({
  resolve: {
    alias: { "@": `${process.cwd()}/src` },
  },
  test: {
    environment: "node",
  },
});
