# Gimle Fafnir Console

Fafnir's own operator web console — sign in, browse a tenant's secrets, view versions, and manage
the key ring, against the real `gimle-fafnir` `/secrets/*` and `/auth/*` API. Built with React,
TanStack Router, Zustand, and Tailwind (shadcn/ui components), the same design system
`gimle-andvari-console` later reused: Work Sans/JetBrains Mono, OKLCH light/dark tokens, shadcn/ui
`new-york` over Radix.

This is an independent Bun/Vite/React project — no Node, npm, or Bun code is written by hand in
Java — but it _is_ a Maven module (see `pom.xml`): `exec-maven-plugin` shells out to Bun to
install, build, and test it as part of the normal `mvn verify` reactor build, and its built output
is packaged into this module's own jar under `fafnir-console/**` for `gimle-fafnir` to depend on
and serve. Mirrors `gimle-console`'s exact `pom.xml` shape; the only difference is that bundled
prefix, so the two never collide on a classpath that somehow carried both.

Its emblem is a bundled raster asset (`src/assets/fafnir-mark.png`), unlike `gimle-andvari-console`'s
later inline-SVG `AndvariMark` — the two consoles share a design system, not every implementation
detail of it.

## Architecture

`src/repositories/index.ts` is the single composition root: it always wires the real `Http*`
implementations (`HttpAuthRepository`, `HttpStatusRepository`, `HttpSecretsRepository`) against the
`AuthRepository`/`StatusRepository`/`SecretsRepository` interfaces declared alongside each
resource's own module (`src/repositories/auth.ts`, `status.ts`, `secrets.ts`) — never a runtime
Mock/Http toggle, since this module ships inside `gimle-fafnir`'s own jar and is served to a real
operator against a real vault. `MockSecretsRepository` lives beside the real interface in
`secrets.ts` and is exercised only by Vitest (`secrets.test.ts`) as a reference implementation, not
as a selectable mode. State lives in Zustand stores (`src/stores/`) that call into the
repositories, and routes/components read from the stores.

## Development

```sh
bun install
bun run dev      # dev server on :8090, proxying /auth, /status, /secrets to a local Fafnir
                  # instance (GIMLE_FAFNIR_PORT, default 9092)
bun run build    # production build (SPA mode -- outputs a static app, no Node server needed)
bun run test     # vitest run
bun run lint
```

`vite.config.ts` deliberately configures a plain client-side-rendered SPA, never TanStack
Start/SSR: Lovable's own default scaffold for this stack uses TanStack Start with Nitro-based
prerendering, which breaks outside Lovable's own build sandbox (a hardcoded server entry mismatch
against what Nitro actually emits) — this console hit exactly this and was converted away from it,
the pattern `gimle-console` had already proven working, before ever landing here. `base` is only
set for the production build (`/console/`, matching where `FafnirServer` serves the built assets);
left unset for `vite dev` so the local dev server keeps serving from root — kept in sync with the
basepath condition in `src/router.tsx`.

## Serving

`gimle-fafnir` depends on this module and serves its bundled build output at `/console`
automatically: `FafnirMain` resolves it straight off the classpath at startup via
`BundledSpa.resolve(..., "fafnir-console/index.html")` and hands it to
`FafnirServer#serveConsole` — no separate build/copy/flag step, no `--console-dir`. Just
`mvn install` from the repo root. Sign-in against the console goes through `FafnirServer`'s own
`/auth/login`/`/auth/logout`/`/auth/session` surface and its `gimle_fafnir_session` cookie
(`HttpOnly`, `SameSite=Strict`, `Secure` under TLS) — the same session story
`gimle-andvari-console` was later built to reuse for its own process.
