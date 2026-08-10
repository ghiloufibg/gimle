import { defineConfig } from "vite";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import viteReact from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import tsConfigPaths from "vite-tsconfig-paths";

// Plain client-side-rendered SPA config (no TanStack Start / Nitro / SSR) -- matches the pattern
// already proven working in gimle-console. Lovable's own default scaffold for this stack is
// TanStack Start (via @lovable.dev/vite-tanstack-config) with Nitro-based prerendering, which
// breaks outside Lovable's own build sandbox (a hardcoded server entry mismatch against what
// Nitro actually emits) -- gimle-console hit exactly this and was converted away from it; this
// module was converted the same way before ever landing here, rather than repeating that failure.
//
// `base` is only set for the production build, not `vite dev`: the built dist/ is served by
// gimle-fafnir's own FafnirServer at /console (see SpaStaticHandler) -- without a matching
// `base`, every asset URL in the built index.html would be absolute from site root and 404 under
// that path. Left unset for dev so the local dev server keeps serving from root. Matches the
// basepath condition in src/router.tsx -- keep both in sync.
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
    host: "::",
    port: 8090,
    // Dev-only proxy so src/repositories/http/apiClient.ts can use same-origin relative paths
    // in both dev and prod, with no runtime env var for an API base URL. 9092 matches
    // FafnirMojo/BootstrapMojo's own gimle.fafnir.port default; override with GIMLE_FAFNIR_PORT
    // if your local Fafnir instance is on a different port.
    proxy: {
      "/auth": `http://localhost:${process.env.GIMLE_FAFNIR_PORT ?? "9092"}`,
      "/status": `http://localhost:${process.env.GIMLE_FAFNIR_PORT ?? "9092"}`,
      "/secrets": `http://localhost:${process.env.GIMLE_FAFNIR_PORT ?? "9092"}`,
    },
  },
}));
