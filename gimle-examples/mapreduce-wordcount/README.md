# Distributed Word Count

A real, deployable map-reduce style word-count application hosted as genuine Gimlé modules. Like
`gimle-examples/orders-platform`, this is **not platform code** — it's a hand-built sample for
manually validating actual parallel distributed computation on a running Gimlé cluster, not a
synthetic fixture or a simulation of parallelism. It is deliberately **not** listed in the repo
root `pom.xml`'s modules list — see that file's own comment, and this directory's own `pom.xml`,
for why. Never add it there.

## What it is

Two real Gimlé modules implementing the two halves of map-reduce, each bundling its own literal
copy of the fabric contract it shares with the other (`MapReduceWorker.java`), the same
"structural contract, not a shared jar" convention `greeter-provider`/`greeter-consumer` already
establish:

- **`mapreduce-worker`** — the map phase. A `ModuleLifecycleHooks` implementation that registers a
  real `MapReduceWorker` fabric service on startup: `mapChunk(List<String> lines)` tokenizes and
  counts one chunk of text, returning a partial word-frequency map. `TIER_2`, a dedicated worker
  JVM, deployed with **three replicas** (`deployment.yaml`) — real, independent JVM processes any
  one of which might answer a given chunk, not a single instance pretending to be several.
- **`mapreduce-coordinator`** — the reduce phase, and the fan-out that drives the map phase. A
  `JobHooks` implementation (like `orders-platform`'s own `orders-report-job`, not a long-running
  `ModuleLifecycleHooks`): its single `run` generates a sizable synthetic text corpus, splits it
  into chunks, dispatches every chunk's `mapChunk` call to `mapreduce-worker` over the real fabric
  on its own virtual thread, then merges every partial result it gets back into one final
  word-frequency report.

## How the parallelism is real, not simulated

Two things make this genuine distributed computation rather than a single-process stand-in for
it:

1. **Three independent worker JVMs, not one.** `mapreduce-worker-deployment` runs three replicas
   (`TIER_2`, each its own dedicated worker JVM, its own crash domain, its own `-Xmx`/CPU ceiling)
   — a chunk that lands on replica 2 genuinely never touches replica 1's or 3's memory or CPU.
2. **A fresh `ModuleContext#lookupService` call per chunk, not one lookup reused for all of
   them.** `WordCountJobHooks#mapChunk` re-resolves `MapReduceWorker` immediately before every
   single chunk dispatch — the same "re-resolve every time" posture `GreeterConsumerHooks` already
   establishes (there, to never get stuck calling a redeployed provider's stale endpoint; here,
   it's also what actually lets the fabric's own load balancer spread `mapreduce.chunks` chunks
   across all three replicas instead of pinning every call to whichever one instance a single
   lookup happened to resolve). Every chunk's dispatch runs on its own virtual thread
   (`Executors.newVirtualThreadPerTaskExecutor()`), so all of them are genuinely in flight
   together, not serialized one after another.

Watch it happen across replicas: each `mapreduce-worker` instance logs
`mapped a 500-line chunk into N distinct words (chunk #M handled by this replica)` for every chunk
it personally handles — three separate instance logs (console Logs screen, or
`gimle-cli logs --follow`), each showing a distinct, disjoint subset of chunk numbers, is the
visible proof that the work really was split across independent JVMs.

## Config

`mapreduce-coordinator` reads two optional tenant/plain config keys via `ctx.config(...)`, both
with sane defaults if never delivered:

| Key | Default | Meaning |
| --- | --- | --- |
| `mapreduce.chunks` | `12` | Number of independent chunks the corpus is split into (and dispatched in parallel). |
| `mapreduce.linesPerChunk` | `500` | Lines of synthetic text per chunk. |

Twelve chunks across three replicas means each replica handles roughly four chunks per run —
raise `mapreduce.chunks` (or lower `linesPerChunk` alongside it, if just exercising the fan-out
matters more than the per-chunk data volume) to see more chunks queue up behind a fixed worker
pool, the same "more chunks than workers" work-distribution story a real map-reduce cluster faces
at scale, without checking a large data file into this repo: `SyntheticCorpus` generates a
deterministic (fixed-seed), Zipf-skewed vocabulary text on the fly, so every run's input — and
therefore its reduced result — is reproducible without a bundled asset.

## Building

This tree is not part of the root reactor, so build it explicitly, from this directory:

```sh
mvn -f gimle-examples/mapreduce-wordcount/pom.xml package
```

Prerequisite: `com.gimle:gimle-module` and `com.gimle:gimle-core` (at the exact version this
tree's own `pom.xml` pins, `gimle.platform.version`) must already be installed into your local
Maven repository — a real Gimlé build already does this:

```sh
mvn install -DskipTests   # from the repo root
```

Each module's `package` phase produces one jar (`mapreduce-worker.jar`,
`mapreduce-coordinator.jar`) — no shading needed here: unlike `orders-platform`, neither module
pulls in a third-party dependency tree, so each is a plain, real named JPMS module all the way
through, no classpath-fallback workaround required.

## Deploying

Against a running Gimlé cluster (see `gimle-console/LOCAL_DEV.md` in the repo root for how to
stand one up locally: store, control plane, one agent):

```sh
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/mapreduce-wordcount/mapreduce-worker/deployment.yaml
```

Wait for all three `mapreduce-worker-deployment` instances to reach `ACTIVE`, then run the
coordinator once on demand:

```sh
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/mapreduce-wordcount/mapreduce-coordinator/job.yaml
```

or deploy `cronjob.yaml` instead for a fresh run every 5 minutes. Deploy `job.yaml` **or**
`cronjob.yaml`, not both at once — they're alternate manifests for the same module, not two
different deployments meant to coexist.

Then watch it happen: the console's Logs screen, or `gimle-cli`'s own `logs --follow`, against each
`mapreduce-worker` instance and the `mapreduce-coordinator` job. You should see the three worker
replicas each log a handful of `mapped a 500-line chunk into N distinct words` lines with
disjoint chunk numbers, and the coordinator log a single final line:
`mapreduce-coordinator reduced 12 chunks (0 failed) into N distinct words, T total occurrences, in
Xms. Top 20 words: the=..., of=..., gimle=..., ...`

## What was, and wasn't, verified building this

This sandbox has no JDK 25 (the platform's own required release) and no running Gimlé cluster —
the same limitation `orders-platform`'s own README documents — so nothing here was verified by an
automated build in this environment. Every hooks/probe class, the fabric contract, and the
`ModuleContext`/`JobHooks`/`CompletionStatus` usage are written against the exact same real
interfaces `greeter-provider`, `greeter-consumer`, and `orders-platform`'s `orders-report-job`
already exercise end to end in `gimle-smoke-tests`, following those modules' own proven patterns
(bundled hooks/probes inside the module's own jar, `requires static` + explicit
`ModuleLayerFactory` readability grants, re-resolve-per-call fabric lookups, a bounded lookup
retry for a job racing a fresh cluster boot) rather than inventing a new one.
