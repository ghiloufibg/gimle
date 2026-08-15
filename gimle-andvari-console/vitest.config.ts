import { defineConfig } from "vitest/config";

// Deliberately separate from vite.config.ts: pure store/repository logic tests, not
// component/route rendering tests, so none of the SPA build's plugins (tanstackRouter, viteReact,
// tailwindcss) are needed here -- just the "@" path alias and a plain Node environment (no DOM/
// jsdom required). Same convention gimle-console's/gimle-fafnir-console's own vitest.config.ts
// already establishes.
export default defineConfig({
  resolve: {
    alias: { "@": `${process.cwd()}/src` },
  },
  test: {
    environment: "node",
  },
});
