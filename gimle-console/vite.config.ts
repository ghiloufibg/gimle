import { defineConfig } from "vite";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import viteReact from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import tsConfigPaths from "vite-tsconfig-paths";
import { readFileSync } from "node:fs";

// Single source of truth for the version the Overview footer and sidebar both display --
// previously two independent hardcoded strings, one of them wrong.
const pkgVersion = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf-8"))
  .version as string;

// Plain client-side-rendered SPA config (no TanStack Start / Nitro / SSR) -- matches the pattern
// already proven working in the sibling flow-trace-ui project's frontend module. The Lovable-
// generated version of this project used TanStack Start (the workspace's account-level default
// stack) with SPA mode + prerendering to get a static shell; that prerender step turned out to be
// broken outside Lovable's own build sandbox (a hardcoded server.js filename mismatch against
// nitro's actual entry naming, see git history for the workaround that was tried and abandoned).
// Switching to TanStack Router directly, with no Start/Nitro layer at all, sidesteps that entire
// class of bug and produces a conventional dist/index.html + dist/assets/* SPA bundle -- exactly
// what ConsoleStaticHandler already expects to serve (its "_shell.html" detection falls back to
// "index.html" precisely for this case).
//
// `base` is only set for the production build, not `vite dev`: the built dist/ is served by
// gimle-controlplane's ApiServer at /console (see ConsoleStaticHandler) -- without a matching
// `base`, every asset URL in the built index.html would be absolute from site root and 404 under
// that path. Left unset for dev so the local dev server keeps serving from root. Matches the
// basepath condition in src/router.tsx -- keep both in sync.
export default defineConfig(({ command }) => ({
  base: command === "build" ? "/console/" : "/",
  define: {
    __APP_VERSION__: JSON.stringify(pkgVersion),
  },
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
    port: 8080,
    // Dev-only proxy so src/repositories/http/apiClient.ts can use same-origin relative paths
    // (/deployments, /nodes, /tenants, /config) in both dev and prod, with no runtime env var for
    // an API base URL -- that constraint is about the shipped app's own code, not this
    // build-time-only Vite config, so reading process.env here is fine.
    // Override the target port with GIMLE_CONTROLPLANE_PORT if your local control plane isn't on
    // 8081 (chosen to avoid colliding with this dev server's own port 8080 above).
    proxy: {
      "/deployments": `http://localhost:${process.env.GIMLE_CONTROLPLANE_PORT ?? "8081"}`,
      "/nodes": `http://localhost:${process.env.GIMLE_CONTROLPLANE_PORT ?? "8081"}`,
      "/tenants": `http://localhost:${process.env.GIMLE_CONTROLPLANE_PORT ?? "8081"}`,
      "/config": `http://localhost:${process.env.GIMLE_CONTROLPLANE_PORT ?? "8081"}`,
      "/logs": `http://localhost:${process.env.GIMLE_CONTROLPLANE_PORT ?? "8081"}`,
      "/auth": `http://localhost:${process.env.GIMLE_CONTROLPLANE_PORT ?? "8081"}`,
      "/metrics-history": `http://localhost:${process.env.GIMLE_CONTROLPLANE_PORT ?? "8081"}`,
      "/traces-history": `http://localhost:${process.env.GIMLE_CONTROLPLANE_PORT ?? "8081"}`,
      "/audit": `http://localhost:${process.env.GIMLE_CONTROLPLANE_PORT ?? "8081"}`,
      "/artifacts": `http://localhost:${process.env.GIMLE_CONTROLPLANE_PORT ?? "8081"}`,
      "/services": `http://localhost:${process.env.GIMLE_CONTROLPLANE_PORT ?? "8081"}`,
      "/networkpolicies": `http://localhost:${process.env.GIMLE_CONTROLPLANE_PORT ?? "8081"}`,
    },
  },
}));
