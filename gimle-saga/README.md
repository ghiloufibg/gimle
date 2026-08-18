# Gimle Saga

Saga (`SagaMain`) is a standalone test-run report server: a local development tool that ingests
`SagaEvent` NDJSON streams from test runs anywhere in the reactor, stores each run durably, derives a
cross-run flaky-test scoreboard, and serves the whole record — runs, live event tails, the flake
scoreboard, per-test history — over a small JSON HTTP API plus a bundled web console at `/console`
(`gimle-saga-console`). It is deliberately unauthenticated, TLS-free, and loopback-bound by default;
it is never one of a deployed Gimlé cluster's own process kinds (unlike `gimle-mimir`/`gimle-fafnir`/
`gimle-muninn`/`gimle-andvari`/`gimle-skald`), and Skald/Bifrost/DNS-style service discovery has
nothing to do with it.

This is not the same thing as `gimle-holmgang`'s own internal `com.gimle.holmgang.saga` package
(`SagaCollector`/`SagaCucumberPlugin`/`SagaJUnitListener`/`SagaWriter`), which writes Holmgang's own
per-run `holmgang-report.json`/`holmgang-report.html` regardless of whether this server is running at
all. The two are related, not identical: Holmgang's `SagaShipper` will *additionally* ship its
Gherkin/Fenrir/Surtr/topology results here as attachment events when `-Dgimle.saga.endpoint` points
at a running Saga server, and `gimle-core`'s own `SagaTestListener` (a JUnit `TestExecutionListener`,
active reactor-wide whenever the same property is set) streams any test module's ordinary per-test
results here live as they run. Both integrations are best-effort and dormant unless explicitly
configured — a Saga server being down or absent never fails a test run.

## HTTP API

| Method & path | Purpose |
|---|---|
| `GET /api/health` | Liveness check; `{"status":"ok"}`. |
| `POST /api/ingest` | Ingests one or more NDJSON `SagaEvent` lines (a run-started event opens a run; a run-started for a run ID that's already known replaces that run's files and ledger lines rather than appending, making re-shipping a whole run after a partial upload safe). |
| `POST /api/import` | Folds in results after the fact from a Surefire XML report body, or `{"paths":[...]}` pointing at Surefire XML files on disk. With `?runId=`, appends/folds into that already-open run (`store.fold`) instead of opening a new one — how `SagaShipper`'s attachment-only ingest joins the same run a live `SagaTestListener` stream is writing into. |
| `POST /api/shutdown` | Acknowledges with `{"status":"stopping"}`, then stops the server — what `gimle:saga-stop` calls before falling back to signalling the recorded pid. |
| `GET /api/runs?limit=N` | Lists runs, newest first. |
| `GET /api/runs/{runId}` | One run's metadata (`RunMeta`). |
| `GET /api/runs/{runId}/events?cursor=N` | A page of that run's raw NDJSON event lines plus the cursor to resume from. |
| `GET /api/runs/{runId}/events?cursor=N&follow=true` | Chunked NDJSON live tail — polls the store the same way `AgentLogServer` tails a log file, ending when the client disconnects or once the run has reached a terminal status and every buffered line has been delivered. |
| `GET /api/flaky?window=DAYS` | The flaky-test scoreboard aggregated over the last `window` days (default 30): score, flake rate, occurrences, failure signatures, quarantine state, and the configured flake-budget allowance. |
| `GET /api/tests/{testId}/history` | One test's outcome/duration/attempt history across every run it appeared in. |
| `GET /console` | The bundled `gimle-saga-console` SPA, when one is present on the classpath. |

Ingest/import request bodies are capped at 50 MiB (`SizeLimitedInputStream`) — the most
attacker/mistake-facing surfaces this server exposes, even though nothing about it is otherwise
hardened.

## Storage (`SagaStore`)

Flat-file, under a configurable data root (`~/.gimle/saga` by default):

- `runs/{runId}/events.ndjson` — the append-only, authoritative record of everything about a run.
  Appends to one run are serialized by a per-run lock; a torn trailing line from a crash mid-append
  is truncated away before the next append and skipped on every read.
- `runs/{runId}/meta.json` — a derived, atomically-rewritten summary of that run.
- `index/flake-ledger.ndjson` — one line per flake observation across every run, appended when a run
  finishes; fully reconstructable from the run directories via `rebuildLedger()`.
- `index/test-tags.ndjson` — current-state (not historical) index of each test ID's most recently
  observed JUnit tags, overwritten rather than appended to on each new observation.

A run still marked `LIVE` when the store starts up (the process crashed or was killed mid-run) is
marked abandoned at construction time rather than left permanently open.

## Flake scoring and quarantine

`flakyScoreboard(sinceEpochMilli)` aggregates ledger observations within the window: `flakeRate` is
occurrences over runs-seen (the runs in the window that exercised the test at all, not every run),
and `score` is `flakeRate * runsSeen` — occurrence count, effectively, but shaped so a test seen only
once or twice can't outrank one flaking steadily across a wider run history. `quarantined(testId)`
answers `true` iff that test's most recently observed tag set includes `"flaky"` — the marker a test
author adds via `@Tag("flaky")` once it's a known offender, not something Saga assigns on its own.

## Running it

Configured entirely through system properties, not the flag-parsing style the cluster process kinds
use — this is a tool started next to a build, not a supervised cluster process:

- `-Dgimle.saga.port` — HTTP port (default `9096`).
- `-Dgimle.saga.dataRoot` — storage root (default `~/.gimle/saga`).
- `-Dgimle.saga.host` — bind address (default: loopback only).
- `-Dgimle.saga.flakeBudgetAllowance` — the budget number surfaced in `/api/flaky` (default `120`).

## Module layout

- `com.gimle.saga.SagaMain` — entry point.
- `com.gimle.saga.SagaServer` — the HTTP surface (`com.sun.net.httpserver.HttpServer`, one virtual
  thread per request).
- `com.gimle.saga.SagaStore` — the file-backed store described above.
- `com.gimle.saga.SurefireXmlImporter` — Surefire XML → `SagaEvent` folding for `/api/import`.
- `com.gimle.saga.RunMeta`, `com.gimle.saga.FlakeObservation` — derived record types.

Depends only on `gimle-core` (for `SagaEvent`/`SagaEventCodec`, shared with `SagaTestListener` and
Holmgang's `SagaShipper`) and SLF4J; `gimle-saga-console`'s built SPA is a runtime-only dependency,
bundled into this module's own classpath the same way `gimle-andvari`/`gimle-fafnir` bundle their own
consoles.
