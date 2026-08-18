# Gimle Andvari Console

Andvari's own operator web console — browse the artifact catalog, inspect a module's stored
versions and provenance, push a new artifact, and view the Maven-repository interop surface. Built
with React, TanStack Router, Zustand, and Tailwind (shadcn/ui components), the same design system
`gimle-fafnir-console` established: Work Sans/JetBrains Mono, OKLCH light/dark tokens, shadcn/ui
`new-york` over Radix.

This is an independent Bun/Vite/React project — no Node, npm, or Bun code is written by hand in
Java — but it _is_ a Maven module (see `pom.xml`): `exec-maven-plugin` shells out to Bun to
install, build, and test it as part of the normal `mvn verify` reactor build, and its built output
is packaged into this module's own jar under `andvari-console/**` for `gimle-andvari` to depend on
and serve. Mirrors `gimle-fafnir-console`'s exact `pom.xml` shape; the only difference is that
bundled prefix, so the two never collide on a classpath that somehow carried both.

Its emblem (`AndvariMark.tsx`) is an inline SVG rune-etched hexagonal vault door, not a bundled
raster asset — crisp at every size (the ~32px sidebar mark and the ~80px login mark alike) with
nothing to keep in sync across a design iteration.

## Architecture

`src/repositories/index.ts` is the single composition root: it always wires the real `Http*`
implementations (`HttpArtifactsRepository`, `HttpAuthRepository`, `HttpStatusRepository`) against
`src/repositories/types.ts`'s interfaces — never a runtime Mock/Http toggle, the same posture
`gimle-console`/`gimle-fafnir-console` already take, since this module ships inside a real process's
jar and is served to a real operator against a real registry. The `Mock*` implementations in
`src/repositories/mock/mockRepositories.ts` exist only for Vitest coverage
(`src/repositories/__tests__/`) and as a reference implementation of the same interfaces, not as a
selectable mode. State lives in Zustand stores (`src/stores/`) that call into the repositories, and
routes/components read from the stores — the same repository-over-store architecture the sibling
consoles use.

## Development

```sh
bun install
bun run dev      # dev server on :8100, proxying /auth, /status, /artifacts, /repository to a
                  # local Andvari instance (GIMLE_ANDVARI_PORT, default 9094)
bun run build    # production build (SPA mode -- outputs a static app, no Node server needed)
bun run test     # vitest run
bun run lint
```

`vite.config.ts` deliberately configures a plain client-side-rendered SPA, never TanStack
Start/SSR: Lovable's own default scaffold for this stack uses TanStack Start with Nitro-based
prerendering, which breaks outside Lovable's own build sandbox (a hardcoded server entry mismatch
against what Nitro actually emits) — both sibling consoles hit exactly this and were converted away
from it before ever landing here, so this module was scaffolded avoiding that mismatch from the
start. `base` is only set for the production build (`/console/`, matching where `AndvariServer`
serves the built assets); left unset for `vite dev` so the local dev server keeps serving from
root — kept in sync with the basepath condition in `src/router.tsx`.

## Serving

`gimle-andvari` depends on this module and serves its bundled build output at `/console`
automatically: `AndvariMain` resolves it straight off the classpath at startup via
`BundledSpa.resolve(..., "andvari-console/index.html")` and hands it to
`AndvariServer#serveConsole` — no separate build/copy/flag step, no `--console-dir`. Just
`mvn install` from the repo root. If no bundled console is found on the classpath (e.g. a partial
build), Andvari logs that `/console` is disabled and keeps serving its operational API regardless.
