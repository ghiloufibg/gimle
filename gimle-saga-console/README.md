# Gimle Saga Console

The web console for `gimle-saga`'s test-run report server — every test run, the flaky-test
scoreboard, per-test history, and run-to-run diffing, in one dense operator UI. Built with React,
TanStack Router, Zustand, and Tailwind (shadcn/ui components), the same design-system posture the
platform's other consoles (`gimle-console`, `gimle-fafnir-console`, `gimle-andvari-console`) share.

Like those siblings, this is an independent Bun/Vite/React project — no Node, npm, or Bun code is
hand-written in Java — but it *is* a Maven module (see `pom.xml`): `exec-maven-plugin` shells out to
Bun to install, build, and test it as part of the normal `mvn verify` reactor build, and the built
`dist/` output is copied into this module's own jar under `saga-console/**` for `gimle-saga` to read
off its classpath and serve at its own `/console`. A plain client-side-rendered SPA config (no
TanStack Start/Nitro/SSR) — `vite.config.ts` documents why: Lovable's own default scaffold for this
stack breaks outside Lovable's own build sandbox, and every sibling console hit the same problem and
was converted to a plain SPA on import.

`src/repositories/index.ts` is the single composition root: stores depend only on the
`RunsRepository`/`FlakyRepository`/`TestHistoryRepository` interfaces in `contracts.ts`, and that one
file always wires the real `Http*Repository` implementations against `gimle-saga`'s `/api/*` surface
— never a runtime Mock/Http toggle. `repositories/mock.ts`/`fixtures.ts` exist only for Vitest
coverage and as a reference implementation of the same interfaces.

## Screens

- **Runs** (`/`) — every run, newest first: status, totals, flake counts, duration, git SHA/branch,
  in one dense operator table.
- **Run detail** (`/runs/$runId`) — one run's event stream, including its own attachment data when
  present (Gherkin scenario/step results, a chaos-strike ledger, Surtr phase measurements).
- **Gjallarhorn** (`/gjallarhorn`) — the flaky-test scoreboard: ranked scores, flake rates,
  last-N-run outcome strips, quarantine state, and the configured flake-budget allowance.
- **Compare** (`/compare`) — diffs two runs: newly failing, newly passing, newly flaky tests, and
  duration regressions over 25%.
- **Test detail** (`/tests/$testId`) — one test's outcome/duration/attempt history across every run
  it appeared in.

## Development

```sh
bun install
bun run dev      # dev server with hot reload (proxies /api to a local SagaMain on :9096 by
                  # default; override with GIMLE_SAGA_PORT)
bun run build    # production build (SPA mode — outputs a static app, no Node server needed to serve it)
bun run lint
bun run test     # vitest
```

## Serving

`gimle-saga` depends on this module and serves its bundled build output at `/console` automatically
(`SagaMain` resolves it off the classpath via `BundledSpa` — the same pattern `gimle-andvari-console`
established) whenever it's present. Just `mvn install` from the repo root.
