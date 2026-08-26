# Session Store

A real, deployable distributed session/key-value store hosted as genuine Gimlé modules. Like
`gimle-examples/orders-platform` and `gimle-examples/mapreduce-wordcount`, this is **not platform
code** — it's a hand-built sample for manually validating one specific platform mechanism on a
running Gimlé cluster: **persistent-volume durability across a real instance restart**. It is
deliberately **not** listed in the repo root `pom.xml`'s modules list — see that file's own
comment, and this directory's own `pom.xml`, for why. Never add it there.

## Why this app exists

No other example in this repo ever declares `volume:` in its own `gimle-module.yaml`, or reads
`ModuleContext#dataDirectory()` for anything beyond a synthetic smoke-test marker file. This app
is the first real one: a genuine key-value store whose entire dataset lives in an append-only log
under its allocated volume, replayed into memory on every start — kill the instance, redeploy the
module, whatever — and the data is still there afterward, because it was never only in memory.

## What it is

Two real Gimlé modules, each bundling its own literal copy of the fabric contract they share
(`SessionStore.java`), the same "structural contract, not a shared jar" convention
`greeter-provider`/`greeter-consumer` already establish:

- **`session-store-service`** — the store itself. `StatefulSet`-hosted, `TIER_2` (a dedicated
  worker JVM), declaring a real `volume: {sizeBytes, reclaimPolicy}` in its own `gimle-module.yaml`.
  `SessionStoreHooks#onStart` resolves `ModuleContext#dataDirectory()` (present only because the
  volume was declared), replays `sessions.log` from it into an in-memory index, and registers a
  real `SessionStore` fabric service (`put`/`get`/`delete`). Every mutation appends to the log
  *before* updating the index, so a crash between the two never leaves the index ahead of what's
  durably recorded.
- **`session-store-client`** — a background caller (the same pattern
  `GreeterConsumerHooks` establishes: never call inline from `onStart`, always from a background
  virtual thread with MDC captured across it). Every 5 seconds it writes one new session, and
  reads back a session it wrote several cycles earlier to confirm it's still there — the visible,
  logged proof that data survives.

## How to actually watch the durability proof happen

1. Deploy `session-store-service/statefulset.yaml`, wait for it to reach `ACTIVE`, then deploy
   `session-store-client/deployment.yaml`.
2. Watch `session-store-client`'s own log (console Logs screen, or `gimle-cli logs --follow`):
   every 5 seconds it logs `recalled session session-N after 5 more writes: user=demo-N;issuedAt=...`
   — proof the store still has data it was given several cycles ago.
3. **Now kill or redeploy `session-store-service`'s own instance** (e.g. `gimle cli` instance
   restart, or simply re-apply `statefulset.yaml`) while the client keeps running.
4. Watch `session-store-service`'s own log on the fresh instance: instead of
   `starting with an empty volume`, it now logs
   `recovered N session(s) from an existing volume at /data/sessions.log` — the log survived the
   restart because it lives on the StatefulSet's own persistent volume, not the instance's heap.
5. Watch `session-store-client` keep succeeding right through the restart (aside from the handful
   of calls that land exactly during the brief window the instance was down, logged as an ordinary
   transient `call to SessionStore failed` warning, the same as any other example's redeploy
   window) — sessions written *before* the restart are still recalled correctly *after* it.

That sequence — kill it, watch it come back with the same data — is the actual point of this app,
and the one thing no other example in this repo has ever demonstrated.

## Building

This tree is not part of the root reactor, so build it explicitly, from this directory:

```sh
mvn -f gimle-examples/session-store/pom.xml package
```

Prerequisite: `com.gimle:gimle-module` (at the exact version this tree's own `pom.xml` pins,
`gimle.platform.version`) must already be installed into your local Maven repository — a real
Gimlé build already does this:

```sh
mvn install -DskipTests   # from the repo root
```

Each module's `package` phase produces one plain jar (`session-store-service.jar`,
`session-store-client.jar`) — no shading needed, neither module pulls in a third-party
dependency.

## Deploying

Against a running Gimlé cluster (see `gimle-console/LOCAL_DEV.md` in the repo root for how to
stand one up locally: store, control plane, one agent):

```sh
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/session-store/session-store-service/statefulset.yaml
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/session-store/session-store-client/deployment.yaml
```

Neither module reads tenant config or secrets, so no `gimle set tenant`/`gimle secret set` step is
needed here — unlike `orders-platform`'s `web-ui` or `mapreduce-wordcount`'s coordinator.

## What was, and wasn't, verified building this

This sandbox has no JDK 25 (the platform's own required release) and no running Gimlé cluster —
the same limitation `orders-platform`'s and `mapreduce-wordcount`'s own READMEs document — so
nothing here was verified by an automated build in this environment. Every hooks/probe class is
written against the exact real `ModuleContext`/`ModuleLifecycleHooks`/`LivenessProbe`/
`ReadinessProbe` interfaces (stub-compiled against copies of their real signatures to catch
type/syntax errors), and the `volume:`/`dataDirectory()` usage matches the one place in this repo
that already proves the mechanism works end to end: `gimle-smoke-tests`'
`GreeterSmokeClusterSupport#buildStatefulModuleJar`, whose own marker-file test this app's real
append-only log is a fuller version of.
