# Gimlé Console

Operator web console for the Gimlé cluster control plane — deployments, instances, nodes, tenants,
config, and logs. Built with TanStack Start, React, Zustand, and Tailwind (shadcn/ui components).

Pulled from a Lovable-generated project (commit `a8b1adff21e60177df602d40f3b0a1a8a5d32e54`); see
`claudedocs/web-console-design.md` in the repo root for the design rationale and integration plan.

**Status**: mock-only. All data is generated in-memory (`src/repositories/fixture.ts`); no repository
yet talks to the real `gimle-controlplane` API (`gimle-controlplane`'s `ApiServer`) — that's a
separate follow-up (repository/store layering is already in place specifically so that wiring is a
one-file change to `src/repositories/index.ts`).

This directory is **not** a Maven module — it's an independent npm/Vite-family project, built with
[Bun](https://bun.sh).

## Development

```sh
bun install
bun run dev      # dev server with hot reload
bun run build    # production build (SPA mode — outputs a static app, no Node server needed to serve it)
bun run lint
```

## Serving

`gimle-controlplane`'s `ApiServer` can serve this app's build output at `/console` — see
`ControlPlaneMain --console-dir <path>` (defaults to `console-dist` relative to the process's working
directory).
