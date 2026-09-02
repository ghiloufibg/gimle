---
sidebar_position: 2
---

# Conventions

Binding, not optional — enforced by tooling wherever practical, not just written down. Distilled
from this repo's own `CLAUDE.md`; see that file for the complete, authoritative list.

## Build and formatting

- **Maven**, JDK 25 (`maven.compiler.release: 25`).
- **Google Java Format**, enforced via `fmt-maven-plugin` on every `mvn verify` — not a style
  suggestion, a build failure if violated.
- **Method naming**: standard `camelCase` everywhere — production code, JUnit lifecycle hooks,
  private/helper methods — with one exception: methods directly annotated `@Test` are `snake_case`,
  so a test's name reads as a sentence describing the behavior it verifies. Enforced by two
  Checkstyle `MethodName` instances, not a convention you have to remember unaided.

## Error handling

**No checked exceptions anywhere.** Gimlé failures use dedicated unchecked types in `gimle-core`
(`GimleResolutionException`, `GimleLifecycleException`, `GimleSchedulingException`, and similar),
all extending `RuntimeException`. Control-plane errors map to structured API responses, not
propagated stack traces.

## Immutability

Records and `List.of`/unmodifiable collections wherever feasible. Desired state, observed state,
and reconciliation events are strictly immutable snapshots — a reconciler reads a snapshot and
returns actions, it never mutates in place (see [Control plane](../architecture/control-plane.md)
for why that matters). `final` on variables, fields, and parameters wherever possible.

## No Lombok, no JNI

Plain Java (records, standard getters/constructors) — no Lombok. OS interaction only via
`java.nio.file` or the FFM API — no JNI, no native code, anywhere, now or once the deferred
kernel-level resource limiter lands (see [Tiered isolation](../architecture/tiered-isolation.md)).

## Comments

Clear names and small methods over Javadoc. Add a comment only where the logic is genuinely
non-obvious — layer parent selection, leak-detection reference handling, FFM struct layouts,
reconciler convergence edge cases. Applies to test code too. This is also why the aggregate Javadoc
this site publishes (see the [API Reference](pathname:///javadoc/)) is sparse by design, not by
neglect — the doclint checks that would normally flag missing `@param`/`@return` tags are
deliberately disabled for that build.

## Test coverage

Cover both happy paths and failure paths — unresolvable dependency, version conflict, probe
timeout, worker OOM, network partition, no feasible placement, corrupt manifest, cgroup write
failure. Reconcilers additionally require convergence tests from arbitrary starting states, not
just the happy-path transition — see [Control plane](../architecture/control-plane.md)'s note on
why that property is the hardest one to test. The module system requires a repeated-redeploy leak
test; the supervisor requires kill-and-recover tests at every isolation tier.

## Git hooks

- `commit-msg` rejects any commit message mentioning an AI assistant.
- Commit messages follow Conventional Commits (`feat`, `fix`, `chore`, `refactor`, `docs`, `test`,
  ...), short subject, max 3 lines total.
- `pre-commit` is currently disabled (`exit 0`) — a full `mvn verify` per commit stopped being
  practical once the reactor grew to this many modules, paying for a full uncached rebuild on every
  commit rather than just what changed. See `.githooks/pre-commit`'s own comment for the reasoning
  and what re-enabling it would take.

These hooks are tracked at `.githooks/` (not `.git/hooks/`, which is per-checkout and never
committed) — run `scripts/install-hooks.sh` once per checkout to point Git at them
(`git config core.hooksPath .githooks`). CI (`.github/workflows/ci.yml`) runs a real `mvn verify`
independently on every push/PR — with `pre-commit` disabled, that CI run is the only enforcement of
it today, not a second gate behind a local one.

## Repo hygiene

Commit only essential source and config. No generated reports or ad-hoc markdown files except
`CLAUDE.md`/`README.md` and the repo-root requirements and QA documents rendered from their JSON
sources (`REQUIREMENTS_MATRIX.md`, `RTM.md`, `UAT_CHECKLIST.md`, `FORSETI.md`, from
`requirements-matrix.json`, `rtm.json`, `uat-checklist.json`, `forseti.json`). `claudedocs/` (design
notes, QA audit findings) is gitignored — see [Project structure](./project-structure.md) if you're
looking for where things actually live in the build.

## Pre-release QA (Forseti)

`FORSETI.md` at the repo root is the standing pre-release QA doctrine: a fleet of black-box tester
agents, each a persona (operator, module author, on-call engineer, release engineer, …), runs an
objective-plus-oracle scenario catalog against purpose-built environments in parallel, and one lead
merges their findings into a deduplicated report with reproduction steps. Coverage is measured
against `requirements-matrix.json` directly: every `GIMLE-NNN` is reached by a fleet scenario,
classified internal with its unit-test or Holmgang citation, or excluded with a stated reason —
`forseti.json` holds that classification and the catalog, and
`python3 scripts/generate_forseti_docs.py` renders the generated sections and fails loudly on an
unplaced requirement. Adding a requirement therefore means placing it in `forseti.json` too. A run's
findings report is published, not committed; only its one-line index row lands in `FORSETI.md`.
