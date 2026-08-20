# Distributed Word Count

A real, deployable map-reduce style word-count application hosted as genuine Gimlé modules, sized
and hardened to look like a genuine enterprise batch-processing workload rather than a toy demo.
Like `gimle-examples/orders-platform`, this is **not platform code** — it's a hand-built sample for
manually validating actual parallel distributed computation on a running Gimlé cluster under real
load, not a synthetic fixture or a simulation of parallelism. It is deliberately **not** listed in
the repo root `pom.xml`'s modules list — see that file's own comment, and this directory's own
`pom.xml`, for why. Never add it there.

## What it is

Two real Gimlé modules implementing the two halves of map-reduce, each bundling its own literal
copy of the fabric contract it shares with the other (`MapReduceWorker.java`), the same
"structural contract, not a shared jar" convention `greeter-provider`/`greeter-consumer` already
establish:

- **`mapreduce-worker`** — the map phase. A `ModuleLifecycleHooks` implementation that registers a
  real `MapReduceWorker` fabric service on startup: `mapChunk(List<String> lines)` tokenizes and
  counts one chunk of text, returning a partial word-frequency map. `TIER_2`, a dedicated worker
  JVM, deployed with **six replicas** by default (`deployment.yaml`) — real, independent JVM
  processes any one of which might answer a given chunk, not a single instance pretending to be
  several.
