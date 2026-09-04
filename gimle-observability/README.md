# Gimle Observability

A shared library, not a process of its own: per-process Micrometer metrics wiring, OpenTelemetry
tracing installation, JFR-backed CPU/allocation accounting, and the `MuninnShipper` mechanism every
other process kind uses to ship logs/metrics/traces to `gimle-muninn`. Every Gimlé process kind
that reports metrics or traces (`gimle-worker`, `gimle-agent`, `gimle-controlplane`, `gimle-mimir`,
`gimle-fafnir`, `gimle-andvari`) depends on this module rather than reimplementing any of it.

## Per-process metrics wiring

One thin class per process kind, each wrapping a `MeterRegistry` (a plain in-memory
`SimpleMeterRegistry` by default — the counters just need to exist and be queryable, not export
anywhere on their own) with request rate/latency/error counters tagged by whatever dimension that
process cares about:

| Class | Tags requests by |
|---|---|
| `WorkerMetrics` | module id — also tracks per-module thread counts and metaspace footprint |
| `AgentMetrics` | node-level agent request shape |
| `ApiServerMetrics` | control-plane API route |
| `StoreMetrics` | Raft/store RPC kind |
| `FafnirMetrics` | secrets API route |
| `AndvariMetrics` | artifact registry API route |

All six are built on the shared `TaggedRequestMetrics` helper, so the request-rate/latency/error
counter shape (and its Micrometer naming convention) stays identical across every process kind
instead of six independent implementations drifting apart. Gauges (thread counts, metaspace bytes)
are backed by an internally-tracked, mutable `AtomicLong` per key rather than a boxed primitive
handed to `MeterRegistry#gauge` directly — the registry only re-reads the same object reference on
every scrape, so a fresh boxed value each update would silently freeze at whatever was passed
first.

## Tracing

`GimleTracing` installs the process-wide `OpenTelemetry` instance that `gimle-fabric`'s
`FabricServer`/`FabricServiceRegistry` read via `GlobalOpenTelemetry`. `installDefault()` wires a
`LoggingSpanExporter` over a `SimpleSpanProcessor` — spans are real and correctly parented even
with nothing configured to ship them anywhere. `install(SpanExporter)` generalizes that to an
arbitrary exporter over a `BatchSpanProcessor` (a real network-bound exporter shouldn't block the
exporting thread per span), and `installWithMuninnShipping(MuninnShipper)` is the concrete case of
that for shipping to Muninn. All three are idempotent — a process that already installed a tracer
provider is left alone.

Trace context is propagated by capture-and-restore of OpenTelemetry's own `Context` across virtual
thread boundaries — see `BoundedModuleScheduler` in `gimle-worker`, which captures the caller's
`Context` at submit time and restores it on the fresh virtual thread each task runs on. A worker
JVM has no outbound network identity of its own, so it can't run a `MuninnSpanExporter` directly;
instead `RelayingSpanExporter` (in `gimle-worker`) relays every exported span batch to its agent
over the existing control channel, using `SpanLineCodec` to produce byte-identical NDJSON to what a
process shipping straight to Muninn would send — Muninn's ingest side never needs to know whether a
batch arrived directly or via a relaying agent.

## JFR-backed accounting

`ThreadNameJfrAttributor` attributes `jdk.ExecutionSample`/`jdk.ObjectAllocationSample` JFR events
to modules by thread-name prefix (`gimle-<module>-<version>-`, the same naming convention
`BoundedModuleScheduler` gives every virtual thread it creates) — a different classification key
from the module system's classloader-package heuristic, because the question here is whose *work*
a sampled thread represents, not whose *classes* it's running. This per-module CPU/allocation
accounting is what makes Tier 1 soft resource limits enforceable without a kernel-level mechanism.
Both events are sampling-based and virtual-thread-aware; the older periodic
`jdk.ThreadAllocationStatistics` was tried first for the allocation side and rejected — it walks
only the JVM's live platform-thread list, so it never once names a virtual thread, and every
module-hosting thread here is virtual. If JFR itself is unavailable in the running environment,
attribution degrades to reporting no samples rather than failing the owning process — the same
degrade-don't-fail posture `MuninnShipper` applies when a ship attempt fails.

`WorkerMain` constructs one `ThreadNameJfrAttributor` per worker JVM, over the same `MeterRegistry`
`WorkerMetrics` builds its own request-rate/latency counters in, and registers/unregisters each
module's thread-name prefix as it goes `Active`/`Uninstalled` (or `Completed`, for a finished Job).
Sharing that registry is what lets `gimle.module.cpu.samples`/`gimle.module.allocated.bytes` ride
the same periodic `MeterSnapshotCodec` NDJSON snapshot request-rate/latency already do, instead of
being visible only through a separate live-read path.

## Shipping to Muninn

`MuninnShipper` is the shared batch-POST-with-retry class every shipping process constructs — one
instance per shipped stream, bound at construction to Muninn's endpoint(s) and the one ingest path
it ships to (the caller bakes the process kind/id or node id and category into that path). It fans
a batch out to every configured Muninn replica independently and best-effort, so one unreachable
replica never blocks delivery to the others; a tick counts as successful (advancing a log-shipping
cursor) if *any* configured endpoint accepts the batch. The log-shipping cursor is in-memory only —
a process restart re-ships from "nothing shipped yet," a deliberate small-duplicate-window
tradeoff rather than a second persisted-cursor mechanism alongside `LogFileReader`'s own.
`MeterSnapshotCodec` and `SpanLineCodec` are the NDJSON wire formats a `MeterRegistry`
snapshot/exported span batch are encoded to before shipping (or relaying, for a worker).
`MuninnSpanExporter` is the `SpanExporter` implementation that feeds a `MuninnShipper` from
`GimleTracing`.

## Consumers

Nothing in this module has a `main` method — it exists purely to be depended on. `gimle-worker`,
`gimle-agent`, `gimle-mimir`, `gimle-controlplane`, `gimle-fafnir`, and `gimle-andvari` each pull in
the metrics class and `MuninnShipper`/`GimleTracing` wiring relevant to their own process kind;
`gimle-agent` also depends on it in main scope (not just test scope) specifically to ship its own
platform log plus every supervised worker's logs/metrics/traces on `-Dgimle.agent.muninnEndpoint`.
