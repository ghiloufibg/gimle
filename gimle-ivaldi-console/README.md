# Gimle Ivaldi Console

The web console for `gimle-ivaldi`, the Gimlé cluster designer: a drag-and-drop canvas for laying
out a local Gimlé cluster (platform processes) together with the application deployed on it
(tenants, workloads, services, network policies, config, secrets, limit ranges), with validation
problems surfaced live while drawing. Built with React, TanStack Router, Zustand, and Tailwind
(shadcn/ui components) plus `@xyflow/react` for the canvas, the same design-system posture the
platform's other consoles (`gimle-console`, `gimle-fafnir-console`, `gimle-andvari-console`,
`gimle-saga-console`) share.

Like those siblings, this is an independent Bun/Vite/React project — no Node, npm, or Bun code is
hand-written in Java — but it _is_ a Maven module (see `pom.xml`): `exec-maven-plugin` shells out to
Bun to install, build, and test it as part of the normal `mvn verify` reactor build, and the built
`dist/` output is copied into this module's own jar under `ivaldi-console/**` for `gimle-ivaldi` to
read off its classpath and serve at its own `/console`. A plain client-side-rendered SPA config (no
TanStack Start/Nitro/SSR) — `vite.config.ts` documents why: Lovable's own default scaffold for this
stack breaks outside Lovable's own build sandbox, and every sibling console hit the same problem and
was converted to a plain SPA on import.

`src/repositories/index.ts` is the single composition root: stores depend only on the
`BlueprintsRepository`/`ClustersRepository`/`RunnerClient`/`HilmirValidatorClient` interfaces in
`contracts.ts`. It wires the real `Http*` implementation for everything `gimle-ivaldi` already
serves — blueprint CRUD and tier-2 validate, both same-origin `/api/*` — and a client-side
stand-in (`LocalStorageClustersRepository`, `MockRunnerClient`) for cluster targeting and the run
protocol, which the backend doesn't implement yet (`/api/clusters`, `/api/runs`). `Mock*`/
`LocalStorage*` implementations exist for Vitest coverage and as reference implementations of the
same interfaces, never behind a runtime toggle.

## Screens

- **Blueprints** (`/`) — every saved Blueprint: name, version, updated. New, duplicate, delete,
  import a zip or `ivaldi.blueprint.json`.
- **Designer** (`/designer/$blueprintId`) — the canvas: a Platform/Application palette, the
  React Flow canvas, an Inspector for the selected node, and Problems/Files/Run drawers.
- **Clusters** (`/clusters`) — saved local cluster connections (control-plane URL, optional
  runner daemon URL, mTLS client cert/key for a TLS-mode cluster — never a username or password;
  Gimlé has no such auth). Client-side only until `/api/clusters` exists server-side.

## Development

```sh
bun install
bun run dev      # dev server with hot reload (proxies /api to a local IvaldiMain on :9097 by
                  # default; override with GIMLE_IVALDI_PORT)
bun run build    # production build (SPA mode — outputs a static app, no Node server needed to serve it)
bun run lint
bun run test     # vitest
```

## Serving

`gimle-ivaldi` depends on this module and serves its bundled build output at `/console`
automatically (`IvaldiMain` resolves it off the classpath via `BundledSpa` — the same pattern
`gimle-saga-console` established) whenever it's present. Just `mvn install` from the repo root.

## Origin

Imported from the Lovable prototype at `github.com/ghiloufibg/cluster-forge` @
`c789832a8128fdb0de0a995d70c60f2c9c6d26c3`, then converted to a plain SPA and wired to the real
`gimle-ivaldi` backend following the conversion steps every sibling console went through.