- **`mapreduce-coordinator`** — the reduce phase, and the fan-out that drives the map phase. A
  `JobHooks` implementation (like `orders-platform`'s own `orders-report-job`, not a long-running
  `ModuleLifecycleHooks`): its single `run` generates a large synthetic text corpus (400,000 lines
  by default, roughly 4 million word occurrences — see [Config](#config) to push it further),
  splits it into chunks, dispatches every chunk's `mapChunk` call to `mapreduce-worker` over the
  real fabric on its own virtual thread (bounded to a configurable number in flight at once), then
  merges every partial result it gets back into one final word-frequency report — tolerating a
  bounded fraction of chunk failures rather than aborting the whole run over a handful of
  stragglers.

## How the parallelism is real, not simulated

Two things make this genuine distributed computation rather than a single-process stand-in for
it:

1. **Six independent worker JVMs, not one.** `mapreduce-worker-deployment` runs six replicas
   (`TIER_2`, each its own dedicated worker JVM, its own crash domain, its own `-Xmx`/CPU ceiling)
   — a chunk that lands on replica 4 genuinely never touches replica 1's, 2's, 3's, 5's, or 6's
   memory or CPU.
2. **A fresh `ModuleContext#lookupService` call per chunk, not one lookup reused for all of
   them.** `WordCountJobHooks#mapChunk` re-resolves `MapReduceWorker` immediately before every
   single chunk dispatch — the same "re-resolve every time" posture `GreeterConsumerHooks` already
   establishes (there, to never get stuck calling a redeployed provider's stale endpoint; here,
   it's also what actually lets the fabric's own load balancer spread `mapreduce.chunks` chunks
   across all six replicas instead of pinning every call to whichever one instance a single lookup
   happened to resolve). Every chunk's dispatch runs on its own virtual thread
   (`Executors.newVirtualThreadPerTaskExecutor()`), so hundreds of them are genuinely in flight
   together, not serialized one after another — bounded by a `Semaphore` (see
   [Backpressure and partial-failure tolerance](#backpressure-and-partial-failure-tolerance) below)
   rather than left unbounded.

Watch it happen across replicas: each `mapreduce-worker` instance logs
`mapped a 2000-line chunk into N distinct words (chunk #M handled by this replica)` for every chunk
it personally handles — six separate instance logs (console Logs screen, or
`gimle-cli logs --follow`), each showing a distinct, disjoint subset of chunk numbers, is the
visible proof that the work really was split across independent JVMs.

## Backpressure and partial-failure tolerance

Two things distinguish this from a "just fan everything out and hope" demo, the same two
production concerns a real batch-processing pipeline never skips:

- **Bounded concurrency.** At a few hundred chunks, submitting every `mapChunk` call at once would
  open that many simultaneous fabric round trips against a fixed handful of worker replicas —
  cheap for the virtual threads themselves, but not the kind of unbounded fan-out a real caller
  sends at a downstream service it doesn't own. A `Semaphore` caps how many chunks are genuinely
  in flight at once (`mapreduce.maxInFlight`, default `32`), the same "a caller controls its own
  concurrency" discipline a production pipeline applies against any shared backend.
- **Partial-failure tolerance.** Not every chunk has to succeed for the whole run to: a replica
  that crashed mid-run, or a lookup that outlasted its retries, excludes just that one chunk from
  the reduced result (see `WordCountJobHooks#mapChunk`'s own bounded retry) rather than failing
  the entire batch. The job only fails outright once the fraction of failed chunks exceeds
  `mapreduce.maxFailureRatio` (default `0.05`, i.e. 5%) — a run with a few stragglers still
  succeeds, logged as a **degraded** result, the same tolerance a real large-scale batch job takes
  as opposed to "any single failure aborts everything."

## Config

`mapreduce-coordinator` reads four optional tenant config keys via `ctx.config(...)`, all with sane
defaults if never delivered — but see [Tenancy is required](#tenancy-is-required-for-config)
below: none of them are ever delivered at all unless the manifest declares a `tenantId`.

| Key | Default | Meaning |
| --- | --- | --- |
| `mapreduce.chunks` | `200` | Number of independent chunks the corpus is split into. |
| `mapreduce.linesPerChunk` | `2000` | Lines of synthetic text per chunk (200 x 2000 = 400,000 lines by default). |
| `mapreduce.maxInFlight` | `32` | Maximum number of chunk dispatches genuinely in flight at once. |
| `mapreduce.maxFailureRatio` | `0.05` | Fraction of chunks allowed to fail before the whole job is marked `FAILED` (see above). |

`SyntheticCorpus` generates a deterministic (fixed-seed), Zipf-skewed vocabulary text on the fly —
no large data file checked into this repo, yet every run's input, and therefore its reduced
result, is reproducible.

### Tenancy is required for config

`AgentMain#deliverConfig` returns immediately, no config delivered at all, for an *untenanted*
instance — so `mapreduce-coordinator`'s own manifests declare `tenantId: mapreduce-wordcount`, and
that tenant (plus whichever config keys you want to override) must exist **before** applying
`job.yaml`/`cronjob.yaml` — the same "push before apply" ordering `orders-platform`'s own `web-ui`
needs for its own tenant-scoped secret:

```sh
gimle set tenant mapreduce-wordcount --max-memory-bytes 1073741824 --max-cpu-millicores 4000 --max-instances 10
gimle set config mapreduce-wordcount mapreduce.chunks 200
gimle set config mapreduce-wordcount mapreduce.linesPerChunk 2000
gimle set config mapreduce-wordcount mapreduce.maxInFlight 32
gimle set config mapreduce-wordcount mapreduce.maxFailureRatio 0.05
```

Applying `job.yaml`/`cronjob.yaml` against a `tenantId` nothing has registered yet is rejected
outright at admission by `TenantQuotaPlugin`, the same way an unpushed artifact coordinate is.
`mapreduce-worker` itself stays untenanted (it never reads `ctx.config`), the same "only the
module that actually needs config gets a tenant" posture `orders-platform` establishes.

## Scaling it up further

Every knob above is deliberately overridable, so pushing this to a genuinely large run needs no
code change — only more tenant config, more replicas, and (if your cluster spans more than one
node) more machines for the scheduler's own anti-affinity to spread those replicas across:

```sh
gimle set config mapreduce-wordcount mapreduce.chunks 2000
gimle set config mapreduce-wordcount mapreduce.linesPerChunk 5000
gimle set config mapreduce-wordcount mapreduce.maxInFlight 128
```

2,000 chunks x 5,000 lines is 10,000,000 lines (tens of millions of word occurrences) split across
however many `mapreduce-worker` replicas you scale `mapreduce-worker-deployment` to — raise
`replicas:` in `deployment.yaml` (or `gimle scale mapreduce-worker-deployment <n>` against a
running one) well past six for a real many-node cluster; the fan-out logic and the per-replica
resource ceiling in `gimle-module.yaml` don't change, only how many independent JVMs are racing to
answer chunks. `mapreduce.maxInFlight` is the one knob worth raising alongside a bigger worker
pool — a fixed 32 in-flight chunks against fifty replicas would starve most of them.

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

Wait for all six `mapreduce-worker-deployment` instances to reach `ACTIVE`, register the
`mapreduce-wordcount` tenant and its config (see [Tenancy is required](#tenancy-is-required-for-config)
above), then run the coordinator once on demand:

```sh
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/mapreduce-wordcount/mapreduce-coordinator/job.yaml
```

or deploy `cronjob.yaml` instead for a fresh run every 5 minutes. Deploy `job.yaml` **or**
`cronjob.yaml`, not both at once — they're alternate manifests for the same module, not two
different deployments meant to coexist.

Then watch it happen: the console's Logs screen, or `gimle-cli`'s own `logs --follow`, against each
`mapreduce-worker` instance and the `mapreduce-coordinator` job. You should see all six worker
replicas each log a few dozen `mapped a 2000-line chunk into N distinct words` lines with disjoint
chunk numbers, and the coordinator log a single final line:

```
mapreduce-coordinator reduced 200 chunks (0 failed) into N distinct words, T total occurrences
from 400000 lines, in Xms (L lines/s, W words/s). Top 20 words: the=..., of=..., gimle=..., ...
```

or, if a replica dropped out mid-run but stayed within tolerance:

```
mapreduce-coordinator succeeded with a degraded result: 6 of 200 chunks failed (3%), within the
configured tolerance of 5%
```

## What was, and wasn't, verified building this

This sandbox has no JDK 25 (the platform's own required release) and no running Gimlé cluster —
the same limitation `orders-platform`'s own README documents, and downloading a JDK 25 build was
attempted and blocked by this environment's own egress policy (a `403` at the proxy layer, not a
configuration problem — see this session's own proxy diagnostics) — so nothing here was verified
by an automated build in this environment. Every hooks/probe class, the fabric contract, and the
`ModuleContext`/`JobHooks`/`CompletionStatus` usage were instead compiled against hand-written
stub copies of those exact real interfaces (matching `gimle-module`'s own signatures line for
line) to catch type errors, and are written against the exact same real interfaces
`greeter-provider`, `greeter-consumer`, and `orders-platform`'s `orders-report-job` already
exercise end to end in `gimle-smoke-tests`, following those modules' own proven patterns (bundled
hooks/probes inside the module's own jar, `requires static` + explicit `ModuleLayerFactory`
readability grants, re-resolve-per-call fabric lookups, a bounded lookup retry for a job racing a
fresh cluster boot) rather than inventing new ones.
