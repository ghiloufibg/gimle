# Gimlé Console

Operator web console for the Gimlé cluster control plane — deployments, instances, nodes, tenants,
config, and logs. Built with TanStack Start, React, Zustand, and Tailwind (shadcn/ui components).

Pulled from a Lovable-generated project (commit `a8b1adff21e60177df602d40f3b0a1a8a5d32e54`); see
`claudedocs/web-console-design.md` in the repo root for the design rationale and integration plan.

**Status**: wired to the real `gimle-controlplane` API for every screen, including live log tailing
(`src/repositories/http/*.ts`) — the mock repository set (`src/repositories/fixture.ts`) still exists
for reference/tests but is no longer the default.

This is an independent Bun/Vite/React project — no Node, npm, or Bun code is written by hand in
Java — but it _is_ a Maven module (see `pom.xml`): `exec-maven-plugin` shells out to Bun to install,
build, and test it as part of the normal `mvn verify` reactor build, and its built output is packaged
into this module's own jar for `gimle-controlplane` to depend on and serve. See `LOCAL_DEV.md` for the
full local-dev flow.

## Development

```sh
bun install
bun run dev      # dev server with hot reload
bun run build    # production build (SPA mode — outputs a static app, no Node server needed to serve it)
bun run lint
```

## Serving

`gimle-controlplane` depends on this module and serves its bundled build output at `/console`
automatically (`ControlPlaneMain` reads it straight off the classpath — see `BundledConsole.java`) —
no separate build/copy/flag step. Just `mvn install` from the repo root.
