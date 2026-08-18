# Gimle Worker

The worker JVM runtime: the process that actually hosts module instances. `WorkerMain` is spawned
by `gimle-agent` (one worker JVM per instance, matching the scheduler's anti-affinity assumption),
loads and drives modules through `gimle-module`'s lifecycle state machine inside `ModuleLayer`s,
probes them, schedules their work on bounded virtual-thread pools, exposes them to callers over the
service fabric, and reports health/metrics back to its own agent. It is disposable by design: an
agent kills and respawns a worker JVM wholesale rather than trying to recover it in place.

## Process role

`WorkerMain.main(nodeId, tenantId-or-empty, controlSocketPath)` connects *out* to the agent's
control socket (the worker is transient and just-spawned, the agent long-lived and already
listening — connecting outward sidesteps that startup-order race rather than adding coordination
for it) and then treats every module operation, including the very first module this worker ever
hosts, as arriving over that channel. There is deliberately no separate "initial load" path — a
freshly-started worker and one mid-redeploy look identical from `WorkerMain`'s point of view.

Beyond module hosting, `WorkerMain` also:

- Binds a `FabricServer` — one Unix-domain-socket listener for same-machine callers, one TCP
  listener for cross-machine callers — and wraps the worker's `SimpleServiceRegistry` in a
  `FabricServiceRegistry`, so services this worker hosts become reachable from other workers on the
  same machine and other nodes in the cluster, not just from other modules in this same JVM.
- Periodically snapshots its own Micrometer registry (`MeterSnapshotCodec`) and relays every
  exported OpenTelemetry span batch (`RelayingSpanExporter`/`SpanLineCodec`) to the agent over the
  same control channel `ControlMessage.MetricsReport` already uses — a worker has no outbound
  network identity of its own, so it can't ship to Muninn directly the way its agent can.
- Runs a `FabricServerTlsWatcher` in TLS mode, polling its agent-managed certificate file's mtime
  and reloading `FabricServer`'s TLS material when the agent rotates it in place — a worker has no
  `gimle-pki` dependency and no channel for the agent to push that notification, so polling is
  deliberate here rather than a placeholder for a push mechanism that doesn't exist.

## Isolation tiers hosted here

This runtime hosts **Tier 1** (module in this shared worker JVM — millisecond deploys, classloader
isolation, soft JFR-based accounting) and, by simply being one of possibly several worker JVMs an
agent spawns for a module's replicas, backs **Tier 2** (module in its own dedicated worker JVM —
`gimle-agent`'s scheduling/spawning decision, not something `gimle-worker` itself distinguishes at
runtime). Tier 3 (Linux namespace isolation via FFM `unshare`/`setns`) is not implemented anywhere
in this codebase and is rejected outright — this module has no code path for it at all.

## Key types

| Type | Responsibility |
|---|---|
| `WorkerRuntime` | Event-driven glue between `ModuleController`'s lifecycle events and this worker's concerns: creates/disposes each module's `BoundedModuleScheduler` and probes in lockstep with lifecycle transitions, escalates repeated liveness failures to a module restart, and owns service-registry teardown timing on stop/uninstall. |
| `BoundedModuleScheduler` | A per-module, bounded virtual-thread scheduler — submitted tasks queue behind a `Semaphore` rather than running unbounded, while still spawning a fresh (cheap) virtual thread per task. Captures the caller's OpenTelemetry `Context` at submit time and restores it on the task's own virtual thread, since `Context` is thread-scoped and a new virtual thread otherwise starts with none. Every thread is named `gimle-<module>-<version>-N`, the naming convention `ThreadNameJfrAttributor` (in `gimle-observability`) keys its JFR-to-module attribution off. |
| `ProbeLoop` | Periodically invokes a bounded liveness/readiness check on the owning module's own `BoundedModuleScheduler` (so a hung probe consumes that module's concurrency budget, never a shared one) with a hard timeout; a timeout or thrown exception counts as a failed check. |
| `InstanceIdentityRegistry` / `InstanceIdentity` | Maps a hosted module's `ModuleId` to the instance identity the agent reported for it at install time, consulted for MDC log tagging in the probe loop and request dispatch. |
| `InstanceTaggingServiceRegistry` | Wraps the worker's `ServiceRegistry`, tagging each registered service with an MDC dynamic proxy at registration time — the single choke point covering both same-worker direct calls and `FabricServer`'s local-invoke path, since both ultimately call through the same registered reference. |
| `ControlChannelClient` | Worker-side half of the newline-delimited control channel to the agent; connects with retry. |
| `ControlPlaneRelay` | Turns a hosted module's synchronous `ModuleContext#relayControlPlaneRead` call into a real request/response round trip over that channel, since only `WorkerMain`'s own receive loop reads it. |
| `RelayingSpanExporter` | Ships exported span batches to the agent instead of to Muninn directly (see "Process role" above). |
| `FabricServerTlsWatcher` | Polls for agent-rotated TLS material and reloads `FabricServer`'s certificate in place. |

## A documented platform-layer stopgap

`ModuleLayerFactory` (in `gimle-module`, exercised here) grants a hosted module's own `ModuleLayer`
readability to the platform's own unnamed-module classes via `requires static` plus explicit
`Module.addReads`, so a module's bundled `ModuleLifecycleHooks`/`LivenessProbe`/`ReadinessProbe`
implementation can resolve those interfaces at all. This is a deliberate, documented stopgap for
`gimle-api` not existing as its own module yet — not a parallel path meant to be migrated away from
independently of that.

## How other modules use this one

`gimle-agent` depends on `gimle-worker` only in test scope — it spawns `WorkerMain` as a child OS
process via the `Process` API rather than embedding this runtime, and drives it purely through the
control-channel wire protocol (`gimle-core`'s `ControlMessage`). `gimle-examples`'s greeter modules
and `gimle-smoke-tests` exercise this runtime end to end as the thing that actually loads and runs
deployed module jars.
