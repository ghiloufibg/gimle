import { defineConfig } from "vite";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import viteReact from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import tsConfigPaths from "vite-tsconfig-paths";

// Plain client-side-rendered SPA config (no TanStack Start / Nitro / SSR) -- matches the pattern
// already proven working in gimle-console/gimle-fafnir-console/gimle-andvari-console. Lovable's
// own default scaffold for this stack is TanStack Start (via @lovable.dev/vite-tanstack-config)
// with Nitro-based prerendering, which breaks outside Lovable's own build sandbox (a hardcoded
// server entry mismatch against what Nitro actually emits) -- every sibling console hit exactly
// this and was converted to a plain SPA on import; this one was converted the same way.
//
// `base` is only set for the production build, not `vite dev`: the built dist/ is bundled into
// this module's jar under saga-console/** and meant to be served from a /console sub-path the
// same way the sibling consoles are -- without a matching `base`, every asset URL in the built
// index.html would be absolute from site root and 404 under that path. Left unset for dev so the
// local dev server keeps serving from root. Matches the basepath condition in src/router.tsx --
// keep both in sync.
export default defineConfig(({ command }) => ({
  base: command === "build" ? "/console/" : "/",
  plugins: [
    tanstackRouter({ target: "react", autoCodeSplitting: true }),
    viteReact(),
    tailwindcss(),
    tsConfigPaths({ projects: ["./tsconfig.json"] }),
  ],
  resolve: {
    alias: { "@": `${process.cwd()}/src` },
  },
  server: {
    // `true`, not the literal "::" -- that hardcodes the IPv6 wildcard address and fails outright
    // (EAFNOSUPPORT) on an IPv6-less host/container. `true` leaves the bind host unset, which
    // Node's own http.Server.listen() resolves to "::" when IPv6 is available and falls back to
    // 0.0.0.0 otherwise -- the same "reachable from the LAN" intent, portably.
    host: true,
    port: 8110,
    strictPort: true,
    // Dev-only proxy so src/repositories/http/apiClient.ts can use same-origin relative paths in
    // both dev and prod, with no runtime env var for an API base URL. 9096 matches SagaMain's own
    // gimle.saga.port default; override with GIMLE_SAGA_PORT if your local instance differs.
    proxy: {
      "/api": `http://localhost:${process.env["GIMLE_SAGA_PORT"] ?? "9096"}`,
    },
  },
  preview: {
    port: 8110,
  },
}));
