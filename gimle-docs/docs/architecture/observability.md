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
module. Defaults to an in-memory `SimpleMeterRegistry` with no exporter wired up — the counters
exist and are queryable today; which real exporter backend (Prometheus, OTLP, something else) a
deployment wants is a separate, later decision, independent of the instrumentation itself.
`FabricServer` records every real inbound call's latency/outcome here, not just synthetic test
traffic. Separately, each worker JVM self-reports its own process CPU load and heap usage (portable
`java.lang.management`, no cgroups) to its agent every few seconds, which is what feeds
`AutoscaleReconciler`'s CPU-utilization math with real, non-zero data.

## Tracing: `GimleTracing`

Installs a process-wide OpenTelemetry `SdkTracerProvider` that `gimle-fabric`'s `FabricServer`/
`FabricServiceRegistry` read via `GlobalOpenTelemetry` — so a trace started by a same-worker call
stays attached across a same-machine or cross-machine hop too (see
[Service fabric](./service-fabric.md)'s `TraceContext`). Defaults to a `LoggingSpanExporter`: spans
are real and correctly parented today, just logged rather than shipped to a collector — same
"instrumentation now, exporter later" posture as `WorkerMetrics`. Idempotent: a worker that's
already installed a tracer provider (or a test that pre-configured one) is left alone rather than
double-registered.

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
