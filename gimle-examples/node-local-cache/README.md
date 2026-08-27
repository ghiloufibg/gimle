# Node-Local Cache

A real, deployable node-local feature-flag cache hosted as genuine Gimlé modules. Like
`gimle-examples/orders-platform`/`mapreduce-wordcount`/`session-store`, this is **not platform
code** — it's a hand-built sample proving out one specific platform mechanism on a running Gimlé
cluster: **same-machine locality-preferred fabric routing**. It is deliberately **not** listed in
the repo root `pom.xml`'s modules list — see that file's own comment, and this directory's own
`pom.xml`, for why. Never add it there.

## Why this app exists

The only existing `DaemonSet` manifest in this repo (`orders-platform`'s
`inventory-service/daemonset.yaml`) is just an alternate manifest for a service that doesn't care
which node it lands on — nothing in this repo demonstrates the actual reason a real system runs a
`DaemonSet` at all: a node-local agent or cache a caller *wants* to keep hitting on its own
machine, per `CLAUDE.md`'s own service-fabric section ("same-worker → same-machine → remote
(least-outstanding-requests)"). This app is the first one that proves that preference is real and
observable, not just documented.

## What it is

Two real Gimlé modules, each bundling its own literal copies of the fabric contract they share
(`FeatureFlagCache.java`, `FlagAnswer.java`), the same "structural contract, not a shared jar"
convention `greeter-provider`/`greeter-consumer` already establish:

- **`local-flag-cache`** — a small, fixed feature-flag vocabulary seeded once at startup, deployed
  as a **`DaemonSet`** (`TIER_2`): the platform guarantees exactly one instance per node. Each
  instance generates its own random `replicaId` once at startup and returns it alongside every
  flag value in a `FlagAnswer` — the mechanism, not the point: the point is what a caller does
  with it.
- **`flag-consumer`** — a background caller (the same pattern `GreeterConsumerHooks` establishes),
  re-resolving `FeatureFlagCache` fresh on every call. It tracks the `replicaId` the *previous*
  call's answer carried and compares it against the current one: the same id on every call (logged
  at `INFO`) is the observable proof this consumer keeps landing on its own node's co-located
  cache replica; a *different* id (logged at `WARN`, not hidden) means this particular call
  crossed to another node's — expected to be rare on a healthy cluster, and honestly reported
  rather than asserted away when it does happen (e.g. right as `local-flag-cache` rolls to a new
  version on this consumer's own node).

## Why `DaemonSet` is what makes this provable

A `Deployment` gives no placement guarantee at all — `flag-consumer` could easily land on a node
with zero `FeatureFlagCache` replicas, forcing every call remote regardless of locality
preference. A `DaemonSet` closes that gap by construction: every node has exactly one
`local-flag-cache` instance, so `flag-consumer`'s own node is *always* a same-machine candidate,
and `gimle-fabric`'s own locality-first selection (same-worker → same-machine → remote) has a real
choice to make in the caller's favor on every single call.

## Deploying

Against a running Gimlé cluster — ideally a real multi-node one, since a single-node local dev
cluster (`gimle-console/LOCAL_DEV.md`) only ever has one `local-flag-cache` replica to land on,
making the locality claim trivially true rather than genuinely tested:

```sh
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/node-local-cache/local-flag-cache/daemonset.yaml
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/node-local-cache/flag-consumer/deployment.yaml
```

Wait for `local-flag-cache-daemonset` to reach `ACTIVE` on every node before deploying
`flag-consumer-deployment` — the same ordering every multi-module app in this repo follows.

Watch each `flag-consumer` replica's own log (console Logs screen, or `gimle-cli logs --follow`):
steady `dark-mode=true (answered by replica ..., same as last call)` lines, one stable replica id
per consumer instance for as long as it runs. On a multi-node cluster, different `flag-consumer`
replicas should show *different* stable replica ids from each other — proof each one settled on
its own node's cache, not that they all happened to converge on one.

Expect an `INFO no FeatureFlagCache reachable yet on this consumer's first call` line right after
`flag-consumer` itself reaches `ACTIVE`, even though `local-flag-cache-daemonset` was already fully
`ACTIVE` before it was deployed. That's not a race in the deploy ordering above — it's this
consumer's own node still catching its local membership view up to `local-flag-cache`'s export,
which is a separate event from that instance reaching `ACTIVE`. It resolves itself on the very next
call, five seconds later, with no operator action needed; only a failed call *after* one has already
succeeded is logged at `WARN`, since that would be a genuine regression rather than routine startup.



## Building

This tree is not part of the root reactor, so build it explicitly, from this directory:

```sh
mvn -f gimle-examples/node-local-cache/pom.xml package
```

Prerequisite: `com.gimle:gimle-module` (at the exact version this tree's own `pom.xml` pins,
`gimle.platform.version`) must already be installed into your local Maven repository:

```sh
mvn install -DskipTests   # from the repo root
```

Neither module reads tenant config or secrets, so no `gimle set tenant` step is needed here.

## What was, and wasn't, verified building this

This sandbox has no JDK 25 (the platform's own required release), no network access to fetch one,
and no running multi-node Gimlé cluster — the same limitation every other standalone example in
this directory documents. Every hooks/probe class here is stub-compiled against the real
`ModuleContext`/`ModuleLifecycleHooks`/`LivenessProbe`/`ReadinessProbe` signatures to catch
type/syntax errors, following the exact same fabric lookup/register patterns
`greeter-provider`/`greeter-consumer` already prove out end to end in `gimle-smoke-tests`. The
locality claim itself — that `flag-consumer` really does keep landing on its own node's replica —
can only be genuinely observed on a real multi-node cluster; this README's own "what to watch for"
section above is what that observation looks like when it happens.
