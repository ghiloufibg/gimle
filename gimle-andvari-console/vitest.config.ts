import { defineConfig } from "vitest/config";

// Deliberately separate from vite.config.ts: store/repository logic tests, plus the occasional
// static-markup render (react-dom/server) where a rendered attribute is itself what is under test.
// None of the SPA build's plugins (tanstackRouter, viteReact, tailwindcss) are needed for either --
// just the "@" path alias and a plain Node environment (no DOM/jsdom required). Same convention
// gimle-console's/gimle-fafnir-console's own vitest.config.ts already establishes.
export default defineConfig({
  resolve: {
    alias: { "@": `${process.cwd()}/src` },
  },
  test: {
    environment: "node",
  },
});
