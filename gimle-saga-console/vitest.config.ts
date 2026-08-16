import { defineConfig } from "vitest/config";

// Deliberately separate from vite.config.ts: pure store/repository logic tests, not
// component/route rendering tests, so none of the SPA build's plugins (tanstackRouter, viteReact,
// tailwindcss) are needed here -- just the "@" path alias and a plain Node environment (no DOM/
// jsdom required). Same convention the sibling consoles' own vitest.config.ts files establish.
// passWithNoTests keeps `bun run test` (and the pom's bun-test execution) green while this
// console ships no tests yet -- the imported prototype had none, and none were invented for it.
export default defineConfig({
  resolve: {
    alias: { "@": `${process.cwd()}/src` },
  },
  test: {
    environment: "node",
    passWithNoTests: true,
  },
});
