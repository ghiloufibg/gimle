---
sidebar_position: 6
---

import ZoomableDiagram from '@site/src/components/ZoomableDiagram';

# Observability

`gimle-observability` gives every module instance tagged metrics, propagated tracing, and
JFR-backed per-module resource accounting — the last of which is what makes Tier 1's *soft*
resource accounting (see [Tiered isolation](./tiered-isolation.md)) actually observable, not just
assumed.

Everything ends up in one place, [Muninn](./node-topology.md#muninn), by two different paths — a
worker JVM relays through its node agent (it has no outbound network identity of its own), while
every other process kind ships directly — and comes back out through the same read API the console
and CLI both use (source: `diagrams/observability-shipping-pipeline.d2`):

<ZoomableDiagram
  src="/diagrams/observability-shipping-pipeline.svg"
  alt="A worker JVM relays its metrics and traces through its node agent to Muninn; the control plane, Fafnir, gimle-mimir, and Andvari each ship their own directly; an operator reads all of it back through the control plane's proxied metrics-history/traces-history/logs API"
  width={760}
/>

## Metrics: `WorkerMetrics`

One Micrometer `MeterRegistry` per worker JVM, with every meter tagged by module name and version
(`Tags.of("module", ..., "version", ...)`) so per-module dashboards are a query away, not a
separate registry per module. Tracks request rate/latency/error counts
(`gimle.module.request.latency`/`.count`/`.errors`), thread counts, and metaspace footprint per
module. Defaults to an in-memory `SimpleMeterRegistry` — the counters exist and are queryable
today. `FabricServer` records every real inbound call's latency/outcome here, not just synthetic
test traffic. Separately, each worker JVM self-reports its own process CPU load and heap usage
(portable `java.lang.management`, no cgroups) to its agent every few seconds, which is what feeds
`AutoscaleReconciler`'s CPU-utilization math with real, non-zero data. That same periodic report
also diffs `WorkerMetrics`' cumulative request/error counters against the previous tick to compute
real request-rate/error-rate figures (not just CPU/memory), plus each module's current
`BoundedModuleScheduler` queue depth — all three travel through `ControlMessage.MetricsReport` to
the node agent and on into `InstanceObservation`, the same heartbeat pipeline CPU/memory usage
already rides. Each observation also carries the declared `isolationTier` and `resources.limit` the
instance was admitted under, read straight off the module descriptor the agent already holds, so a
reader has a ceiling to judge the usage figures against rather than a bare number. Both are absent
for a vessel instance, which is an OS process with no module descriptor behind it. Read the limit
against the tier: at `TIER_2` the instance owns its worker JVM, so the declared limit is its own
enforced `-Xmx` and a used/limit ratio is correct; at `TIER_1` several instances share a worker JVM
sized from the node's shared-worker budget, and the declared limit is the share that instance was
admitted for rather than a bound applied to it — see [Node sizing and worker
density](../reference/node-sizing.md). The control plane exposes a `GET /metrics` per-deployment rollup (owning tenant, average request
rate, average error rate, instance count) built from that same observation data, and the console's
own Instances/Metrics screens surface both figures per instance and per deployment (an error-rate
column on the Instances table, a total-error-rate stat tile and a ranked "instances with errors"
panel on the Metrics screen derived per instance, plus a "per-deployment rollup" panel reading
`GET /metrics` itself — see [Web console](./web-console.md#per-deployment-metrics-rollup)).
Every row names its own tenant, so two tenants running a same-named deployment produce two rows
that are told apart rather than merged; `gimle metrics` reads that same rollup from a terminal —
see the [CLI reference](../reference/cli-reference.md). `WorkerMetrics`' own request-latency `Timer` is built with
`publishPercentiles(0.5, 0.95, 0.99)` too, for parity with the three process-tier registries below.

The same registry also carries the fabric's own circuit-breaker state, which used to be invisible
outside `CircuitBreaker`'s internals: `gimle.fabric.circuitbreaker.state` is a gauge per
(callee interface, `nodeId/workerId` endpoint) holding `0` for CLOSED, `1` for HALF_OPEN and `2` for
OPEN — ordered by severity, so a max over endpoints answers "is anything ejected right now" — and
`gimle.fabric.circuitbreaker.transitions` counts every transition, additionally tagged by the state
entered, so a breaker that opens and closes repeatedly between two snapshots still shows up. Both
ride the existing `MeterSnapshotCodec` → agent → Muninn shipping path, which makes them queryable
through `GET /metrics-history/*` with no new control-plane wire fields. Every transition is logged
too, by `FabricServiceRegistry` — `WARN` on open, `INFO` on half-open and close — naming the
interface and the endpoint. Before this, an endpoint whose breaker had tripped simply stopped being
selected, and an operator asking "why is traffic not reaching instance X" had no way to tell that
apart from the catalog never having learned about X or the instance never having become ready.

`WorkerMetrics#evict(ModuleInstanceId)` removes one instance's entire meter set (request/error
counters, the latency timer, and the thread-count/metaspace gauges) once that instance is
uninstalled — never
on a mere stop, since a stopped-but-installed module can restart and resume its counters. Without
this, a worker that redeploys the same module name across many versions over its lifetime would
accumulate one permanent meter set per historical `(module, version, instance)` triple forever;
`WorkerMain`'s `UninstallModule` handler calls it only after a successful uninstall.

`gimle-controlplane`, `gimle-fafnir`, `gimle-mimir`, and `gimle-andvari` each carry their own
analogous per-process `MeterRegistry` (`ApiServerMetrics`/`FafnirMetrics`/`StoreMetrics`/
`AndvariMetrics` — request/RPC count, latency, error count, tagged by endpoint+verb or RPC kind),
and ship it to Muninn (see
[Node topology](./node-topology.md#muninn)) via a periodic `MuninnShipper` when a Muninn endpoint
is configured, readable back through `GET /metrics-history/{processKind}/{processId}`. Each of
these registries' own request-latency `Timer` is built with `publishPercentiles(0.5, 0.95, 0.99)`,
and `MeterSnapshotCodec` (a pure, no-I/O NDJSON serializer extracted out of `MuninnShipper` itself)
special-cases any `Timer` meter to call its `HistogramSnapshot#percentileValues()` and ship the
result as a `"percentiles"` map alongside the existing `"measurements"` map (`{"0.5": ..., "0.95":
..., "0.99": ...}`, in seconds) — readable back through the same `/metrics-history/*` route
unchanged, since `MuninnDayFileStore` stores each shipped line as opaque JSON. A `Timer` that was
never built with `publishPercentiles(...)` ships exactly as before (no `"percentiles"` key at all).

### Certificate rotation health

Every process that renews its own leaf certificate — the node agent plus `gimle-controlplane`,
`gimle-mimir`, `gimle-fafnir`, `gimle-muninn` and `gimle-andvari` — publishes the health of that
renewal into the same registry it already ships, through `CertificateRotationMetrics`:

| Meter | Kind | What it says |
|---|---|---|
| `gimle.certificate.rotation.consecutive.failures` | gauge | How many rotation checks in a row have failed. `0` while renewal is healthy. |
| `gimle.certificate.remaining.seconds` | gauge | How much validity the certificate currently in use still has. Goes negative once it has expired. |
| `gimle.certificate.rotation.checks` | counter, tagged `outcome` | One increment per check, tagged `DISABLED`, `NOT_DUE`, `ROTATED` or `FAILED`. |

Both gauges are registered at startup rather than on the first check, so a healthy process reads
as an explicit zero instead of an absent meter an operator has to interpret. They ride the same
`MeterSnapshotCodec` → agent → Muninn shipping path everything else does, so they are queryable
through `GET /metrics-history/*` with no new control-plane wire fields.

Alert on the pair, never on either alone: a failing rotation is harmless while the certificate it
failed to renew still has weeks of runway, and an outage in the making once it doesn't. The
practical rule is "`consecutive.failures > 0` **and** `remaining.seconds` below your renewal
window" for a page, with a plain `consecutive.failures > 0` warning long before that. `gimle-muninn`
is the one process with no meter registry of its own (it is the sink, and ships nothing), so its
rotation health is visible only through its log and the audit trail below.

The same signal shows up two more ways. A failure streak is **logged** with escalation rather than
one repeated `WARN`: the first failure logs at `WARN`, the third at `ERROR`, and an ongoing streak
re-logs at most once a minute — each line naming the error, how many checks have failed in a row,
the expiry of the certificate still in use with the runway left on it, and when the next attempt
happens. And the start and escalation point of a streak, plus every completed rotation, are
appended to the **durable audit trail** as a `CERTIFICATE_REQUEST`/`WRITE` `AuditEvent` against the
target `own-certificate` (`APPLIED` for a rotation, `REJECTED` for a failure) — so "when did this
node last renew, and when did renewal start failing" survives both log rotation and the process
itself. Rotation checks run every few seconds, which is why only those points are audited rather
than every failed check; the gauges carry the state in between. An unreachable store is logged and
dropped: auditing a rotation failure must never break the loop that keeps the certificate alive.

**Worker JVM metrics and traces reach Muninn too, relayed through the worker's own node agent** —
a worker has no outbound network identity of its own (`WorkerMain`'s only CLI arguments are
`nodeId`/`tenantId`/a control-socket path, no `-Dgimle.agent.muninnEndpoint`-equivalent), so it
can't run a `MuninnShipper` directly the way
`gimle-controlplane`/`gimle-fafnir`/`gimle-mimir`/`gimle-andvari`/`gimle-agent` do. Instead, `WorkerMain` builds a `MeterSnapshotCodec.toNdjson(WorkerMetrics.registry())`
snapshot every five seconds and sends it as `ControlMessage.MetricsSnapshot(workerId, ndjsonPayload)`
over the same agent↔worker control channel `MetricsReport`/`ModuleStateChanged` already use — one
snapshot per worker JVM, not per module (`WorkerMetrics` already tags every meter by its own module
internally, so there's nothing kind-specific for the agent to do). The agent relays the payload
byte-for-byte to `MuninnShipper#shipPreparedBatch`, no re-serialization, under
`/ingest/metrics/WORKER/{nodeId}:{workerId}` — a worker JVM has no `host:port` of its own, so its
`processId` is that colon-joined pair instead. A matching `ControlMessage.TracesSnapshot` carries a
worker's own exported span batch, built by `RelayingSpanExporter` (in `gimle-worker`, since it needs
the control-channel connection) via the same `SpanLineCodec` a direct-shipping `MuninnSpanExporter`
uses, so the two paths produce byte-identical NDJSON. `WorkerMain` installs `RelayingSpanExporter`
in place of `installDefault()`'s `LoggingSpanExporter` unconditionally — it degrades to "the agent
has nothing configured to forward to" exactly the same way an unset `gimle.agent.muninnEndpoint`
already does agent-side, so there's no case where the old default behaved usefully differently.
Both snapshot loops also flush once on `StopModule`, not just on their periodic tick, so a
short-lived instance (a completed `JobRun`, for example) doesn't lose its final metrics/spans to a
five-second tick that may never fire again before the worker process exits. Both are readable back
through the same `/metrics-history/*`/`/traces-history/*` routes as every other process kind —
`WORKER` needed zero `gimle-muninn` changes, since its `processKind` path segment was already an
unvalidated string.

## Alerting

Every metric above is a store, not a notifier — nothing compares a stored value against an
operator-declared threshold and tells anyone, except `AlertRuleSpec`. An `AlertRuleSpec` (Raft-
replicated the same way `ServiceSpec`/`NetworkPolicySpec` are, via `gimle-mimir`) names one
`DeploymentSpec` it watches, one of the same five signals `AutoscalePolicy` already scores
(`REQUEST_RATE_PER_SECOND`/`ERROR_RATE_PER_SECOND`/`QUEUE_DEPTH`/`CPU_MILLICORES_USED`/
`MEMORY_BYTES_USED`), a `GREATER_THAN`/`LESS_THAN` comparator and threshold, and a `webhookUrl` to
notify — deliberately one signal, one comparison, not a general expression language.

`AlertReconciler` runs on the same level-triggered reconcile tick as every other reconciler,
averaging each enabled rule's configured signal across its deployment's current
`InstanceObservation`s (the identical aggregation `GET /metrics` already uses) and calling
`AlertNotifier#notify` exactly once per `FIRING`/`RESOLVED` transition — never re-notifying every
tick a condition merely continues to hold. `WebhookAlertNotifier` POSTs a small JSON body
(`{rule, deploymentName, metric, comparator, threshold, observedValue, state}`) and is best-effort:
an unreachable webhook is logged and dropped, never allowed to fail the reconcile tick. Which rule
is currently firing is tracked purely in-process, not durable state — a control-plane restart
forgets it and may re-notify once on the first tick after, the same tradeoff `MuninnShipper`'s own
in-memory shipping cursor already accepts.

`gimle-controlplane` exposes `POST`/`GET`/`DELETE /alertrules*`, RBAC-gated via
`ResourceKind.ALERT_RULE` (a tenant able to deploy a workload can also alert on it, without a
cluster-admin grant); `gimle-cli` exposes it as `get`/`set`/`delete alertrule` — see the
[CLI reference](../reference/cli-reference.md).

## Tracing: `GimleTracing`

Installs a process-wide OpenTelemetry `SdkTracerProvider` that `gimle-fabric`'s `FabricServer`/
`FabricServiceRegistry` read via `GlobalOpenTelemetry` — so a trace started by a same-worker call
stays attached across a same-machine or cross-machine hop too (see
[Service fabric](./service-fabric.md)'s `TraceContext`). `installDefault()` keeps installing a
`LoggingSpanExporter`: spans are real and correctly parented, just logged rather than shipped to a
collector. `install(SpanExporter)` generalizes this to an arbitrary exporter over a
`BatchSpanProcessor` (a real network-bound exporter shouldn't block on every single span the way
the default's `SimpleSpanProcessor` does); `installWithMuninnShipping(MuninnShipper)` is the common
case, wrapping a `MuninnSpanExporter` that ships every batch to Muninn, readable back through `GET
/traces-history/{processKind}/{processId}` — from the console's Traces screen or from
`gimle traces-history <processKind> <processId>`, which reads that identical route (and
`gimle metrics-history` its metrics counterpart). `gimle-controlplane`, `gimle-fafnir`, `gimle-mimir`, and
`gimle-andvari` each install tracing this way — Muninn-backed when a Muninn endpoint is configured,
falling back to the logging default otherwise — the same "genuine RPC-serving process" set that
ships its own metrics. `gimle-worker` installs it a third way, `install(new RelayingSpanExporter(workerId, sink))`
(the plain `SpanExporter` overload, not `installWithMuninnShipping`, since `RelayingSpanExporter`
relays through the agent's control channel rather than shipping to Muninn directly — see above).
`gimle-agent` deliberately doesn't install tracing at all: its local log-tail surface isn't part of
the fabric-call trace chain, so there's no span parent/child to attach to. Idempotent: a process
that's already installed a tracer provider (or a test that pre-configured one) is left alone rather
than double-registered. `GimleTracing.flush()` forces the installed provider's `BatchSpanProcessor`
to export immediately rather than waiting for its own periodic interval — `WorkerMain` calls it
alongside its `StopModule` metrics flush (above), the tracing half of the same "don't lose a
short-lived instance's final data" concern.

### Sampling

Every provider `GimleTracing` builds — all three install paths above — gets the same
**parent-based** sampler, whose root sampling ratio comes from `-Dgimle.tracing.samplingRatio`
(`0.0`..`1.0`, defaulting to `1.0`: record every trace). A ratio of `1.0` records everything, `0.0`
records nothing, and anything between records that fraction of *root* spans.

Parent-based is the load-bearing part. The decision is made once, at the trace's root, and every
downstream hop honours whatever the incoming `TraceContext` already decided — so a cross-worker
fabric call's client span and the provider's server span are always both kept or both dropped. If
each hop sampled independently at ratio `r`, only `r^hops` of any multi-process trace would survive
whole and the rest would arrive as orphaned fragments, which is precisely the trace shape this
platform's own service fabric produces most of.

A malformed or out-of-range value is logged and ignored, falling back to recording everything: a
mistyped observability knob is never a reason for a worker or control plane to refuse to start.

## Per-module CPU and allocation: `ThreadNameJfrAttributor`

This is the piece that makes Tier 1's soft accounting concrete. It subscribes to the JVM's own JFR
event stream (`jdk.ExecutionSample`, `jdk.ThreadAllocationStatistics`) and attributes each sample to
a module by **thread-name prefix** (`gimle-<module>-<version>-`) — a deliberately different
classification key than the module system's classloader-package heuristic used elsewhere, because
the question here is *whose work is this*, not *whose classes are these*. Classification is
memoized per thread name (a virtual thread's name never changes after creation), so repeated
samples for a long-lived thread don't re-scan the live prefix set every time.

Degrades gracefully, never fails a worker over it: if JFR is unavailable or disabled in a given
environment, attribution silently produces no samples rather than throwing — the same
degrade-don't-fail posture this codebase uses elsewhere for JFR-dependent instrumentation.

## Structured logging

SLF4J + Logback, configured once in `gimle-core` and inherited everywhere, including by hosted
modules (which see the platform's logging API through the shared platform layer rather than
bundling their own binding). Output is split into `PLATFORM` and `APPLICATION` categories per
instance, and lifecycle-hook execution is MDC-tagged so a hook's own synchronous logging is
correctly attributed to that instance's `APPLICATION` output rather than miscategorized as platform
noise — a real gap the `greeter-provider`/`greeter-consumer` example surfaced and fixed, not a
default that was always correct.

### Reading logs back: cursor, level, and text

Every log read — the console's Logs screen, `gimle logs`, and the control plane's own platform log
alike — goes through one shared reader (`LogFileReader` in `gimle-core`, and its day-bucketed twin
inside Muninn), so a query means the same thing whichever surface asks and whichever store answers.
Three parameters narrow a read, all applied server-side, all combinable:

- **`cursor`/`since`** — the timestamp cursor: page backward through history, or poll forward for
  what's new. Stable across log rotation, since rotation renames files but never changes what
  instant a line was written at.
- **`level`** — a **threshold**, not an equality test: `WARN` keeps `WARN` and `ERROR`, which is what
  an operator narrowing down an incident actually wants. A line carrying no level, or one outside
  the `TRACE < DEBUG < INFO < WARN < ERROR` scale (a raw, unstructured SYSTEM capture), cannot be
  placed against a threshold and is therefore excluded rather than silently admitted. An
  unrecognized level is a 400 naming the accepted values, never a silently unfiltered page.
- **`contains`** — a plain, **case-insensitive substring**, deliberately not a regular expression,
  so a pasted message fragment containing `(`, `[` or `.` matches literally instead of erroring or
  wildcarding. Tested against a line's human-readable fields only (`message`, `logger`,
  `stackTrace`, `raw`) — never machine identifiers like `nodeId` or `thread`, where a short query
  would otherwise match every line from one node.

The page `limit` is applied *after* filtering, so a page is the most recent N *matching* lines
rather than however few matches happened to fall inside the most recent N raw ones. Under
`follow=true` the cursor still advances over suppressed lines, so a long run of non-matching output
is never re-scanned on every poll tick. And because the identical filter runs on the node agent's
live reader, on the control plane's own log, and inside Muninn's shipped history, a filtered read of
a node that has since died returns exactly what the same read against its live agent would have.
