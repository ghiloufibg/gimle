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
already rides. The control plane exposes a `GET /metrics` per-deployment rollup (average request
rate, average error rate, instance count) built from that same observation data, and the console's
own Instances/Metrics screens surface both figures per instance and per deployment (an error-rate
column on the Instances table, a total-error-rate stat tile and a ranked "instances with errors"
panel on the Metrics screen). `WorkerMetrics`' own request-latency `Timer` is built with
`publishPercentiles(0.5, 0.95, 0.99)` too, for parity with the three process-tier registries below.

`WorkerMetrics#evict(ModuleId)` removes a module's entire meter set (request/error counters, the
latency timer, and the thread-count/metaspace gauges) once that `ModuleId` is uninstalled — never
on a mere stop, since a stopped-but-installed module can restart and resume its counters. Without
this, a worker that redeploys the same module name across many versions over its lifetime would
accumulate one permanent meter set per historical `(module, version)` pair forever;
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
/traces-history/{processKind}/{processId}`. `gimle-controlplane`, `gimle-fafnir`, `gimle-mimir`, and
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
