---
sidebar_position: 6
---

# Observability

`gimle-observability` gives every module instance tagged metrics, propagated tracing, and
JFR-backed per-module resource accounting — the last of which is what makes Tier 1's *soft*
resource accounting (see [Tiered isolation](./tiered-isolation.md)) actually observable, not just
assumed.

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
rate, average error rate, instance count) built from that same observation data. `WorkerMetrics`'
own request-latency `Timer` is now built with `publishPercentiles(0.5, 0.95, 0.99)` too, for local
parity with the three process-tier registries below — but, matching the "worker metrics stay
local-registry-only" note two paragraphs down, that percentile data is never shipped anywhere on
its own; it only becomes externally visible once/if worker-tier shipping itself is built.

`gimle-controlplane`, `gimle-fafnir`, and `gimle-mimir` each carry their own analogous per-process
`MeterRegistry` (`ApiServerMetrics`/`FafnirMetrics`/`StoreMetrics` — request/RPC count, latency,
error count, tagged by endpoint+verb or RPC kind), and ship it to Muninn (see
[Node topology](./node-topology.md#muninn)) via a periodic `MuninnShipper` when a Muninn endpoint
is configured, readable back through `GET /metrics-history/{processKind}/{processId}`. Each of
these three registries' own request-latency `Timer` is built with `publishPercentiles(0.5, 0.95,
0.99)`, and `MuninnShipper#meterToJsonLine` special-cases any `Timer` meter to call its
`HistogramSnapshot#percentileValues()` and ship the result as a `"percentiles"` map alongside the
existing `"measurements"` map (`{"0.5": ..., "0.95": ..., "0.99": ...}`, in seconds) — readable back
through the same `/metrics-history/*` route unchanged, since `MuninnDayFileStore` stores each
shipped line as opaque JSON. A `Timer` that was never built with `publishPercentiles(...)` ships
exactly as before (no `"percentiles"` key at all), so this is purely additive. **Worker JVM metrics
are explicitly not part of this**: `WorkerMetrics` above stays local-registry-only, shipped
nowhere — only a worker's *logs* reach Muninn, relayed by its supervising node agent (see
[Node topology](./node-topology.md#muninn)), not its metrics or traces, p99s included. Extending
metrics/trace shipping down to the worker tier itself is a real, acknowledged gap, not an
oversight: the agent↔worker control channel would need a new message shape for a full
`MeterRegistry` snapshot, materially more plumbing than the existing `ControlMessage.MetricsReport`'s
few scalar fields.

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
/traces-history/{processKind}/{processId}`. `gimle-controlplane`, `gimle-fafnir`, and `gimle-mimir`
each install tracing this way — Muninn-backed when a Muninn endpoint is configured, falling back to
the logging default otherwise — the same "genuine RPC-serving process" set that ships its own
metrics. `gimle-agent` deliberately doesn't install tracing at all: its local log-tail surface
isn't part of the fabric-call trace chain, so there's no span parent/child to attach to.
Idempotent: a process that's already installed a tracer provider (or a test that pre-configured
one) is left alone rather than double-registered.

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
