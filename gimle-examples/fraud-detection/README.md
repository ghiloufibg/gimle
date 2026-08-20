# Fraud Detection

A real, deployable three-hop fraud-scoring pipeline hosted as genuine Gimlé modules. Like the
other standalone `gimle-examples/*` apps, this is **not platform code** — it's a hand-built sample
proving out one specific platform mechanism on a running Gimlé cluster: `gimle-fabric`'s own
**automatic, transparent circuit breaking / outlier ejection** against a genuinely misbehaving
replica. It is deliberately **not** listed in the repo root `pom.xml`'s modules list — see that
file's own comment, and this directory's own `pom.xml`, for why. Never add it there.

## Why this app exists

Every existing example in this repo is at most two hops (a consumer calling a provider). Nothing
exercises a real three-hop chain, and nothing exercises `gimle-fabric`'s own
`com.gimle.fabric.breaker.CircuitBreaker` — a real, already-implemented per-endpoint sliding-
window error-rate breaker that opens and excludes a failing endpoint from candidacy, entirely
transparently to the caller. This app is the first to deliberately trigger it.

## What it is

`transaction-ingest` → `fraud-scorer` → `alert-sink`, three real Gimlé modules (all `TIER_2`,
dedicated worker JVMs, so every hop is a genuine cross-worker fabric call), each bundling its own
literal copies of the interfaces/records it shares with its neighbors — the same "structural
contract, not a shared jar" convention `greeter-provider`/`greeter-consumer` already establish:

- **`transaction-ingest`** — generates a steady stream of synthetic transactions (a small, fixed
  pool of account/merchant ids, so the same account genuinely transacts more than once) and calls
  `fraud-scorer`'s `FraudScorer.score` for each one.
- **`fraud-scorer`** — a simple rule-based risk scorer: large amount + this replica's own
  per-account transaction count (a deliberately per-replica, not globally consistent, velocity
  check — documented, not hidden, as a simplification) push the score up. A `HIGH` verdict calls
  onward to `alert-sink`'s `AlertSink.alert`.
- **`alert-sink`** — receives alerts. **The same jar and `gimle-module.yaml` are deployed twice**:
  `deployment.yaml` (healthy, `alertsink.failureRate` at its default `0.0`) and
  `canary-deployment.yaml` (a second tenant, a deliberately high failure rate). Both export the
  identical `AlertSink` service/version, so `fraud-scorer`'s own
  `ctx.lookupService(AlertSink.class)` sees every instance from *both* deployments as candidates
  for the same call.

## How the circuit breaking is real, not simulated

`fraud-scorer` never implements retry-avoidance or health-tracking logic of its own for
`AlertSink` — it just retries a normal, bounded number of times against a *fresh*
`lookupService` call each attempt (`FraudScorerHooks#tryAlert`), the same "re-resolve every time"
posture every other example in this repo already establishes. What actually happens underneath,
with zero app code responsible for it: `gimle-fabric`'s own `FabricServiceRegistry` tracks a
`CircuitBreaker` per endpoint, and once the canary replica's own error rate (it fails
`alertsink.failureRate` of its calls, by construction) crosses threshold within its sliding
window, that replica's breaker opens and `FabricServiceRegistry` stops offering it as a candidate
— entirely transparently. Watch it happen across three logs:

1. **The canary's own log** (`alert-sink-canary-deployment`'s instance): a burst of
   `ALERT delivered` and `simulated alert delivery failure` lines, then — once its breaker opens —
   the line count against it should plateau while the healthy replicas' own counts keep climbing.
2. **The two healthy replicas' own logs** (`alert-sink-deployment`'s instances): their own
   `this replica has now handled N` counts should climb faster than the canary's, once the breaker
   opens and stops sending it new traffic.
3. **`fraud-scorer`'s own log**: `giving up on alerting transaction ... after 3 attempts` lines
   should become rare or disappear once the canary is excluded — earlier attempts against it were
   what generated those, and later attempts land on a healthy replica instead.

## Tenancy is required for `alert-sink`'s config

Both `alert-sink` deployments read `alertsink.failureRate` via `ctx.config(...)`, which
(`AgentMain#deliverConfig`) is never delivered to an untenanted instance — so both manifests
declare a `tenantId`, and both tenants must exist before applying them:

```sh
gimle set tenant fraud-detection --max-memory-bytes 268435456 --max-cpu-millicores 1000 --max-instances 5
gimle set config fraud-detection alertsink.failureRate 0

gimle set tenant fraud-detection-canary --max-memory-bytes 134217728 --max-cpu-millicores 500 --max-instances 3
gimle set config fraud-detection-canary alertsink.failureRate 0.9
```

`transaction-ingest` and `fraud-scorer` never read `ctx.config`, so they stay untenanted — the
same "only the module that actually needs config gets a tenant" posture `orders-platform`
establishes.

## Building

This tree is not part of the root reactor, so build it explicitly, from this directory:

```sh
mvn -f gimle-examples/fraud-detection/pom.xml package
```

Prerequisite: `com.gimle:gimle-module` must already be installed into your local Maven repository
at this tree's own pinned `gimle.platform.version`:

```sh
mvn install -DskipTests   # from the repo root
```

## Deploying

Against a running Gimlé cluster (see `gimle-console/LOCAL_DEV.md`), run both `gimle set tenant`
blocks above first, then:

```sh
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/fraud-detection/alert-sink/deployment.yaml
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/fraud-detection/alert-sink/canary-deployment.yaml
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/fraud-detection/fraud-scorer/deployment.yaml
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/fraud-detection/transaction-ingest/deployment.yaml
```

Deploy `alert-sink` (both manifests) and let them reach `ACTIVE` before `fraud-scorer`, and
`fraud-scorer` before `transaction-ingest` — the same "downstream first" ordering every
multi-module app in this repo follows.

## What was, and wasn't, verified building this

This sandbox has no JDK 25, no network access to fetch one, and no running Gimlé cluster — the
same limitation every other standalone example in this directory documents. Every hooks/probe
class here is stub-compiled against the real `ModuleContext`/`ModuleLifecycleHooks`/
`LivenessProbe`/`ReadinessProbe` signatures to catch type/syntax errors. The circuit breaker's own
behavior (windowSize, error-rate threshold, cooldown/backoff) is read straight from
`com.gimle.fabric.breaker.CircuitBreaker`'s own javadoc and implementation in this repo, not
guessed at -- this app's own "what to watch for" section above describes what that mechanism
produces when a real misbehaving replica exists, which is exactly what `alert-sink`'s canary
deployment is built to be.
